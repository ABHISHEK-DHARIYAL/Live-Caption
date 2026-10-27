# Putting the app on GitHub and building the APK

`.github/workflows/build-apk.yml` runs the unit tests, builds a debug APK on every push, and uploads it.

## One-time setup
1. Create an empty repository on GitHub.
2. In this folder:
   ```
   git init
   git add .
   git commit -m "Lecture Caption 0.10.0"
   git branch -M main
   git remote add origin https://github.com/YOUR_USERNAME/YOUR_REPO.git
   git push -u origin main
   ```
   (The sherpa-onnx library in `app/libs` is about 23 MB, well under GitHub's 100 MB file limit.)

## Getting the APK
- **Every push:** repo -> **Actions** -> latest "Build APK" run -> **Artifacts** -> `LectureCaption-debug-apk`.
- **As a download on the repo page:** push a tag, e.g.
  ```
  git tag v0.10.0
  git push origin v0.10.0
  ```
  The workflow then creates a **Release** with `LectureCaption-<version>-debug.apk` attached.
- Copy the APK to your phone and open it; allow "install unknown apps" once.

## If the build fails
This workflow has never run (it was written without access to GitHub), so a version mismatch on
the first run is possible. Open the failed run's log, fix the version it names in
`build.gradle.kts` / `app/build.gradle.kts`, and push again. The unit tests run first but do not
block the APK; check the `unit-test-reports` artifact if the step shows a warning.
