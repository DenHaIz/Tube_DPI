package com.tube.dpi.apps

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/** Известные клиенты YouTube и сопутствующие пакеты. */
enum class AppKind { YouTube, Music, Services }

data class KnownApp(val packageName: String, val kind: AppKind, val title: String)

data class InstalledApp(
    val packageName: String,
    val label: String,
    val kind: AppKind?,
    val system: Boolean,
)

object TargetApps {

    /**
     * Порядок важен: первый установленный клиент открывается кнопкой «YouTube» по умолчанию —
     * сначала модифицированные (их ставят специально), потом оригинал.
     */
    val known: List<KnownApp> = listOf(
        KnownApp("app.morphe.android.youtube", AppKind.YouTube, "YouTube Morphe"),
        KnownApp("app.revanced.android.youtube", AppKind.YouTube, "YouTube ReVanced"),
        KnownApp("app.rvx.android.youtube", AppKind.YouTube, "YouTube RVX"),
        KnownApp("com.google.android.youtube", AppKind.YouTube, "YouTube"),
        KnownApp("com.google.android.apps.youtube.kids", AppKind.YouTube, "YouTube Kids"),
        KnownApp("com.google.android.youtube.tv", AppKind.YouTube, "YouTube TV"),
        KnownApp("app.morphe.android.apps.youtube.music", AppKind.Music, "YT Music Morphe"),
        KnownApp("app.revanced.android.apps.youtube.music", AppKind.Music, "YT Music ReVanced"),
        KnownApp("app.rvx.android.apps.youtube.music", AppKind.Music, "YT Music RVX"),
        KnownApp("com.google.android.apps.youtube.music", AppKind.Music, "YouTube Music"),
        // microG / GmsCore: вход в аккаунт Google в ReVanced и Morphe.
        KnownApp("app.revanced.android.gms", AppKind.Services, "GmsCore (ReVanced)"),
        KnownApp("app.morphe.android.gms", AppKind.Services, "GmsCore (Morphe)"),
        KnownApp("com.mgoogle.android.gms", AppKind.Services, "microG"),
    )

    private val knownByPackage = known.associateBy { it.packageName }

    /**
     * Клиент YouTube по имени пакета: точное совпадение или переименованный патчем
     * «Change package name» (ReVanced/Morphe позволяют задать любое имя, но обычно в нём остаётся youtube).
     */
    fun kindOf(packageName: String, label: String): AppKind? {
        knownByPackage[packageName]?.let { return it.kind }
        val name = packageName.lowercase()
        val title = label.lowercase()
        return when {
            "youtube.music" in name || ("youtube" in title && "music" in title) -> AppKind.Music
            "youtube" in name || "youtube" in title -> AppKind.YouTube
            name.endsWith(".gms") && (name.startsWith("app.revanced") || name.startsWith("app.morphe")) -> AppKind.Services
            else -> null
        }
    }

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** Все приложения с доступом в интернет; клиенты YouTube — первыми. */
    fun installed(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val apps = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .filter { it.packageName != context.packageName }
            .filter { pkg -> pkg.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
            .mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                val label = pm.getApplicationLabel(info).toString()
                InstalledApp(
                    packageName = pkg.packageName,
                    label = label,
                    kind = kindOf(pkg.packageName, label),
                    system = info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pm.getLaunchIntentForPackage(pkg.packageName) == null,
                )
            }
        return apps.sortedWith(
            compareBy<InstalledApp> { it.kind?.ordinal ?: Int.MAX_VALUE }
                .thenBy { known.indexOfFirst { k -> k.packageName == it.packageName }.let { i -> if (i < 0) Int.MAX_VALUE else i } }
                .thenBy { it.system }
                .thenBy { it.label.lowercase() },
        )
    }

    /** Выбор по умолчанию: все найденные клиенты YouTube, Music и GmsCore. */
    fun defaultTargets(context: Context): Set<String> {
        val pm = context.packageManager
        val found = known.map { it.packageName }.filter { isInstalled(context, it) }.toMutableSet()
        pm.getInstalledApplications(0).forEach { info ->
            if (info.packageName != context.packageName && kindOf(info.packageName, pm.getApplicationLabel(info).toString()) != null) {
                found += info.packageName
            }
        }
        return found
    }

    /** Клиенты YouTube из выбранных, которые можно открыть с рабочего стола (в порядке [known]). */
    fun launchableClients(context: Context, targets: Set<String>): List<InstalledApp> {
        val pm = context.packageManager
        return targets.mapNotNull { pkg ->
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
            val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return@mapNotNull null
            val label = pm.getApplicationLabel(info).toString()
            val kind = kindOf(pkg, label)
            if (kind == AppKind.Services || launch.component == null) null else InstalledApp(pkg, label, kind, false)
        }.sortedWith(
            compareBy<InstalledApp> { it.kind?.ordinal ?: Int.MAX_VALUE }
                .thenBy { known.indexOfFirst { k -> k.packageName == it.packageName }.let { i -> if (i < 0) Int.MAX_VALUE else i } },
        )
    }

    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        return runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
    }
}
