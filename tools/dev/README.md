# Developer tooling (optional)

These scripts let the pure-Kotlin parts be compiled and tested **without Gradle or the Android SDK**. They were used
to produce the "run here" results in `docs/VERIFICATION.md`. They are NOT a substitute for the Gradle build.

- `coretest.sh`: compiles `core` with kotlinc 1.9.24 and runs its tests with a minimal JUnit-compatible runner
  (`junit-shim-src/`). Paths at the top of the script are for the original sandbox; adjust them.
- `appcheck_all.sh` + `stubs/`: type-checks the Android sources that do not use Compose against a real `android.jar`
  (API 35) and the real sherpa-onnx classes. `stubs/` holds tiny stand-ins for Room, DataStore and WorkManager, so it
  checks Kotlin types only, not SQL, annotation processing or runtime behaviour.

On your machine use Gradle instead: `./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug`.
