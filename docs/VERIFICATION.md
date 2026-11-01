# Verification status

Honest split between what was actually run and what still needs you. Date of work: 31 Oct 2026, in a sandbox with **no Android SDK, no Gradle and no access to Google Maven or Maven Central**.

## Actually run
| Check | Result |
|---|---|
| `core` compiled with kotlinc 1.9.24 and all tests run with a minimal JUnit-compatible runner (`tools/dev/coretest.sh`) | **152 passed, 0 failed** (21 test classes). Not run through Gradle/JUnit proper |
| Android sources without Compose (capture, asr, models, session, data, work, App) type-checked against real `android.jar` API 35 and the real sherpa-onnx 1.13.8 classes, with stand-ins for Room, DataStore and WorkManager | Compiles. Stubs check types only |
| Catalogue: HTTP size of all 6 archives | All match |
| Catalogue: SHA-256 recomputed from downloaded archives | Zipformer, Parakeet, Whisper tiny, Whisper base, Silero VAD **match**. Whisper small **not** re-verified |
| Catalogue: per-file sizes inside the 4 archives (zipformer, parakeet, tiny, base) | 12 of 12 match |
| Desktop transcription of 3 English clips with 4 catalogue models, scored by the app's scorer | See docs/EVALUATION.md |
| AAR contents | ABIs arm64-v8a and armeabi-v7a only; API classes listed in docs |

Two defects were found and fixed by running the tests (file-name sanitiser; Devanagari share metric ignoring vowel signs).
The type check found three more (nullability, a cross-module smart cast, annotation form), also fixed.

## NOT run (needs your build and device)
- Any Gradle task: sync, `:core:test`, `:app:testDebugUnitTest`, `:app:assembleDebug`. **No APK was built.**
- Compose UI code (all of `app/.../ui/`) has never been compiled. Room annotation processing, DataStore, WorkManager and Navigation were checked only against my own stubs.
- `androidTest` (Room behaviour tests) never run.
- Every on-device behaviour: microphone capture, AudioPlaybackCapture and MediaProjection consent, foreground-service types on Android 10 to 15, overlay, notifications, pause/resume, interruption handling, process-death recovery, model download over redirects with Range resume, WorkManager scheduling, resampler performance, AudioRecord format negotiation on real hardware.
- Hindi and Hinglish accuracy; phone latency, memory, CPU, battery, thermal; 60-minute stability; multi-manufacturer behaviour.
- Whisper small download and checksum.

## Risks I would check first when you build
1. Compose compile errors (written without a compiler; wildcard imports used to reduce risk).
2. Room KSP generation and the `ESCAPE '\'` search query.
3. `AudioRecord` playback-capture formats (48 kHz stereo first) on your phone; Diagnostics shows the format actually used.
4. WorkManager foreground type `dataSync` on Android 14+ (manifest override added).
5. GitHub redirect + HTTP Range resume in the downloader.
