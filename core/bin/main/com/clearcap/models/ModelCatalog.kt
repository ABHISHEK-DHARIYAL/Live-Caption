package com.clearcap.models

/** What a model does in the pipeline. */
enum class ModelKind { LIVE_STREAMING, OFFLINE_ACCURATE, VAD }

/** Decoder family; decides which sherpa-onnx configuration the app builds. */
enum class ModelFamily { ZIPFORMER_TRANSDUCER, PARAKEET_CTC, WHISPER, SILERO_VAD }

/** Rough device class by installed RAM. See [DeviceProfile]. */
enum class DeviceTier { LOW, MID, HIGH }

/** A file that must exist after unpacking, with its exact size (verified against the published archive). */
data class ModelFile(val name: String, val bytes: Long)

/**
 * One downloadable model. [archiveBytes], [archiveSha256] and [files] were computed from the archives published
 * at [url]; they are used to verify the download and the unpacked result. [minTotalRamMb] and [minTier] are
 * conservative ESTIMATES (not measurements on a device); the UI labels them as such.
 */
data class ModelInfo(
    val id: String,
    val label: String,
    val kind: ModelKind,
    val family: ModelFamily,
    val url: String,
    val archiveBytes: Long,
    val archiveSha256: String,
    val files: List<ModelFile>,
    val languages: Set<String>,
    val minTotalRamMb: Int,
    val minTier: DeviceTier,
    val relativeSpeed: Int,
    val relativeAccuracy: Int,
    val bestFor: String,
    val license: String,
    val attribution: String?,
    val isSingleFile: Boolean = false,
) {
    val downloadMb: Int get() = ((archiveBytes + 999_999) / 1_000_000).toInt()
    val installedBytes: Long get() = files.sumOf { it.bytes }
    val installedMb: Int get() = ((installedBytes + 999_999) / 1_000_000).toInt()
    val multilingual: Boolean get() = "multi" in languages
    fun supportsLanguage(code: String): Boolean = multilingual || code in languages || "any" in languages
    fun file(name: String): ModelFile? = files.firstOrNull { it.name == name }
}

/** Everything the app can download. Nothing is fetched until the user asks. */
object ModelCatalog {
    private const val BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/"

    val ZIPFORMER_EN = ModelInfo(
        id = "zipformer-en", label = "Live English (Zipformer)", kind = ModelKind.LIVE_STREAMING, family = ModelFamily.ZIPFORMER_TRANSDUCER,
        url = BASE + "sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
        archiveBytes = 310414022L, archiveSha256 = "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282",
        files = listOf(
            ModelFile("decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 540688L),
            ModelFile("encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 70108816L),
            ModelFile("joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx", 259416L),
            ModelFile("tokens.txt", 5048L),
        ),
        languages = setOf("en"), minTotalRamMb = 2000, minTier = DeviceTier.LOW,
        relativeSpeed = 5, relativeAccuracy = 4,
        bestFor = "Streaming captions with the lowest delay. English only.",
        license = "Apache-2.0", attribution = null,
    )

    val PARAKEET_110M = ModelInfo(
        id = "parakeet-110m", label = "Parakeet 110M (English)", kind = ModelKind.OFFLINE_ACCURATE, family = ModelFamily.PARAKEET_CTC,
        url = BASE + "sherpa-onnx-nemo-parakeet_tdt_ctc_110m-en-36000-int8.tar.bz2",
        archiveBytes = 104337827L, archiveSha256 = "17f945007b52ccd8b7200ffc7c5652e9e8e961dfdf479cefcabd06cf5703630b",
        files = listOf(
            ModelFile("model.int8.onnx", 131652171L),
            ModelFile("tokens.txt", 9953L),
        ),
        languages = setOf("en"), minTotalRamMb = 3000, minTier = DeviceTier.MID,
        relativeSpeed = 5, relativeAccuracy = 5,
        bestFor = "Most accurate English transcript with punctuation and capitals. English only.",
        license = "CC-BY-4.0", attribution = "NVIDIA Parakeet 110M (CC-BY-4.0)",
    )

    val WHISPER_TINY = ModelInfo(
        id = "whisper-tiny", label = "Whisper tiny (multilingual)", kind = ModelKind.OFFLINE_ACCURATE, family = ModelFamily.WHISPER,
        url = BASE + "sherpa-onnx-whisper-tiny.tar.bz2",
        archiveBytes = 116204861L, archiveSha256 = "c46116994e539aa165266d96b325252728429c12535eb9d8b6a2b10f129e66b1",
        files = listOf(
            ModelFile("tiny-decoder.int8.onnx", 89855401L),
            ModelFile("tiny-encoder.int8.onnx", 12937772L),
            ModelFile("tiny-tokens.txt", 816730L),
        ),
        languages = setOf("multi"), minTotalRamMb = 2000, minTier = DeviceTier.LOW,
        relativeSpeed = 4, relativeAccuracy = 2,
        bestFor = "Smallest multilingual model, for low-memory phones. Expect more mistakes, especially in Hindi and mixed speech.",
        license = "MIT", attribution = null,
    )

    val WHISPER_BASE = ModelInfo(
        id = "whisper-base", label = "Whisper base (multilingual)", kind = ModelKind.OFFLINE_ACCURATE, family = ModelFamily.WHISPER,
        url = BASE + "sherpa-onnx-whisper-base.tar.bz2",
        archiveBytes = 207557382L, archiveSha256 = "911b2083efd7c0dca2ac3b358b75222660dc09fb716d64fbfc417ba6c99ff3de",
        files = listOf(
            ModelFile("base-decoder.int8.onnx", 130672026L),
            ModelFile("base-encoder.int8.onnx", 29120534L),
            ModelFile("base-tokens.txt", 816730L),
        ),
        languages = setOf("multi"), minTotalRamMb = 3000, minTier = DeviceTier.MID,
        relativeSpeed = 3, relativeAccuracy = 3,
        bestFor = "Hindi and other languages at moderate speed and memory.",
        license = "MIT", attribution = null,
    )

    val WHISPER_SMALL = ModelInfo(
        id = "whisper-small", label = "Whisper small (multilingual)", kind = ModelKind.OFFLINE_ACCURATE, family = ModelFamily.WHISPER,
        url = BASE + "sherpa-onnx-whisper-small.tar.bz2",
        archiveBytes = 639387718L, archiveSha256 = "486a46afbb7ba798507190ffe02fea2dd726049af212e774537efac6afb210a6",
        files = listOf(
            ModelFile("small-decoder.int8.onnx", 262226114L),
            ModelFile("small-encoder.int8.onnx", 112442483L),
            ModelFile("small-tokens.txt", 816730L),
        ),
        languages = setOf("multi"), minTotalRamMb = 4000, minTier = DeviceTier.HIGH,
        relativeSpeed = 2, relativeAccuracy = 4,
        bestFor = "Best multilingual accuracy here. Large download, slowest, needs the most memory.",
        license = "MIT", attribution = null,
    )

    val SILERO_VAD = ModelInfo(
        id = "silero-vad", label = "Speech detector (Silero VAD)", kind = ModelKind.VAD, family = ModelFamily.SILERO_VAD,
        url = BASE + "silero_vad.onnx",
        archiveBytes = 643854L, archiveSha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        files = listOf(ModelFile("silero_vad.onnx", 643854L)),
        languages = setOf("any"), minTotalRamMb = 1000, minTier = DeviceTier.LOW,
        relativeSpeed = 5, relativeAccuracy = 4,
        bestFor = "Finds speech so only speech is decoded. Needed for Hindi, Hinglish and the accurate pass.",
        license = "MIT", attribution = null,
        isSingleFile = true,
    )

    val all: List<ModelInfo> = listOf(ZIPFORMER_EN, PARAKEET_110M, WHISPER_TINY, WHISPER_BASE, WHISPER_SMALL, SILERO_VAD)
    val whisper: List<ModelInfo> = all.filter { it.family == ModelFamily.WHISPER }
    val accurate: List<ModelInfo> = all.filter { it.kind == ModelKind.OFFLINE_ACCURATE }

    fun byId(id: String?): ModelInfo? = all.firstOrNull { it.id == id }
}
