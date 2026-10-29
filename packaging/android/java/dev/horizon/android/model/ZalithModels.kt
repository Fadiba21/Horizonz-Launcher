package dev.horizon.android.model

enum class ZalithNavTab(val title: String) {
    HOME("Home"),
    VERSIONS("Versions"),
    FILES("Files"),
    SETTINGS("Settings")
}

enum class AccountType(val badge: String) {
    OFFLINE("Offline"),
    MICROSOFT("Microsoft"),
    ELY_BY("Ely.by"),
    CUSTOM_SERVER("Custom")
}

enum class ZalithThemePalette(val label: String, val primaryHex: Long, val secondaryHex: Long, val surfaceHex: Long) {
    EMBERMIRE("Embermire", 0xFFFF7043, 0xFFFFB74D, 0xFF12151E),
    HORIZON_RUST("Horizon Rust", 0xFFFF5722, 0xFF26A69A, 0xFF0E1218),
    MATERIAL_OCEAN("Ocean Blue", 0xFF29B6F6, 0xFF80CBC4, 0xFF0E1520),
    AMOLED_EMERALD("Emerald", 0xFF66BB6A, 0xFFA5D6A7, 0xFF090C10)
}

data class ZalithAccount(
    val id: String,
    val username: String,
    val uuid: String,
    val accountType: AccountType,
    val chromaNameEnabled: Boolean = false,
    val slimSkinModel: Boolean = false,
    val selectedCapeName: String = "Horizon Cape",
    val isActive: Boolean = false
)

data class CapeItem(
    val id: String,
    val name: String,
    val source: String,
    val accentColorHex: Long,
    val isFavorite: Boolean = false
)

data class HorizonConfigState(
    val wgpuBackend: String = "Vulkan",
    val graphicsPreset: String = "Fancy",
    val resolutionScalePercent: Int = 70,
    val renderDistanceChunks: Int = 16,
    val simulationDistanceChunks: Int = 10,
    val distantTerrainChunks: Int = 128,
    val maxFps: Int = 120,
    val enableVsync: Boolean = true,
    val enableHdr: Boolean = false,
    val fov: Float = 90f,
    val guiScale: Int = 3,
    val particlesPreset: String = "all",
    val highContrastBlockOutline: Boolean = true,
    val themePalette: ZalithThemePalette = ZalithThemePalette.EMBERMIRE,
    val splitTouchControls: Boolean = true,
    val hideControllerWhileLoading: Boolean = true,
    val autoJump: Boolean = true,
    val toggleSprint: Boolean = true,
    val toggleSneak: Boolean = false,
    val dpadScale: Float = 1.0f,
    val dpadOpacity: Float = 0.78f,
    val hotbarScale: Float = 1.0f,
    val masterVolume: Float = 1.0f,
    val musicVolume: Float = 0.5f,
    val closedCaptions: Boolean = true,
    val disableAudioSafeMode: Boolean = false,
    val enableScreenRecorder: Boolean = false,
    val requestMicOnFirstLaunch: Boolean = true,
    val publicOnDeviceAssetDownload: Boolean = true,
    val enableZoom: Boolean = true,
    val enableMinimap: Boolean = true,
    val discordRichPresence: Boolean = true,
    val integratedServerThread: Boolean = true
)
