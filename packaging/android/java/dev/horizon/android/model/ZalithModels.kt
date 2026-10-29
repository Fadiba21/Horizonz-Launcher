package dev.horizon.android.model

enum class ZalithNavTab(val title: String, val subtitle: String) {
    DASHBOARD("Dashboard", "Play & Status"),
    ACCOUNTS("Accounts & Capes", "Profiles, Skins & Chroma"),
    ASSETS("26.3 Assets", "Horizon Prepare & Engine"),
    CONTENT("Files & Mods", "Worlds, Packs & Wasm Mods"),
    CONTROLS("Touch & HUD", "Bedrock Controls & Keymap"),
    SETTINGS("Settings", "Vulkan, Terrain & Audio")
}

enum class AccountType(val badge: String) {
    OFFLINE("Offline Local"),
    MICROSOFT("Microsoft OAuth (Online 26.3)"),
    ELY_BY("Ely.by / Authlib"),
    CUSTOM_SERVER("Custom Yggdrasil")
}

enum class ZalithThemePalette(val label: String, val primaryHex: Long, val secondaryHex: Long, val surfaceHex: Long) {
    EMBERMIRE("Zalith Embermire (ZL2+ Default)", 0xFFFF7043, 0xFFFFB74D, 0xFF141821),
    HORIZON_RUST("Horizon Rust Orange", 0xFFFF5722, 0xFF26A69A, 0xFF10141C),
    MATERIAL_OCEAN("Material You Ocean", 0xFF29B6F6, 0xFF80CBC4, 0xFF0F1722),
    AMOLED_EMERALD("Redstone Melter Emerald", 0xFF66BB6A, 0xFFA5D6A7, 0xFF090C10)
}

data class ZalithAccount(
    val id: String,
    val username: String,
    val uuid: String,
    val accountType: AccountType,
    val chromaNameEnabled: Boolean = false,
    val slimSkinModel: Boolean = false,
    val selectedCapeName: String = "Horizon Founder Cape",
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
    // Renderer & Display
    val wgpuBackend: String = "Vulkan",
    val graphicsPreset: String = "Fancy",
    val resolutionScalePercent: Int = 70, // ZL2+ 1.2 default 70%
    val renderDistanceChunks: Int = 16,
    val simulationDistanceChunks: Int = 10,
    val distantTerrainChunks: Int = 256, // Up to 1024 chunks in Horizon
    val maxFps: Int = 120,
    val enableVsync: Boolean = true,
    val enableHdr: Boolean = false,
    val fov: Float = 90f,
    val guiScale: Int = 3,
    val particlesPreset: String = "all",
    val highContrastBlockOutline: Boolean = true,
    val themePalette: ZalithThemePalette = ZalithThemePalette.EMBERMIRE,

    // Touch & Input (Vasysik/horizon-android + ZL2+)
    val splitTouchControls: Boolean = true,
    val hideControllerWhileLoading: Boolean = true, // ZL2+ 1.2 feature
    val autoJump: Boolean = true,
    val toggleSprint: Boolean = true,
    val toggleSneak: Boolean = false,
    val dpadScale: Float = 1.0f,
    val dpadOpacity: Float = 0.78f,
    val hotbarScale: Float = 1.0f,

    // Audio & Screen Recorder (ZL2+ 1.3 + Horizon Audio)
    val masterVolume: Float = 1.0f,
    val musicVolume: Float = 0.5f,
    val closedCaptions: Boolean = true, // Horizon 0.2.93 fix
    val disableAudioSafeMode: Boolean = false, // ALSOFT_DISABLE_EVENTS / HORIZON_NO_AUDIO
    val enableScreenRecorder: Boolean = false,
    val requestMicOnFirstLaunch: Boolean = true,

    // Horizon Extras & Engine
    val publicOnDeviceAssetDownload: Boolean = true, // --public / HORIZON_ANDROID_PUBLIC=1
    val enableZoom: Boolean = true,
    val enableMinimap: Boolean = true,
    val discordRichPresence: Boolean = true,
    val integratedServerThread: Boolean = true
)
