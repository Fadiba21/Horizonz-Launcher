package dev.horizon.android.model

enum class ZalithNavTab(val title: String) {
    HOME("Dashboard"),
    VERSIONS("Game & Assets"),
    STORE("Store"),
    FILES("Files & Mods"),
    SETTINGS("Settings")
}

enum class StoreCategory(val label: String, val subtitle: String) {
    HORIZON_MODS("Mods (playhorizon.dev)", "Official Horizon .hmod & Native Mods"),
    MODRINTH_PACKS("Resource Packs (Modrinth)", "Community Resource Packs & Textures")
}

data class StoreCatalogItem(
    val id: String,
    val title: String,
    val summary: String,
    val author: String,
    val version: String,
    val supportedMcVersions: List<String>,
    val downloadUrl: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String = "",
    val category: StoreCategory,
    val pageUrl: String = ""
) {
    val isCompatibleWith26_3: Boolean
        get() = supportedMcVersions.any {
            it.contains("26.3") || it.contains("26.") || it.equals("all", ignoreCase = true)
        }

    val versionBadge: String
        get() = if (supportedMcVersions.isEmpty()) "Unknown" else supportedMcVersions.first()
}

data class GithubReleaseUpdateInfo(
    val latestVersion: String,
    val releaseTitle: String,
    val downloadUrl: String,
    val htmlUrl: String
)

enum class AccountType(val badge: String) {
    OFFLINE("Offline"),
    MICROSOFT("Microsoft")
}

/**
 * Matches `crates/horizon-client/src/android/catalog.rs` `RendererMode` (`#[serde(rename_all = "kebab-case")]`):
 * - VulkanCompatibility -> "vulkan-compatibility"
 * - Vulkan -> "vulkan"
 * - OpenGl -> "open-gl"
 */
enum class HorizonRendererMode(val serdeKey: String, val label: String) {
    VULKAN_COMPAT("vulkan-compatibility", "Vulkan Compatibility (Default)"),
    VULKAN("vulkan", "Vulkan (High Performance)"),
    OPENGL("open-gl", "OpenGL ES (Fallback)")
}

enum class HorizonInputHost(val serdeKey: String, val label: String) {
    AUTO("auto", "Auto Detect"),
    TOUCH("touch", "Touch Screen"),
    KEYBOARD_MOUSE("keyboard-mouse", "Keyboard & Mouse"),
    CONTROLLER("controller", "Gamepad / Controller")
}

enum class HorizonOrientation(val serdeKey: String, val label: String) {
    LANDSCAPE("landscape", "Landscape"),
    REVERSE_LANDSCAPE("reverse-landscape", "Reverse Landscape (Flipped)"),
    PORTRAIT("portrait", "Portrait"),
    REVERSE_PORTRAIT("reverse-portrait", "Reverse Portrait"),
    AUTO("auto", "Auto Sensor")
}

enum class HorizonContentOrigin(val serdeKey: String, val label: String) {
    HORIZON("horizon", "Horizon (playhorizon.dev)"),
    MODRINTH("modrinth", "Modrinth")
}

enum class ZalithThemePalette(val label: String, val primaryHex: Long, val secondaryHex: Long, val surfaceHex: Long) {
    MATERIAL_YOU("Material You (Dynamic)", 0xFF64B5F6, 0xFF81C784, 0xFF10131A),
    EMBERMIRE("Embermire Dark", 0xFFFF7043, 0xFFFFB74D, 0xFF12151E),
    HORIZON_RUST("Horizon Rust", 0xFFFF5722, 0xFF26A69A, 0xFF0E1218),
    AMOLED_EMERALD("Emerald AMOLED", 0xFF66BB6A, 0xFFA5D6A7, 0xFF090C10)
}

data class ZalithAccount(
    val id: String,
    val username: String,
    val uuid: String,
    val accountType: AccountType,
    val isActive: Boolean = false
)

data class HorizonConfigState(
    val profile: Int = 0,
    val renderer: HorizonRendererMode = HorizonRendererMode.VULKAN_COMPAT,
    val renderScalePercent: Int = 75,
    val orientation: HorizonOrientation = HorizonOrientation.LANDSCAPE,
    val inputHost: HorizonInputHost = HorizonInputHost.AUTO,
    val keypadControls: Boolean = false,
    val recordPerformance: Boolean = false,
    val modSource: HorizonContentOrigin = HorizonContentOrigin.HORIZON,
    val shaderSource: HorizonContentOrigin = HorizonContentOrigin.HORIZON,
    val defaultServer: String = "",
    val proxy: String = "",
    val mcRenderDistance: Int = 8,
    val mcSimulationDistance: Int = 8,
    val mcMaxFps: Int = 120,
    val mcGuiScale: Int = 0,
    val mcFov: Int = 70,
    val mcVsync: Boolean = true,
    val mcFullscreen: Boolean = true,
    val themePalette: ZalithThemePalette = ZalithThemePalette.MATERIAL_YOU,
    val useDynamicColor: Boolean = true,
    val enableSystemLog: Boolean = true,
    val disableAudioSafeMode: Boolean = false,
    val discordRichPresence: Boolean = true,
    val publicOnDeviceAssetDownload: Boolean = true
)

val MINECRAFT_SPLASH_QUOTES = listOf(
    "Never dig straight down!",
    "Punching trees since day one.",
    "Creeper? Aww man...",
    "100% Native Rust — Zero JVM lag!",
    "Watch out for Wardens in the Deep Dark.",
    "Always carry a water bucket in the Overworld!",
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
