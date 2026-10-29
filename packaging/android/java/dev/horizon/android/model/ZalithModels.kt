package dev.horizon.android.model

enum class ZalithNavTab(val title: String) {
    HOME("Dashboard"),
    VERSIONS("Game & Assets"),
    FILES("Files & Mods"),
    SETTINGS("Settings")
}

enum class AccountType(val badge: String) {
    OFFLINE("Offline"),
    MICROSOFT("Microsoft")
}

enum class ZalithThemePalette(val label: String, val primaryHex: Long, val secondaryHex: Long, val surfaceHex: Long) {
    MATERIAL_YOU("Material You (System)", 0xFF64B5F6, 0xFF81C784, 0xFF10131A),
    EMBERMIRE("Embermire", 0xFFFF7043, 0xFFFFB74D, 0xFF12151E),
    HORIZON_RUST("Horizon Rust", 0xFFFF5722, 0xFF26A69A, 0xFF0E1218),
    AMOLED_EMERALD("Emerald Dark", 0xFF66BB6A, 0xFFA5D6A7, 0xFF090C10)
}

data class ZalithAccount(
    val id: String,
    val username: String,
    val uuid: String,
    val accountType: AccountType,
    val isActive: Boolean = false
)

data class HorizonConfigState(
    val wgpuBackend: String = "Vulkan",
    val graphicsPreset: String = "Fancy",
    val resolutionScalePercent: Int = 75,
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
    val themePalette: ZalithThemePalette = ZalithThemePalette.MATERIAL_YOU,
    val useDynamicColor: Boolean = true, // Default Material 3 (You) Android
    val enableSystemLog: Boolean = true, // Lightweight System & Game Log toggle
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
    val publicOnDeviceAssetDownload: Boolean = true,
    val enableZoom: Boolean = true,
    val enableMinimap: Boolean = true,
    val discordRichPresence: Boolean = true,
    val integratedServerThread: Boolean = true
)

val MINECRAFT_SPLASH_QUOTES = listOf(
    "Never dig straight down!",
    "Punching trees since day one.",
    "Creeper? Aww man...",
    "100% Native Rust — Zero JVM lag!",
    "Watch out for Wardens in the Deep Dark.",
    "Always carry a water bucket in the Nether... wait, don't!",
    "Diamonds are best found deep in Deepslate.",
    "Redstone engineers build computers inside blocks.",
    "Don't look an Enderman in the eyes!",
    "A stack of 64 torches keeps the phantoms away.",
    "Villager trading halls: 1 Emerald for Mending!",
    "Sleeping in the Nether is an explosive idea.",
    "Enchanting Table speaks Standard Galactic Alphabet.",
    "Elytra + Firework Rockets = Infinite Skies.",
    "Beware of Trapped Chests in Desert Temples!",
    "Craft a Shield before your first skeleton duel.",
    "Silk Touch saves the bee nest with all its bees.",
    "Fortune III turns one diamond ore into four!",
    "Netherite floats in lava — your items are safe.",
    "Copper Golems and Trial Chambers await!",
    "Build a portal with 10 Obsidian blocks.",
    "Golden Carrots give the best saturation in the game.",
    "Crouch near Sculk Sensors to walk silently.",
    "Boats on Blue Ice travel faster than minecarts!",
    "Feed a dolphin raw cod to find sunken treasure.",
    "Name a sheep 'jeb_' for a rainbow wool coat!",
    "Name any mob 'Dinnerbone' to flip it upside down.",
    "Cats scare Creepers away from your front door.",
    "Snow Golems leave a trail of snow in cool biomes.",
    "Axolotls grant Regeneration when you help them fight.",
    "Totem of Undying saves you from fatal falls.",
    "Soul Speed III makes Soul Sand valleys a breeze.",
    "Bartering Gold Ingots with Piglins for Ender Pearls.",
    "Conduits give Water Breathing and Night Vision underwater.",
    "Every world seed holds 60 million blocks of adventure.",
    "Minecraft 26.3 running natively in Rust on Android!"
)
