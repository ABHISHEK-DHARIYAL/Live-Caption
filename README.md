# Clearcap

Android app (Kotlin, Jetpack Compose, minSdk 29) that shows live captions for any audio playing on the phone
(videos, lectures, Meet/Zoom) or the microphone, and saves a transcript. Offline. No accounts, ads or analytics.

## Why it is built this way (measured)
The previous app used Vosk small + a second language model + Whisper at the same time: words came out wrong
and captions fell ~44 s behind. This one runs ONE light streaming model while you record, and the heavy model
only after you stop.

Desktop benchmark (sherpa-onnx, 1 CPU core, int8, 35 s of test speech with clean / 1.5x-speed / noisy / low-bitrate
variants; WER lower is better, RTF = processing time / audio time):

| Engine | role | WER (clean .. worst) | RTF |
|---|---|---|---|
| Streaming Zipformer en-2023-06-26 | live (default) | 0.0% .. 2.6% | 0.10 |
| Streaming Zipformer 20M | (rejected: too inaccurate) | 13% .. 27% | 0.05 |
| Parakeet 110M | accurate pass (default) | 2.6% .. 5.2% | 0.05 |
| Whisper tiny.en / base.en | (Whisper kept for Hindi) | 6.5% / 3.9% .. 12% | 0.17 / 0.27 |
| Moonshine tiny | not used | 3.9% .. 5.2% | 0.05 |

Caveats: tiny sample (3 clips), read speech, desktop CPU, not a phone. Vosk could not be benchmarked here.
Decode-time dictionary biasing ("hotwords") ran at no speed cost but did not fix the one real error I could
test (a very common word), so it is not included; dictionary words are corrected after decoding instead.

## Hard-audio test and what it changed (v1.1)
A 3.5-minute sung, sped-up pop track was run through every engine (single core, whole file; the opening was
checked against the real lyrics by eye, not scored; lyrics are not included here):

| Engine | Opening verse | Notes |
|---|---|---|
| Streaming Zipformer (old live model) | badly wrong, garbled words | why live captions were weak |
| Parakeet 110M | partly wrong | |
| Parakeet 600M v2 | nearly right | best; speed ~4x real time on one core |
| Whisper base | good, but adds "(upbeat music)" and skips words | |
| Whisper tiny / Moonshine tiny | good start, then invented repeating phrases (18 / 22 repeats) | hallucination loops |

So the default is now **Accurate live captions**: the speech detector cuts sentences and Parakeet 600M (English;
or Whisper for Hindi) transcribes each one a few seconds after it is spoken. **Fast** (streaming Zipformer) stays
as an option. Caveat: this was music, not speech, and not on a phone; real-phone speed is untested. If the phone
cannot keep up the app shows a "behind" warning (pending audio over 5 s).

## Pipeline
capture (AudioPlaybackCapture and/or mic, averaged) -> boost-only auto-gain -> 100 ms chunks
-> ACCURATE (default): Silero VAD cuts sentences -> Parakeet 600M / 110M / Whisper decodes each, a few seconds late
   FAST: streaming Zipformer, instant; then (optional) the accurate pass over the saved speech after Stop
-> noise gate + hallucination filter + dictionary corrector -> saved transcript.
If anything fails the live text is kept.

## Screens
Home (record, source: system / mic / both, live captions) - History (search) - Transcript (edit, copy, share,
AI notes) - Dictionary (add, bulk import, edit; editing a transcript learns new words) - Settings (model cards).

## Build
GitHub: push this folder, Actions -> "Build APK" -> download the artifact (see GITHUB_ACTIONS_SETUP.md).
Local: open in Android Studio. First launch: Settings -> Download "Parakeet 600M" (482 MB) and "Speech detector" (1 MB)
(Fast mode instead needs "Live English", 310 MB). Downloads happen only when you tap Download and resume if interrupted.

## Not verified
Nothing Android-side has been compiled or run from my environment (no Android SDK). The pure-Kotlin logic
(text, dictionary, filters, auto-gain) has 46 passing unit tests. Capture code is reused from the earlier app
that ran on a phone. Live captions are English only; Hindi works in the accurate pass (Whisper).
