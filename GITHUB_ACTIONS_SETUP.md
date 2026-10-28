# Put Clearcap on GitHub and get the APK
1. Create an empty GitHub repo.
2. In this folder: `git init && git add . && git commit -m "Clearcap 1.0" && git branch -M main && git remote add origin https://github.com/YOU/REPO.git && git push -u origin main`
3. Repo -> Actions -> "Build APK" -> latest run -> Artifacts -> `Clearcap-debug-apk`.
4. Or tag a release: `git tag v1.0 && git push origin v1.0` -> the APK is attached to a GitHub Release.
The workflow has not run yet; if it fails, the log names the version to fix. Unit tests do not block the APK.
