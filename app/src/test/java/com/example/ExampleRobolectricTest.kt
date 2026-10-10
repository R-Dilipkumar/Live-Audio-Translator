package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.service.NotificationHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("LiveAudio Translate", appName)
    }

    @Test
    fun `verify notification helper creates capture notification`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        NotificationHelper.createNotificationChannels(context)
        val notification = NotificationHelper.buildCaptureNotification(context)
        assertNotNull(notification)
    }

    @Test
    fun `verify offline dictionary translation fallback operates without google services`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val engine = com.example.translate.LocalTranslatorEngine(context)
        val translated = engine.fallbackOfflineTranslate("こんにちは", "ja", "en")
        assertEquals("Hello", translated)

        val spanishTranslated = engine.fallbackOfflineTranslate("gracias", "es", "en")
        assertEquals("Thank you", spanishTranslated)
    }

    @Test
    fun `verify speech recognizer engine transitions to error when model not ready`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val modelManager = com.example.asr.ModelManager(context)
        val speechEngine = com.example.asr.SpeechRecognizerEngine(context, modelManager)

        val ready = speechEngine.initEngine()
        assertEquals(false, ready)
        val state = speechEngine.engineState.value
        org.junit.Assert.assertTrue(state is com.example.asr.RecognizerState.Error)
        assertEquals(
            "ASR Model not initialized. Please install model in Manage Models.",
            (state as com.example.asr.RecognizerState.Error).message
        )
    }
}

