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
}

