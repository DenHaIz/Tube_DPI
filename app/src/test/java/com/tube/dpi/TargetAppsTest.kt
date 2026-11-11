package com.tube.dpi

import com.tube.dpi.apps.AppKind
import com.tube.dpi.apps.TargetApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TargetAppsTest {
    @Test
    fun knownPackages() {
        assertEquals(AppKind.YouTube, TargetApps.kindOf("com.google.android.youtube", "YouTube"))
        assertEquals(AppKind.YouTube, TargetApps.kindOf("app.morphe.android.youtube", "YouTube Morphe"))
        assertEquals(AppKind.Music, TargetApps.kindOf("app.revanced.android.apps.youtube.music", "YT Music"))
        assertEquals(AppKind.Services, TargetApps.kindOf("app.revanced.android.gms", "GmsCore"))
    }

    @Test
    fun renamedPatchedClients() {
        // Патч «Change package name» с произвольным именем: узнаём по метке.
        assertEquals(AppKind.YouTube, TargetApps.kindOf("my.custom.yt", "YouTube"))
        assertEquals(AppKind.Music, TargetApps.kindOf("my.custom.music", "YouTube Music"))
        assertEquals(AppKind.Services, TargetApps.kindOf("app.morphe.custom.gms", "microG"))
    }

    @Test
    fun unrelatedApps() {
        assertNull(TargetApps.kindOf("com.android.chrome", "Chrome"))
        assertNull(TargetApps.kindOf("org.telegram.messenger", "Telegram"))
    }

    @Test
    fun patchedClientsComeFirst() {
        val youtube = TargetApps.known.filter { it.kind == AppKind.YouTube }.map { it.packageName }
        assertEquals("app.morphe.android.youtube", youtube.first())
    }
}
