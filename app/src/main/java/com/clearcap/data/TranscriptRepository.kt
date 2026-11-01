package com.clearcap.data

import android.content.Context
import com.clearcap.data.db.AppDatabase
import com.clearcap.data.db.SegmentEntity
import com.clearcap.data.db.SessionEntity
import com.clearcap.data.db.VocabularyEntity
import com.clearcap.text.CorrectionLearner
import com.clearcap.text.SegmentTextPipeline
import com.clearcap.text.VocabEntry
import com.clearcap.text.VocabularyCorrector
import com.clearcap.text.VocabularyRules
import com.clearcap.transcript.ExportDoc
import com.clearcap.transcript.ExportSegment
import com.clearcap.transcript.LanguageMode
import com.clearcap.transcript.LegacyImport
import com.clearcap.transcript.Segment
import com.clearcap.transcript.SessionStatus
import com.clearcap.transcript.TextVersion
import com.clearcap.transcript.TranscriptAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File

fun SegmentEntity.toSegment() = Segment(idx, startMs, endMs, liveText, refinedText, language, rawText)
fun Segment.toEntity(sessionId: String) = SegmentEntity(sessionId, index, startMs, endMs, liveText, refinedText, rawText, language)
fun VocabularyEntity.toEntry() = VocabEntry(term, spokenAs.lines().map { it.trim() }.filter { it.isNotEmpty() })

fun sourceLabel(source: String): String = when (source) {
    "SYSTEM" -> "System audio"
    "MICROPHONE" -> "Microphone"
    "LEGACY_BOTH" -> "System audio + microphone (earlier version)"
    else -> source
}

/** Everything the UI and the recording service read or write about transcripts and vocabulary. */
class TranscriptRepository(private val ctx: Context, private val db: AppDatabase) {
    private val sessionDao = db.sessions()
    private val segmentDao = db.segments()
    private val vocabDao = db.vocabulary()

    /** Audio never leaves this folder, and it is excluded from backups (no_backup storage). */
    val audioDir: File get() = File(ctx.noBackupFilesDir, "audio").apply { mkdirs() }
    fun audioFile(s: SessionEntity): File? = s.audioFile?.let { File(audioDir, it) }?.takeIf { it.exists() }

    // ---- sessions ----
    fun sessions(): Flow<List<SessionEntity>> = sessionDao.observeAll()
    fun search(q: String): Flow<List<SessionEntity>> = if (q.isBlank()) sessionDao.observeAll() else sessionDao.search(escapeLike(q.trim()))
    fun session(id: String): Flow<SessionEntity?> = sessionDao.observe(id)
    suspend fun getSession(id: String): SessionEntity? = sessionDao.get(id)
    fun segments(id: String): Flow<List<Segment>> = segmentDao.observe(id).map { l -> l.map { it.toSegment() } }
    suspend fun segmentList(id: String): List<Segment> = segmentDao.list(id).map { it.toSegment() }

    suspend fun upsertSession(s: SessionEntity) = sessionDao.upsert(s)
    suspend fun upsertSegment(sessionId: String, s: Segment) = segmentDao.upsert(s.toEntity(sessionId))
    suspend fun replaceSegments(sessionId: String, list: List<Segment>) = segmentDao.replaceAll(sessionId, list.map { it.toEntity(sessionId) })
    suspend fun rename(id: String, title: String) = sessionDao.rename(id, title.trim().ifEmpty { "Recording" })
    suspend fun setVersion(id: String, v: TextVersion) = sessionDao.setVersion(id, v.name)
    suspend fun saveEdit(id: String, text: String) = sessionDao.setEdited(id, text, TextVersion.EDITED.name)

    suspend fun delete(id: String) {
        sessionDao.get(id)?.let { s -> audioFile(s)?.delete() }
        sessionDao.delete(id)
    }

    suspend fun deleteAllTranscripts() {
        withContext(Dispatchers.IO) { audioDir.listFiles()?.forEach { it.delete() } }
        sessionDao.deleteAll()
    }

    /** Deletes every stored recording but keeps the transcripts. */
    suspend fun deleteAllAudio(): Int = withContext(Dispatchers.IO) {
        var n = 0
        for (s in sessionDao.withAudio()) { audioFile(s)?.delete(); sessionDao.clearAudio(s.id); n++ }
        audioDir.listFiles()?.forEach { f -> if (sessionDao.withAudio().none { it.audioFile == f.name }) f.delete() }
        n
    }

    /** Audio files that belong to no recording (a crash between writing audio and saving the row) are removed. */
    suspend fun deleteOrphanAudio(): Int = withContext(Dispatchers.IO) {
        val known = sessionDao.withAudio().mapNotNull { it.audioFile }.toSet()
        var n = 0
        audioDir.listFiles()?.forEach { f -> if (f.name !in known && sessionDao.get(f.nameWithoutExtension) == null) { f.delete(); n++ } }
        n
    }

    suspend fun deleteAudioOf(id: String) {
        val s = sessionDao.get(id) ?: return
        audioFile(s)?.delete()
        sessionDao.clearAudio(id)
    }

    suspend fun purgeOlderThan(days: Int): Int {
        if (days <= 0) return 0
        val old = sessionDao.olderThan(System.currentTimeMillis() - days * 86_400_000L)
        old.forEach { delete(it.id) }
        return old.size
    }

    suspend fun sessionsWithAudio(): List<SessionEntity> = sessionDao.withAudio()
    suspend fun runningSessions(): List<SessionEntity> = sessionDao.running()

    /** Text for a version, built from the stored segments (or the user's edit). */
    suspend fun textOf(id: String, version: TextVersion? = null): String {
        val s = sessionDao.get(id) ?: return ""
        val v = version ?: TextVersion.valueOf(s.activeVersion)
        return TranscriptAssembler.text(segmentList(id), v, s.editedText)
    }

    suspend fun buildExport(id: String, version: TextVersion? = null): ExportDoc? {
        val s = sessionDao.get(id) ?: return null
        val v = version ?: TextVersion.valueOf(s.activeVersion)
        val segs = segmentList(id)
        val models = listOfNotNull(s.liveModelId, s.refineModelId).joinToString(", ").ifEmpty { "n/a" }
        return ExportDoc(
            title = s.title, startedAtMs = s.startedAt, durationMs = s.durationMs,
            language = LanguageMode.fromCode(s.languageMode).label + (s.detectedLanguage?.let { " (detected: $it)" } ?: ""),
            source = sourceLabel(s.source), modelInfo = models, version = v,
            fullText = TranscriptAssembler.text(segs, v, s.editedText),
            segments = segs.sortedBy { it.startMs }.map { ExportSegment(it.startMs, it.endMs, it.textFor(v)) },
        )
    }

    // ---- vocabulary ----
    fun vocabulary(): Flow<List<VocabularyEntity>> = vocabDao.observeAll()

    suspend fun addVocabulary(entries: List<VocabEntry>): Int {
        var added = 0
        for (e in entries) {
            val term = e.term.trim()
            if (term.isEmpty()) continue
            val id = vocabDao.insert(VocabularyEntity(term = term, spokenAs = e.spokenAs.joinToString("\n"), createdAt = System.currentTimeMillis()))
            if (id != -1L) added++
        }
        return added
    }

    suspend fun updateVocabulary(id: Long, e: VocabEntry) = vocabDao.update(id, e.term.trim(), e.spokenAs.joinToString("\n"))
    suspend fun deleteVocabulary(id: Long) = vocabDao.delete(id)
    suspend fun clearVocabulary() = vocabDao.clear()

    /** Learns terms the user corrected while editing a transcript. Returns the terms added. */
    suspend fun learnFromEdit(original: String, edited: String): List<String> {
        val existing = vocabDao.list().map { it.term }
        val learned = CorrectionLearner.extract(original, edited, existing)
        if (learned.isNotEmpty()) addVocabulary(learned.map { VocabEntry(it) })
        return learned
    }

    /** The text pipeline for the current vocabulary. Built when a recording starts, so a session is consistent. */
    suspend fun pipeline(): SegmentTextPipeline {
        val list = vocabDao.list().map { it.toEntry() }
        return SegmentTextPipeline(VocabularyCorrector(list.map { it.term }), VocabularyRules(list))
    }

    // ---- earlier JSON transcripts and dictionary ----
    /** Imports transcripts saved by the earlier Clearcap once; files are moved to transcripts_imported/, not deleted. */
    suspend fun importLegacy(): Int = withContext(Dispatchers.IO) {
        var imported = 0
        val dir = File(ctx.filesDir, "transcripts")
        val done = File(ctx.filesDir, "transcripts_imported").apply { mkdirs() }
        for (f in dir.listFiles { x -> x.name.endsWith(".json") }.orEmpty()) {
            val t = runCatching { LegacyImport.parse(f.readText()) }.getOrNull() ?: continue
            if (sessionDao.get(t.id) == null) {
                val seg = LegacyImport.toSegment(t)
                val source = when (t.source.lowercase()) { "mic" -> "MICROPHONE"; "both" -> "LEGACY_BOTH"; else -> "SYSTEM" }
                sessionDao.upsert(SessionEntity(
                    id = t.id, title = t.title, startedAt = t.createdAt, endedAt = t.createdAt + t.durationMs, durationMs = t.durationMs,
                    status = SessionStatus.COMPLETE.name, source = source, languageMode = LanguageMode.AUTO.code, detectedLanguage = null,
                    liveMode = "IMPORTED", liveModelId = null, refineModelId = null, activeVersion = LegacyImport.initialVersion(t).name,
                    editedText = t.edited, behindMs = t.behindMs, droppedMs = 0, audioFile = null, notes = t.notes,
                    statusNote = "Imported from an earlier version. It has no segment timestamps.", imported = true,
                ))
                segmentDao.upsert(seg.toEntity(t.id))
                imported++
            }
            f.renameTo(File(done, f.name))
        }
        // The earlier app kept the dictionary as one word per line in SharedPreferences.
        val prefs = ctx.getSharedPreferences("dictionary", Context.MODE_PRIVATE)
        val words = (prefs.getString("words", "") ?: "").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (words.isNotEmpty()) {
            addVocabulary(words.map { VocabEntry(it) })
            prefs.edit().remove("words").apply()
        }
        imported
    }

    private fun escapeLike(q: String) = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
