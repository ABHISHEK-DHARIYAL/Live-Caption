package com.clearcap.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<SessionEntity>>

    @Query(
        """SELECT * FROM sessions
           WHERE title LIKE '%' || :q || '%' ESCAPE '\'
              OR IFNULL(editedText, '') LIKE '%' || :q || '%' ESCAPE '\'
              OR id IN (SELECT sessionId FROM segments
                        WHERE liveText LIKE '%' || :q || '%' ESCAPE '\'
                           OR IFNULL(refinedText, '') LIKE '%' || :q || '%' ESCAPE '\')
           ORDER BY startedAt DESC"""
    )
    fun search(q: String): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: String): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: String): SessionEntity?

    @Upsert
    suspend fun upsert(s: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()

    @Query("SELECT * FROM sessions WHERE status IN ('RECORDING', 'FINALIZING')")
    suspend fun running(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE audioFile IS NOT NULL")
    suspend fun withAudio(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE startedAt < :cutoff")
    suspend fun olderThan(cutoff: Long): List<SessionEntity>

    @Query("UPDATE sessions SET title = :title WHERE id = :id")
    suspend fun rename(id: String, title: String)

    @Query("UPDATE sessions SET activeVersion = :version WHERE id = :id")
    suspend fun setVersion(id: String, version: String)

    @Query("UPDATE sessions SET editedText = :text, activeVersion = :version WHERE id = :id")
    suspend fun setEdited(id: String, text: String?, version: String)

    @Query("UPDATE sessions SET audioFile = NULL WHERE id = :id")
    suspend fun clearAudio(id: String)

    @Query("SELECT COUNT(*) FROM sessions")
    suspend fun count(): Int
}

@Dao
abstract class SegmentDao {
    @Query("SELECT * FROM segments WHERE sessionId = :id ORDER BY startMs, idx")
    abstract fun observe(id: String): Flow<List<SegmentEntity>>

    @Query("SELECT * FROM segments WHERE sessionId = :id ORDER BY startMs, idx")
    abstract suspend fun list(id: String): List<SegmentEntity>

    @Upsert
    abstract suspend fun upsert(s: SegmentEntity)

    @Upsert
    abstract suspend fun upsertAll(list: List<SegmentEntity>)

    @Query("DELETE FROM segments WHERE sessionId = :id")
    abstract suspend fun deleteFor(id: String)

    @Transaction
    open suspend fun replaceAll(id: String, list: List<SegmentEntity>) {
        deleteFor(id)
        upsertAll(list)
    }
}

@Dao
interface VocabularyDao {
    @Query("SELECT * FROM vocabulary ORDER BY term COLLATE NOCASE")
    fun observeAll(): Flow<List<VocabularyEntity>>

    @Query("SELECT * FROM vocabulary ORDER BY term COLLATE NOCASE")
    suspend fun list(): List<VocabularyEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(v: VocabularyEntity): Long

    @Query("UPDATE vocabulary SET term = :term, spokenAs = :spokenAs WHERE id = :id")
    suspend fun update(id: Long, term: String, spokenAs: String)

    @Query("DELETE FROM vocabulary WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM vocabulary")
    suspend fun clear()
}
