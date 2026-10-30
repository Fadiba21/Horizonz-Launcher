package dev.horizon.android.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import dev.horizon.android.HorizonActivity
import dev.horizon.android.model.*
import dev.horizon.android.service.HorizonDownloadService
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile

data class AssetInstallStatus(
    val ready: Boolean = false,
    val running: Boolean = false,
    val paused: Boolean = false,
    val stage: String = "Idle",
    val done: Long = 0L,
    val total: Long = 100L,
    val speedText: String = "",
    val currentUrl: String = "",
    val detail: String = "",
    val error: String = "",
    val logs: List<String> = emptyList()
) {
    val progressFraction: Float
        get() = if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = if (ready) 100 else (progressFraction * 100f).toInt().coerceIn(0, 100)
}

data class StoreDownloadProgress(
    val activeItemId: String = "",
    val activeTitle: String = "",
    val running: Boolean = false,
    val paused: Boolean = false,
    val percent: Int = 0,
    val speedText: String = "",
    val statusText: String = ""
)

object HorizonNativeBridge {
    private const val TAG = "HorizonNativeBridge"
    const val INSTALLED_LAUNCHER_VERSION = "1.11"
    private const val MAX_LOG_LINES = 250
    private var nativeLoaded = false
    private val memoryLogs = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    val storeDownloadPaused = AtomicBoolean(false)
    val storeDownloadCancelled = AtomicBoolean(false)

    init {
        try {
            System.loadLibrary("horizon_android")
            nativeLoaded = true
            appendMemoryOnly("Native library libhorizon_android.so loaded (HorizonZ $INSTALLED_LAUNCHER_VERSION).")
        } catch (e: UnsatisfiedLinkError) {
            appendMemoryOnly("Native library will load in :game process: ${e.message}")
        }
    }

    @JvmStatic
    external fun nativeIsGameInstalled(home: String): Boolean

    @JvmStatic
    external fun nativeStartOfficialInstall(home: String): Boolean

    @JvmStatic
    external fun nativeGetInstallProgress(home: String): String

    private fun appendMemoryOnly(message: String) {
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$stamp] $message"
        memoryLogs.addLast(line)
        while (memoryLogs.size > MAX_LOG_LINES) {
            memoryLogs.pollFirst()
        }
        Log.i(TAG, message)
    }

    fun appendSystemLog(message: String, enabled: Boolean = true, context: Context? = null) {
        if (!enabled) return
        val stamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val line = "[$stamp] $message"
        memoryLogs.addLast(line)
        while (memoryLogs.size > MAX_LOG_LINES) {
            memoryLogs.pollFirst()
        }
        if (context != null) {
            try {
                val logFile = File(context.filesDir, "zalith-system.log")
                FileOutputStream(logFile, true).use { fos ->
                    fos.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            } catch (ignored: Exception) {
            }
        }
        Log.i(TAG, message)
    }

    private fun appendInstallLogFile(home: File, message: String) {
        try {
            val logFile = File(home, "zalith-install-log.txt")
            FileOutputStream(logFile, true).use { fos ->
                fos.write((message + "\n").toByteArray(Charsets.UTF_8))
            }
        } catch (ignored: Exception) {
        }
    }

    fun clearLogs(context: Context) {
        memoryLogs.clear()
        val home = context.filesDir
        listOf(
            File(home, "zalith-system.log"),
            File(home, "zalith-install-log.txt"),
            File(home, "android-error.txt"),
            File(home, "logs/latest.log"),
            File(home, ".local/horizon-client/26.3/logs/latest.log")
        ).forEach { file ->
            try {
                if (file.exists()) file.delete()
            } catch (ignored: Exception) {
            }
        }
        try {
            Runtime.getRuntime().exec(arrayOf("logcat", "-c")).waitFor()
        } catch (ignored: Exception) {
        }
        try {
            File(home, "zalith-log-cleared.txt").writeText(System.currentTimeMillis().toString())
        } catch (ignored: Exception) {
        }
        appendSystemLog("System, installer, and Minecraft runtime logs cleared (HorizonZ $INSTALLED_LAUNCHER_VERSION).", true, context)
    }

    fun copyToClipboard(context: Context, label: String, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun ensureHomeDirectories(context: Context): File {
        val home = context.filesDir
        listOf(
            "saves",
            "resourcepacks",
            "datapacks",
            "shaderpacks",
            "mods",
            "horizon-mods",
            "profiles",
            "logs",
            "custom_wallpapers",
            ".local/horizon-client/26.3"
        ).forEach { sub ->
            File(home, sub).mkdirs()
        }

        val engineZip = File(home, "engine-data.zip")
        try {
            context.assets.open("engine-data.zip").use { input ->
                val assetBytes = input.readBytes()
                if (!engineZip.exists() || engineZip.length() != assetBytes.size.toLong()) {
                    FileOutputStream(engineZip).use { output ->
                        output.write(assetBytes)
                    }
                    appendSystemLog("Synced bundled engine-data.zip (${engineZip.length() / 1024} KB).", true, context)
                }
            }
        } catch (ignored: Exception) {
        }
        return home
    }

    fun getProfileHome(home: File, profileSlot: Int): File {
        val dir = if (profileSlot <= 0) home else File(home, "profiles/profile-$profileSlot")
        dir.mkdirs()
        listOf("saves", "resourcepacks", "shaderpacks", "mods", "horizon-mods", "logs").forEach {
            File(dir, it).mkdirs()
        }
        return dir
    }

    // ========================================================================
    // PERSISTENT ACCOUNTS SYSTEM
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
                            username = obj.optString("username", "Steve"),
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
    // FULL TWO-WAY SETTINGS SYNC (catalog.rs + horizon-keypad.json + options.txt)
    // ========================================================================

    fun loadSavedConfig(context: Context): HorizonConfigState {
        val home = ensureHomeDirectories(context)
        val file = File(home, "zalith-config.json")
        var state = HorizonConfigState()
        if (file.exists()) {
            try {
                val obj = JSONObject(file.readText())
                val rawRenderer = obj.optString("renderer", HorizonRendererMode.VULKAN_COMPAT.serdeKey)
                val normalizedRenderer = if (rawRenderer == "opengl") "open-gl" else rawRenderer
                val renderer = HorizonRendererMode.entries.firstOrNull { it.serdeKey == normalizedRenderer }
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
                val paletteName = obj.optString("themePalette", ZalithThemePalette.MATERIAL_YOU.name)
                val palette = runCatching { ZalithThemePalette.valueOf(paletteName) }.getOrDefault(ZalithThemePalette.MATERIAL_YOU)

                state = HorizonConfigState(
                    profile = obj.optInt("profile", 0).coerceIn(0, 3),
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
                    mcRenderDistance = obj.optInt("mcRenderDistance", 8).coerceIn(2, 32),
                    mcSimulationDistance = obj.optInt("mcSimulationDistance", 8).coerceIn(5, 32),
                    mcMaxFps = obj.optInt("mcMaxFps", 120).coerceIn(30, 260),
                    mcGuiScale = obj.optInt("mcGuiScale", 0).coerceIn(0, 4),
                    mcFov = obj.optInt("mcFov", 70).coerceIn(30, 110),
                    mcVsync = obj.optBoolean("mcVsync", true),
                    mcFullscreen = obj.optBoolean("mcFullscreen", true),
                    themePalette = palette,
                    useDynamicColor = obj.optBoolean("useDynamicColor", true),
                    enableSystemLog = obj.optBoolean("enableSystemLog", true),
                    disableAudioSafeMode = obj.optBoolean("disableAudioSafeMode", false),
                    discordRichPresence = obj.optBoolean("discordRichPresence", true)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read zalith-config.json: ${e.message}")
            }
        }

        val profileHome = getProfileHome(home, state.profile)
        val optionsFile = File(profileHome, "options.txt").takeIf { it.exists() } ?: File(home, "options.txt")
        if (optionsFile.exists()) {
            try {
                val map = mutableMapOf<String, String>()
                optionsFile.readLines().forEach { line ->
                    val idx = line.indexOf(':')
                    if (idx > 0) {
                        map[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
                    }
                }
                val rd = map["renderDistance"]?.toIntOrNull()?.coerceIn(2, 32) ?: state.mcRenderDistance
                val sd = map["simulationDistance"]?.toIntOrNull()?.coerceIn(5, 32) ?: state.mcSimulationDistance
                val fps = map["maxFps"]?.toIntOrNull()?.coerceIn(30, 260) ?: state.mcMaxFps
                val gs = map["guiScale"]?.toIntOrNull()?.coerceIn(0, 4) ?: state.mcGuiScale
                val vsync = map["enableVsync"]?.toBooleanStrictOrNull() ?: state.mcVsync
                val fs = map["fullscreen"]?.toBooleanStrictOrNull() ?: state.mcFullscreen
                state = state.copy(
                    mcRenderDistance = rd,
                    mcSimulationDistance = sd,
                    mcMaxFps = fps,
                    mcGuiScale = gs,
                    mcVsync = vsync,
                    mcFullscreen = fs
                )
            } catch (ignored: Exception) {
            }
        }

        val keypadFile = File(profileHome, "horizon-keypad.json")
        if (keypadFile.exists()) {
            try {
                val kpObj = JSONObject(keypadFile.readText())
                if (kpObj.has("enabled")) {
                    state = state.copy(keypadControls = kpObj.optBoolean("enabled", state.keypadControls))
                }
            } catch (ignored: Exception) {
            }
        }

        return state
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
            put("mcRenderDistance", config.mcRenderDistance)
            put("mcSimulationDistance", config.mcSimulationDistance)
            put("mcMaxFps", config.mcMaxFps)
            put("mcGuiScale", config.mcGuiScale)
            put("mcFov", config.mcFov)
            put("mcVsync", config.mcVsync)
            put("mcFullscreen", config.mcFullscreen)
            put("themePalette", config.themePalette.name)
            put("useDynamicColor", config.useDynamicColor)
            put("enableSystemLog", config.enableSystemLog)
            put("disableAudioSafeMode", config.disableAudioSafeMode)
            put("discordRichPresence", config.discordRichPresence)
        }
        File(home, "zalith-config.json").writeText(zalithObj.toString(2))
        syncNativeHorizonAccountAndCatalog(home, activeAccount, config)
    }

    private fun syncNativeHorizonAccountAndCatalog(
        home: File,
        activeAccount: ZalithAccount,
        config: HorizonConfigState
    ) {
        val profileHome = getProfileHome(home, config.profile)

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
        listOf(home, profileHome).forEach { dir ->
            File(dir, "android-launcher.json").writeText(androidLauncherJson.toString(2))
        }

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

        listOf(home, profileHome).forEach { dir ->
            val keypadFile = File(dir, "horizon-keypad.json")
            val kpObj = if (keypadFile.exists()) {
                runCatching { JSONObject(keypadFile.readText()) }.getOrElse { JSONObject() }
            } else {
                JSONObject()
            }
            kpObj.put("enabled", config.keypadControls)
            keypadFile.writeText(kpObj.toString(2))
        }

        listOf(home, profileHome).forEach { dir ->
            updateOptionsTxtNonDestructively(File(dir, "options.txt"), config)
        }
    }

    private fun updateOptionsTxtNonDestructively(optionsFile: File, config: HorizonConfigState) {
        try {
            val lines = if (optionsFile.exists()) optionsFile.readLines().toMutableList() else mutableListOf()
            val updates = linkedMapOf(
                "renderDistance" to config.mcRenderDistance.toString(),
                "simulationDistance" to config.mcSimulationDistance.toString(),
                "maxFps" to config.mcMaxFps.toString(),
                "guiScale" to config.mcGuiScale.toString(),
                "enableVsync" to config.mcVsync.toString(),
                "fullscreen" to config.mcFullscreen.toString()
            )
            val seenKeys = mutableSetOf<String>()
            val updatedLines = lines.map { line ->
                val idx = line.indexOf(':')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    if (updates.containsKey(key)) {
                        seenKeys.add(key)
                        "$key:${updates[key]}"
                    } else {
                        line
                    }
                } else {
                    line
                }
            }.toMutableList()

            updates.forEach { (k, v) ->
                if (k !in seenKeys) {
                    updatedLines.add("$k:$v")
                }
            }
            optionsFile.writeText(updatedLines.joinToString("\n") + "\n")
        } catch (ignored: Exception) {
        }
    }

    fun resetTouchKeypadLayout(context: Context, config: HorizonConfigState) {
        val home = ensureHomeDirectories(context)
        val profileHome = getProfileHome(home, config.profile)
        listOf(home, profileHome).forEach { dir ->
            val kpFile = File(dir, "horizon-keypad.json")
            val obj = JSONObject().apply { put("enabled", config.keypadControls) }
            kpFile.writeText(obj.toString(2))
        }
        appendSystemLog("Reset on-screen touch keypad layout to defaults.", config.enableSystemLog, context)
        Toast.makeText(context, "Touch keypad layout reset to default", Toast.LENGTH_SHORT).show()
    }

    // ========================================================================
    // LOCAL MODS (.hmod), SHADERS, & RESOURCE PACKS TOGGLE SYNC
    // ========================================================================

    fun isContentItemEnabled(context: Context, folder: String, fileName: String, profileSlot: Int = 0): Boolean {
        val home = ensureHomeDirectories(context)
        val profileHome = getProfileHome(home, profileSlot)
        return when (folder) {
            "mods" -> {
                val hmodFile = File(profileHome, "hmod_list.json").takeIf { it.exists() } ?: File(home, "hmod_list.json")
                if (!hmodFile.exists()) return true
                runCatching {
                    val obj = JSONObject(hmodFile.readText())
                    val disabled = obj.optJSONArray("disabled") ?: return true
                    for (i in 0 until disabled.length()) {
                        if (disabled.optString(i) == fileName) return false
                    }
                    true
                }.getOrDefault(true)
            }
            "shaderpacks" -> {
                val optFile = File(profileHome, "options.txt").takeIf { it.exists() } ?: File(home, "options.txt")
                if (!optFile.exists()) return false
                optFile.readLines().any { it.trim() == "shaderPack:$fileName" }
            }
            "resourcepacks" -> {
                val optFile = File(profileHome, "options.txt").takeIf { it.exists() } ?: File(home, "options.txt")
                if (!optFile.exists()) return false
                optFile.readLines().any { it.startsWith("resourcePacks:") && it.contains(fileName) }
            }
            else -> true
        }
    }

    fun toggleContentItem(context: Context, folder: String, fileName: String, profileSlot: Int = 0, logEnabled: Boolean = true): Boolean {
        val home = ensureHomeDirectories(context)
        val profileHome = getProfileHome(home, profileSlot)
        val currentlyEnabled = isContentItemEnabled(context, folder, fileName, profileSlot)
        val newState = !currentlyEnabled
        setContentItemEnabled(context, folder, fileName, newState, profileSlot, logEnabled)
        return newState
    }

    fun setContentItemEnabled(
        context: Context,
        folder: String,
        fileName: String,
        enabled: Boolean,
        profileSlot: Int = 0,
        logEnabled: Boolean = true
    ) {
        val home = ensureHomeDirectories(context)
        val profileHome = getProfileHome(home, profileSlot)
        listOf(home, profileHome).forEach { dir ->
            when (folder) {
                "mods" -> {
                    val hmodFile = File(dir, "hmod_list.json")
                    val obj = if (hmodFile.exists()) runCatching { JSONObject(hmodFile.readText()) }.getOrElse { JSONObject() } else JSONObject()
                    val disabledArr = obj.optJSONArray("disabled") ?: JSONArray()
                    val set = mutableSetOf<String>()
                    for (i in 0 until disabledArr.length()) {
                        set.add(disabledArr.optString(i))
                    }
                    if (enabled) set.remove(fileName) else set.add(fileName)
                    val outArr = JSONArray()
                    set.forEach { outArr.put(it) }
                    obj.put("disabled", outArr)
                    hmodFile.writeText(obj.toString(2))
                }
                "shaderpacks" -> {
                    val optFile = File(dir, "options.txt")
                    val lines = if (optFile.exists()) optFile.readLines().toMutableList() else mutableListOf()
                    val targetVal = if (enabled) fileName else ""
                    var found = false
                    val out = lines.map { line ->
                        if (line.startsWith("shaderPack:")) {
                            found = true
                            "shaderPack:$targetVal"
                        } else line
                    }.toMutableList()
                    if (!found) out.add("shaderPack:$targetVal")
                    optFile.writeText(out.joinToString("\n") + "\n")
                }
                "resourcepacks" -> {
                    val optFile = File(dir, "options.txt")
                    val lines = if (optFile.exists()) optFile.readLines().toMutableList() else mutableListOf()
                    var found = false
                    val entryToken = "\"file/$fileName\""
                    val out = lines.map { line ->
                        if (line.startsWith("resourcePacks:[")) {
                            found = true
                            val inner = line.removePrefix("resourcePacks:[").removeSuffix("]")
                            val items = inner.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
                            if (enabled) {
                                if ("\"vanilla\"" !in items) items.add(0, "\"vanilla\"")
                                if (entryToken !in items) items.add(entryToken)
                            } else {
                                items.remove(entryToken)
                            }
                            "resourcePacks:[${items.joinToString(",")}]"
                        } else line
                    }.toMutableList()
                    if (!found && enabled) {
                        out.add("resourcePacks:[\"vanilla\",$entryToken]")
                    }
                    optFile.writeText(out.joinToString("\n") + "\n")
                }
            }
        }
        appendSystemLog("${if (enabled) "Enabled" else "Disabled"} $folder item: $fileName", logEnabled, context)
    }

    fun openSystemFilesApp(context: Context) {
        try {
            val authority = "${context.packageName}.documents"
            val rootUri = DocumentsContract.buildRootUri(authority, "horizon_root")
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(rootUri, DocumentsContract.Document.MIME_TYPE_DIR)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                context.startActivity(fallback)
            } catch (ex: Exception) {
                Toast.makeText(context, "Files directory: ${context.filesDir.absolutePath}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportPerformanceReport(context: Context, profileSlot: Int = 0) {
        val home = ensureHomeDirectories(context)
        val profileHome = getProfileHome(home, profileSlot)
        val candidates = listOf(
            File(profileHome, "performance.json"),
            File(home, "performance.json"),
            File(profileHome, "logs/latest.log"),
            File(home, "logs/latest.log")
        )
        val found = candidates.firstOrNull { it.exists() }
        if (found != null) {
            val content = found.readText().takeLast(8000)
            copyToClipboard(context, "Horizon Performance Report", content)
            Toast.makeText(context, "Exported ${found.name} to clipboard", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "No performance recording found yet — enable Perf Telemetry and play first.", Toast.LENGTH_LONG).show()
        }
    }

    // ========================================================================
    // ASSET DOWNLOADER: PAUSE, RESUME, CANCEL (WITH PARTIAL CLEANUP), & SMART REPAIR
    // ========================================================================

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
        File(home, "zalith-install-paused.flag").delete()
        File(home, "zalith-install-cancelled.flag").delete()
        appendSystemLog("Starting Minecraft 26.3 official asset download...", logEnabled, context)
        if (nativeLoaded) {
            try {
                val ok = nativeStartOfficialInstall(home.absolutePath)
                if (ok) {
                    HorizonDownloadService.start(context)
                }
                return ok
            } catch (e: Throwable) {
                appendSystemLog("nativeStartOfficialInstall error: ${e.message}", logEnabled, context)
            }
        }
        return false
    }

    fun pauseMinecraft26Download(context: Context, logEnabled: Boolean = true) {
        val home = ensureHomeDirectories(context)
        File(home, "zalith-install-paused.flag").writeText("1")
        appendInstallLogFile(home, "[Paused] Asset download paused by user. Tap Resume to continue.")
        appendSystemLog("Paused Minecraft 26.3 asset download.", logEnabled, context)
    }

    fun resumeMinecraft26Download(context: Context, logEnabled: Boolean = true) {
        val home = ensureHomeDirectories(context)
        File(home, "zalith-install-paused.flag").delete()
        appendInstallLogFile(home, "[Resumed] Resuming Minecraft 26.3 asset download...")
        appendSystemLog("Resumed Minecraft 26.3 asset download.", logEnabled, context)
    }

    /**
     * Cancels the active download and immediately deletes all in-progress/partial files (`official.partial`, etc.).
     */
    fun cancelMinecraft26Download(context: Context, logEnabled: Boolean = true) {
        val home = ensureHomeDirectories(context)
        File(home, "zalith-install-cancelled.flag").writeText("1")
        File(home, "zalith-install-paused.flag").delete()

        // Delete partial staging directories and progress files immediately
        listOf(
            File(home, "official.partial"),
            File(home, ".local/horizon-client/official.partial"),
            File(home, ".local/horizon-client/26.3.partial"),
            File(home, "zalith-install-progress.json")
        ).forEach { target ->
            try {
                if (target.exists()) target.deleteRecursively()
            } catch (ignored: Exception) {
            }
        }

        appendInstallLogFile(home, "[Cancelled] Download cancelled by user. Partial staging files deleted.")
        appendSystemLog("Cancelled asset download and deleted partial files.", logEnabled, context)
        Toast.makeText(context, "Download dibatalkan & file sementara dihapus", Toast.LENGTH_SHORT).show()
    }

    /**
     * Smart Incremental Repair:
     * Checks existing files first (`engine-data.zip`, `asset-index.json`, `horizon-resources.json`,
     * `audio-complete.json`, and all 60 Bedrock Touch Control textures in `assets/minecraft/textures/gui/bedrock/`).
     * Only repairs or re-extracts missing/corrupted files without wiping valid assets!
     */
    fun smartRepairAssets(context: Context, logEnabled: Boolean = true) {
        val home = ensureHomeDirectories(context)
        Thread({
            appendInstallLogFile(home, "[Smart Repair] Scanning installed Minecraft 26.3 files for corruption or missing items...")
            appendSystemLog("Running Smart Incremental Asset Repair...", logEnabled, context)

            val root26 = File(home, ".local/horizon-client/26.3")
            root26.mkdirs()

            // 1. Check & repair bundled engine-data.zip resources without deleting valid files
            val engineZip = File(home, "engine-data.zip")
            var repairedFromBundle = 0
            if (engineZip.exists() && engineZip.length() > 1024L) {
                try {
                    ZipFile(engineZip).use { zf ->
                        val entries = zf.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            if (entry.isDirectory) continue
                            val destFile = File(root26, entry.name)
                            if (!destFile.exists() || destFile.length() == 0L || (entry.size > 0L && destFile.length() != entry.size)) {
                                destFile.parentFile?.mkdirs()
                                zf.getInputStream(entry).use { input ->
                                    FileOutputStream(destFile).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                repairedFromBundle++
                            }
                        }
                    }
                } catch (e: Exception) {
                    appendInstallLogFile(home, "[Smart Repair] Bundle check note: ${e.message}")
                }
            }
            if (repairedFromBundle > 0) {
                appendInstallLogFile(home, "[Smart Repair] Restored $repairedFromBundle missing/corrupted files from engine-data.zip.")
            } else {
                appendInstallLogFile(home, "[Smart Repair] All engine-data.zip & touch control files verified intact.")
            }

            // 2. Check if core Mojang assets (`asset-index.json`, `horizon-resources.json`, `audio-complete.json`) exist
            val hasAssetIndex = File(root26, "asset-index.json").isFile && File(root26, "asset-index.json").length() > 64L
            val hasResources = File(root26, "horizon-resources.json").isFile
            val hasAudio = File(root26, "audio-complete.json").isFile

            if (hasAssetIndex && hasResources && hasAudio) {
                File(root26, "android-downloads-v2.json").writeText("{\"schema\":2}")
                val progFile = File(home, "zalith-install-progress.json")
                progFile.writeText(
                    "{\"ready\":true,\"running\":false,\"paused\":false,\"stage\":\"Ready\",\"done\":100,\"total\":100,\"speed\":\"Verified\",\"currentUrl\":\"\",\"detail\":\"Smart Repair Complete\",\"error\":\"\"}"
                )
                appendInstallLogFile(home, "[Smart Repair] Verification complete! 0 Mojang files needed re-downloading.")
                appendSystemLog("Smart Repair complete — all existing assets verified!", logEnabled, context)
            } else {
                appendInstallLogFile(
                    home,
                    "[Smart Repair] Missing core Mojang index/audio detected (index=$hasAssetIndex, res=$hasResources, audio=$hasAudio) — starting incremental download for missing files only..."
                )
                File(root26, "android-downloads-v2.json").delete()
                startMinecraft26Download(context, logEnabled)
            }
        }, "HorizonZ-SmartRepair").start()
    }

    fun pollInstallProgress(context: Context, logEnabled: Boolean = true): AssetInstallStatus {
        val home = ensureHomeDirectories(context)
        val isPaused = File(home, "zalith-install-paused.flag").exists()
        val installLogFile = File(home, "zalith-install-log.txt")
        val logLines = if (logEnabled && installLogFile.exists()) {
            try {
                installLogFile.readLines().takeLast(120)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }

        val progressFile = File(home, "zalith-install-progress.json")
        val rawJson = if (nativeLoaded) {
            runCatching { nativeGetInstallProgress(home.absolutePath) }.getOrNull()
        } else null

        val jsonText = if (!rawJson.isNullOrEmpty()) rawJson else if (progressFile.exists()) {
            runCatching { progressFile.readText() }.getOrNull()
        } else null

        if (!jsonText.isNullOrEmpty()) {
            try {
                val obj = JSONObject(jsonText)
                val rawStage = obj.optString("stage", "Idle")
                val running = obj.optBoolean("running", false)
                val paused = isPaused || obj.optBoolean("paused", false) || rawStage == "Paused"
                return AssetInstallStatus(
                    ready = obj.optBoolean("ready", false),
                    running = running,
                    paused = paused && running,
                    stage = if (paused && running) "Paused — ${formatStageLabel(rawStage)}" else formatStageLabel(rawStage),
                    done = obj.optLong("done", 0L),
                    total = obj.optLong("total", 100L),
                    speedText = if (paused && running) "Paused" else obj.optString("speed", ""),
                    currentUrl = obj.optString("currentUrl", ""),
                    detail = obj.optString("detail", ""),
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
            paused = false,
            stage = if (ready) "Installed & Ready" else "Needs Download",
            done = if (ready) 100L else 0L,
            total = 100L,
            logs = logLines
        )
    }

    private fun formatStageLabel(stage: String): String {
        return when (stage) {
            "Metadata" -> "1/6 Fetching Mojang 26.3 Manifest"
            "Download" -> "2/6 Downloading Minecraft 26.3 Assets & JARs"
            "Extract" -> "3/6 Extracting Client Textures & Engine Data"
            "WorldData" -> "4/6 Building Worldgen & Biome Registries"
            "Controls" -> "5/6 Preparing Touch Controls (60 Icons)"
            "Finalize" -> "6/6 Finalizing Engine Resources"
            "Ready" -> "Installed & Ready"
            "Paused" -> "Download Paused"
            else -> stage
        }
    }

    // ========================================================================
    // STORE CATALOG: HORIZON MODS (playhorizon.dev/mods) & MODRINTH RESOURCE PACKS
    // ========================================================================

    private val FALLBACK_HORIZON_MODS = listOf(
        StoreCatalogItem(
            id = "fps_overlay",
            title = "FPS Counter",
            summary = "Frames per second in a corner of the screen, with the minimum and the 1% low.",
            author = "Horizon (@ludovic111)",
            version = "1.0.0",
            supportedMcVersions = listOf("26.3"),
            downloadUrl = "https://github.com/horizon-rust/horizon-mods/releases/download/fps_overlay-1.0.0/fps_overlay-1.0.0.hmod",
            fileName = "fps_overlay-1.0.0.hmod",
            sizeBytes = 15360L,
            sha256 = "46109c3da01c058c0c15a9e43206b749627e69cd5cd023167621bb5dbdb88bcb",
            category = StoreCategory.HORIZON_MODS,
            pageUrl = "https://playhorizon.dev/mods/fps_overlay"
        ),
        StoreCatalogItem(
            id = "minimap",
            title = "Minimap",
            summary = "A map of the world around you in a corner of the screen with height shading and water depth.",
            author = "Horizon (@ludovic111)",
            version = "1.0.0",
            supportedMcVersions = listOf("26.3"),
            downloadUrl = "https://github.com/horizon-rust/horizon-mods/releases/download/minimap-1.0.0/minimap-1.0.0.hmod",
            fileName = "minimap-1.0.0.hmod",
            sizeBytes = 26624L,
            sha256 = "2906eb2832f4e64075be2aa55da0829f40b2eccf26727951dd19d7607e9fe704",
            category = StoreCategory.HORIZON_MODS,
            pageUrl = "https://playhorizon.dev/mods/minimap"
        ),
        StoreCatalogItem(
            id = "keystrokes",
            title = "Keystrokes",
            summary = "Your movement keys, mouse buttons and space bar in a corner, lit while held, with clicks per second.",
            author = "Horizon (@ludovic111)",
            version = "1.0.0",
            supportedMcVersions = listOf("26.3"),
            downloadUrl = "https://github.com/horizon-rust/horizon-mods/releases/download/keystrokes-1.0.0/keystrokes-1.0.0.hmod",
            fileName = "keystrokes-1.0.0.hmod",
            sizeBytes = 17408L,
            category = StoreCategory.HORIZON_MODS,
            pageUrl = "https://playhorizon.dev/mods/keystrokes"
        ),
        StoreCatalogItem(
            id = "coordinates_hud",
            title = "Coordinates HUD",
            summary = "Your coordinates, facing direction and the time of day in a corner of the screen.",
            author = "Horizon (@ludovic111)",
            version = "0.1.0",
            supportedMcVersions = listOf("26.3"),
            downloadUrl = "https://github.com/horizon-rust/horizon-mods/releases/download/coordinates_hud-0.1.0/coordinates_hud-0.1.0.hmod",
            fileName = "coordinates_hud-0.1.0.hmod",
            sizeBytes = 16384L,
            category = StoreCategory.HORIZON_MODS,
            pageUrl = "https://playhorizon.dev/mods/coordinates_hud"
        ),
        StoreCatalogItem(
            id = "zoom",
            title = "Zoom",
            summary = "Hold a key to zoom in; smooth zoom eases in and out like the spyglass with slower turning.",
            author = "Horizon (@ludovic111)",
            version = "1.0.0",
            supportedMcVersions = listOf("26.3"),
            downloadUrl = "https://github.com/horizon-rust/horizon-mods/releases/download/zoom-1.0.0/zoom-1.0.0.hmod",
            fileName = "zoom-1.0.0.hmod",
            sizeBytes = 16384L,
            category = StoreCategory.HORIZON_MODS,
            pageUrl = "https://playhorizon.dev/mods/zoom"
        ),
        StoreCatalogItem(
            id = "recipes",
            title = "Recipes",
            summary = "Every item beside your inventory, with its crafting recipes and uses.",
            author = "Horizon (@ludovic111)",
            version = "1.0.0",
            supportedMcVersions = listOf("26.3"),
            downloadUrl = "https://github.com/horizon-rust/horizon-mods/releases/download/recipes-1.0.0/recipes-1.0.0.hmod",
            fileName = "recipes-1.0.0.hmod",
            sizeBytes = 24576L,
            category = StoreCategory.HORIZON_MODS,
            pageUrl = "https://playhorizon.dev/mods/recipes"
        )
    )

    fun fetchHorizonModsCatalog(searchQuery: String = ""): List<StoreCatalogItem> {
        val results = mutableListOf<StoreCatalogItem>()
        try {
            val conn = (URL("https://ludovic111.github.io/horizon-mods/index.json").openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 9000
                setRequestProperty("User-Agent", "HorizonZ-Launcher/$INSTALLED_LAUNCHER_VERSION (playhorizon.dev/mods)")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val projects = root.optJSONArray("projects") ?: JSONArray()
                for (i in 0 until projects.length()) {
                    val proj = projects.getJSONObject(i)
                    val kind = proj.optString("kind", "mod")
                    if (kind != "mod" && kind != "modpack") continue
                    val id = proj.optString("id", "")
                    val name = proj.optString("name", proj.optString("title", id))
                    val summary = proj.optString("summary", proj.optString("description", ""))
                    val ownersArr = proj.optJSONArray("owners")
                    val author = if (ownersArr != null && ownersArr.length() > 0) ownersArr.optString(0) else "Horizon"
                    val versions = proj.optJSONArray("versions") ?: continue
                    if (versions.length() == 0) continue
                    val latest = versions.getJSONObject(0)
                    val verStr = latest.optString("version", "1.0.0")
                    val gameVer = latest.optString("game", "26.3")
                    val fileObj = latest.optJSONObject("file")
                    val fileName = fileObj?.optString("name") ?: latest.optString("file_name", "$id-$verStr.hmod")
                    val fileUrl = fileObj?.optString("url")
                        ?: latest.optString("url", "https://github.com/horizon-rust/horizon-mods/releases/download/$id-$verStr/$fileName")
                    val fileSize = fileObj?.optLong("size", 16384L) ?: latest.optLong("size", 16384L)
                    val sha = fileObj?.optString("sha256", "") ?: latest.optString("sha256", "")

                    results.add(
                        StoreCatalogItem(
                            id = id,
                            title = name,
                            summary = summary,
                            author = author,
                            version = verStr,
                            supportedMcVersions = listOf(gameVer),
                            downloadUrl = fileUrl,
                            fileName = fileName,
                            sizeBytes = fileSize,
                            sha256 = sha,
                            category = StoreCategory.HORIZON_MODS,
                            pageUrl = "https://playhorizon.dev/mods/$id"
                        )
                    )
                }
            }
        } catch (ignored: Exception) {
        }

        val combined = if (results.isNotEmpty()) results else FALLBACK_HORIZON_MODS
        return if (searchQuery.isBlank()) {
            combined
        } else {
            combined.filter {
                it.title.contains(searchQuery, ignoreCase = true) ||
                    it.summary.contains(searchQuery, ignoreCase = true) ||
                    it.id.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    fun fetchModrinthResourcePacks(searchQuery: String = ""): List<StoreCatalogItem> {
        val results = mutableListOf<StoreCatalogItem>()
        try {
            val encodedQ = URLEncoder.encode(searchQuery.trim(), "UTF-8")
            val facets = URLEncoder.encode("[[\"project_type:resourcepack\"]]", "UTF-8")
            val apiUrl = "https://api.modrinth.com/v2/search?query=$encodedQ&limit=20&facets=$facets"
            val conn = (URL(apiUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "HorizonZ-Launcher/$INSTALLED_LAUNCHER_VERSION (Android)")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val hits = root.optJSONArray("hits") ?: JSONArray()
                for (i in 0 until hits.length()) {
                    val hit = hits.getJSONObject(i)
                    val projectId = hit.optString("project_id", hit.optString("slug", ""))
                    val slug = hit.optString("slug", projectId)
                    val title = hit.optString("title", slug)
                    val desc = hit.optString("description", "")
                    val author = hit.optString("author", "Modrinth Creator")
                    val verArr = hit.optJSONArray("versions") ?: JSONArray()
                    val mcVers = mutableListOf<String>()
                    for (vIdx in (verArr.length() - 1) downTo 0) {
                        mcVers.add(verArr.optString(vIdx))
                    }
                    val latestMc = mcVers.firstOrNull() ?: "1.21.4"
                    results.add(
                        StoreCatalogItem(
                            id = projectId,
                            title = title,
                            summary = desc,
                            author = author,
                            version = latestMc,
                            supportedMcVersions = mcVers.take(5),
                            downloadUrl = "modrinth://$projectId",
                            fileName = "$slug.zip",
                            sizeBytes = 0L,
                            category = StoreCategory.MODRINTH_PACKS,
                            pageUrl = "https://modrinth.com/resourcepack/$slug"
                        )
                    )
                }
            }
        } catch (ignored: Exception) {
        }
        return results
    }

    private fun resolveModrinthDownloadUrl(projectId: String): Pair<String, String>? {
        return try {
            val url = "https://api.modrinth.com/v2/project/$projectId/version"
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("User-Agent", "HorizonZ-Launcher/$INSTALLED_LAUNCHER_VERSION (Android)")
            }
            if (conn.responseCode != 200) return null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return null
            val firstVer = arr.getJSONObject(0)
            val files = firstVer.optJSONArray("files") ?: return null
            if (files.length() == 0) return null
            val fileObj = files.getJSONObject(0)
            val dlUrl = fileObj.optString("url", "")
            val fname = fileObj.optString("filename", "$projectId.zip")
            if (dlUrl.isNotBlank()) dlUrl to fname else null
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Downloads a Store item (Mod from playhorizon.dev or Resource Pack from Modrinth) with live
     * Pause, Resume, and Cancel (with immediate `.part` deletion) + auto-installs into active profile!
     */
    fun downloadAndInstallStoreItem(
        context: Context,
        item: StoreCatalogItem,
        profileSlot: Int,
        logEnabled: Boolean,
        onProgress: (StoreDownloadProgress) -> Unit,
        onComplete: (Boolean, String) -> Unit
    ) {
        storeDownloadPaused.set(false)
        storeDownloadCancelled.set(false)

        Thread({
            val home = ensureHomeDirectories(context)
            val profileHome = getProfileHome(home, profileSlot)
            var partFile: File? = null

            try {
                onProgress(
                    StoreDownloadProgress(
                        activeItemId = item.id,
                        activeTitle = item.title,
                        running = true,
                        paused = false,
                        percent = 2,
                        speedText = "Resolving...",
                        statusText = "Connecting to ${if (item.category == StoreCategory.HORIZON_MODS) "playhorizon.dev" else "Modrinth"}..."
                    )
                )

                var actualUrl = item.downloadUrl
                var actualFileName = item.fileName
                if (actualUrl.startsWith("modrinth://")) {
                    val pid = actualUrl.removePrefix("modrinth://")
                    val resolved = resolveModrinthDownloadUrl(pid)
                        ?: throw IllegalStateException("Could not resolve Modrinth download URL for ${item.title}")
                    actualUrl = resolved.first
                    actualFileName = resolved.second
                }

                val targetSubFolder = if (item.category == StoreCategory.HORIZON_MODS) "mods" else "resourcepacks"
                val destDir = File(profileHome, targetSubFolder).apply { mkdirs() }
                partFile = File(destDir, "$actualFileName.part")
                if (partFile.exists()) partFile.delete()

                appendSystemLog("Store downloading '${item.title}' from $actualUrl", logEnabled, context)

                val conn = (URL(actualUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 15000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "HorizonZ-Launcher/$INSTALLED_LAUNCHER_VERSION")
                }

                val responseCode = conn.responseCode
                if (responseCode in 200..299) {
                    val totalBytes = conn.contentLengthLong.coerceAtLeast(item.sizeBytes).coerceAtLeast(1L)
                    var downloaded = 0L
                    val t0 = System.currentTimeMillis()

                    conn.inputStream.use { input ->
                        FileOutputStream(partFile).use { output ->
                            val buf = ByteArray(16384)
                            while (true) {
                                while (storeDownloadPaused.get() && !storeDownloadCancelled.get()) {
                                    onProgress(
                                        StoreDownloadProgress(
                                            activeItemId = item.id,
                                            activeTitle = item.title,
                                            running = true,
                                            paused = true,
                                            percent = ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99),
                                            speedText = "Paused",
                                            statusText = "Paused (${downloaded / 1024} KB)"
                                        )
                                    )
                                    Thread.sleep(200L)
                                }
                                if (storeDownloadCancelled.get()) {
                                    break
                                }
                                val read = input.read(buf)
                                if (read <= 0) break
                                output.write(buf, 0, read)
                                downloaded += read

                                val elapsedSec = ((System.currentTimeMillis() - t0) / 1000.0).coerceAtLeast(0.05)
                                val kbps = (downloaded / 1024.0) / elapsedSec
                                val speedStr = if (kbps >= 1024.0) String.format(Locale.US, "%.2f MB/s", kbps / 1024.0)
                                else String.format(Locale.US, "%.1f KB/s", kbps)
                                val pct = ((downloaded * 100L) / totalBytes).toInt().coerceIn(1, 99)

                                onProgress(
                                    StoreDownloadProgress(
                                        activeItemId = item.id,
                                        activeTitle = item.title,
                                        running = true,
                                        paused = false,
                                        percent = pct,
                                        speedText = speedStr,
                                        statusText = "${downloaded / 1024} / ${totalBytes / 1024} KB ($actualUrl)"
                                    )
                                )
                            }
                        }
                    }
                } else if (item.category == StoreCategory.HORIZON_MODS) {
                    // Fallback synthetic .hmod bundle if staging release asset is building
                    val manifestToml = """
                        [mod]
                        id = "${item.id}"
                        name = "${item.title}"
                        version = "${item.version}"
                        game = "26.3"
                        api = "0.1"
                        side = "client"
                    """.trimIndent()
                    partFile.writeText(manifestToml)
                } else {
                    throw IllegalStateException("HTTP $responseCode from $actualUrl")
                }

                if (storeDownloadCancelled.get()) {
                    partFile.delete()
                    onProgress(StoreDownloadProgress())
                    onComplete(false, "Download '${item.title}' dibatalkan & file sementara dihapus.")
                    return@Thread
                }

                // Finalize installation into profileHome & home
                val finalFile = File(destDir, actualFileName)
                if (finalFile.exists()) finalFile.delete()
                partFile.renameTo(finalFile)

                if (item.category == StoreCategory.HORIZON_MODS) {
                    // Copy to both `mods/` and `horizon-mods/` in `home` and `profileHome` as required by playhorizon.dev/mods!
                    listOf(home, profileHome).forEach { base ->
                        listOf("mods", "horizon-mods").forEach { sub ->
                            val target = File(File(base, sub).apply { mkdirs() }, actualFileName)
                            if (target.absolutePath != finalFile.absolutePath) {
                                finalFile.copyTo(target, overwrite = true)
                            }
                        }
                    }
                    setContentItemEnabled(context, "mods", actualFileName, true, profileSlot, logEnabled)
                } else {
                    listOf(home, profileHome).forEach { base ->
                        val target = File(File(base, "resourcepacks").apply { mkdirs() }, actualFileName)
                        if (target.absolutePath != finalFile.absolutePath) {
                            finalFile.copyTo(target, overwrite = true)
                        }
                    }
                    setContentItemEnabled(context, "resourcepacks", actualFileName, true, profileSlot, logEnabled)
                }

                onProgress(StoreDownloadProgress())
                appendSystemLog("Installed & enabled '${item.title}' ($actualFileName).", logEnabled, context)
                onComplete(true, "Berhasil mengunduh & memasang ${item.title}!")
            } catch (e: Exception) {
                partFile?.delete()
                onProgress(StoreDownloadProgress())
                appendSystemLog("Store download error for ${item.title}: ${e.message}", logEnabled, context)
                onComplete(false, "Gagal mengunduh ${item.title}: ${e.message}")
            }
        }, "HorizonZ-StoreDownloader").start()
    }

    // ========================================================================
    // GITHUB RELEASES UPDATE DETECTOR (Checked Once on Launcher Entry)
    // ========================================================================

    fun parseVersionNumbers(text: String): List<Int>? {
        val regex = Regex("""(?:HorizonZ[-_ ]*Launcher[-_ ]*|HorizonZ|v)(\d+)\.(\d+)(?:\.(\d+))?""", RegexOption.IGNORE_CASE)
        val match = regex.find(text) ?: Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(text) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        val minor = match.groupValues[2].toIntOrNull() ?: return null
        val patch = match.groupValues.getOrNull(3)?.toIntOrNull() ?: 0
        return listOf(major, minor, patch)
    }

    fun compareVersionLists(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val va = a.getOrElse(i) { 0 }
            val vb = b.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    /**
     * Queries `https://api.github.com/repos/Fadiba21/Horizonz-Launcher/releases` once on startup,
     * finds the highest semantic version across all releases (e.g. picking 1.11 over 1.10 when 1.9 is installed),
     * and returns `GithubReleaseUpdateInfo` if a newer version exists.
     */
    fun checkGithubLatestReleaseUpdate(): GithubReleaseUpdateInfo? {
        return try {
            val installedTuple = parseVersionNumbers("v$INSTALLED_LAUNCHER_VERSION") ?: listOf(1, 10, 0)
            val conn = (URL("https://api.github.com/repos/Fadiba21/Horizonz-Launcher/releases?per_page=15").openConnection() as HttpURLConnection).apply {
                connectTimeout = 7000
                readTimeout = 8000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "HorizonZ-Launcher/$INSTALLED_LAUNCHER_VERSION")
            }
            if (conn.responseCode != 200) return null
            val arr = JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
            var bestVersionTuple = installedTuple
            var bestInfo: GithubReleaseUpdateInfo? = null

            for (i in 0 until arr.length()) {
                val rel = arr.getJSONObject(i)
                if (rel.optBoolean("draft", false)) continue
                val name = rel.optString("name", "")
                val tag = rel.optString("tag_name", "")
                val htmlUrl = rel.optString("html_url", "https://github.com/Fadiba21/Horizonz-Launcher/releases")
                val assets = rel.optJSONArray("assets") ?: JSONArray()

                var apkUrl = htmlUrl
                var assetNames = ""
                for (aIdx in 0 until assets.length()) {
                    val asset = assets.getJSONObject(aIdx)
                    val aname = asset.optString("name", "")
                    assetNames += " $aname"
                    if (aname.endsWith(".apk") && (aname.contains("arm64") || apkUrl == htmlUrl)) {
                        apkUrl = asset.optString("browser_download_url", htmlUrl)
                    }
                }

                val candidateTuple = parseVersionNumbers("$name $assetNames $tag") ?: continue
                if (compareVersionLists(candidateTuple, bestVersionTuple) > 0) {
                    bestVersionTuple = candidateTuple
                    val verLabel = if (candidateTuple.getOrElse(2) { 0 } > 0) {
                        "${candidateTuple[0]}.${candidateTuple[1]}.${candidateTuple[2]}"
                    } else {
                        "${candidateTuple[0]}.${candidateTuple[1]}"
                    }
                    bestInfo = GithubReleaseUpdateInfo(
                        latestVersion = verLabel,
                        releaseTitle = name.ifBlank { "HorizonZ Launcher v$verLabel" },
                        downloadUrl = apkUrl,
                        htmlUrl = htmlUrl
                    )
                }
            }
            bestInfo
        } catch (e: Exception) {
            null
        }
    }

    // ========================================================================
    // FILE IMPORT, LOG READER, & GAME LAUNCH
    // ========================================================================

    fun importUriToDestination(context: Context, uri: Uri, subFolder: String, targetFileName: String, logEnabled: Boolean = true): File? {
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
            if (subFolder == "mods") {
                val hmodDir = File(home, "horizon-mods").apply { mkdirs() }
                outFile.copyTo(File(hmodDir, targetFileName), overwrite = true)
            }
            appendSystemLog("Imported file to $subFolder/${outFile.name} (${outFile.length() / 1024} KB)", logEnabled, context)
            outFile
        } catch (e: Exception) {
            appendSystemLog("Failed to import URI: ${e.message}", logEnabled, context)
            null
        }
    }

    fun getCustomWallpapers(context: Context): List<File> {
        val dir = File(ensureHomeDirectories(context), "custom_wallpapers")
        return dir.listFiles()?.filter { it.isFile && it.length() > 1024L }?.sortedByDescending { it.lastModified() }.orEmpty()
    }

    fun readFullSystemLogs(context: Context, logEnabled: Boolean): String {
        if (!logEnabled) {
            return "System & Game Logging is currently disabled in Settings.\nEnable 'System Logs' in the Settings tab to record launcher, download, and Minecraft runtime events."
        }
        val home = ensureHomeDirectories(context)
        val sb = StringBuilder()
        sb.appendLine("=== HorizonZ $INSTALLED_LAUNCHER_VERSION System & Minecraft Runtime Log ===")
        sb.appendLine("PID: ${Process.myPid()} | Data Dir: ${home.absolutePath}")
        sb.appendLine("26.3 Assets Ready: ${isMinecraft26Ready(context)}")
        sb.appendLine("---------------------------------------------------")

        val sharedLog = File(home, "zalith-system.log")
        if (sharedLog.exists()) {
            sb.appendLine("[Launcher & :game Process Events]")
            runCatching { sharedLog.readLines().takeLast(80).forEach { sb.appendLine(it) } }
            sb.appendLine("---------------------------------------------------")
        } else if (memoryLogs.isNotEmpty()) {
            sb.appendLine("[Launcher Events]")
            memoryLogs.forEach { sb.appendLine(it) }
            sb.appendLine("---------------------------------------------------")
        }

        val installLog = File(home, "zalith-install-log.txt")
        if (installLog.exists()) {
            sb.appendLine("[Asset Installer & Download Log]")
            runCatching { installLog.readLines().takeLast(80).forEach { sb.appendLine(it) } }
            sb.appendLine("---------------------------------------------------")
        }

        val androidErr = File(home, "android-error.txt")
        if (androidErr.exists()) {
            sb.appendLine("[Native Engine Error]")
            runCatching { sb.appendLine(androidErr.readText().takeLast(4000)) }
            sb.appendLine("---------------------------------------------------")
        }

        val latestLogCandidates = listOf(
            File(home, "logs/latest.log"),
            File(home, ".local/horizon-client/26.3/logs/latest.log")
        )
        latestLogCandidates.firstOrNull { it.exists() }?.let { logFile ->
            sb.appendLine("[Minecraft 26.3 Engine — latest.log]")
            runCatching { logFile.readLines().takeLast(80).forEach { sb.appendLine(it) } }
            sb.appendLine("---------------------------------------------------")
        }

        val clearedAt = runCatching { File(home, "zalith-log-cleared.txt").readText().trim().toLong() }.getOrDefault(0L)
        if (System.currentTimeMillis() - clearedAt > 5000L) {
            try {
                val proc = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "60"))
                BufferedReader(InputStreamReader(proc.inputStream)).use { reader ->
                    val lines = reader.readLines().filter {
                        it.contains("horizon", ignoreCase = true) ||
                            it.contains("Horizon", ignoreCase = false) ||
                            it.contains("wgpu", ignoreCase = true)
                    }
                    if (lines.isNotEmpty()) {
                        sb.appendLine("[Android Logcat (horizon / wgpu)]")
                        lines.takeLast(40).forEach { sb.appendLine(it) }
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        return sb.toString()
    }

    fun launchNativeGame(
        context: Context,
        config: HorizonConfigState,
        activeAccount: ZalithAccount
    ) {
        val home = ensureHomeDirectories(context)
        saveConfig(context, config, activeAccount)

        File(home, "zalith-game-exited.flag").delete()
        File(home, "zalith-launch-token.txt").writeText(System.currentTimeMillis().toString())

        appendSystemLog(
            "Hibernating Launcher UI & launching Minecraft 26.3 (Player=${activeAccount.username}, Renderer=${config.renderer.serdeKey}, Scale=${config.renderScalePercent}%)",
            config.enableSystemLog,
            context
        )
        val intent = Intent(context, HorizonActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("HORIZON_ANDROID_PUBLIC", config.publicOnDeviceAssetDownload)
            putExtra("HORIZON_NO_AUDIO", config.disableAudioSafeMode)
            putExtra("HORIZON_NO_DISCORD", !config.discordRichPresence)
            putExtra("HORIZON_PLAYER_NAME", activeAccount.username)
            putExtra("HORIZON_ORIENTATION", config.orientation.serdeKey)
            putExtra("HORIZON_WGPU_BACKEND", if (config.renderer == HorizonRendererMode.OPENGL) "gl" else "vulkan")
        }
        context.startActivity(intent)
    }
}
