package com.lecturecaption.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.lecturecaption.app.data.db.entity.TranscriptSegment
import kotlinx.coroutines.flow.Flow

@Dao
interface TranscriptSegmentDao {

    /** Called immediately after every recognized chunk — never batched at session end.
     *  This is what makes a crash mid-lecture lose at most the last unflushed chunk. */
    @Insert
    suspend fun insert(segment: TranscriptSegment): Long

    @Query("SELECT * FROM transcript_segments WHERE lectureId = :lectureId ORDER BY sequenceIndex ASC")
    fun observeForLecture(lectureId: Long): Flow<List<TranscriptSegment>>

    @Query("SELECT * FROM transcript_segments WHERE lectureId = :lectureId ORDER BY sequenceIndex ASC")
    suspend fun getForLecture(lectureId: Long): List<TranscriptSegment>

    @Query("SELECT COUNT(*) FROM transcript_segments WHERE lectureId = :lectureId")
    suspend fun countForLecture(lectureId: Long): Int

    @Query("SELECT MAX(sequenceIndex) FROM transcript_segments WHERE lectureId = :lectureId")
    suspend fun maxSequenceIndex(lectureId: Long): Int?
}
