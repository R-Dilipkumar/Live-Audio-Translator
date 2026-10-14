package com.example.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [TranscriptEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transcriptDao(): TranscriptDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // v2 adds the timestamp index. Name matches what Room generates for @Index(["timestamp"]).
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_transcripts_timestamp` ON `transcripts` (`timestamp`)")
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "liveaudio_translate.db"
                )
                .addMigrations(MIGRATION_1_2)
                // Only for versions without a migration path; add a Migration for each future bump
                .fallbackToDestructiveMigration(dropAllTables = false)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
