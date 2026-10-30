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

object HorizonNativeBridge {
    private const val TAG = "HorizonNativeBridge"
    private const val MAX_LOG_LINES = 250
    private var nativeLoaded = false
    private val memoryLogs = ConcurrentLinkedDeque<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    init {
        try {
            System.loadLibrary("horizon_android")
            nativeLoaded = true
            appendMemoryOnly("Native library libhorizon_android.so loaded (HorizonZ 1.8).")
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

    /**
     * Completely clears all in-memory logs, persistent log files across both processes,
     * and the Android kernel logcat buffer so old logs never reappear!
     */
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
        appendSystemLog("System, installer, and Minecraft runtime logs cleared (HorizonZ 1.8).", true, context)
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
        listOf("saves", "resourcepacks", "shaderpacks", "mods", "logs").forEach {
            File(dir, it).mkdirs()
        }
        return dir
    }

    // ========================================================================
    // PERSISTENT ACCOUNTS SYSTEM (Offline + Microsoft OAuth Device Code flow)
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

        // Pull latest in-game options.txt and horizon-keypad.json from profile_home so changes made inside Minecraft sync back to Launcher!
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

    /**
     * Synchronizes:
     * 1. `android-launcher.json` (with exact `catalog.rs` serde keys: `"open-gl"`, `"vulkan"`, `"vulkan-compatibility"`, etc.)
     * 2. `accounts.json` and `horizon-player.txt` (in both `home` and `profile_home`)
     * 3. `horizon-keypad.json` (`"enabled": config.keypadControls`, preserving any existing button layout!)
     * 4. Non-destructive key updates in `options.txt` (updating only the synced keys while preserving all in-game keybinds/audio/etc.!)
     */
    private fun syncNativeHorizonAccountAndCatalog(
        home: File,
        activeAccount: ZalithAccount,
        config: HorizonConfigState
    ) {
        val profileHome = getProfileHome(home, config.profile)

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
        listOf(home, profileHome).forEach { dir ->
            File(dir, "android-launcher.json").writeText(androidLauncherJson.toString(2))
        }

        // 2. Write `accounts.json` and `horizon-player.txt`
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

        // 3. Sync `horizon-keypad.json` so Keypad Controls toggle works even after first launch!
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

        // 4. Non-destructively update only the synced graphics keys in `options.txt` (preserving all other in-game settings!)
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
    // LOCAL MODS (.hmod), SHADERS, & RESOURCE PACKS TOGGLE SYNC (catalog.rs)
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
                    if (newState) set.remove(fileName) else set.add(fileName)
                    val outArr = JSONArray()
                    set.forEach { outArr.put(it) }
                    obj.put("disabled", outArr)
                    hmodFile.writeText(obj.toString(2))
                }
                "shaderpacks" -> {
                    val optFile = File(dir, "options.txt")
                    val lines = if (optFile.exists()) optFile.readLines().toMutableList() else mutableListOf()
                    val targetVal = if (newState) fileName else ""
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
                            if (newState) {
                                if ("\"vanilla\"" !in items) items.add(0, "\"vanilla\"")
                                if (entryToken !in items) items.add(entryToken)
                            } else {
                                items.remove(entryToken)
                            }
                            "resourcePacks:[${items.joinToString(",")}]"
                        } else line
                    }.toMutableList()
                    if (!found && newState) {
                        out.add("resourcePacks:[\"vanilla\",$entryToken]")
                    }
                    optFile.writeText(out.joinToString("\n") + "\n")
                }
            }
        }
        appendSystemLog("${if (newState) "Enabled" else "Disabled"} $folder item: $fileName", logEnabled, context)
        return newState
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
    // ASSET DOWNLOADER + BACKGROUND SERVICE + VERBOSE SPEED/URL LOGGER
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
        appendSystemLog("Starting Minecraft 26.3 official asset download & background notification service...", logEnabled, context)
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

    fun repairAndVerifyAssets(context: Context, logEnabled: Boolean = true) {
        val home = ensureHomeDirectories(context)
        File(home, ".local/horizon-client/26.3/android-downloads-v2.json").delete()
        appendSystemLog("Triggered full asset & touch control verification...", logEnabled, context)
        startMinecraft26Download(context, logEnabled)
    }

    fun pollInstallProgress(context: Context, logEnabled: Boolean = true): AssetInstallStatus {
        val home = ensureHomeDirectories(context)
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
                return AssetInstallStatus(
                    ready = obj.optBoolean("ready", false),
                    running = obj.optBoolean("running", false),
                    stage = formatStageLabel(rawStage),
                    done = obj.optLong("done", 0L),
                    total = obj.optLong("total", 100L),
                    speedText = obj.optString("speed", ""),
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
            else -> stage
        }
    }

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
        sb.appendLine("=== HorizonZ 1.8 System & Minecraft Runtime Log ===")
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

        // Only append logcat lines if not recently cleared
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

        // Write one-time launch token & remove any previous exit flag so HorizonActivity starts once and never loops!
        File(home, "zalith-game-exited.flag").delete()
        File(home, "zalith-launch-token.txt").writeText(System.currentTimeMillis().toString())

        appendSystemLog(
            "Launching Minecraft 26.3 (Player=${activeAccount.username}, Renderer=${config.renderer.serdeKey}, Scale=${config.renderScalePercent}%, Profile=${config.profile})",
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
