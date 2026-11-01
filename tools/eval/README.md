# Evaluation tools

`run_whisper_eval.py` produces hypotheses with desktop sherpa-onnx; the Kotlin scorer in `core` computes WER and CER
(`./gradlew :core:run --args="--dir <refs> <hyps>"`). See `docs/EVALUATION.md` for the method and the measured results.
Add your own clips as `name.wav` + `name.txt` (reference written by a person who listened to it).
