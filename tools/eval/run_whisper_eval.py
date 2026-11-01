#!/usr/bin/env python3
"""Desktop evaluation harness: transcribes every WAV in a folder with an offline Whisper or Parakeet model from
sherpa-onnx and writes one hypothesis .txt per clip, plus timing. Scoring is done by the app's own Kotlin scorer
(core/.../eval/EvalMain.kt) so desktop and app use identical normalisation:

    python3 tools/eval/run_whisper_eval.py --model-dir <extracted whisper dir> --prefix tiny --lang en \
        --wavs <dir with a.wav + a.txt ...> --out <hyp dir>
    ./gradlew :core:run --args="--dir <dir with refs> <hyp dir>"

Reference transcripts live next to each WAV as <name>.txt. This measures the model on a laptop CPU, NOT on a phone:
phone speed, memory and heat must be measured on the phone (Settings > Diagnostics shows measured decoding speed).
Version note: the desktop wheel (sherpa-onnx PyPI) can differ from the AAR bundled in the app (1.13.8).
"""
import argparse, json, os, time, wave
import numpy as np
import sherpa_onnx


def read_wav(path):
    with wave.open(path) as w:
        assert w.getsampwidth() == 2, "16-bit PCM WAV only"
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32) / 32768.0
        if w.getnchannels() > 1:
            data = data.reshape(-1, w.getnchannels()).mean(axis=1)
        return data, w.getframerate()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model-dir", required=True)
    ap.add_argument("--family", default="whisper", choices=["whisper", "nemo", "zipformer"], help="whisper = offline Whisper, nemo = Parakeet CTC, zipformer = streaming transducer (live engine)")
    ap.add_argument("--prefix", default="", help="whisper file prefix, e.g. tiny, base, small")
    ap.add_argument("--lang", default="", help="whisper language code (en, hi) or empty for auto-detect")
    ap.add_argument("--wavs", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--threads", type=int, default=1)
    ap.add_argument("--speed", type=float, default=1.0, help="time-compress the clips first (ffmpeg atempo, pitch preserved), e.g. 1.5 or 2.0, to test fast playback")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    if a.speed != 1.0:
        import subprocess, tempfile
        fast = tempfile.mkdtemp(prefix="speed_")
        for f in os.listdir(a.wavs):
            if f.endswith(".wav"):
                subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", os.path.join(a.wavs, f), "-filter:a", f"atempo={a.speed}", "-ar", "16000", "-ac", "1", os.path.join(fast, f)], check=True)
        a.wavs = fast
    d = a.model_dir
    t0 = time.time()
    if a.family == "whisper":
        rec = sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=f"{d}/{a.prefix}-encoder.int8.onnx", decoder=f"{d}/{a.prefix}-decoder.int8.onnx",
            tokens=f"{d}/{a.prefix}-tokens.txt", language=a.lang, task="transcribe", num_threads=a.threads)
    elif a.family == "nemo":
        rec = sherpa_onnx.OfflineRecognizer.from_nemo_ctc(model=f"{d}/model.int8.onnx", tokens=f"{d}/tokens.txt", num_threads=a.threads)
    else:
        pick = lambda p: next(os.path.join(d, f) for f in sorted(os.listdir(d)) if f.startswith(p) and f.endswith(".int8.onnx"))
        rec = sherpa_onnx.OnlineRecognizer.from_transducer(
            encoder=pick("encoder"), decoder=pick("decoder"), joiner=pick("joiner"), tokens=f"{d}/tokens.txt",
            num_threads=a.threads, decoding_method="greedy_search", enable_endpoint_detection=False)
    load_s = time.time() - t0
    rows = []
    for f in sorted(os.listdir(a.wavs)):
        if not f.endswith(".wav"):
            continue
        x, sr = read_wav(os.path.join(a.wavs, f))
        name = f[:-4]
        row = {"clip": name, "audio_s": round(len(x) / sr, 2)}
        if a.family == "zipformer":
            # Feed 100 ms chunks like the app does; record the audio position at which text first appears.
            st = rec.create_stream()
            step = int(sr * 0.1)
            first_at = None
            t1 = time.time()
            for i in range(0, len(x), step):
                st.accept_waveform(sr, x[i:i + step])
                while rec.is_ready(st):
                    rec.decode_stream(st)
                if first_at is None and rec.get_result(st).strip():
                    first_at = (i + step) / sr
            st.accept_waveform(sr, np.zeros(int(sr * 0.4), dtype=np.float32))
            st.input_finished()
            while rec.is_ready(st):
                rec.decode_stream(st)
            dec = time.time() - t1
            text = rec.get_result(st).strip()
            row["first_text_at_audio_s"] = None if first_at is None else round(first_at, 2)
        else:
            st = rec.create_stream()
            st.accept_waveform(sr, x)
            t1 = time.time()
            rec.decode_stream(st)
            dec = time.time() - t1
            text = st.result.text.strip()
        open(os.path.join(a.out, name + ".txt"), "w", encoding="utf-8").write(text + "\n")
        row.update({"decode_s": round(dec, 2), "rtf": round(dec / (len(x) / sr), 3), "text": text})
        rows.append(row)
    print(json.dumps({"family": a.family, "model": a.prefix or os.path.basename(d.rstrip("/")), "lang": a.lang or "auto", "threads": a.threads, "load_s": round(load_s, 2), "clips": rows}, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
