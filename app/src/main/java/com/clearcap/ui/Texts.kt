package com.clearcap.ui

const val PRIVACY_TEXT = """What stays on your phone
Everything. Speech recognition runs on this phone with no internet. Your audio, transcripts, vocabulary and settings are stored only in this app's private storage, which is excluded from Android backups. Nothing is uploaded, and there are no accounts, ads, analytics or crash reporters.

What uses the internet
Only model downloads, and only after you tap Download on the Models screen. They come from the public sherpa-onnx releases on GitHub. GitHub can see the request like any download. After the models are installed, captions work in airplane mode.

Audio storage
Off by default: audio is never written to storage. If you choose Temporary, audio is saved in a private folder while recording so an interrupted recording can be recovered or checked again, and it is deleted automatically once the transcript is saved. If you choose Keep, audio stays until you delete it. You can delete all saved audio or all transcripts in Settings at any time. Uninstalling the app removes everything.

Transcripts
Saved in the app's private database: the text, timestamps, language and model used. You can edit, rename, delete, copy, share or export them. Sharing and exporting send the text only to the app or file you choose.

System audio
When you choose System audio, Android shows its own screen-capture approval. This app uses it only to receive the sound that other apps play, and only from apps that allow capture. It does not record your screen. Android shows an indicator while capture is active.

Floating captions
The caption bar only draws text over other apps. It cannot read what is on their screens.

Permissions
Microphone: required for the microphone, and required by Android for system-audio capture. Notifications: shows the recording notification. Display over other apps: optional, for the floating caption bar. Internet: model downloads only.

Removed feature
Earlier versions could send a transcript to a cloud AI for notes. That feature has been removed. Notes already saved by it remain on your phone.
"""

const val ABOUT_TEXT = """Clearcap 2.0.0
Offline live captions and transcripts for Android.

Speech engine
sherpa-onnx 1.13.8 (Apache-2.0) with ONNX Runtime (MIT). All recognition runs through it on the CPU.

Models (downloaded by you, not bundled)
- Streaming Zipformer English: Apache-2.0
- NVIDIA Parakeet TDT-CTC 110M (English): CC-BY-4.0, attribution: NVIDIA Parakeet 110M
- OpenAI Whisper tiny, base, small, converted for sherpa-onnx: MIT
- Silero VAD speech detector: MIT

Code and ideas adapted from
- OpenWhispr (MIT): vocabulary import, correction learning, prompt and echo filters
- VoiceFlow (MIT): recovery approach, export formats, job and state design
- faster-whisper (MIT, SYSTRAN): used as a behavioural reference for filtering and thresholds only; no code from it runs on the phone
- The earlier Clearcap project this app grew from

A fuller list with file-level notes is in ATTRIBUTION.md in the project. Check the license of each model before redistributing it.

Limits you should know
Accuracy depends on the model, the audio and the speaker. Hindi and Hinglish accuracy has not been measured for your speech. Some apps block capture of their audio. Estimates of memory use are not measurements.
"""
