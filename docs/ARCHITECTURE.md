# Architecture

## Modules
| Module | Kind | Contents |
|---|---|---|
| `:core` | pure Kotlin/JVM, no Android | `text/` filters, casing, vocabulary corrector + exact alias rules, text pipeline; `dsp/` resampler, mono conversion, auto-gain, level meter, PCM spool; `transcript/` model, reconciler, aligner, exporter, legacy import, diagnostics; `eval/` WER/CER; `models/` catalogue, device profile; `pipeline/` engine router, refinement planner; `capture/` format planning; `session/` retention and recovery policies |
| `:app` | Android application | capture, sherpa-onnx engines, model manager, Room, DataStore, foreground service, overlay, WorkManager, Compose UI |

Everything that can be a pure function lives in `:core` so it is unit-tested without an emulator. The original plan listed six modules;
a single Android module plus `:core` was chosen because multi-module wiring cannot be verified without Gradle here and gave no testing benefit.

## Data flow of a recording
```
Microphone OR playback capture (never both)          capture/AudioCapture.kt
  -> device-native PCM -> mono -> windowed resample to 16 kHz   (core dsp/ToMono16k)
  -> raw 16 kHz chunk (100 ms) ----> optional spool file (private storage)   (core dsp/PcmSpool)
  -> auto-gain copy ---------------> bounded Channel(100 chunks, drop OLDEST, drops counted)
  -> recognition thread: LiveRecognizer.accept()            asr/LiveRecognizer.kt
        STREAMING  : sherpa OnlineRecognizer (English Zipformer), partial + final per endpoint
        NEAR_LIVE  : Silero VAD cuts speech (<=12 s) -> sherpa OfflineRecognizer (Whisper) per segment
  -> SegmentTextPipeline (filters, aliases, casing, fuzzy vocabulary)   raw text kept separately
  -> CaptionReconciler (stable segment ids, duplicate and extension handling)
  -> Room: every final segment saved immediately; session row updated every 5 s
  -> SessionBus (StateFlow) -> Compose UI and the floating caption bar
On Stop: drain, finish engine, free live model, optional second pass (SegmentAligner), mark COMPLETE, delete temporary audio.
```

## Replaceable engine
`LiveRecognizer` is the only interface the session code sees. `StreamingRecognizer` and `NearLiveRecognizer` implement it.
`EngineRouter` (core) chooses by language and installed models; adding a runtime means implementing this interface and a catalogue entry.

## Threads
Capture thread (high priority, only reads, converts, writes the spool, enqueues) -> one recognition thread -> coroutines on `Default` for DB and refinement.
Nothing recognises on the UI thread. The channel is bounded, so a slow phone loses live audio (counted and shown) rather than growing memory;
spooled audio is complete, so no audio is lost when audio storage is on.

## Session states
`RECORDING -> FINALIZING -> COMPLETE`, or `INTERRUPTED` (killed process or capture/engine failure with saved text) or `FAILED` (nothing saved).
`RecoverySweep` runs at app start when no capture is active.

## Storage (Room, version 1)
- `sessions`: id, title, times, duration, status, source, language mode, detected language, live mode, model ids, active version, edited text, behind/dropped ms, audio file name, notes, status note, imported flag.
- `segments`: (sessionId, idx) key, start/end ms, `liveText`, `refinedText`, `rawText`, language. Cascade delete.
- `vocabulary`: unique term, aliases (newline separated).
Versions: Original (`liveText`), Refined (`refinedText`, per segment, falls back to original), My edit (whole-transcript text). `rawText` is the exact engine output.
Earlier Clearcap JSON transcripts and the SharedPreferences dictionary are imported once; JSON files are moved to `transcripts_imported/`.
Preferences use DataStore. Audio lives in `noBackupFilesDir/audio`. Backups are disabled and extraction rules exclude app data.

## Models
`ModelCatalog` (core) holds URL, archive size, SHA-256, every file name and exact size, licence, RAM estimate, tier.
`ModelManager` downloads with HTTP Range resume to `<id>.part`, verifies SHA-256, unpacks into `<id>.tmp`, checks each file size, writes `.complete`, then renames to `<id>`.
Start-up and pre-recording checks re-verify file presence and sizes. Downloads run in WorkManager (Wi-Fi only by default, retry with backoff). Nothing downloads without a tap.

## Safety rules enforced in code
- Silence/noise segments are not sent to Whisper (`SpeechFilters.isSpeechLike`), and known hallucination phrases and loops are removed.
- Refined text replaces original only after overlap and length checks (`SegmentAligner`); otherwise the original stays.
- Whisper task is `transcribe`. There is no translation path.
- Recognised text is data: it is never executed, used as a path, or interpolated into queries (Room uses bound parameters; search escapes LIKE wildcards).
- Diagnostics contain counters and codes only.

## Rejected alternatives
faster-whisper/CTranslate2 (Python, no Android build); whisper.cpp (would need an unverified NDK/JNI build; revisit only if sherpa Whisper fails Hindi/Hinglish);
React Native/whisper.rn (not native Kotlin); mixing mic and system audio (no verified echo handling); cloud notes (violates "never upload").
