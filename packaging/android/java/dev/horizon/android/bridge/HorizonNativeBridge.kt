package dev.horizon.android.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.horizon.android.HorizonActivity
import dev.horizon.android.model.HorizonConfigState
import dev.horizon.android.model.ZalithAccount
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

data class AssetInstallStatus(
    val ready: Boolean = false,
    val running: Boolean = false,
    val stage: String = "Idle",
    val done: Long = 0L,
    val total: Long = 100L,
    val error: String = ""
) {
    val progressFraction: Float
        get() = if (total > 0L) (done.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = (progressFraction * 100f).toInt().coerceIn(0, 100)
}

object HorizonNativeBridge {
    private const val TAG = "HorizonNativeBridge"
    private var nativeLoaded = false

    init {
        try {
            System.loadLibrary("horizon_android")
            nativeLoaded = true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "libhorizon_android.so not yet loaded: ${e.message}")
        }
    }

    @JvmStatic
    external fun nativeIsGameInstalled(home: String): Boolean

    @JvmStatic
    external fun nativeStartOfficialInstall(home: String): Boolean

    @JvmStatic
    external fun nativeGetInstallProgress(home: String): String

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

        // Extract engine-data.zip from APK assets to filesDir/engine-data.zip if missing
        val engineZip = File(home, "engine-data.zip")
        if (!engineZip.exists() || engineZip.length() < 1024L) {
            try {
                context.assets.open("engine-data.zip").use { input ->
                    FileOutputStream(engineZip).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (ignored: Exception) {
            }
        }

        val controlsFile = File(home, "bedrock-controls.json")
        if (!controlsFile.exists()) {
            try {
                context.assets.open("bedrock-controls.json").use { input ->
                    FileOutputStream(controlsFile).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (ignored: Exception) {
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

    fun startMinecraft26Download(context: Context): Boolean {
        val home = ensureHomeDirectories(context)
        if (nativeLoaded) {
            try {
                return nativeStartOfficialInstall(home.absolutePath)
            } catch (e: Throwable) {
                Log.e(TAG, "nativeStartOfficialInstall failed: ${e.message}")
            }
        }
        return false
    }

    fun pollInstallProgress(context: Context): AssetInstallStatus {
        val home = ensureHomeDirectories(context)
        if (nativeLoaded) {
            try {
                val raw = nativeGetInstallProgress(home.absolutePath)
                if (raw.isNotEmpty()) {
                    val obj = JSONObject(raw)
                    return AssetInstallStatus(
                        ready = obj.optBoolean("ready", false),
                        running = obj.optBoolean("running", false),
                        stage = obj.optString("stage", "Idle"),
                        done = obj.optLong("done", 0L),
                        total = obj.optLong("total", 100L),
                        error = obj.optString("error", "")
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
                    stage = obj.optString("stage", "Idle"),
                    done = obj.optLong("done", 0L),
                    total = obj.optLong("total", 100L),
                    error = obj.optString("error", "")
                )
            } catch (ignored: Exception) {
            }
        }
        val ready = isMinecraft26Ready(context)
        return AssetInstallStatus(
            ready = ready,
            running = false,
            stage = if (ready) "Ready" else "Not Installed",
            done = if (ready) 100L else 0L,
            total = 100L
        )
    }

    fun writeEngineConfigs(
        context: Context,
        config: HorizonConfigState,
        activeAccount: ZalithAccount
    ) {
        val home = ensureHomeDirectories(context)

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
            appendLine("# Generated by HorizonZ Launcher")
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

    fun importUriToDestination(context: Context, uri: Uri, subFolder: String, targetFileName: String): Boolean {
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
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to import URI $uri", e)
            false
        }
    }

    fun readLatestLogs(context: Context): String {
        val home = ensureHomeDirectories(context)
        val latestLog = File(home, "logs/latest.log")
        val androidErr = File(home, "android-error.txt")
        val sb = StringBuilder()
        if (androidErr.exists()) {
            sb.appendLine("[android-error.txt]")
            sb.appendLine(androidErr.readText())
        }
        if (latestLog.exists()) {
            sb.appendLine("[logs/latest.log]")
            sb.appendLine(latestLog.readText())
        }
        if (sb.isEmpty()) {
            sb.appendLine("Horizon 26.3 Rust Engine Ready.")
            sb.appendLine("Game Installed: ${isMinecraft26Ready(context)}")
        }
        return sb.toString()
    }

    fun launchNativeGame(
        context: Context,
        config: HorizonConfigState,
        activeAccount: ZalithAccount
    ) {
        writeEngineConfigs(context, config, activeAccount)
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
