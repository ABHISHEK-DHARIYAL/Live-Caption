# Building the APK with GitHub Actions

This project now includes `.github/workflows/build-apk.yml`, which builds a debug
APK automatically and uploads it as a downloadable artifact — no third-party
service (Codemagic/AppCircle) needed.

## One-time setup

1. Unzip this file.
2. Create a new repository on GitHub (public or private, doesn't matter).
3. Push the unzipped folder's contents to that repo, either:
   - Via GitHub's web UI: "Add file" → "Upload files" → drag the whole folder in, or
   - Via git:
     ```
     git init
     git add .
     git commit -m "Initial commit"
     git branch -M main
     git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
     git push -u origin main
     ```

## Getting the APK

1. On GitHub, go to your repo's **Actions** tab.
2. You should see a "Build Debug APK" run start automatically after the push
   (or click "Run workflow" to trigger it manually).
3. Wait for it to finish (5-15 minutes; ☑️ green check = success, ❌ red = failed —
   click into the run to read the error log).
4. On the finished run's page, scroll to **Artifacts** at the bottom and click
   `LectureCaption-debug-apk` to download a zip containing the `.apk` file.
5. Transfer that `.apk` to your phone (email it to yourself, Google Drive, USB,
   whatever's easiest) and tap it to install. Android will ask you to allow
   "install from unknown sources" the first time — that's expected since this
   isn't from the Play Store.

## If the build fails

This project's Gradle/AGP/Kotlin/Compose versions (see README.md) were picked
without being tested against a live build, so a version-mismatch error on first
run is possible. The Actions log will usually name the exact incompatible
versions — open the relevant `build.gradle.kts` / `gradle/wrapper/gradle-wrapper.properties`
file, bump the version it suggests, commit, and push again to re-trigger the build.

Also note (from README.md): Vosk transcription is fully working, but Whisper is
a stub — the APK will install and run, but Whisper-mode transcription won't
actually transcribe until that engine is finished.
