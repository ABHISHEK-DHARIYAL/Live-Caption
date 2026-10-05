package com.lecturecaption.app.data.repository

import android.content.Context
import com.lecturecaption.app.data.db.AppDatabase
import com.lecturecaption.app.data.db.entity.Lecture
import com.lecturecaption.app.data.db.entity.TranscriptSegment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * All writes for a single lecture funnel through here so that "one continuous session"
 * is enforced in one place: every recognized chunk gets appended to the SAME lectureId,
 * saved to Room the instant it's recognized (not buffered until Stop), and immediately
 * folded into Lecture.fullText so segments never need to be re-joined for display/export.
 */
/** Where the speech pipeline writes finalized recognition results. Kept as an interface so the
 * pipeline can be unit-tested without Room/Android. */
interface TranscriptSink {
    suspend fun appendSegment(lectureId: Long, startTimeMs: Long, endTimeMs: Long, text: String)
}

class LectureRepository(context: Context) : TranscriptSink {

    private val db = AppDatabase.getInstance(context)
    private val lectureDao = db.lectureDao()
    private val segmentDao = db.transcriptSegmentDao()

    // Guards the read-modify-write of fullText/duration against overlapping chunk callbacks.
    private val writeMutex = Mutex()

    suspend fun startNewLecture(
        title: String,
        language: String,
        audioSource: String,
        transcriptionEngine: String
    ): Long {
        val lecture = Lecture(
            title = title,
            createdAt = System.currentTimeMillis(),
            language = language,
            audioSource = audioSource,
            transcriptionEngine = transcriptionEngine,
            isActive = true
        )
        return lectureDao.insert(lecture)
    }

    /** Call this for every recognized chunk, in order. Never call once at the end. */
    override suspend fun appendSegment(
        lectureId: Long,
        startTimeMs: Long,
        endTimeMs: Long,
        text: String
    ): Unit = writeMutex.withLock {
        if (text.isBlank()) return@withLock
        val nextIndex = (segmentDao.maxSequenceIndex(lectureId) ?: -1) + 1
        segmentDao.insert(
            TranscriptSegment(
                lectureId = lectureId,
                startTimeMs = startTimeMs,
                endTimeMs = endTimeMs,
                text = text,
                sequenceIndex = nextIndex
            )
        )
        val lecture = lectureDao.getById(lectureId) ?: return@withLock
        val separator = if (lecture.fullText.isBlank()) "" else "\n\n"
        lectureDao.appendFullText(
            id = lectureId,
            fullText = lecture.fullText + separator + text,
            durationMs = endTimeMs
        )
    }

    suspend fun finishLecture(lectureId: Long, finalDurationMs: Long, finalTitle: String? = null) {
        if (!finalTitle.isNullOrBlank()) lectureDao.rename(lectureId, finalTitle.trim())
        lectureDao.updateDuration(lectureId, finalDurationMs)
        lectureDao.setActive(lectureId, active = false)
    }

    suspend fun getDanglingActiveLecture(): Lecture? = lectureDao.getDanglingActiveLecture()

    fun observeLectures(): Flow<List<Lecture>> = lectureDao.observeAll()
    fun searchLectures(query: String): Flow<List<Lecture>> = lectureDao.search(query)
    fun observeLecture(id: Long): Flow<Lecture?> = lectureDao.observeById(id)
    fun observeSegments(lectureId: Long): Flow<List<TranscriptSegment>> =
        segmentDao.observeForLecture(lectureId)

    suspend fun getSegments(lectureId: Long) = segmentDao.getForLecture(lectureId)
    suspend fun rename(lectureId: Long, newTitle: String) = lectureDao.rename(lectureId, newTitle)
    suspend fun delete(lectureId: Long) = lectureDao.delete(lectureId)
    suspend fun getLecture(lectureId: Long) = lectureDao.getById(lectureId)
}
