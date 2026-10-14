package com.example.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Indexed on timestamp: history is always read ORDER BY timestamp
@Entity(tableName = "transcripts", indices = [Index(value = ["timestamp"])])
data class TranscriptEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val originalText: String,
    val translatedText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val timestamp: Long = System.currentTimeMillis()
)
