@file:OptIn(ExperimentalMaterial3Api::class)

package dev.horizon.android.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.bridge.HorizonNativeBridge
import dev.horizon.android.model.*
import dev.horizon.android.ui.theme.HorizonZalithTheme
import java.io.File
import java.util.UUID

class ZalithMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HorizonNativeBridge.ensureHomeDirectories(this)

        setContent {
            var config by remember { mutableStateOf(HorizonConfigState()) }
            var useDynamicColor by remember { mutableStateOf(false) }

            HorizonZalithTheme(
                palette = config.themePalette,
                useDynamicColor = useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZalithCleanLauncherScreen(
                        config = config,
                        onConfigChange = { config = it },
                        useDynamicColor = useDynamicColor,
                        onToggleDynamicColor = { useDynamicColor = it }
                    )
                }
            }
        }
    }
}

@Composable
fun ZalithCleanLauncherScreen(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ZalithNavTab.HOME) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }

    var accounts by remember {
        mutableStateOf(
            listOf(
                ZalithAccount(
                    id = "1",
                    username = "Steve",
                    uuid = "8667ba71-b85a-4004-af54-457a9734eed7",
                    accountType = AccountType.OFFLINE,
                    chromaNameEnabled = false,
                    slimSkinModel = false,
                    selectedCapeName = "Horizon Cape",
                    isActive = true
                ),
                ZalithAccount(
                    id = "2",
                    username = "Alex",
                    uuid = "ec561538-f3fd-461d-aff5-086b22154bce",
                    accountType = AccountType.MICROSOFT,
                    chromaNameEnabled = true,
                    slimSkinModel = true,
                    selectedCapeName = "Migrator",
                    isActive = false
                )
            )
        )
    }
    val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.first()

    val assetImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            HorizonNativeBridge.importUriToDestination(context, uri, "", "engine-data.zip")
            Toast.makeText(context, "Assets imported", Toast.LENGTH_SHORT).show()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Minimalist Zalith TopBar (48.dp)
            ZalithMinimalTopBar(
                onOpenLogs = { showLogDialog = true },
                onCycleTheme = {
                    val list = ZalithThemePalette.entries
                    val next = list[(config.themePalette.ordinal + 1) % list.size]
                    onConfigChange(config.copy(themePalette = next))
                }
            )

            // 2. Main 3-Zone Zalith Layout: Left Icon Rail + Clean Center + Right Launch Sidebar
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Left Icon-Only Navigation Rail (56.dp)
                ZalithIconRail(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it }
                )

                // Center Content Viewport
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    transitionSpec = {
                        fadeIn(tween(180)).togetherWith(fadeOut(tween(140)))
                    },
                    label = "CenterTab"
                ) { tab ->
                    when (tab) {
                        ZalithNavTab.HOME -> CleanHomeViewport(
                            config = config,
                            onNavigate = { selectedTab = it }
                        )
                        ZalithNavTab.VERSIONS -> CleanVersionsViewport(
                            config = config,
                            onConfigChange = onConfigChange,
                            onImportAssets = { assetImporter.launch("*/*") }
                        )
                        ZalithNavTab.FILES -> CleanFilesViewport()
                        ZalithNavTab.SETTINGS -> CleanSettingsViewport(
                            config = config,
                            onConfigChange = onConfigChange,
                            useDynamicColor = useDynamicColor,
                            onToggleDynamicColor = onToggleDynamicColor
                        )
                    }
                }

                // Right Floating Launch Sidebar (196.dp)
                ZalithLaunchSidebar(
                    activeAccount = activeAccount,
                    config = config,
                    onOpenAccountDialog = { showAccountDialog = true },
                    onOpenVersions = { selectedTab = ZalithNavTab.VERSIONS },
                    onLaunchGame = {
                        HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
                    }
                )
            }
        }
    }

    if (showAccountDialog) {
        AccountManagerModal(
            accounts = accounts,
            onSelectAccount = { id ->
                accounts = accounts.map { it.copy(isActive = it.id == id) }
            },
            onAddAccount = { name, type, chroma ->
                val added = ZalithAccount(
                    id = UUID.randomUUID().toString(),
                    username = name,
                    uuid = UUID.randomUUID().toString(),
                    accountType = type,
                    chromaNameEnabled = chroma,
                    isActive = true
                )
                accounts = accounts.map { it.copy(isActive = false) } + added
            },
            onDismiss = { showAccountDialog = false }
        )
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Runtime Log", style = MaterialTheme.typography.titleMedium) },
            text = {
                val logText = remember { HorizonNativeBridge.readLatestLogs(context) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0A0D12))
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = logText,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = Color(0xFFA5D6A7)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun ZalithMinimalTopBar(
    onOpenLogs: () -> Unit,
    onCycleTheme: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "HorizonZ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "26.3 Rust",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpenLogs, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = "Log",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onCycleTheme, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Default.Palette,
                    contentDescription = "Theme",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ZalithIconRail(
    selectedTab: ZalithNavTab,
    onSelectTab: (ZalithNavTab) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxHeight()
            .width(56.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically)
        ) {
            ZalithNavTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val icon = when (tab) {
                    ZalithNavTab.HOME -> Icons.Default.Home
                    ZalithNavTab.VERSIONS -> Icons.Default.Layers
                    ZalithNavTab.FILES -> Icons.Default.Folder
                    ZalithNavTab.SETTINGS -> Icons.Default.Settings
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onSelectTab(tab) }
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tab.title,
                        tint = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanHomeViewport(
    config: HorizonConfigState,
    onNavigate: (ZalithNavTab) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
        ) {
            // Minimal center hero badge
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "NATIVE RUST ENGINE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                Text(
                    text = "Minecraft 26.3",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "${config.wgpuBackend} • ${config.resolutionScalePercent}% Scale • ${config.renderDistanceChunks} Chunks",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Bottom quick action chips (clean, 1-word labels)
            Row(
                modifier = Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                QuickGlassPill(Icons.Default.Public, "Worlds") { onNavigate(ZalithNavTab.FILES) }
                QuickGlassPill(Icons.Default.Extension, "Mods") { onNavigate(ZalithNavTab.FILES) }
                QuickGlassPill(Icons.Default.Inventory2, "Assets") { onNavigate(ZalithNavTab.VERSIONS) }
                QuickGlassPill(Icons.Default.Tune, "Config") { onNavigate(ZalithNavTab.SETTINGS) }
            }
        }
    }
}

@Composable
private fun QuickGlassPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun CleanVersionsViewport(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    onImportAssets: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Instances & Assets", style = MaterialTheme.typography.titleMedium)
                FilledTonalButton(onClick = onImportAssets, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) {
                    Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import Assets", fontSize = 12.sp)
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Column {
                            Text("Minecraft 26.3 (Horizon Native)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                            Text("libhorizon_android.so • Direct Boot", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    RadioButton(selected = true, onClick = {})
                }
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Auto-Prepare Assets on Device", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Switch(
                        checked = config.publicOnDeviceAssetDownload,
                        onCheckedChange = { onConfigChange(config.copy(publicOnDeviceAssetDownload = it)) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanFilesViewport() {
    val context = LocalContext.current
    val home = remember { HorizonNativeBridge.ensureHomeDirectories(context) }
    var currentFolder by remember { mutableStateOf("saves") }
    var refreshCount by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        "saves" to "Worlds",
        "resourcepacks" to "Packs",
        "mods" to "Mods",
        "shaderpacks" to "Shaders"
    )

    val dirFile = File(home, currentFolder).apply { mkdirs() }
    val itemsList = remember(currentFolder, refreshCount) {
        dirFile.listFiles()?.toList().orEmpty()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            HorizonNativeBridge.importUriToDestination(context, uri, currentFolder, "item_${System.currentTimeMillis()}.zip")
            refreshCount += 1
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tabs.forEach { (folder, label) ->
                        FilterChip(
                            selected = currentFolder == folder,
                            onClick = { currentFolder = folder },
                            label = { Text(label, fontSize = 12.sp) }
                        )
                    }
                }
                FilledTonalButton(
                    onClick = { picker.launch("*/*") },
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import", fontSize = 12.sp)
                }
            }

            if (itemsList.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Empty folder", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(itemsList) { file ->
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(file.name, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                IconButton(
                                    onClick = {
                                        file.deleteRecursively()
                                        refreshCount += 1
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete", modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CleanSettingsViewport(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Row 1: Renderer & Graphics Preset
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Renderer", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    listOf("Vulkan", "GLES3").forEach { item ->
                        FilterChip(
                            selected = config.wgpuBackend == item,
                            onClick = { onConfigChange(config.copy(wgpuBackend = item)) },
                            label = { Text(item, fontSize = 11.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Preset", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    listOf("Fast", "Fancy", "Ultra").forEach { preset ->
                        FilterChip(
                            selected = config.graphicsPreset == preset,
                            onClick = { onConfigChange(config.copy(graphicsPreset = preset)) },
                            label = { Text(preset, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Compact Sliders
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Resolution: ${config.resolutionScalePercent}%", fontSize = 12.sp)
                    Slider(
                        value = config.resolutionScalePercent.toFloat(),
                        onValueChange = { onConfigChange(config.copy(resolutionScalePercent = it.toInt())) },
                        valueRange = 40f..100f
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Render Distance: ${config.renderDistanceChunks}", fontSize = 12.sp)
                    Slider(
                        value = config.renderDistanceChunks.toFloat(),
                        onValueChange = { onConfigChange(config.copy(renderDistanceChunks = it.toInt())) },
                        valueRange = 4f..32f
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Distant Terrain: ${config.distantTerrainChunks}", fontSize = 12.sp)
                    Slider(
                        value = config.distantTerrainChunks.toFloat(),
                        onValueChange = { onConfigChange(config.copy(distantTerrainChunks = it.toInt())) },
                        valueRange = 16f..1024f
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Button Size: ${(config.dpadScale * 100).toInt()}%", fontSize = 12.sp)
                    Slider(
                        value = config.dpadScale,
                        onValueChange = { onConfigChange(config.copy(dpadScale = it)) },
                        valueRange = 0.5f..1.5f
                    )
                }
            }

            // Clean Toggle Chips
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = config.splitTouchControls,
                    onClick = { onConfigChange(config.copy(splitTouchControls = !config.splitTouchControls)) },
                    label = { Text("Split Touch", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = config.autoJump,
                    onClick = { onConfigChange(config.copy(autoJump = !config.autoJump)) },
                    label = { Text("Auto Jump", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = config.enableMinimap,
                    onClick = { onConfigChange(config.copy(enableMinimap = !config.enableMinimap)) },
                    label = { Text("Minimap", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = useDynamicColor,
                    onClick = { onToggleDynamicColor(!useDynamicColor) },
                    label = { Text("Material You", fontSize = 11.sp) }
                )
            }
        }
    }
}

@Composable
private fun ZalithLaunchSidebar(
    activeAccount: ZalithAccount,
    config: HorizonConfigState,
    onOpenAccountDialog: () -> Unit,
    onOpenVersions: () -> Unit,
    onLaunchGame: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxHeight()
            .width(196.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top: Account Profile Selector Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenAccountDialog() }
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeAccount.username,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeAccount.accountType.badge,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Bottom: Version Card + Prominent PLAY Button
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenVersions() }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text("26.3 Rust", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(config.wgpuBackend, fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                        }
                        Icon(
                            Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Button(
                    onClick = onLaunchGame,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "PLAY",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountManagerModal(
    accounts: List<ZalithAccount>,
    onSelectAccount: (String) -> Unit,
    onAddAccount: (String, AccountType, Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(AccountType.OFFLINE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accounts", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                accounts.forEach { acc ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (acc.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAccount(acc.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${acc.username} (${acc.accountType.badge})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            RadioButton(selected = acc.isActive, onClick = { onSelectAccount(acc.id) })
                        }
                    }
                }

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(AccountType.OFFLINE, AccountType.MICROSOFT).forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.badge, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onAddAccount(newName.trim(), selectedType, false)
                        newName = ""
                    }
                    onDismiss()
                }
            ) {
                Text("Done")
            }
        }
    )
}
