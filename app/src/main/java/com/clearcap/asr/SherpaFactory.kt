package com.clearcap.asr

import com.clearcap.models.ModelFamily
import com.clearcap.models.ModelInfo
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig

/** A model could not be loaded. [message] is written for the user. */
class EngineLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)

const val SAMPLE_RATE = 16_000

/**
 * Builds sherpa-onnx recognizers. Execution is CPU only: no hardware acceleration has been verified on real
 * devices for these models, so none is enabled. All load failures (missing files, out of memory, missing native
 * library for the CPU) are converted to [EngineLoadException] so a model that does not fit never crashes the app.
 */
object SherpaFactory {
    inline fun <T> guard(what: String, block: () -> T): T = try {
        block()
    } catch (e: EngineLoadException) {
        throw e
    } catch (e: OutOfMemoryError) {
        throw EngineLoadException("Not enough memory to load $what. Close other apps or choose a smaller model.", e)
    } catch (e: UnsatisfiedLinkError) {
        throw EngineLoadException("The speech library is not available for this phone's CPU.", e)
    } catch (e: Throwable) {
        throw EngineLoadException("$what could not be loaded: ${e.message ?: e.javaClass.simpleName}", e)
    }

    private fun find(p: Map<String, String>, what: String, test: (String) -> Boolean): String =
        p.entries.firstOrNull { test(it.key) }?.value ?: throw EngineLoadException("Model file missing: $what. Download the model again.")

    fun streaming(m: ModelInfo, p: Map<String, String>, threads: Int): OnlineRecognizer = guard(m.label) {
        require(m.family == ModelFamily.ZIPFORMER_TRANSDUCER)
        OnlineRecognizer(
            config = OnlineRecognizerConfig(
                modelConfig = OnlineModelConfig(
                    transducer = OnlineTransducerModelConfig(
                        encoder = find(p, "encoder") { it.startsWith("encoder") && it.endsWith(".int8.onnx") },
                        decoder = find(p, "decoder") { it.startsWith("decoder") && it.endsWith(".int8.onnx") },
                        joiner = find(p, "joiner") { it.startsWith("joiner") && it.endsWith(".int8.onnx") },
                    ),
                    tokens = find(p, "tokens") { it == "tokens.txt" },
                    numThreads = threads,
                    provider = "cpu",
                ),
                // A sentence ends after about 0.9 s of silence following speech, or after 25 s regardless.
                endpointConfig = EndpointConfig(
                    rule1 = EndpointRule(false, 2.0f, 0.0f),
                    rule2 = EndpointRule(true, 0.9f, 0.0f),
                    rule3 = EndpointRule(false, 0.0f, 25.0f),
                ),
                enableEndpoint = true,
                decodingMethod = "greedy_search",
            ),
        )
    }

    /** [language] is Whisper's code ("en", "hi") or "" for automatic detection. Task is always "transcribe": nothing is translated. */
    fun offline(m: ModelInfo, p: Map<String, String>, language: String, threads: Int): OfflineRecognizer = guard(m.label) {
        val model = when (m.family) {
            ModelFamily.PARAKEET_CTC -> OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = find(p, "model") { it == "model.int8.onnx" }),
                tokens = find(p, "tokens") { it.endsWith("tokens.txt") },
                numThreads = threads, provider = "cpu",
            )
            ModelFamily.WHISPER -> OfflineModelConfig(
                whisper = OfflineWhisperModelConfig(
                    encoder = find(p, "encoder") { it.endsWith("-encoder.int8.onnx") },
                    decoder = find(p, "decoder") { it.endsWith("-decoder.int8.onnx") },
                    language = language, task = "transcribe",
                ),
                tokens = find(p, "tokens") { it.endsWith("tokens.txt") },
                numThreads = threads, provider = "cpu",
            )
            else -> throw EngineLoadException("${m.label} is not an offline recognizer.")
        }
        OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = model, decodingMethod = "greedy_search"))
    }

    fun vad(path: String, maxSpeechSec: Float): Vad = guard("the speech detector") {
        Vad(
            config = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = path, threshold = 0.5f, minSilenceDuration = 0.5f, minSpeechDuration = 0.25f,
                    windowSize = 512, maxSpeechDuration = maxSpeechSec,
                ),
                sampleRate = SAMPLE_RATE, numThreads = 1, provider = "cpu", debug = false,
            ),
        )
    }
}
