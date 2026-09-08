package dev.threadline

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LauncherIconTest {
    @Test
    fun manifestUsesTemporaryLauncherArtwork() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val applicationInfo = context.packageManager.getApplicationInfo(context.packageName, 0)

        assertEquals(R.mipmap.ic_launcher, applicationInfo.icon)
        assertNotNull(context.getDrawable(applicationInfo.icon))
    }
}
