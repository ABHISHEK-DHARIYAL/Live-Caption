package com.lecturecaption.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transcript_segments",
    foreignKeys = [
        ForeignKey(
            entity = Lecture::class,
            parentColumns = ["id"],
            childColumns = ["lectureId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("lectureId")]
)
data class TranscriptSegment(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val lectureId: Long,
    /** Offset in ms from the start of the lecture session, not wall-clock time. */
    val startTimeMs: Long,
    val endTimeMs: Long,
    val text: String,
    /** Monotonically increasing per lecture so segments always reassemble in order,
     *  even if two chunks are persisted out of strict startTime order under load. */
    val sequenceIndex: Int
)
