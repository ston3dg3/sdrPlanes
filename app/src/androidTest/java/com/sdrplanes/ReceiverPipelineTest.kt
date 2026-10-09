package com.sdrplanes

import android.Manifest
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.sdrplanes.service.ReceiverService
import com.sdrplanes.service.ReceiverState
import com.sdrplanes.service.ReceiverStatus
import com.sdrplanes.service.Settings
import com.sdrplanes.service.SourceMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end on device: fake rtl_tcp -> ReceiverService -> demod/decoder -> ReceiverState. */
@RunWith(AndroidJUnit4::class)
class ReceiverPipelineTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.POST_NOTIFICATIONS,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val savedSettings = Settings.load(InstrumentationRegistry.getInstrumentation().targetContext)

    @After
    fun restoreSettings() {
        ReceiverService.stop(context)
        Settings.save(context, savedSettings)
    }

    /** The service reads its source from the saved settings. */
    private fun startNetwork(port: Int, gainTenthsDb: Int? = 496) {
        Settings.save(
            context,
            Settings(SourceMode.NETWORK, "127.0.0.1", port, gainTenthsDb, ppm = 0, sampleRate = 2_000_000),
        )
        ReceiverService.start(context)
    }

    private fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    @Test
    fun serviceDecodesAircraftFromRtlTcp() {
        val iq = SyntheticIq.modulate(
            listOf(
                "8D4840D6202CC371C32CE0576098", // KLM1023 identification
                "8D485020994409940838175B284F", // velocity 159 kt
                "8D40621D58C382D690C8AC2863A7", // position, even (38 000 ft)
                "8D40621D58C386435CC412692AD6", // position, odd
            )
        )
        FakeRtlTcpServer(iq).use { server ->
            // Foreground service starts must come from a visible app.
            ActivityScenario.launch(MainActivity::class.java).use {
                startNetwork(server.port)

                val running = waitFor { ReceiverState.status.value is ReceiverStatus.Running }
                assertTrue("never reached Running: ${ReceiverState.status.value}", running)
                assertEquals("R820T", (ReceiverState.status.value as ReceiverStatus.Running).tuner)

                val decoded = waitFor {
                    ReceiverState.aircraft.value.map { it.icaoHex }.containsAll(listOf("4840D6", "485020", "40621D"))
                }
                assertTrue("aircraft not decoded: ${ReceiverState.aircraft.value}", decoded)
                val byIcao = ReceiverState.aircraft.value.associateBy { it.icaoHex }
                assertEquals("KLM1023", byIcao.getValue("4840D6").callsign)
                assertEquals(159.2, byIcao.getValue("485020").speedKt!!, 0.1)
                assertEquals(38000, byIcao.getValue("40621D").altitudeFt)
                assertTrue(ReceiverState.stats.value.totalMessages >= 4)

                // rtl_tcp commands: sample rate, frequency, manual gain 49.6 dB.
                val cmds = server.commands.toList()
                assertTrue(cmds.toString(), (0x02 to 2_000_000) in cmds)
                assertTrue(cmds.toString(), (0x01 to 1_090_000_000) in cmds)
                assertTrue(cmds.toString(), (0x03 to 1) in cmds)
                assertTrue(cmds.toString(), (0x04 to 496) in cmds)

                ReceiverService.stop(context)
                assertTrue(waitFor { ReceiverState.status.value == ReceiverStatus.Idle })
            }
        }
    }

    @Test
    fun connectionFailureSchedulesReconnect() {
        ActivityScenario.launch(MainActivity::class.java).use {
            // Nothing listens on port 1: connect retries are exhausted, then the service backs off and retries.
            startNetwork(1)
            val retrying = waitFor(30_000) { ReceiverState.status.value is ReceiverStatus.Reconnecting }
            assertTrue("status: ${ReceiverState.status.value}", retrying)
            ReceiverService.stop(context)
            assertTrue(waitFor { ReceiverState.status.value == ReceiverStatus.Idle })
        }
    }

    @Test
    fun reconnectsAfterServerDrops() {
        val iq = SyntheticIq.modulate(listOf("8D4840D6202CC371C32CE0576098"))
        ActivityScenario.launch(MainActivity::class.java).use {
            val first = FakeRtlTcpServer(iq)
            val port = first.port
            startNetwork(port)
            assertTrue(waitFor { ReceiverState.aircraft.value.any { it.icaoHex == "4840D6" } })
            first.close()
            assertTrue(waitFor(20_000) { ReceiverState.status.value is ReceiverStatus.Reconnecting })
            // A new server on the same port: the service picks it up and keeps the aircraft.
            FakeRtlTcpServer(iq, port).use {
                assertTrue(
                    "status: ${ReceiverState.status.value}",
                    waitFor(30_000) { ReceiverState.status.value is ReceiverStatus.Running },
                )
                assertTrue(ReceiverState.aircraft.value.any { it.icaoHex == "4840D6" })
            }
        }
    }
}
