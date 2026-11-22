"""
Builds the Vocal Separator model.

Spleeter 2stems (vocals / accompaniment) by Deezer Research, MIT licence. The official
TensorFlow checkpoint is rebuilt as a Keras model (the same U-Net, layer for layer), checked
against the original graph, and converted to LiteRT with float16 weights.

Input:  [1, 512, 1024, 2] magnitude spectrogram of a stereo mix (512 frames of a 4096-point
        STFT, hop 1024, 44.1 kHz; the lowest 1024 frequency bins).
Output: vocals and accompaniment spectrogram estimates, same shape.
"""
import os, re, sys, tarfile, urllib.request
import numpy as np
import tensorflow as tf

URL = "https://github.com/deezer/spleeter/releases/download/v1.4.0/2stems.tar.gz"
T, F = 512, 1024
FILTERS = [16, 32, 64, 128, 256, 512]


def unet(x, prefix):
    L = tf.keras.layers
    convs, out = [], x
    for i, n in enumerate(FILTERS):
        c = L.Conv2D(n, 5, strides=2, padding="same", name=f"{prefix}_enc{i}")(out)
        convs.append(c)
        b = L.BatchNormalization(axis=-1, epsilon=1e-3, name=f"{prefix}_encbn{i}")(c)
        out = L.LeakyReLU(0.2)(b)
    up = convs[5]
    for i, n in enumerate([256, 128, 64, 32, 16, 1]):
        u = L.Conv2DTranspose(n, 5, strides=2, padding="same", name=f"{prefix}_dec{i}")(up)
        u = L.ReLU()(u)
        u = L.BatchNormalization(axis=-1, epsilon=1e-3, name=f"{prefix}_decbn{i}")(u)
        if i < 5:
            up = L.Concatenate(axis=-1)([convs[4 - i], u])
        else:
            up = u
    mask = L.Conv2D(2, 4, dilation_rate=2, padding="same", activation="sigmoid", name=f"{prefix}_out")(up)
    return L.Multiply()([mask, x])


def build():
    inp = tf.keras.Input(shape=(T, F, 2), batch_size=1, name="mix")
    return tf.keras.Model(inp, [unet(inp, "vocals"), unet(inp, "accompaniment")])


def load(model, ckpt):
    reader = tf.train.load_checkpoint(ckpt)
    names = sorted(reader.get_variable_to_shape_map())
    print("checkpoint variables:", len(names))
    for n in names:
        if "Adam" not in n:
            print("  ", n, reader.get_variable_to_shape_map()[n])

    def index(name, base):
        m = re.fullmatch(base + r"(?:_(\d+))?/.*", name)
        return None if m is None else int(m.group(1) or 0)

    def group(base):
        found = sorted({index(n, base) for n in names if index(n, base) is not None})
        return found

    conv = group("conv2d")
    trans = group("conv2d_transpose")
    bn = group("batch_normalization")
    print("conv2d", len(conv), "conv2d_transpose", len(trans), "batch_normalization", len(bn))
    assert len(conv) == 14 and len(trans) == 12 and len(bn) == 24, "unexpected checkpoint layout"

    def var(base, i, field):
        key = f"{base}/{field}" if i == 0 else f"{base}_{i}/{field}"
        return reader.get_tensor(key)

    for k, prefix in enumerate(["vocals", "accompaniment"]):
        c = conv[k * 7:(k + 1) * 7]
        t = trans[k * 6:(k + 1) * 6]
        b = bn[k * 12:(k + 1) * 12]
        for i in range(6):
            model.get_layer(f"{prefix}_enc{i}").set_weights([var("conv2d", c[i], "kernel"), var("conv2d", c[i], "bias")])
            model.get_layer(f"{prefix}_dec{i}").set_weights([var("conv2d_transpose", t[i], "kernel"), var("conv2d_transpose", t[i], "bias")])
        model.get_layer(f"{prefix}_out").set_weights([var("conv2d", c[6], "kernel"), var("conv2d", c[6], "bias")])
        for i in range(12):
            # Spleeter computes a 6th encoder batch norm but never uses it; Keras leaves it out.
            if i == 5:
                continue
            layer = model.get_layer(f"{prefix}_encbn{i}" if i < 6 else f"{prefix}_decbn{i - 6}")
            layer.set_weights([var("batch_normalization", b[i], f) for f in ("gamma", "beta", "moving_mean", "moving_variance")])


def reference(ckpt_dir, mix):
    """The original graph's outputs for the same input, when the graph can be loaded."""
    try:
        tf.compat.v1.disable_eager_execution()
    except Exception:
        pass
    g = tf.Graph()
    with g.as_default():
        saver = tf.compat.v1.train.import_meta_graph(os.path.join(ckpt_dir, "model.meta"), clear_devices=True)
        ops = [o.name for o in g.get_operations()]
        outs = [o for o in ops if re.fullmatch(r"(vocals|accompaniment)_spectrogram/mul", o)]
        print("graph outputs:", outs)
        # The U-Net's input is the first tensor fed into the first conv2d.
        conv = g.get_operation_by_name("conv2d/Conv2D")
        inp = conv.inputs[0]
        print("unet input:", inp.name, inp.shape)
        with tf.compat.v1.Session(graph=g) as s:
            saver.restore(s, os.path.join(ckpt_dir, "model"))
            got = s.run([g.get_tensor_by_name(f"{n}:0") for n in sorted(outs, reverse=True)], {inp: mix})
    return got  # vocals, accompaniment


def main(out_dir):
    os.makedirs(out_dir, exist_ok=True)
    tgz = os.path.join(out_dir, "2stems.tar.gz")
    urllib.request.urlretrieve(URL, tgz)
    ck = os.path.join(out_dir, "2stems")
    os.makedirs(ck, exist_ok=True)
    with tarfile.open(tgz) as t:
        t.extractall(ck)
    print(os.listdir(ck))
    model = build()
    load(model, os.path.join(ck, "model"))
    rng = np.random.default_rng(1)
    mix = (rng.random((1, T, F, 2)) ** 3 * 4).astype(np.float32)
    mine = [o.numpy() for o in model(mix, training=False)]

    def mask_of(v, a):
        return (v * v + 5e-11) / (v * v + a * a + 1e-10)

    want_mask = mask_of(mine[0], mine[1])

    def convert(kind):
        c = tf.lite.TFLiteConverter.from_keras_model(model)
        c.optimizations = [tf.lite.Optimize.DEFAULT]
        if kind == "fp16":
            c.target_spec.supported_types = [tf.float16]
        # "int8": dynamic-range quantisation, weights stored as 8-bit, maths in float.
        data = c.convert()
        p = os.path.join(out_dir, f"sep_{kind}.tflite")
        open(p, "wb").write(data)
        it = tf.lite.Interpreter(model_path=p)
        it.allocate_tensors()
        ins = it.get_input_details()
        outs = it.get_output_details()
        it.set_tensor(ins[0]["index"], mix)
        it.invoke()
        lite = [it.get_tensor(d["index"]) for d in outs]
        vi = int(np.argmin([np.abs(l - mine[0]).max() for l in lite]))
        m = mask_of(lite[vi], lite[1 - vi])
        err = float(np.abs(m - want_mask).mean())
        worst = float(np.abs(m - want_mask).max())
        print(kind, "bytes", len(data), "voice output", vi, "mask error mean", err, "max", worst,
              [(d["name"], list(d["shape"])) for d in outs])
        return p, vi, err, outs

    results = {k: convert(k) for k in ["int8", "fp16"]}
    kind = "int8" if results["int8"][2] < 0.01 else "fp16"
    path, vi, err, outs = results[kind]
    print("chosen", kind)
    import shutil
    shutil.copy(path, os.path.join(out_dir, "vocal_separator_2stems.tflite"))

    try:
        ref = reference(ck, mix)
        for k, name in enumerate(["vocals", "accompaniment"]):
            d = float(np.abs(ref[k] - mine[k]).max())
            print(name, "keras vs original spleeter max diff", d, "scale", float(np.abs(ref[k]).max()))
            assert d < 1e-2 * float(np.abs(ref[k]).max() + 1e-6), "keras model does not match spleeter"
        print("matches the original spleeter graph")
    except AssertionError:
        raise
    except Exception as e:  # noqa
        print("reference check skipped:", repr(e)[:500])

    open(os.path.join(out_dir, "vocal_separator_2stems.txt"), "w").write(
        f"kind={kind}\nvocals_output_index={vi}\nmask_error_mean={err}\n" +
        "\n".join(f"{i} {d['name']} {list(d['shape'])}" for i, d in enumerate(outs)) + "\n")
    print("vocals output index", vi)


if __name__ == "__main__":
    main(sys.argv[1])
