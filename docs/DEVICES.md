# Devices and compatibility

## Minimum
- Android 10 (API 29). Reason: `AudioPlaybackCapture` needs API 29.
- CPU arm64-v8a or armeabi-v7a (verified from the bundled sherpa-onnx 1.13.8 AAR). **x86/x86_64 are not supported**; the app tells the user on the Models screen.
- About 350 MB of free storage for English captions; more for Whisper (see below).

## Model advice by memory (estimates from `DeviceProfile`, not measurements)
| Installed RAM | Class | Whisper suggested |
|---|---|---|
| under 3 GB | Low | tiny |
| 3 to 5.5 GB | Mid | base |
| 5.5 GB and up | High | small |

RAM estimates used for warnings: Zipformer 2 GB, Whisper tiny 2 GB, Parakeet 3 GB, Whisper base 3 GB, Whisper small 4 GB, detector 1 GB.
A model is blocked below 75% of its estimate and warned below 100%. These are guesses to prevent obviously bad choices; they were not measured.
Android reports about 7% less RAM than the nominal size. A model estimated near the limit shows a warning; clearly too large is not offered for download.

## Storage during install (computed from the catalogue)
| Model | Download | Installed | Free space needed while installing |
|---|---|---|---|
| Live English Zipformer | 310 MB | 70 MB | about 419 MB |
| Parakeet 110M | 104 MB | 131 MB | about 259 MB |
| Whisper tiny | 116 MB | 103 MB | about 241 MB |
| Whisper base | 207 MB | 160 MB | about 404 MB |
| Whisper small | 639 MB | 375 MB | about 1116 MB |
| Speech detector | under 1 MB | under 1 MB | about 1 MB |

## System-audio capture: what is and is not promised
Works only when the Android version allows playback capture **and** the app producing the sound allows it.
Often blocked: DRM video, many phone/VoIP calls, some meeting and music apps, apps that opt out in their manifest.
Android then supplies silence; the app shows "No audio heard" after 10 seconds. Behaviour differs by manufacturer and Android version and is **unverified on real devices**.
Android 14+ shows the screen-capture approval every time. Android 15 can restrict starting foreground services from the background; recordings are started only from the visible app.

## Background limits by manufacturer
Xiaomi/Redmi/POCO (MIUI/HyperOS), Oppo/Realme (ColorOS), Vivo (Funtouch/OriginOS), Huawei/Honor, OnePlus and Samsung ("Sleeping/Deep sleeping apps") may stop a service even while a notification is showing.
Settings > Battery settings opens the system page; set the app to unrestricted and allow auto-start where offered. If a recording is killed anyway, captions already saved are kept and the session is marked Interrupted.

## Tablets and foldables
Layouts are single-column and scale with window size; they have not been tuned for large screens. Untested.

## Not supported / not verified
GPU, NNAPI, Qualcomm NPU: not enabled (unverified). Wear OS, Android TV, Chromebooks: untested.
