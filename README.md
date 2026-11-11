# ReelForge

An open-source Android video editor written in Kotlin, built only on free libraries:
Jetpack Media3 (ExoPlayer for preview, Transformer + Effect for export) and Jetpack Compose.

## What works in this version (0.1)

- Projects: create, reopen, delete; saved automatically. Aspect ratios 16:9, 9:16, 1:1, 4:5.
- Import videos and photos from the gallery onto a single main track.
- Timeline: select, reorder, duplicate, delete, split at the playhead, trim.
- Undo / redo for every edit.
- Per clip: speed (0.25x–4x), volume (0–200%) and mute, rotate, mirror, zoom, fit or fill
  the frame, opacity, 8 colour filters, brightness / contrast / saturation, fade in / fade out,
  dip-to-black transition to the next clip.
- Text overlays: colour, font, background box, size, position, start and end time.
- Text outline and entry / exit animations (fade, slide, pop) for text, stickers and layers.
- Emoji stickers.
- Image layers (picture-in-picture photos, logos, watermarks) with size, position, opacity,
  timing and animation.
- Voice-over recording and extra sound clips placed anywhere on the timeline.
- Freeze frame; sound fades together with the picture fade.
- Backdrop behind clips that do not fill the frame: black, a colour, or a blurred copy.
- Fade styles: fade, slide, zoom (slide and zoom are rendered on export).
- Voice changer (pitch presets) per clip.
- Custom fonts (import a .ttf / .otf) and a handwriting / drawing pad.
- Two-point motion for text, stickers and image layers.
- Background music: any audio file, volume, loop.
- Live preview with scrubbing.
- Export to MP4 (H.264 + AAC) at 480p / 720p / 1080p with three quality levels, saved to `Movies/ReelForge`, with share.

## Not built yet

Video layers (video picture-in-picture), chroma key, blend modes, true crossfade / wipe
transitions, multi-point keyframes, reverse, speed curves, equaliser, auto captions, asset store.

## Build

Requirements: Android Studio (Ladybug or newer) or JDK 17 + Android SDK 35.

```
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`. Every push is also built by GitHub
Actions (`.github/workflows/android.yml`), and the APK is attached to the run as an artifact.

Minimum Android version: 10 (API 29).

## Code map

| Path | Purpose |
| --- | --- |
| `model/Project.kt` | Project, clip, text and music data classes |
| `data/ProjectRepository.kt` | JSON storage for projects |
| `engine/Effects.kt` | Colour filters, fade, timed text overlay |
| `engine/ExportManager.kt` | Project → Media3 `Composition` → MP4 |
| `ui/EditorViewModel.kt` | Editing actions, preview playback, export state |
| `ui/EditorScreen.kt`, `ui/Panels.kt` | Editor screen and tool panels |
| `ui/HomeScreen.kt` | Project list |

## Licence

MIT. Media3 and Compose are Apache-2.0.
