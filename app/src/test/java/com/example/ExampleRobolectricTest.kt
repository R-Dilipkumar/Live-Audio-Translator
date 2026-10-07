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
}

