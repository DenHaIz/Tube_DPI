package com.tube.dpi

import com.tube.dpi.autostart.AutoStartPolicy
import com.tube.dpi.autostart.AutoStartPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoStartPolicyTest {
    private val youtube = "app.revanced.android.youtube"
    private val targets = setOf(youtube)
    private val delay = 5 * 60_000L
    private val policy = AutoStartPolicy(ownPackage = "com.tube.dpi")

    private fun foreground(pkg: String, active: Boolean, byAutostart: Boolean = true, now: Long = 0, stopDelay: Long? = delay) =
        policy.onForeground(pkg, targets, transient = setOf("com.google.android.inputmethod.latin"), tunnelActive = active, startedByAutostart = byAutostart, stopDelayMs = stopDelay, now = now)

    @Test
    fun startsWhenTargetOpens() {
        assertEquals(Decision.Start, foreground(youtube, active = false))
        assertEquals(Decision.None, foreground(youtube, active = true))
    }

    @Test
    fun ignoresOtherAppsWhenStopped() {
        assertEquals(Decision.None, foreground("com.android.chrome", active = false))
        assertNull(policy.stopAt)
    }

    @Test
    fun stopsAfterDelayWhenIdle() {
        foreground("com.android.launcher3", active = true, now = 1_000)
        assertEquals(1_000 + delay, policy.stopAt)
        assertEquals(Decision.None, policy.onTick(1_000 + delay - 1, tunnelActive = true, rxRate = 0, stopDelayMs = delay))
        assertEquals(Decision.Stop, policy.onTick(1_000 + delay, tunnelActive = true, rxRate = 0, stopDelayMs = delay))
        assertNull(policy.stopAt)
    }

    @Test
    fun backgroundPlaybackExtendsTimer() {
        foreground("com.android.launcher3", active = true, now = 0)
        assertEquals(Decision.None, policy.onTick(delay, tunnelActive = true, rxRate = 64 * 1024, stopDelayMs = delay))
        assertEquals(2 * delay, policy.stopAt)
        assertEquals(Decision.Stop, policy.onTick(2 * delay, tunnelActive = true, rxRate = 100, stopDelayMs = delay))
    }

    @Test
    fun returningToTargetCancelsTimer() {
        foreground("com.android.launcher3", active = true, now = 0)
        foreground(youtube, active = true, now = 10)
        assertNull(policy.stopAt)
        assertEquals(Decision.None, policy.onTick(delay * 2, tunnelActive = true, rxRate = 0, stopDelayMs = delay))
    }

    @Test
    fun manualStartIsNeverStoppedAutomatically() {
        foreground("com.android.launcher3", active = true, byAutostart = false, now = 0)
        assertNull(policy.stopAt)
    }

    @Test
    fun transientWindowsDoNotArmTimer() {
        listOf("com.android.systemui", "com.google.android.inputmethod.latin", "com.tube.dpi", "android").forEach {
            foreground(it, active = true)
        }
        assertNull(policy.stopAt)
    }

    @Test
    fun neverStopOption() {
        foreground("com.android.launcher3", active = true, stopDelay = null)
        assertNull(policy.stopAt)
    }

    @Test
    fun timerDroppedWhenTunnelStoppedManually() {
        foreground("com.android.launcher3", active = true, now = 0)
        assertEquals(Decision.None, policy.onTick(delay, tunnelActive = false, rxRate = 0, stopDelayMs = delay))
        assertNull(policy.stopAt)
    }
}
