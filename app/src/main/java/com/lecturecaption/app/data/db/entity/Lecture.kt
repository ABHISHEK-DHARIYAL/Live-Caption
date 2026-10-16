package com.lecturecaption.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One lecture = one continuous transcription session. `fullText` is a denormalized
 * concatenation of all TranscriptSegments, kept in sync on every segment insert so
 * "Copy All" / export never needs to join across tables. Segments remain the source
 * of truth for timestamps and for crash-safe incremental saving.
 */
@Entity(tableName = "lectures")
data class Lecture(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long,
    /** Duration in milliseconds. Updated continuously while recording, not just at the end. */
    val durationMs: Long = 0,
    val language: String,
    val audioSource: String,       // "SYSTEM_AUDIO" | "MICROPHONE" | "SYSTEM_AND_MICROPHONE"
    val transcriptionEngine: String, // "VOSK" | "WHISPER_TINY" | "WHISPER_BASE" | "WHISPER_SMALL"
    val fullText: String = "",
    /** true while a foreground service is actively recording this lecture; used to recover
     *  from a process-kill mid-lecture (the row + all saved segments survive regardless). */
    val isActive: Boolean = false
)
