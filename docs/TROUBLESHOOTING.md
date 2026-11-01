# Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| "Download what this language needs" | Required models not installed | Models screen > Download. English needs Live English + speech detector; Hindi/Hinglish/Auto need a Whisper model + detector |
| Download stays "Waiting for Wi-Fi" | Wi-Fi-only is on | Connect to Wi-Fi or turn the switch off on the Models screen |
| "checksum mismatch" | Corrupted download | The file is deleted automatically; tap Try again |
| "Not enough storage" | Install needs the archive and unpacked files at once | Free the amount shown (see docs/DEVICES.md) |
| "No audio heard" in System mode | The playing app blocks capture, nothing is playing, or media is DRM | Try another source, or use the microphone with speakers |
| System mode asks for the microphone | Android requires RECORD_AUDIO for playback capture | Allow it; the microphone is not used in System mode |
| Capture prompt appears each time | Android 14+ requires it | Expected |
| "Android silenced this recording" | A call or another app has the microphone | Captions resume when it ends |
| "Captions are N s behind" | Model too heavy for the phone | Models > Recognition profile > a smaller model; close other apps |
| Hindi text shows English words in Devanagari | Whisper behaviour on code-switching | Use "also written as" aliases in Vocabulary; accuracy is unmeasured (docs/EVALUATION.md) |
| Floating captions missing | Overlay permission off | Settings > Allow display over other apps |
| Recording ended by itself | Manufacturer battery management | Settings > Battery settings; see docs/DEVICES.md. Saved captions are kept as "Interrupted" |
| "This CPU is not supported" | x86/x86_64 device or emulator | Use an arm64 phone |
| Model "not installed correctly" | A file is missing or the wrong size | Models > Verify, then Delete and Download again |
| Build: "SDK location not found" | Android SDK not configured | Open in Android Studio, or set `sdk.dir` in `local.properties` |
| Build: Room/KSP errors | First build after changing versions | `./gradlew clean :app:assembleDebug`, then send the first error |
