package com.clearcap.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SessionEntity::class, SegmentEntity::class, VocabularyEntity::class], version = 1, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun segments(): SegmentDao
    abstract fun vocabulary(): VocabularyDao

    companion object {
        fun create(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, "clearcap.db").build()
    }
}
