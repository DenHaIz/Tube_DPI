package com.tube.dpi.autostart

/**
 * Решения автозапуска без Android-зависимостей (покрыто тестами).
 *
 * - Выбранное приложение вышло на экран → включить обход.
 * - На экране другое приложение → через [stopDelayMs] выключить, но только если обход включил
 *   сам автозапуск (ручное включение не трогаем) и через туннель больше не идёт трафик —
 *   иначе это фоновое воспроизведение в ReVanced/Morphe, и срок продлевается.
 * - Шторка, клавиатура, сам Tube и системные оверлеи состояние не меняют.
 */
class AutoStartPolicy(
    private val ownPackage: String,
    /** Скорость, ниже которой туннель считается простаивающим (байт/с): фоновое аудио — десятки КБ/с. */
    private val idleRateBytes: Long = 4 * 1024,
) {
    sealed interface Decision {
        data object None : Decision
        data object Start : Decision
        data object Stop : Decision
    }

    /** Момент, когда пора выключить обход; null — таймер не взведён. */
    var stopAt: Long? = null
        private set

    fun onForeground(
        packageName: String,
        targets: Set<String>,
        transient: Set<String>,
        tunnelActive: Boolean,
        startedByAutostart: Boolean,
        stopDelayMs: Long?,
        now: Long,
    ): Decision {
        if (packageName == ownPackage || packageName in transient || packageName in AlwaysTransient) return Decision.None
        if (packageName in targets) {
            stopAt = null
            return if (tunnelActive) Decision.None else Decision.Start
        }
        if (tunnelActive && startedByAutostart && stopDelayMs != null && stopAt == null) {
            stopAt = now + stopDelayMs
        }
        return Decision.None
    }

    /** Периодическая проверка таймера; [rxRate] — средняя скорость приёма через туннель за интервал. */
    fun onTick(now: Long, tunnelActive: Boolean, rxRate: Long, stopDelayMs: Long?): Decision {
        val deadline = stopAt ?: return Decision.None
        if (!tunnelActive || stopDelayMs == null) {
            stopAt = null
            return Decision.None
        }
        if (now < deadline) return Decision.None
        if (rxRate > idleRateBytes) {
            stopAt = now + stopDelayMs
            return Decision.None
        }
        stopAt = null
        return Decision.Stop
    }

    fun reset() {
        stopAt = null
    }

    companion object {
        /** Окна поверх приложений, которые не означают «пользователь ушёл из YouTube». */
        val AlwaysTransient = setOf(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.vpndialogs",
        )
    }
}
