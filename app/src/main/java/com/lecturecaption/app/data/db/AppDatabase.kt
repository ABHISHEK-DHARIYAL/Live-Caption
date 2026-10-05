package com.lecturecaption.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.lecturecaption.app.data.db.dao.LectureDao
import com.lecturecaption.app.data.db.dao.TranscriptSegmentDao
import com.lecturecaption.app.data.db.entity.Lecture
import com.lecturecaption.app.data.db.entity.TranscriptSegment

@Database(
    entities = [Lecture::class, TranscriptSegment::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun lectureDao(): LectureDao
    abstract fun transcriptSegmentDao(): TranscriptSegmentDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "lecturecaption.db"
                ).build().also { INSTANCE = it }
            }
    }
}
