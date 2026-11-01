# Evaluation: what was measured, what was not

## Measured (desktop, reproducible)
**Setup.** Three English read-speech clips (6.6 s, 16.7 s, 4.8 s at 8 kHz; 77 words) with their published reference transcripts,
taken from the `test_wavs` folder inside the `sherpa-onnx-whisper-tiny` archive. Models are the exact files in the app's catalogue
(SHA-256 of the archives verified, see VERIFICATION.md). Engine: desktop sherpa-onnx 2.0.1 (PyPI), 1 CPU thread on a shared sandbox CPU.
Scored by the app's own Kotlin scorer (`core/.../eval/ErrorRates.kt`: case and punctuation ignored).
Data and outputs: `docs/eval/`. Harness: `tools/eval/run_whisper_eval.py`.

| Model (role in app) | WER | CER | Decode speed (x real time, lower = faster) |
|---|---|---|---|
| Streaming Zipformer English (live) | 0.0% (0/77) | 0.0% | 0.06 to 0.08 |
| Parakeet 110M (second pass, English) | 2.6% (2/77) | 1.1% | 0.04 |
| Whisper base, language=en (near-live / second pass) | 5.2% (4/77) | 0.8% | 0.15 to 0.17 |
| Whisper tiny, language=en | 7.8% (6/77) | 2.5% | 0.08 to 0.10 |

Streaming latency (algorithmic): with 100 ms chunks the Zipformer produced its first text after **1.2 s of audio** on all three clips.

### How to read this, honestly
- **Tiny sample.** 77 words, one reader style, three clips. A single word changes WER by 1.3 points. Do not quote these as general accuracy.
- The clips are read book speech that Zipformer-class models are trained on, so 0.0% is almost certainly optimistic for real lectures, meetings or accents.
- Some Whisper "errors" are spelling variants (for example American vs British spelling of one word), not mishearings.
- **On this sample the second pass did not improve English**: the live model scored best. That is why the second pass is off by default.
- Decode speeds are from a desktop sandbox CPU. **Nothing here predicts phone speed, latency, memory, heat or battery.**

## Fast playback (1.5x and 2x), desktop, same 3 clips
Clips were time-compressed with ffmpeg `atempo` (pitch preserved, similar to a video player's speed setting) and run through the same models.

| Model | 1x | 1.5x | 2x |
|---|---|---|---|
| Streaming Zipformer (live) | 0.0% | 3.9% | 15.6% |
| Parakeet 110M | 2.6% | 5.2% | 22.1% |
| Whisper base (en) | 5.2% | 5.2% | 23.4% |

Reading: usable at 1.5x, clearly worse at 2x (about 1 word in 6 wrong for the live model, 1 in 4 to 5 for the others). The second pass did not rescue 2x speech.
Same caveats: 77 words, English only, desktop CPU, simulated speed-up; Hindi/Hinglish at speed and the real phone/system-audio path are not measured.
Reproduce: add `--speed 2.0` to the harness command. The app warns about fast playback on Home and the Source screen.

## Not measured
| Item | Status |
|---|---|
| Hindi accuracy (WER/CER, Devanagari output) | **No verified clips available. Not measured.** |
| Hinglish / code-switching | **Not measured.** Output script (Devanagari vs Roman) is unknown until tested |
| Whisper small | Not run (large download); its SHA-256 is not independently verified |
| Phone: time to first caption, live latency, final-pass time | Not measured. Settings > Diagnostics reports measured decode speed after a recording |
| Memory, CPU, battery, thermal, 60-minute stability | Not measured |
| System-audio capture on real devices | Not tested |
| Noisy audio, accents, far-field microphone | Not measured |
| Hindi/Hinglish at 1.5x or 2x | Not measured |

## How to measure Hindi and Hinglish yourself (10 to 20 clips)
1. Record or choose short clips (20 to 60 s): Hindi speech, Hinglish with English words inside Hindi sentences, a lecture, a video, a noisy one.
2. Write the transcript yourself **exactly as you want it**, in the script you expect (Devanagari for Hindi; decide Roman or Devanagari for English words in Hinglish and stay consistent).
   Put `name.wav` (16-bit PCM) and `name.txt` in one folder.
3. Produce hypotheses: `python3 tools/eval/run_whisper_eval.py --family whisper --prefix base --lang hi --model-dir <dir> --wavs <clips> --out <hyp>`
   (try `--lang ""` for auto-detect and models tiny/base/small; this is the same engine family as the app).
4. Score: `./gradlew :core:run --args="--dir <clips> <hyp>"` (reference files are the `.txt` in the clip folder).
5. Report WER for English and CER for Hindi/Hinglish, with Devanagari and Roman variants kept separate. The scorer reports both numbers.
6. On the phone, run the same clip in the app and compare with step 4; note Diagnostics output.

Send me the score lines and, if you like, the hypothesis files, and I will use them to set honest defaults and wording.
