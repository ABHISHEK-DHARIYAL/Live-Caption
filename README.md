# LectureCaption

**Developed by [Abhishek.DL](https://github.com/ABHISHEK-DHARIYAL)**

A privacy-first Android app that captures system (or microphone) audio, transcribes it
offline in real time, shows a floating Live-Caption-style overlay, and saves the full
lecture transcript to a local Room database — continuously, as one session, from Start
to Stop.

## ⚠️ Read this first — what I could and couldn't verify

I built this in a sandboxed environment with **no Android SDK, no Gradle, no emulator, and
no network access to Google's Maven repository (`dl.google.com`)**. That means:

- I could not run a Gradle build, so **this project has not been compiled**. You need to
  open it in Android Studio, let Gradle sync, fix whatever version mismatch comes up
  (AGP/Kotlin/Compose compiler versions drift constantly — see "If sync fails" below),
  and build it there.
- The Gradle wrapper jar (`gradle/wrapper/gradle-wrapper.jar`) is a binary file I can't
  fetch from here. Android Studio will offer to regenerate it automatically on first open
  ("Gradle wrapper not found — attempting to use local Gradle distribution" or a prompt to
  create one) — accept that prompt, or run `gradle wrapper` once with any local Gradle
  install.
- **Whisper inference is a real stub, not a working feature.** Vosk is fully wired and
  will actually transcribe. Whisper's model download/management UI works, but there's no
  compiled whisper.cpp native library behind it — see `WhisperEngine.kt` for exactly what's
  needed to finish it. I'd rather tell you this plainly than hand you code that pretends
  to call an inference engine that isn't there.

## What's real and working

- MediaProjection + `AudioPlaybackCaptureConfiguration` + `AudioRecord` system-audio capture
  (`audio/AudioCaptureManager.kt`), using the actual public API
  (`AudioPlaybackCaptureConfiguration.Builder(mediaProjection).addMatchingUsage(...)`),
  not an invented one.
- A transparent bridge Activity (`MediaProjectionRequestActivity`) to show the system
  consent dialog, since only an Activity can launch it — a Service cannot.
- A foreground service (`TranscriptionForegroundService`) declaring
  `foregroundServiceType="mediaProjection"`, which is mandatory on Android 14+ (API 34) for
  a service using a MediaProjection-backed AudioRecord.
- Vosk offline speech recognition, fully wired end to end (`stt/VoskEngine.kt`), with a
  real model download/unzip/delete flow (`stt/ModelManager.kt`) driven from Settings.
- Room database with `Lecture` + `TranscriptSegment` entities; every recognized chunk is
  written to disk immediately (`LectureRepository.appendSegment`), not batched at session
  end, so a crash loses at most one unflushed chunk.
- A floating overlay window (`overlay/CaptionOverlayManager.kt`) via
  `WindowManager` + `TYPE_APPLICATION_OVERLAY`, draggable, resizable, with adjustable font
  size/transparency/background/max lines, that survives switching apps because it's
  attached to WindowManager, not to any Activity.
- Lecture history, transcript viewer, TXT/Markdown export via `FileProvider` share intent,
  copy-all, search, rename, delete.
- A `Delete All Data` control and a static Privacy screen matching the required notice text.
- A deliberately decoupled `NotesGenerator` interface so a real AI provider can be dropped
  in later without touching any transcription code.

## Project structure

```
app/src/main/java/com/lecturecaption/app/
  audio/       MediaProjection + AudioRecord capture, chunking pipeline
  data/        Room entities, DAOs, database, repository
  stt/         SpeechEngine interface, Vosk (working), Whisper (stub), model downloads
  service/     Foreground service, MediaProjection request bridge activity
  overlay/     Floating live-caption window
  ui/screens/  Compose screens (Main, History, Transcript Viewer, Settings, Privacy)
  viewmodel/   MainViewModel
  export/      TXT/Markdown export
  notes/       Placeholder "Generate Notes" module
```

## Build & run

1. Open the `LectureCaption/` folder in Android Studio (Koala/2024.1 or newer recommended
   for AGP 8.5.x + Compose compiler 1.5.14).
2. Let Gradle sync. **If sync fails on version mismatches**, this is expected — I picked
   currently-plausible versions (AGP 8.5.2, Kotlin 1.9.24, Compose BOM 2024.06.00, Room
   2.6.1) but couldn't verify them against a live Maven index. Use Android Studio's
   "Upgrade Assistant" / suggested quick-fixes to align AGP/Kotlin/Compose-compiler
   versions — they must match each other's compatibility table.
3. Connect your Motorola Edge 50 Neo (Android 16) with USB debugging enabled, or use an
   API 34+ emulator image (system-audio capture behaves closest to a real device; some
   emulator images virtualize audio differently).
4. Run the app. In Settings, download the **Vosk small (English)** model first — Local
   Whisper won't transcribe until the native module described in `WhisperEngine.kt` is
   added.
5. On the main screen, leave **System Audio** selected (the default), pick **Vosk**, and
   tap **Start Transcription**. Grant the screen-capture consent dialog (this is what
   `MediaProjectionRequestActivity` triggers) and the overlay permission if prompted.
6. Play audio in another app (YouTube, a podcast app, etc.) and switch back to
   LectureCaption or to any other app — the floating caption and the persistent
   notification should keep running.

## Known platform restrictions (not bugs)

- **Some apps block playback capture.** Android lets an app mark its audio as
  non-capturable via `AudioAttributes.Builder#setAllowedCapturePolicy` — commonly used by
  DRM-protected video players. When that happens, `AudioRecord.Builder.build()` throws
  `UnsupportedOperationException`, or the resulting `AudioRecord` fails to initialize.
  The app surfaces this as "System audio could not be captured…" (see
  `AudioCaptureManager` → `AudioCaptureError.TargetAppBlocksCapture`) rather than pretending
  to bypass it — because it can't, and shouldn't.
- **Android 14+ foreground service rules.** `foregroundServiceType="mediaProjection"` in
  the manifest is required, and `MediaProjection.registerCallback()` must be called before
  starting capture — both are already implemented.
- **No microphone permission is requested when System Audio is selected.** `RECORD_AUDIO`
  is only requested on the Microphone path (`MainActivity.requestMicPermission`).

## Development phases (matches the original spec's plan)

The code is written phase-by-phase as requested — Phase 1–4 (system audio → PCM →
Vosk → live overlay → Room → history) are implemented and should work once you fix any
Gradle version drift. Phase 5 (export) is implemented. Phase 6 (AI notes) is intentionally
a placeholder, not connected to any API.
