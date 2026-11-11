package com.tube.dpi.ui

import com.tube.dpi.vpn.FailReason
import com.tube.ui.i18n.AppLanguage
import java.util.Locale

fun AppLanguage.bytes(value: Long): String {
    val kb = 1024.0
    return when {
        value < kb -> pick("$value Б", "$value B")
        value < kb * kb -> String.format(Locale.ROOT, "%.0f %s", value / kb, pick("КБ", "KB"))
        value < kb * kb * kb -> String.format(Locale.ROOT, "%.1f %s", value / kb / kb, pick("МБ", "MB"))
        else -> String.format(Locale.ROOT, "%.2f %s", value / kb / kb / kb, pick("ГБ", "GB"))
    }
}

fun AppLanguage.rate(bytesPerSecond: Long): String = bytes(bytesPerSecond) + pick("/с", "/s")

fun AppLanguage.failMessage(reason: FailReason): String = when (reason) {
    FailReason.NoConsent -> pick("Нужно разрешение на создание VPN", "VPN permission is required")
    FailReason.NoApps -> pick("Не выбрано ни одного установленного приложения", "No installed app is selected")
    FailReason.ProxyFailed -> pick("Прокси byedpi не запустился", "The byedpi proxy failed to start")
    FailReason.TunnelFailed -> pick("Не удалось создать VPN-туннель", "Could not create the VPN tunnel")
    FailReason.Revoked -> pick("VPN отключён системой или другим VPN-приложением", "VPN was turned off by the system or another VPN app")
}

/** Через сколько выключать обход после ухода из YouTube; 0 — никогда. */
val StopDelayOptions = listOf(1, 5, 15, 0)

fun AppLanguage.stopDelay(minutes: Int): String =
    if (minutes == 0) pick("никогда", "never") else pick("$minutes мин", "$minutes min")
