package com.lecturecaption.app.notes

data class LectureNotes(
    val keyConcepts: List<String>,
    val definitions: List<Pair<String, String>>,
    val examples: List<String>,
    val importantPoints: List<String>,
    val examQuestions: List<String>,
    val quickRevision: String
)

/**
 * Deliberately separate from the transcription pipeline (per spec) so an AI provider can be
 * dropped in later without touching audio/STT/Room code at all. This first version needs no
 * API key and no network access — it ships a local placeholder so the UI/button/flow are real
 * and wired up, while the actual "Transcript -> AI -> Notes" step is left for a future engine.
 */
interface NotesGenerator {
    suspend fun generate(transcriptText: String, lectureTitle: String): LectureNotes
}

/** Ships today: no network, no API key, obviously a placeholder rather than a fake AI result. */
class PlaceholderNotesGenerator : NotesGenerator {
    override suspend fun generate(transcriptText: String, lectureTitle: String): LectureNotes {
        return LectureNotes(
            keyConcepts = listOf("Connect an AI provider in Settings to generate real notes."),
            definitions = emptyList(),
            examples = emptyList(),
            importantPoints = emptyList(),
            examQuestions = emptyList(),
            quickRevision = "Note generation requires an AI provider, which isn't configured yet."
        )
    }
}

/**
 * Where a real provider plugs in later — e.g. calling the Anthropic API with the transcript.
 * Left unimplemented on purpose: wiring a live API key/network call is a product decision
 * (which provider, where the key lives, whether it's opt-in) that belongs in Settings, not
 * hardcoded here.
 */
class AiNotesGenerator(/* inject an API client here later */) : NotesGenerator {
    override suspend fun generate(transcriptText: String, lectureTitle: String): LectureNotes {
        throw NotImplementedError("No AI provider configured yet. See NotesGenerator.kt.")
    }
}
