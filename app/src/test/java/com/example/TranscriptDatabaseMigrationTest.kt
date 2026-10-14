package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The v1 -> v2 bump (timestamp index) must keep saved history. The real builder also has
 * fallbackToDestructiveMigration(), which would silently wipe the table if no migration matched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TranscriptDatabaseMigrationTest {

    private val dbName = "migration-test.db"
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    @Test
    fun `migration from v1 keeps saved transcripts and adds the timestamp index`() {
        // Build a v1 database exactly as Room created it (same table, no index)
        val dbFile = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(dbFile, null).use { v1 ->
            v1.execSQL("CREATE TABLE IF NOT EXISTS `transcripts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `originalText` TEXT NOT NULL, `translatedText` TEXT NOT NULL, `sourceLanguage` TEXT NOT NULL, `targetLanguage` TEXT NOT NULL, `timestamp` INTEGER NOT NULL)")
            v1.execSQL("INSERT INTO transcripts (originalText, translatedText, sourceLanguage, targetLanguage, timestamp) VALUES ('こんにちは', 'Hello', 'ja', 'en', 1000)")
            v1.version = 1
        }

        // No destructive fallback here: if the migration were missing or wrong, this would throw
        val db = Room.databaseBuilder(context, AppDatabase::class.java, dbName)
            .addMigrations(AppDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()
        try {
            val rows = runBlocking { db.transcriptDao().getAllTranscripts().first() }
            assertEquals(1, rows.size)
            assertEquals("Hello", rows[0].translatedText)

            db.openHelper.readableDatabase
                .query("SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_transcripts_timestamp'")
                .use { cursor -> assertEquals(1, cursor.count) }
        } finally {
            db.close()
        }
    }
}
