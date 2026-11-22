"""
Builds the Super Resolution model the app ships with.

Real-ESRGAN realesr-general-x4v3 (SRVGGNetCompact, x4), BSD-3-Clause, Copyright (c) 2021
Xintao Wang. The official weights are converted to LiteRT (.tflite) so the app can run them
on the phone. Input: one 128x128 RGB tile, NHWC float 0..1. Output: 512x512 RGB tile.
"""
import sys, urllib.request, numpy as np, torch, torch.nn as nn, torch.nn.functional as F

URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-general-x4v3.pth"
TILE = 128


class SRVGGNetCompact(nn.Module):
    def __init__(self, num_feat=64, num_conv=32, upscale=4):
        super().__init__()
        self.upscale = upscale
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(3, num_feat, 3, 1, 1))
        self.body.append(nn.PReLU(num_parameters=num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(nn.PReLU(num_parameters=num_feat))
        self.body.append(nn.Conv2d(num_feat, 3 * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        return out + F.interpolate(x, scale_factor=self.upscale, mode="nearest")


class Nhwc(nn.Module):
    """Takes and gives pictures as height x width x colour, the way Android bitmaps are laid out."""

    def __init__(self, net):
        super().__init__()
        self.net = net

    def forward(self, x):
        y = self.net(x.permute(0, 3, 1, 2))
        return torch.clamp(y, 0.0, 1.0).permute(0, 2, 3, 1)


def main(out_dir):
    path = f"{out_dir}/realesr-general-x4v3.pth"
    urllib.request.urlretrieve(URL, path)
    state = torch.load(path, map_location="cpu", weights_only=True)
    state = state.get("params", state.get("params_ema", state))
    net = SRVGGNetCompact()
    net.load_state_dict(state, strict=True)
    model = Nhwc(net).eval()

    sample = torch.rand(1, TILE, TILE, 3)
    with torch.no_grad():
        want = model(sample).numpy()

    try:
        import litert_torch as conv
    except ImportError:
        import ai_edge_torch as conv
    edge = conv.convert(model, (sample,))
    fp32 = f"{out_dir}/sr_x4_fp32.tflite"
    edge.export(fp32)

    # fp16 weights halve the size; kept only if it still matches.
    fp16 = None
    try:
        from ai_edge_torch.generative.quantize import quant_recipes, quant_config
        q = conv.convert(model, (sample,), quant_config=quant_config.QuantConfig(
            generative_recipe=quant_recipes.full_fp16_recipe()))
        fp16 = f"{out_dir}/sr_x4_fp16.tflite"
        q.export(fp16)
    except Exception as e:  # noqa
        print("fp16 export skipped:", e)

    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        from tensorflow.lite.python.interpreter import Interpreter
    best = None
    for f in [fp16, fp32]:
        if not f:
            continue
        it = Interpreter(model_path=f)
        it.allocate_tensors()
        inp = it.get_input_details()[0]
        outp = it.get_output_details()[0]
        print(f, inp["shape"], inp["dtype"], outp["shape"], outp["dtype"])
        it.set_tensor(inp["index"], sample.numpy().astype(np.float32))
        it.invoke()
        got = it.get_tensor(outp["index"])
        diff = float(np.abs(got - want).max())
        print(f, "max diff", diff)
        if diff < 0.02 and best is None:
            best = f
    if best is None:
        sys.exit("no converted model matched PyTorch")
    import shutil
    shutil.copy(best, f"{out_dir}/super_resolution_x4.tflite")
    print("chosen", best)


if __name__ == "__main__":
    main(sys.argv[1])
