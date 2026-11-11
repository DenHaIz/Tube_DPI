package com.tube.dpi.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.tube.dpi.BuildConfig
import com.tube.dpi.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Релиз на GitHub, который новее установленной версии. */
data class Release(val version: String, val notes: String, val apkUrl: String, val apkSize: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Ready(val release: Release, val apk: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Обновления из релизов github.com/DenHaIz/Tube_DPI: последний релиз сравнивается с версией приложения,
 * APK скачивается в кэш и открывается системным установщиком (подпись та же, поэтому ставится поверх).
 */
class UpdateChecker(private val context: Context, private val settings: SettingsRepository) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Тихая проверка при запуске: не чаще раза в [AUTO_INTERVAL_MS] и только если включено. */
    suspend fun checkIfDue() {
        if (!settings.checkUpdates.first()) return
        if (System.currentTimeMillis() - settings.lastUpdateCheck.first() < AUTO_INTERVAL_MS) return
        check(silent = true)
    }

    suspend fun check(silent: Boolean = false) {
        if (_state.value is UpdateState.Downloading) return
        if (!silent) _state.value = UpdateState.Checking
        val result = withContext(Dispatchers.IO) { runCatching { fetchLatest() } }
        settings.setLastUpdateCheck(System.currentTimeMillis())
        _state.value = result.fold(
            onSuccess = { release -> if (release != null) UpdateState.Available(release) else if (silent) UpdateState.Idle else UpdateState.UpToDate },
            onFailure = { if (silent) UpdateState.Idle else UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    suspend fun download(release: Release) {
        _state.value = UpdateState.Downloading(release, 0f)
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val file = File(dir, "Tube-${release.version}.apk")
                val connection = open(release.apkUrl)
                connection.inputStream.use { input ->
                    file.outputStream().use { output ->
                        val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
                        val buffer = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            if (total > 0) _state.value = UpdateState.Downloading(release, read / total.toFloat())
                        }
                    }
                }
                file
            }
        }
        _state.value = result.fold(
            onSuccess = { UpdateState.Ready(release, it) },
            onFailure = { UpdateState.Failed(it.message ?: it.javaClass.simpleName) },
        )
    }

    /** Разрешено ли приложению ставить APK (Android 8+ спрашивает отдельно для каждого источника). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installPermissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    fun installIntent(apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    private fun fetchLatest(): Release? {
        val connection = open(LATEST_URL)
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        val version = json.getString("tag_name").removePrefix("v")
        if (!isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name").endsWith(".apk") } ?: return null
        return Release(
            version = version,
            notes = json.optString("body"),
            apkUrl = apk.getString("browser_download_url"),
            apkSize = apk.optLong("size"),
            pageUrl = json.optString("html_url"),
        )
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 20_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Tube/${BuildConfig.VERSION_NAME}")
        if (responseCode !in 200..299) error("HTTP $responseCode")
    }

    companion object {
        private const val LATEST_URL = "https://api.github.com/repos/DenHaIz/Tube_DPI/releases/latest"
        private const val AUTO_INTERVAL_MS = 6 * 60 * 60_000L

        /** Сравнение версий по числам: 0.10 > 0.9, 1.0 > 0.9.9. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}
