package com.lecturecaption.app.data.db.dao

import androidx.room.*
import com.lecturecaption.app.data.db.entity.Lecture
import kotlinx.coroutines.flow.Flow

@Dao
interface LectureDao {

    @Insert
    suspend fun insert(lecture: Lecture): Long

    @Update
    suspend fun update(lecture: Lecture)

    @Query("DELETE FROM lectures WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM lectures WHERE id = :id")
    suspend fun getById(id: Long): Lecture?

    @Query("SELECT * FROM lectures WHERE id = :id")
    fun observeById(id: Long): Flow<Lecture?>

    @Query("SELECT * FROM lectures ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Lecture>>

    @Query("SELECT * FROM lectures WHERE title LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    fun search(query: String): Flow<List<Lecture>>

    @Query("UPDATE lectures SET durationMs = :durationMs WHERE id = :id")
    suspend fun updateDuration(id: Long, durationMs: Long)

    @Query("UPDATE lectures SET fullText = :fullText, durationMs = :durationMs WHERE id = :id")
    suspend fun appendFullText(id: Long, fullText: String, durationMs: Long)

    @Query("UPDATE lectures SET isActive = :active WHERE id = :id")
    suspend fun setActive(id: Long, active: Boolean)

    @Query("UPDATE lectures SET title = :title WHERE id = :id")
    suspend fun rename(id: Long, title: String)

    /** Recovery path: if the process was killed mid-lecture, this lets the UI offer
     *  "resume viewing" for whatever was saved, since segments are persisted incrementally. */
    @Query("SELECT * FROM lectures WHERE isActive = 1 LIMIT 1")
    suspend fun getDanglingActiveLecture(): Lecture?
}
