package com.tube.dpi.system

import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.net.toUri

/**
 * Прошивки с агрессивной экономией заряда: закрытое приложение (смахнутое из недавних) там
 * «замораживается»: служба автозапуска и VPN выгружаются, пока пользователь не разрешит автозапуск
 * и не снимет ограничения батареи. Системного API для этого нет — открываем экраны самих прошивок.
 */
object OemPower {

    enum class Vendor { Xiaomi, Huawei, Oppo, Vivo, Samsung, Other }

    val vendor: Vendor by lazy {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        when {
            listOf("xiaomi", "redmi", "poco").any { it in brand } -> Vendor.Xiaomi
            listOf("huawei", "honor").any { it in brand } -> Vendor.Huawei
            listOf("oppo", "realme", "oneplus").any { it in brand } -> Vendor.Oppo
            listOf("vivo", "iqoo").any { it in brand } -> Vendor.Vivo
            "samsung" in brand -> Vendor.Samsung
            else -> Vendor.Other
        }
    }

    /** На этих прошивках без автозапуска служба Tube выгружается вместе с приложением. */
    val needsAutostart: Boolean get() = vendor in setOf(Vendor.Xiaomi, Vendor.Huawei, Vendor.Oppo, Vendor.Vivo)

    private val autostartScreens = mapOf(
        Vendor.Xiaomi to listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        ),
        Vendor.Huawei to listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        ),
        Vendor.Oppo to listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        ),
        Vendor.Vivo to listOf(
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
            ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        ),
    )

    /** Экран автозапуска прошивки; если его нет — карточка приложения в системных настройках. */
    fun openAutostart(context: Context) {
        val opened = autostartScreens[vendor].orEmpty().any { component ->
            tryStart(context, Intent().setComponent(component))
        }
        if (!opened) openAppDetails(context)
    }

    /**
     * Снять ограничения батареи. На Xiaomi системный запрос «не оптимизировать» через пару секунд
     * откатывается собственным контролем прошивки, поэтому открываем её экран («Нет ограничений»).
     */
    @SuppressLint("BatteryLife")
    fun openBatterySettings(context: Context) {
        if (vendor == Vendor.Xiaomi) {
            val miui = Intent()
                .setComponent(ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                .putExtra("package_name", context.packageName)
                .putExtra("package_label", "Tube")
            if (tryStart(context, miui)) return
        }
        if (vendor != Vendor.Other && vendor != Vendor.Samsung) {
            // Сначала системное «не оптимизировать» (от него зависит и сон, и «заморозка» на vivo),
            // потом — карточка приложения, где у этих прошивок «Фоновое энергопотребление».
            val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
            if (!isBatteryUnrestricted(context) && tryStart(context, request)) return
            openAppDetails(context)
            return
        }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        if (!tryStart(context, request) && !tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) {
            openAppDetails(context)
        }
    }

    fun isBatteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Включён ли автозапуск на Xiaomi (скрытая операция MIUI 10008). `null` — узнать нельзя:
     * другая прошивка или система не ответила.
     */
    fun autostartAllowed(context: Context): Boolean? {
        if (vendor != Vendor.Xiaomi) return null
        return runCatching {
            val ops = context.getSystemService(AppOpsManager::class.java)
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            when (method.invoke(ops, MIUI_OP_AUTO_START, Process.myUid(), context.packageName) as Int) {
                AppOpsManager.MODE_ALLOWED -> true
                AppOpsManager.MODE_IGNORED -> false
                else -> null
            }
        }.getOrNull()
    }

    private const val MIUI_OP_AUTO_START = 10008

    private fun openAppDetails(context: Context) {
        tryStart(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
}
