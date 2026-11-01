# Build, install and test

## Prerequisites
- Android Studio (current stable, with the Android SDK Platform 35 and Build-Tools installed through SDK Manager).
- JDK 17 (Android Studio's bundled JBR 17 or 21 works for Gradle; the project compiles to Java 17).
- A phone with Android 10+ and arm64-v8a or armeabi-v7a. An x86 emulator will not run the speech library.

## Android Studio
1. File > Open the project folder. Let Gradle sync (downloads Gradle 8.9 and dependencies).
2. Run configuration `app`, choose your phone (USB debugging) and press Run.

## Command line
```
./gradlew :core:test                 # pure Kotlin tests (152)
./gradlew :app:testDebugUnitTest     # app JVM tests (none yet)
./gradlew :app:assembleDebug         # APK
./gradlew :app:connectedDebugAndroidTest   # Room tests; needs a device or arm emulator
```
Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

The debug build is signed with `app/debug.keystore` (public key, debug only) so successive CI builds update each other.
Never ship a release with it; create your own release keystore.

## GitHub Actions
`.github/workflows/build.yml`: unit tests, then `assembleDebug`, uploading test reports and the APK. Tests are blocking.

## Gradle wrapper
`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` come from the Gradle 8.9.0 source tree; the properties file
points to the official 8.9 distribution. The distribution checksum is not pinned (add `distributionSha256Sum` after you
confirm it from gradle.org if you want that protection).

## What to send back if something fails
1. The first failing Gradle task and its error block (not the whole log), or the CI run's "Build" step output.
2. `core/build/reports/tests` summary if a test fails.
3. For runtime problems: Settings > Diagnostics > Copy report (numbers only, no text or audio), the phone model and Android version, and `adb logcat -d | grep -i clearcap` if you can.

## On-device checklist (not yet done; see docs/VERIFICATION.md)
Run each on a real phone and note pass/fail:
1. Microphone, English: caption appears within a second or two of speaking; Stop saves a transcript.
2. System audio: play a YouTube clip (a permitted source); accept the prompt; captions follow. Then try a DRM or call source and confirm the "No audio heard" message instead of text.
3. Hindi and Hinglish with a known clip; compare with the Evaluation procedure.
4. Pause/Resume; incoming call or another app taking the microphone; Bluetooth headset connect/disconnect.
5. Kill the app mid-recording (swipe away, or `adb shell am kill com.clearcap`): reopen and confirm "Interrupted" with saved captions; with audio storage on, "Re-check audio" works.
6. Airplane mode after models are installed: captions still work.
7. Model download: Wi-Fi only on/off, cancel, resume after turning Wi-Fi off, corrupt-file handling (delete a model file with a file manager, reopen, confirm it is reported).
8. Long session (60+ minutes screen off) and note battery and temperature.
9. Android 10, 12, 14 and 15 if available; and at least one Xiaomi/Oppo/Vivo/Samsung phone for background limits.
