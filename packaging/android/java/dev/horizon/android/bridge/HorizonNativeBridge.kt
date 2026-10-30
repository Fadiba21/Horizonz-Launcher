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
import dev.horizon.android.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
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
            appendSystemLog("Native library libhorizon_android.so loaded.")
        } catch (e: UnsatisfiedLinkError) {
            appendSystemLog("Native library will load in :game process: ${e.message}")
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
            "profiles",
            "logs",
            ".local/horizon-client/26.3"
        ).forEach { sub ->
            File(home, sub).mkdirs()
        }

        val engineZip = File(home, "engine-data.zip")
        if (!engineZip.exists() || engineZip.length() < 1024L) {
            try {
                context.assets.open("engine-data.zip").use { input ->
                    FileOutputStream(engineZip).use { output ->
                        input.copyTo(output)
                    }
                }
                appendSystemLog("Extracted bundled engine-data.zip (${engineZip.length() / 1024} KB).")
            } catch (e: Exception) {
                appendSystemLog("Note: engine-data.zip check: ${e.message}")
            }
        }
        return home
    }

    // ========================================================================
    // PERSISTENT ACCOUNTS SYSTEM (Never resets across app/game restarts!)
    // ========================================================================

    fun loadSavedAccounts(context: Context): List<ZalithAccount> {
        val home = ensureHomeDirectories(context)
        val file = File(home, "zalith-accounts.json")
        if (file.exists()) {
            try {
                val arr = JSONArray(file.readText())
                val list = mutableListOf<ZalithAccount>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val typeName = obj.optString("accountType", AccountType.OFFLINE.name)
                    val type = runCatching { AccountType.valueOf(typeName) }.getOrDefault(AccountType.OFFLINE)
                    list.add(
                        ZalithAccount(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            username = obj.optString("username", "Player"),
                            uuid = obj.optString("uuid", UUID.randomUUID().toString()),
                            accountType = type,
                            isActive = obj.optBoolean("isActive", false)
                        )
                    )
                }
                if (list.isNotEmpty()) {
                    if (list.none { it.isActive }) {
                        return list.mapIndexed { idx, acc -> acc.copy(isActive = idx == 0) }
                    }
                    return list
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read zalith-accounts.json: ${e.message}")
            }
        }
        val defaultList = listOf(
            ZalithAccount(
                id = "default_1",
                username = "Steve",
                uuid = "8667ba71-b85a-4004-af54-457a9734eed7",
                accountType = AccountType.OFFLINE,
                isActive = true
            )
        )
        saveAccounts(context, defaultList, loadSavedConfig(context))
        return defaultList
    }

    fun saveAccounts(context: Context, accounts: List<ZalithAccount>, config: HorizonConfigState) {
        val home = ensureHomeDirectories(context)
        val arr = JSONArray()
        accounts.forEach { acc ->
            arr.put(
                JSONObject().apply {
                    put("id", acc.id)
                    put("username", acc.username)
                    put("uuid", acc.uuid)
                    put("accountType", acc.accountType.name)
                    put("isActive", acc.isActive)
                }
            )
        }
        File(home, "zalith-accounts.json").writeText(arr.toString(2))

        val active = accounts.firstOrNull { it.isActive } ?: accounts.firstOrNull() ?: return
        syncNativeHorizonAccountAndCatalog(home, active, config)
    }

    // ========================================================================
    // PERSISTENT SETTINGS SYSTEM (Aligned with catalog.rs & Non-Destructive to options.txt!)
    // ========================================================================

    fun loadSavedConfig(context: Context): HorizonConfigState {
        val home = ensureHomeDirectories(context)
        val file = File(home, "zalith-config.json")
        if (file.exists()) {
            try {
                val obj = JSONObject(file.readText())
                val rendererKey = obj.optString("renderer", HorizonRendererMode.VULKAN_COMPAT.serdeKey)
                val renderer = HorizonRendererMode.entries.firstOrNull { it.serdeKey == rendererKey }
                    ?: HorizonRendererMode.VULKAN_COMPAT
                val orientKey = obj.optString("orientation", HorizonOrientation.LANDSCAPE.serdeKey)
                val orientation = HorizonOrientation.entries.firstOrNull { it.serdeKey == orientKey }
                    ?: HorizonOrientation.LANDSCAPE
                val inputKey = obj.optString("input_host", HorizonInputHost.AUTO.serdeKey)
                val inputHost = HorizonInputHost.entries.firstOrNull { it.serdeKey == inputKey }
                    ?: HorizonInputHost.AUTO
                val modSrcKey = obj.optString("mod_source", HorizonContentOrigin.HORIZON.serdeKey)
                val modSource = HorizonContentOrigin.entries.firstOrNull { it.serdeKey == modSrcKey }
                    ?: HorizonContentOrigin.HORIZON
                val shaderSrcKey = obj.optString("shader_source", HorizonContentOrigin.HORIZON.serdeKey)
                val shaderSource = HorizonContentOrigin.entries.firstOrNull { it.serdeKey == shaderSrcKey }
                    ?: HorizonContentOrigin.HORIZON

                return HorizonConfigState(
                    profile = obj.optInt("profile", 0),
                    renderer = renderer,
                    renderScalePercent = obj.optInt("render_scale", 75).coerceIn(25, 100),
                    orientation = orientation,
                    inputHost = inputHost,
                    keypadControls = obj.optBoolean("keypad_controls", false),
                    recordPerformance = obj.optBoolean("record_performance", false),
                    modSource = modSource,
                    shaderSource = shaderSource,
                    defaultServer = obj.optString("server", ""),
                    proxy = obj.optString("proxy", ""),
                    useDynamicColor = obj.optBoolean("useDynamicColor", true),
                    enableSystemLog = obj.optBoolean("enableSystemLog", true),
                    disableAudioSafeMode = obj.optBoolean("disableAudioSafeMode", false),
                    discordRichPresence = obj.optBoolean("discordRichPresence", true)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read zalith-config.json: ${e.message}")
            }
        }
        return HorizonConfigState()
    }

    fun saveConfig(context: Context, config: HorizonConfigState, activeAccount: ZalithAccount) {
        val home = ensureHomeDirectories(context)
        val zalithObj = JSONObject().apply {
            put("profile", config.profile)
            put("renderer", config.renderer.serdeKey)
            put("render_scale", config.renderScalePercent)
            put("orientation", config.orientation.serdeKey)
            put("input_host", config.inputHost.serdeKey)
            put("keypad_controls", config.keypadControls)
            put("record_performance", config.recordPerformance)
            put("mod_source", config.modSource.serdeKey)
            put("shader_source", config.shaderSource.serdeKey)
            put("server", config.defaultServer)
            put("proxy", config.proxy)
            put("useDynamicColor", config.useDynamicColor)
            put("enableSystemLog", config.enableSystemLog)
            put("disableAudioSafeMode", config.disableAudioSafeMode)
            put("discordRichPresence", config.discordRichPresence)
        }
        File(home, "zalith-config.json").writeText(zalithObj.toString(2))
        syncNativeHorizonAccountAndCatalog(home, activeAccount, config)
    }

    /**
     * Writes the exact `android-launcher.json` (used by `crates/horizon-client/src/android/catalog.rs`)
     * and `accounts.json` / `horizon-player.txt` (used by `launcher/src/account.rs`) WITHOUT touching
     * existing in-game `options.txt`!
     */
    private fun syncNativeHorizonAccountAndCatalog(
        home: File,
        activeAccount: ZalithAccount,
        config: HorizonConfigState
    ) {
        // 1. Write `android-launcher.json` matching `crates/horizon-client/src/android/catalog.rs`
        val androidLauncherJson = JSONObject().apply {
            put("profile", config.profile)
            if (config.proxy.isNotBlank()) put("proxy", config.proxy.trim()) else put("proxy", JSONObject.NULL)
            put("name", activeAccount.username)
            put("server", config.defaultServer.trim())
            put("renderer", config.renderer.serdeKey)
            put("render_scale", config.renderScalePercent)
            put("orientation", config.orientation.serdeKey)
            put("input_host", config.inputHost.serdeKey)
            put("keypad_controls", config.keypadControls)
            put("record_performance", config.recordPerformance)
            put("shader_source", config.shaderSource.serdeKey)
            put("mod_source", config.modSource.serdeKey)
        }
        File(home, "android-launcher.json").writeText(androidLauncherJson.toString(2))

        // 2. Write `accounts.json` and `horizon-player.txt` in both `home` and `profile_home`
        val profileHome = if (config.profile == 0) home else File(home, "profiles/profile-${config.profile}")
        profileHome.mkdirs()

        val accountsJson = JSONObject().apply {
            put("accounts", JSONArray())
            put("selected", JSONObject.NULL)
            put("offline", JSONArray().apply { put(activeAccount.username) })
            put("offline_selected", activeAccount.username)
        }
        listOf(home, profileHome).forEach { dir ->
            File(dir, "accounts.json").writeText(accountsJson.toString(2))
            File(dir, "horizon-player.txt").writeText(activeAccount.username)
        }
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

    fun readFullSystemLogs(context: Context, logEnabled: Boolean): String {
        if (!logEnabled) {
            return "System & Game Logging is currently disabled in Settings.\nEnable 'System Logs' in the Settings tab to record launcher, download, and Minecraft runtime events."
        }
        val home = ensureHomeDirectories(context)
        val sb = StringBuilder()
        sb.appendLine("=== HorizonZ 1.5 System & Minecraft Runtime Log ===")
        sb.appendLine("PID: ${Process.myPid()} | Data Dir: ${home.absolutePath}")
        sb.appendLine("26.3 Assets Ready: ${isMinecraft26Ready(context)}")
        sb.appendLine("---------------------------------------------------")

        if (memoryLogs.isNotEmpty()) {
            sb.appendLine("[Launcher Events]")
            memoryLogs.forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        val installLog = File(home, "zalith-install-log.txt")
        if (installLog.exists()) {
            sb.appendLine("[Asset Installer Log]")
            installLog.readLines().takeLast(60).forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        val androidErr = File(home, "android-error.txt")
        if (androidErr.exists()) {
            sb.appendLine("[Native Engine Error]")
            sb.appendLine(androidErr.readText().takeLast(4000))
            sb.appendLine("---------------------------------------------------")
        }

        val latestLogCandidates = listOf(
            File(home, "logs/latest.log"),
            File(home, ".local/horizon-client/26.3/logs/latest.log")
        )
        latestLogCandidates.firstOrNull { it.exists() }?.let { logFile ->
            sb.appendLine("[Minecraft 26.3 Engine — latest.log]")
            logFile.readLines().takeLast(80).forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        try {
            val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "80"))
            BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                val lines = reader.readLines().filter {
                    it.contains("horizon", ignoreCase = true) ||
                        it.contains("Horizon", ignoreCase = false) ||
                        it.contains("wgpu", ignoreCase = true)
                }
                if (lines.isNotEmpty()) {
                    sb.appendLine("[Android Logcat (horizon / wgpu)]")
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
        saveConfig(context, config, activeAccount)
        appendSystemLog(
            "Launching Minecraft 26.3 (Player=${activeAccount.username}, Renderer=${config.renderer.label}, Scale=${config.renderScalePercent}%)",
            config.enableSystemLog
        )
        val intent = Intent(context, HorizonActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("HORIZON_ANDROID_PUBLIC", config.publicOnDeviceAssetDownload)
            putExtra("HORIZON_NO_AUDIO", config.disableAudioSafeMode)
            putExtra("HORIZON_NO_DISCORD", !config.discordRichPresence)
            putExtra("HORIZON_PLAYER_NAME", activeAccount.username)
            putExtra("HORIZON_WGPU_BACKEND", if (config.renderer == HorizonRendererMode.OPENGL) "gl" else "vulkan")
        }
        context.startActivity(intent)
    }
}
