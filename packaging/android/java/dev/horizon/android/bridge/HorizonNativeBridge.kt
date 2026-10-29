package dev.horizon.android.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.Log
import android.widget.Toast
import dev.horizon.android.HorizonActivity
import dev.horizon.android.model.HorizonConfigState
import dev.horizon.android.model.ZalithAccount
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class AssetInstallStatus(
    val ready: Boolean = false,
    val running: Boolean = false,
    val stage: String = "Idle",
    val done: Long = 0L,
    val total: Long = 100L,
    val error: String = "",
    val logs: List<String> = emptyList()
) {
    val progressFraction: Float
        get() = if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = if (ready) 100 else (progressFraction * 100f).toInt().coerceIn(0, 100)
}

object HorizonNativeBridge {
    private const val TAG = "HorizonNativeBridge"
    private const val MAX_LOG_LINES = 200
    private var nativeLoaded = false
    private val memoryLogs = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        try {
            System.loadLibrary("horizon_android")
            nativeLoaded = true
            appendSystemLog("Native library libhorizon_android.so loaded successfully.")
        } catch (e: UnsatisfiedLinkError) {
            appendSystemLog("Native library will load on game start: ${e.message}")
        }
    }

    @JvmStatic
    external fun nativeIsGameInstalled(home: String): Boolean

    @JvmStatic
    external fun nativeStartOfficialInstall(home: String): Boolean

    @JvmStatic
    external fun nativeGetInstallProgress(home: String): String

    fun appendSystemLog(message: String, enabled: Boolean = true) {
        if (!enabled) return
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$stamp] $message"
        memoryLogs.addLast(line)
        while (memoryLogs.size > MAX_LOG_LINES) {
            memoryLogs.pollFirst()
        }
        Log.i(TAG, message)
    }

    fun clearLogs(context: Context) {
        memoryLogs.clear()
        val home = context.filesDir
        File(home, "zalith-install-log.txt").delete()
        File(home, "logs/latest.log").delete()
        File(home, "android-error.txt").delete()
        appendSystemLog("System and runtime logs cleared.")
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun ensureHomeDirectories(context: Context): File {
        val home = context.filesDir
        listOf(
            "saves",
            "resourcepacks",
            "datapacks",
            "shaderpacks",
            "mods",
            "capes",
            "skins",
            "logs",
            ".local/horizon-client/26.3"
        ).forEach { sub ->
            File(home, sub).mkdirs()
        }

        // Always ensure engine-data.zip is extracted from APK assets to filesDir/engine-data.zip
        val engineZip = File(home, "engine-data.zip")
        if (!engineZip.exists() || engineZip.length() < 1024L) {
            try {
                context.assets.open("engine-data.zip").use { input ->
                    FileOutputStream(engineZip).use { output ->
                        input.copyTo(output)
                    }
                }
                appendSystemLog("Extracted bundled engine-data.zip (${engineZip.length() / 1024} KB) to home.")
            } catch (e: Exception) {
                appendSystemLog("Note: engine-data.zip asset check: ${e.message}")
            }
        }
        return home
    }

    fun isMinecraft26Ready(context: Context): Boolean {
        val home = ensureHomeDirectories(context)
        if (nativeLoaded) {
            try {
                if (nativeIsGameInstalled(home.absolutePath)) return true
            } catch (ignored: Throwable) {
            }
        }
        val root26 = File(home, ".local/horizon-client/26.3")
        return File(root26, "android-downloads-v2.json").isFile
    }

    fun startMinecraft26Download(context: Context, logEnabled: Boolean = true): Boolean {
        val home = ensureHomeDirectories(context)
        appendSystemLog("Starting Minecraft 26.3 official asset download & preparation...", logEnabled)
        if (nativeLoaded) {
            try {
                return nativeStartOfficialInstall(home.absolutePath)
            } catch (e: Throwable) {
                appendSystemLog("nativeStartOfficialInstall error: ${e.message}", logEnabled)
            }
        }
        return false
    }

    fun pollInstallProgress(context: Context, logEnabled: Boolean = true): AssetInstallStatus {
        val home = ensureHomeDirectories(context)
        val installLogFile = File(home, "zalith-install-log.txt")
        val logLines = if (logEnabled && installLogFile.exists()) {
            try {
                installLogFile.readLines().takeLast(80)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        if (nativeLoaded) {
            try {
                val raw = nativeGetInstallProgress(home.absolutePath)
                if (raw.isNotEmpty()) {
                    val obj = JSONObject(raw)
                    return AssetInstallStatus(
                        ready = obj.optBoolean("ready", false),
                        running = obj.optBoolean("running", false),
                        stage = formatStageLabel(obj.optString("stage", "Idle")),
                        done = obj.optLong("done", 0L),
                        total = obj.optLong("total", 100L),
                        error = obj.optString("error", ""),
                        logs = logLines
                    )
                }
            } catch (ignored: Throwable) {
            }
        }

        val progressFile = File(home, "zalith-install-progress.json")
        if (progressFile.exists()) {
            try {
                val obj = JSONObject(progressFile.readText())
                return AssetInstallStatus(
                    ready = obj.optBoolean("ready", isMinecraft26Ready(context)),
                    running = obj.optBoolean("running", false),
                    stage = formatStageLabel(obj.optString("stage", "Idle")),
                    done = obj.optLong("done", 0L),
                    total = obj.optLong("total", 100L),
                    error = obj.optString("error", ""),
                    logs = logLines
                )
            } catch (ignored: Exception) {
            }
        }

        val ready = isMinecraft26Ready(context)
        return AssetInstallStatus(
            ready = ready,
            running = false,
            stage = if (ready) "Ready" else "Needs Download",
            done = if (ready) 100L else 0L,
            total = 100L,
            logs = logLines
        )
    }

    private fun formatStageLabel(stage: String): String {
        return when (stage) {
            "Metadata" -> "1/6 Fetching Mojang 26.3 Manifest"
            "Download" -> "2/6 Downloading Minecraft 26.3 Assets & JARs"
            "Extract" -> "3/6 Extracting Client Textures & Data"
            "WorldData" -> "4/6 Building Worldgen & Biome Tables"
            "Controls" -> "5/6 Preparing Touch Controls"
            "Finalize" -> "6/6 Finalizing Engine Resources"
            "Ready" -> "Installed & Ready"
            else -> stage
        }
    }

    fun writeEngineConfigs(
        context: Context,
        config: HorizonConfigState,
        activeAccount: ZalithAccount
    ) {
        val home = ensureHomeDirectories(context)
        appendSystemLog(
            "Writing engine config (Player=${activeAccount.username}, Backend=${config.wgpuBackend}, Scale=${config.resolutionScalePercent}%)",
            config.enableSystemLog
        )

        val optionsTxt = buildString {
            appendLine("version:4325")
            appendLine("fov:${(config.fov - 70f) / 40f}")
            appendLine("renderDistance:${config.renderDistanceChunks.coerceAtMost(32)}")
            appendLine("simulationDistance:${config.simulationDistanceChunks}")
            appendLine("guiScale:${config.guiScale}")
            appendLine("particles:${config.particlesPreset}")
            appendLine("maxFps:${config.maxFps}")
            appendLine("enableVsync:${config.enableVsync}")
            appendLine("autoJump:${config.autoJump}")
            appendLine("toggleSprint:${config.toggleSprint}")
            appendLine("toggleCrouch:${config.toggleSneak}")
            appendLine("soundCategory_master:${config.masterVolume}")
            appendLine("soundCategory_music:${config.musicVolume}")
            appendLine("showSubtitles:${config.closedCaptions}")
            appendLine("highContrastBlockOutline:${config.highContrastBlockOutline}")
            appendLine("fullscreen:true")
        }
        File(home, "options.txt").writeText(optionsTxt)

        val horizonExtras = buildString {
            appendLine("# Generated by HorizonZ Launcher 1.1")
            appendLine("graphicsPreset=${config.graphicsPreset.lowercase()}")
            appendLine("distantTerrainChunks=${config.distantTerrainChunks}")
            appendLine("resolutionScale=${config.resolutionScalePercent / 100.0f}")
            appendLine("wgpuBackend=${config.wgpuBackend.lowercase()}")
            appendLine("enableHdr=${config.enableHdr}")
            appendLine("keepAnimatedTextures=true")
            appendLine("enableZoom=${config.enableZoom}")
            appendLine("enableMinimap=${config.enableMinimap}")
            appendLine("discordPresence=${config.discordRichPresence}")
            appendLine("integratedServerThread=${config.integratedServerThread}")
            appendLine("hideControllerWhileLoading=${config.hideControllerWhileLoading}")
            appendLine("touchSplitControls=${config.splitTouchControls}")
            appendLine("touchDpadScale=${config.dpadScale}")
            appendLine("touchDpadOpacity=${config.dpadOpacity}")
            appendLine("playerName=${activeAccount.username}")
            appendLine("accountType=${activeAccount.accountType.name.lowercase()}")
        }
        File(home, "horizon-extras.txt").writeText(horizonExtras)
    }

    fun importUriToDestination(context: Context, uri: Uri, subFolder: String, targetFileName: String, logEnabled: Boolean = true): Boolean {
        return try {
            val home = ensureHomeDirectories(context)
            val destDir = if (subFolder.isEmpty()) home else File(home, subFolder)
            destDir.mkdirs()
            val outFile = File(destDir, targetFileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            appendSystemLog("Imported file to $subFolder/$targetFileName (${outFile.length()} bytes)", logEnabled)
            true
        } catch (e: Exception) {
            appendSystemLog("Failed to import URI: ${e.message}", logEnabled)
            false
        }
    }

    /**
     * Collects lightweight, non-blocking System + Installer + Minecraft Runtime logs.
     * Respects `enableSystemLog` setting so it causes zero overhead when disabled.
     */
    fun readFullSystemLogs(context: Context, logEnabled: Boolean): String {
        if (!logEnabled) {
            return "System & Game Logging is currently disabled in Settings.\nEnable 'System & Game Logs' in the Settings tab to record launcher, download, and Minecraft runtime events."
        }
        val home = ensureHomeDirectories(context)
        val sb = StringBuilder()
        sb.appendLine("=== HorizonZ 1.1 System & Minecraft Runtime Log ===")
        sb.appendLine("PID: ${Process.myPid()} | Data Dir: ${home.absolutePath}")
        sb.appendLine("26.3 Assets Ready: ${isMinecraft26Ready(context)}")
        sb.appendLine("---------------------------------------------------")

        // 1. In-memory Launcher events
        if (memoryLogs.isNotEmpty()) {
            sb.appendLine("[Launcher Events]")
            memoryLogs.forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        // 2. Installer log file
        val installLog = File(home, "zalith-install-log.txt")
        if (installLog.exists()) {
            sb.appendLine("[Asset Installer Log]")
            installLog.readLines().takeLast(60).forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        // 3. Native Error file if any
        val androidErr = File(home, "android-error.txt")
        if (androidErr.exists()) {
            sb.appendLine("[Native Engine Error]")
            sb.appendLine(androidErr.readText().takeLast(4000))
            sb.appendLine("---------------------------------------------------")
        }

        // 4. Minecraft latest.log (checked in both home and game_home)
        val latestLogCandidates = listOf(
            File(home, "logs/latest.log"),
            File(home, ".local/horizon-client/26.3/logs/latest.log")
        )
        latestLogCandidates.firstOrNull { it.exists() }?.let { logFile ->
            sb.appendLine("[Minecraft 26.3 Engine — latest.log]")
            logFile.readLines().takeLast(80).forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        // 5. Lightweight non-blocking process logcat snapshot (last 80 lines for current PID)
        try {
            val pid = Process.myPid().toString()
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "80", "--pid=$pid"))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                val lines = reader.readLines()
                if (lines.isNotEmpty()) {
                    sb.appendLine("[Android Process Logcat (horizon / wgpu / rust)]")
                    lines.takeLast(60).forEach { sb.appendLine(it) }
                }
            }
        } catch (ignored: Exception) {
        }

        return sb.toString()
    }

    fun launchNativeGame(
        context: Context,
        config: HorizonConfigState,
        activeAccount: ZalithAccount
    ) {
        writeEngineConfigs(context, config, activeAccount)
        appendSystemLog("Launching HorizonActivity (Minecraft 26.3 Native Rust Engine)...", config.enableSystemLog)
        val intent = Intent(context, HorizonActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("HORIZON_ANDROID_PUBLIC", config.publicOnDeviceAssetDownload)
            putExtra("HORIZON_NO_AUDIO", config.disableAudioSafeMode)
            putExtra("HORIZON_NO_DISCORD", !config.discordRichPresence)
            putExtra("HORIZON_PLAYER_NAME", activeAccount.username)
            putExtra("HORIZON_WGPU_BACKEND", config.wgpuBackend.lowercase())
        }
        context.startActivity(intent)
    }
}
