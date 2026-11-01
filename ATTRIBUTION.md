# Attribution and licences

Provenance is stated only where it was verified in the source files. "Informed by" means the idea or behaviour was studied and
re-implemented from scratch; no code was copied.

## Projects supplied with the task
| Project | Licence (from its LICENSE file) | What was used | How |
|---|---|---|---|
| Live-Caption ("Clearcap") | No LICENSE file in the upload. Its NOTICE lists third-party terms | The whole app foundation: Compose theme and components, capture approach, Zipformer/Parakeet/Whisper/VAD wiring, text filters, tests | Kept, adapted and extended. **You must choose a licence for this project.** |
| OpenWhispr (MIT, Gizmo Labs / OpenWhispr Team) | MIT | Dictionary bulk import, echo filter and correction learner (`core/.../text/WordBank.kt`, ported earlier into Clearcap, marked in the file) | Ported to Kotlin; MIT notice below |
| VoiceFlow (MIT) | MIT | Design of: audio spooled to disk during recording, start-up recovery sweep, serial re-transcription jobs, TXT/MD/JSON/SRT export with non-overlapping SRT cues, pause/resume semantics | Informed by; re-implemented in Kotlin (`PcmSpool`, `RecoverySweep`, `Retranscriber`, `Export.kt`) |
| faster-whisper (MIT, SYSTRAN) | MIT | Behavioural reference for hallucination filtering thresholds and VAD-segmented decoding | Informed by only. Not used at runtime; its CTranslate2 runtime does not run on Android |
| OpenWhispr mobile engine router | MIT | Idea of routing English to Parakeet and other languages to Whisper | Informed by (`core/.../pipeline/EngineRouter.kt`) |

MIT notices to keep if code derived from these is redistributed:
- OpenWhispr: Copyright (c) OpenWhispr contributors. VoiceFlow: Copyright (c) VoiceFlow contributors. faster-whisper: Copyright (c) 2023 SYSTRAN.
  Permission is granted under the MIT licence, which requires this notice to be included in copies or substantial portions.
  (Exact copyright lines are in each project's own LICENSE file; copy them verbatim into your repository before publishing.)

## Runtime libraries
| Component | Licence | Notes |
|---|---|---|
| sherpa-onnx 1.13.8 AAR (k2-fsa) | Apache-2.0 | The AAR ships no licence file; keep this notice and the Apache-2.0 text |
| ONNX Runtime (inside the AAR) | MIT | |
| AndroidX, Jetpack Compose, Room, DataStore, WorkManager, Navigation | Apache-2.0 | |
| kotlinx.coroutines | Apache-2.0 | |
| Apache Commons Compress | Apache-2.0 | unpacks `.tar.bz2` models |

## Models (downloaded by the user, not bundled)
| Model | Licence | Requirement |
|---|---|---|
| Streaming Zipformer English (k2-fsa, 2023-06-26) | Apache-2.0 | notice |
| NVIDIA Parakeet TDT-CTC 110M (sherpa-onnx export) | CC-BY-4.0 | **Attribution required**: shown on the Models screen and About |
| OpenAI Whisper tiny/base/small (sherpa-onnx export) | MIT | notice |
| Silero VAD | MIT | notice |
Licences were taken from the app's catalogue (`core/.../models/ModelCatalog.kt`, carried over from the earlier project). They were **not re-checked against upstream model cards in this session**; verify before redistributing any model.

## Evaluation clips
The three English clips in `docs/eval` references come from the `test_wavs` folder shipped inside a sherpa-onnx model archive. Their upstream corpus licence was not checked; only the short reference transcripts are included here, not the audio.
