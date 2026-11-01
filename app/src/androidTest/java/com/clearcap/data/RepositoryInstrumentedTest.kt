package com.clearcap.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.clearcap.data.db.AppDatabase
import com.clearcap.data.db.SessionEntity
import com.clearcap.text.VocabEntry
import com.clearcap.transcript.Segment
import com.clearcap.transcript.SessionStatus
import com.clearcap.transcript.TextVersion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Needs an emulator or phone: `./gradlew :app:connectedDebugAndroidTest`. NOT run in CI and NOT yet run by the author.
 * Guards the behaviours that are easy to break: updating a session must not delete its segments, and deleting a
 * session must remove them.
 */
@RunWith(AndroidJUnit4::class)
class RepositoryInstrumentedTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: TranscriptRepository
    private lateinit var ctx: Context

    @Before fun setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java).allowMainThreadQueries().build()
        repo = TranscriptRepository(ctx, db)
    }

    @After fun tearDown() = db.close()

    private fun session(id: String = "s1") = SessionEntity(
        id = id, title = "Test", startedAt = 1_000, endedAt = null, durationMs = 0, status = SessionStatus.RECORDING.name, source = "MICROPHONE",
        languageMode = "auto", detectedLanguage = null, liveMode = "STREAMING", liveModelId = "zipformer-en", refineModelId = null,
        activeVersion = TextVersion.LIVE.name, editedText = null, behindMs = 0, droppedMs = 0, audioFile = null, notes = null, statusNote = null, imported = false,
    )

    @Test fun updatingASessionKeepsItsSegments() = runBlocking {
        repo.upsertSession(session())
        repo.upsertSegment("s1", Segment(0, 0, 1000, "hello", null, "en", "hello"))
        repo.upsertSession(session().copy(durationMs = 5_000, status = SessionStatus.COMPLETE.name))
        assertEquals(1, repo.segmentList("s1").size)
        assertEquals(5_000L, repo.getSession("s1")!!.durationMs)
    }

    @Test fun deletingASessionRemovesItsSegments() = runBlocking {
        repo.upsertSession(session())
        repo.upsertSegment("s1", Segment(0, 0, 1000, "hello", null, "en", null))
        repo.delete("s1")
        assertNull(repo.getSession("s1"))
        assertTrue(repo.segmentList("s1").isEmpty())
    }

    @Test fun searchFindsTitleAndSegmentText() = runBlocking {
        repo.upsertSession(session().copy(title = "Physics lecture"))
        repo.upsertSegment("s1", Segment(0, 0, 1000, "entropy always increases", null, "en", null))
        assertEquals(1, repo.search("physics").first().size)
        assertEquals(1, repo.search("entropy").first().size)
        assertEquals(0, repo.search("chemistry").first().size)
        assertEquals(0, repo.search("%").first().size) // wildcard characters are escaped, not treated as match-all
    }

    @Test fun refinedAndOriginalTextAreBothKept() = runBlocking {
        repo.upsertSession(session())
        repo.upsertSegment("s1", Segment(0, 0, 1000, "original", "refined", "en", "original"))
        assertEquals("original", repo.textOf("s1", TextVersion.LIVE))
        assertEquals("refined", repo.textOf("s1", TextVersion.REFINED))
    }

    @Test fun vocabularyIgnoresDuplicates() = runBlocking {
        assertEquals(1, repo.addVocabulary(listOf(VocabEntry("Kubernetes"))))
        assertEquals(0, repo.addVocabulary(listOf(VocabEntry("Kubernetes"))))
    }
}
