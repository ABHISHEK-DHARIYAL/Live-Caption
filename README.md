# Clearcap: offline live captions for Android

Native Kotlin and Jetpack Compose app that turns speech into text **on the phone, with no internet, server or account**.
It captions either the **microphone** or **other apps' audio** (Android playback capture), in **English, Hindi and Hinglish**,
saves transcripts to a local database, and lets you edit, search, export and restore the original text.

> **Status: complete source, not yet built or run on a device by its author.** The pure-logic module is compiled and tested
> (152 tests pass). The Android layer type-checks against real Android and sherpa-onnx classes, but Gradle, Compose, Room
> code generation, the APK and every on-device behaviour are **unverified until you build and test it**.
> See [docs/VERIFICATION.md](docs/VERIFICATION.md) for exactly what was and was not run.

## What it does

| Feature | Notes |
|---|---|
| English live captions | Streaming Zipformer: text appears as people speak |
| Hindi, Hinglish, auto-detect | **Near-live**: Whisper decodes each phrase after a pause, so text arrives seconds late. No streaming model for these is available in the bundled engine |
| Transcribe, never translate | Whisper task is fixed to "transcribe" |
| System audio **or** microphone | Separate choices, never mixed |
| Floating caption bar | Over other apps, draggable, size setting (needs "Display over other apps") |
| History | Room database, search, rename, delete, edit, copy, share, export TXT / Markdown / JSON / SRT / timestamped text |
| Versions | Original, Refined, My edit. The original recognizer text is always kept and restorable |
| Vocabulary | Terms and "also written as" spellings (Devanagari or Roman), applied after recognition, reversible |
| Recovery | Segments are saved as they finish; killed sessions are marked "Interrupted"; with audio storage on they can be transcribed again |
| Models | Catalogue with sizes, SHA-256 and per-file size checks, resumable downloads, device-aware advice, delete |

## Requirements

- Android **10 (API 29)** or newer. CPU **arm64-v8a** (priority) or **armeabi-v7a**. x86 / x86_64 devices and emulators are **not** supported by the bundled speech library.
- Free storage: from about 1 MB (detector only) up to about 1.1 GB free while installing the largest model (Whisper small: 640 MB download, 375 MB installed); see [docs/DEVICES.md](docs/DEVICES.md).
- RAM guidance (estimates, not measurements): under 3 GB lightweight models; 3 to 5.5 GB base; above that small.

## Build

See [docs/BUILD.md](docs/BUILD.md). Short version, with JDK 17 and Android Studio (Ladybug or newer):

```
./gradlew :core:test :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug                  # app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions (`.github/workflows/build.yml`) runs the same commands; failing tests fail the run.

## First run

1. Open **Models**, download what your language needs (English: Zipformer + speech detector, about 311 MB). Nothing downloads before you tap.
2. Pick a source on **Home**, tap the button, accept the prompts.
3. Optional: Settings > Audio storage if you want crash recovery and re-checking.

## Layout

```
core/   pure Kotlin: text pipeline, audio maths (resampler, gain, spool), reconciler, aligner, exporter, WER/CER, model catalogue, router
app/    Android: capture, sherpa-onnx engines, model manager, Room, service, overlay, Compose UI
docs/   BUILD, ARCHITECTURE, DEVICES, EVALUATION, TROUBLESHOOTING, VERIFICATION, eval data
tools/  desktop evaluation harness, optional dev scripts
```

## Known limitations (honest list)

- Not every app allows system-audio capture. Protected/DRM media, many calls and some meeting apps are silent to this app. It reports "no audio heard" rather than invent text.
- Fast playback lowers accuracy (English test: 0% at 1x, about 4% at 1.5x, about 16% at 2x for the live model). Prefer 1.5x or slower.
- Hindi and Hinglish accuracy has **not been measured**. Whisper may write English words inside Hindi sentences in Devanagari or Roman letters.
- The second pass is **off by default**: on the only clips measured, it did not beat the live English model.
- Whisper's decoder cannot be given a vocabulary prompt through the bundled API, so vocabulary is applied after recognition.
- A running native decode cannot be interrupted; cancel and skip act between speech segments.
- CPU only. No GPU/NNAPI/NPU path has been verified, so none is enabled.
- UI text is English only.
- The earlier cloud "AI notes" feature was removed to honour "never upload transcripts".

Licences and attributions: [ATTRIBUTION.md](ATTRIBUTION.md), [NOTICE](NOTICE). Privacy: [PRIVACY.md](PRIVACY.md).
