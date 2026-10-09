package com.sdrplanes

import android.Manifest
import android.app.Activity
import android.content.Intent
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.sdrplanes.sdr.RtlSdrDriver
import com.sdrplanes.sdr.TunerConfig
import com.sdrplanes.service.Settings
import com.sdrplanes.service.SourceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: TestRule = RuleChain
        .outerRule(
            GrantPermissionRule.grant(
                Manifest.permission.POST_NOTIFICATIONS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        )
        .around(compose)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun launchesIdleAndOpensSettings() {
        compose.onNodeWithText("Idle – press ▶ to start").assertExists()
        compose.onNodeWithContentDescription("Start").assertExists()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Receiver settings", ignoreCase = true).assertExists()
        compose.onNodeWithText("Network rtl_tcp server").performClick()
        compose.onNodeWithText("Host (emulator: 127.0.0.1 + adb reverse)").assertExists()
        compose.onNodeWithText("Cancel", ignoreCase = true).performClick()
        compose.onNodeWithText("Receiver settings", ignoreCase = true).assertDoesNotExist()
    }

    @Test
    fun settingsRoundTrip() {
        val original = Settings.load(context)
        try {
            val s = Settings(SourceMode.NETWORK, "10.0.2.2", 1234, gainTenthsDb = null, ppm = -3)
            Settings.save(context, s)
            assertEquals(s, Settings.load(context))
        } finally {
            Settings.save(context, original)
        }
    }

    @Test
    fun driverIntentAndErrors() {
        val i = RtlSdrDriver.startIntent(TunerConfig())
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("iqsrc://-a 127.0.0.1 -p 1234 -n 1 -f 1090000000 -s 2000000", i.dataString)
        assertNull(RtlSdrDriver.resultError(Activity.RESULT_OK, null))
        val err = Intent().putExtra("detailed_exception_message", "no device")
        assertTrue(RtlSdrDriver.resultError(Activity.RESULT_CANCELED, err)!!.contains("no device"))
    }
}
