package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.asr.ModelManager
import com.example.translate.LocalTranslatorEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineFallbackAndCatalogTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `offline fallback reads the detected code from auto-detect labels`() {
        val engine = LocalTranslatorEngine(context)
        try {
            // "auto (ja)".take(2) used to give "au", which matched no dictionary
            assertEquals("Hello", engine.fallbackOfflineTranslate("こんにちは", "auto (ja)", "en"))
            assertEquals("Thank you", engine.fallbackOfflineTranslate("gracias", "auto (es)", "en"))
            assertEquals("Hola", engine.fallbackOfflineTranslate("hello", "auto (en)", "es"))
            // Plain language codes still work
            assertEquals("Hello", engine.fallbackOfflineTranslate("bonjour", "fr", "en"))
        } finally {
            engine.release()
        }
    }

    @Test
    fun `catalog offers French and Bengali models over HTTPS`() {
        val models = ModelManager(context).availableModels
        assertTrue(models.any { it.languageCode == "fr" })
        assertTrue(models.any { it.languageCode == "bn" })
        // Downloads are refused unless the URL is HTTPS
        assertTrue(models.all { it.downloadUrl.startsWith("https://") })
    }
}
