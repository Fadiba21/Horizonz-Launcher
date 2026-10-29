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
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.bridge.HorizonNativeBridge
import dev.horizon.android.model.*
import dev.horizon.android.ui.theme.HorizonZalithTheme
import kotlinx.coroutines.delay
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
                    HorizonZalithAppScreen(
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
fun HorizonZalithAppScreen(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ZalithNavTab.DASHBOARD) }
    var sidebarExpanded by remember { mutableStateOf(true) }
    var showLogDialog by remember { mutableStateOf(false) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(isRecording) {
        while (isRecording) {
            delay(1000L)
            recordingSeconds += 1
        }
    }

    var accounts by remember {
        mutableStateOf(
            listOf(
                ZalithAccount(
                    id = "acc_1",
                    username = "SteveRust26",
                    uuid = "8667ba71-b85a-4004-af54-457a9734eed7",
                    accountType = AccountType.OFFLINE,
                    chromaNameEnabled = true,
                    slimSkinModel = false,
                    selectedCapeName = "Horizon 26.3 Rust Cape",
                    isActive = true
                ),
                ZalithAccount(
                    id = "acc_2",
                    username = "Alex_MSA",
                    uuid = "ec561538-f3fd-461d-aff5-086b22154bce",
                    accountType = AccountType.MICROSOFT,
                    chromaNameEnabled = false,
                    slimSkinModel = true,
                    selectedCapeName = "Migrator Cape",
                    isActive = false
                )
            )
        )
    }
    val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.first()

    var capes by remember {
        mutableStateOf(
            listOf(
                CapeItem("c1", "Horizon 26.3 Rust Cape", "Built-in Horizon", 0xFFFF7043, true),
                CapeItem("c2", "Redstone Melter 1.2 Cape", "ZalithLauncher2Plus Gallery", 0xFFE53935, true),
                CapeItem("c3", "Migrator Cape", "minecraftcapes.net", 0xFFAB47BC, false),
                CapeItem("c4", "Vanilla 15th Anniversary", "minecraftcapes.net", 0xFF26A69A, false),
                CapeItem("c5", "No Cape", "Default", 0xFF546E7A, false)
            )
        )
    }

    val serverJarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val ok = HorizonNativeBridge.importUriToDestination(context, uri, ".local/horizon-client/26.3", "server-26.3.jar")
            Toast.makeText(
                context,
                if (ok) "Imported Minecraft 26.3 server.jar for Horizon Prepare!" else "Failed to import server.jar",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val engineZipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val ok = HorizonNativeBridge.importUriToDestination(context, uri, "", "engine-data.zip")
            Toast.makeText(
                context,
                if (ok) "Imported engine-data.zip into Horizon home!" else "Failed to import engine-data.zip",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val capeGalleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val customName = "Custom_Cape_${capes.size + 1}.png"
            val ok = HorizonNativeBridge.importUriToDestination(context, uri, "capes", customName)
            if (ok) {
                capes = capes + CapeItem(
                    id = UUID.randomUUID().toString(),
                    name = customName.removeSuffix(".png"),
                    source = "Uploaded from Gallery",
                    accentColorHex = 0xFF42A5F5,
                    isFavorite = true
                )
                Toast.makeText(context, "Cape uploaded to Wardrobe!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ZalithTopAppBar(
            activeAccount = activeAccount,
            sidebarExpanded = sidebarExpanded,
            onToggleSidebar = { sidebarExpanded = !sidebarExpanded },
            isRecording = isRecording,
            recordingSeconds = recordingSeconds,
            onToggleRecorder = {
                isRecording = !isRecording
                if (!isRecording) recordingSeconds = 0
                Toast.makeText(
                    context,
                    if (isRecording) "Game Screen Recorder armed (PLAYBACK_CAPTURE + Video fallback)" else "Screen Recorder stopped",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onOpenFileManager = { selectedTab = ZalithNavTab.CONTENT },
            onOpenLogs = { showLogDialog = true },
            currentPalette = config.themePalette,
            onCyclePalette = {
                val values = ZalithThemePalette.entries
                val next = values[(config.themePalette.ordinal + 1) % values.size]
                onConfigChange(config.copy(themePalette = next))
            }
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            ZalithSidebarRail(
                selectedTab = selectedTab,
                expanded = sidebarExpanded,
                onSelectTab = { selectedTab = it }
            )

            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(12.dp),
                transitionSpec = {
                    (slideInHorizontally(animationSpec = tween(220)) { width -> width / 6 } + fadeIn(tween(220)))
                        .togetherWith(fadeOut(tween(150)))
                },
                label = "ZalithSliceInTransition"
            ) { tab ->
                when (tab) {
                    ZalithNavTab.DASHBOARD -> DashboardScreen(
                        config = config,
                        activeAccount = activeAccount,
                        onNavigate = { selectedTab = it },
                        onOpenLogs = { showLogDialog = true }
                    )
                    ZalithNavTab.ACCOUNTS -> AccountsWardrobeScreen(
                        accounts = accounts,
                        capes = capes,
                        onSelectAccount = { id ->
                            accounts = accounts.map { it.copy(isActive = it.id == id) }
                        },
                        onAddAccount = { name, type, chroma, slim ->
                            val newAcc = ZalithAccount(
                                id = UUID.randomUUID().toString(),
                                username = name,
                                uuid = UUID.randomUUID().toString(),
                                accountType = type,
                                chromaNameEnabled = chroma,
                                slimSkinModel = slim,
                                selectedCapeName = activeAccount.selectedCapeName,
                                isActive = true
                            )
                            accounts = accounts.map { it.copy(isActive = false) } + newAcc
                        },
                        onSelectCape = { capeName ->
                            accounts = accounts.map {
                                if (it.isActive) it.copy(selectedCapeName = capeName) else it
                            }
                        },
                        onUploadCapeFromGallery = { capeGalleryPicker.launch("image/*") }
                    )
                    ZalithNavTab.ASSETS -> VersionAssetsScreen(
                        config = config,
                        onConfigChange = onConfigChange,
                        onPickServerJar = { serverJarPicker.launch("*/*") },
                        onPickEngineZip = { engineZipPicker.launch("*/*") }
                    )
                    ZalithNavTab.CONTENT -> ContentFileManagerScreen()
                    ZalithNavTab.CONTROLS -> ControlMapEditorScreen(
                        config = config,
                        onConfigChange = onConfigChange
                    )
                    ZalithNavTab.SETTINGS -> ZalithSettingsScreen(
                        config = config,
                        onConfigChange = onConfigChange,
                        useDynamicColor = useDynamicColor,
                        onToggleDynamicColor = onToggleDynamicColor
                    )
                }
            }

            ZalithRightLaunchDock(
                config = config,
                activeAccount = activeAccount,
                onLaunchGame = {
                    HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
                }
            )
        }
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("Latest Log & Native Diagnostics") },
            text = {
                val logText = remember { HorizonNativeBridge.readLatestLogs(context) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0B0E14))
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
                TextButton(onClick = { showLogDialog = false }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
private fun ZalithTopAppBar(
    activeAccount: ZalithAccount,
    sidebarExpanded: Boolean,
    onToggleSidebar: () -> Unit,
    isRecording: Boolean,
    recordingSeconds: Int,
    onToggleRecorder: () -> Unit,
    onOpenFileManager: () -> Unit,
    onOpenLogs: () -> Unit,
    currentPalette: ZalithThemePalette,
    onCyclePalette: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggleSidebar) {
                    Icon(
                        imageVector = if (sidebarExpanded) Icons.Default.MenuOpen else Icons.Default.Menu,
                        contentDescription = "Toggle Sidebar"
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "HorizonZ Launcher 2+",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        text = "Rust 26.3 • v0.7.4",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (activeAccount.chromaNameEnabled) {
                            Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF7043),
                                    Color(0xFFFFCA28),
                                    Color(0xFF26A69A),
                                    Color(0xFF42A5F5)
                                )
                            )
                        } else {
                            Brush.horizontalGradient(
                                listOf(
                                    MaterialTheme.colorScheme.secondaryContainer,
                                    MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                    )
                    .padding(horizontal = 14.dp, vertical = 5.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = Color(0xFF10141C),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "${activeAccount.username} (${activeAccount.accountType.badge})",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF10141C)
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = onToggleRecorder,
                    label = {
                        Text(
                            if (isRecording) "REC %02d:%02d".format(recordingSeconds / 60, recordingSeconds % 60)
                            else "Recorder"
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.FiberManualRecord,
                            contentDescription = null,
                            tint = if (isRecording) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
                Spacer(modifier = Modifier.width(6.dp))
                IconButton(onClick = onOpenFileManager) {
                    Icon(Icons.Default.FolderOpen, contentDescription = "In-Launcher File Manager")
                }
                IconButton(onClick = onOpenLogs) {
                    Icon(Icons.Default.Terminal, contentDescription = "Latest Log")
                }
                IconButton(onClick = onCyclePalette) {
                    Icon(Icons.Default.Palette, contentDescription = currentPalette.label)
                }
            }
        }
    }
}

@Composable
private fun ZalithSidebarRail(
    selectedTab: ZalithNavTab,
    expanded: Boolean,
    onSelectTab: (ZalithNavTab) -> Unit
) {
    Surface(
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxHeight()
            .width(if (expanded) 195.dp else 64.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp, horizontal = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ZalithNavTab.entries.forEach { tab ->
                val isSelected = tab == selectedTab
                val icon = when (tab) {
                    ZalithNavTab.DASHBOARD -> Icons.Default.Home
                    ZalithNavTab.ACCOUNTS -> Icons.Default.AccountCircle
                    ZalithNavTab.ASSETS -> Icons.Default.Inventory2
                    ZalithNavTab.CONTENT -> Icons.Default.Extension
                    ZalithNavTab.CONTROLS -> Icons.Default.Gamepad
                    ZalithNavTab.SETTINGS -> Icons.Default.Tune
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelectTab(tab) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = tab.title,
                            tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (expanded) {
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = tab.title,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = tab.subtitle,
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(
    config: HorizonConfigState,
    activeAccount: ZalithAccount,
    onNavigate: (ZalithNavTab) -> Unit,
    onOpenLogs: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Minecraft: Java Edition 26.3 — 100% Native Rust Engine",
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Powered by horizon-rust/horizon (v0.2.97) + Vasysik/horizon-android (v0.7.4) with Star1xr/ZalithLauncher2Plus Material 3 UI. Zero JVM overhead, 281 vanilla parity harnesses.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalButton(onClick = { onNavigate(ZalithNavTab.ACCOUNTS) }) {
                        Icon(Icons.Default.Checkroom, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Capes & Wardrobe")
                    }
                    FilledTonalButton(onClick = { onNavigate(ZalithNavTab.ASSETS) }) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Prepare 26.3 Assets")
                    }
                    FilledTonalButton(onClick = { onNavigate(ZalithNavTab.CONTROLS) }) {
                        Icon(Icons.Default.TouchApp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Touch HUD Editor")
                    }
                    FilledTonalButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Subject, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Full Latest Log")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Changelog • HorizonZ 2.4.10+ / Rust 0.2.97",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Native Rust 26.3 Engine: 100% parity across all 14 subsystems (Worldgen, Redstone, Mob AI, Raids, Anvil 1.8–26.3 saves).
" +
                            "• Android Port (v0.7.3/0.7.4): NativeActivity libhorizon_android.so, SAF DocumentsProvider, Touch HUD & Gamepad support.
" +
                            "• Zalith Plus 1.3 UI: Cape Wardrobe, Chroma Names, Distant Terrain up to 1024 chunks, 70% default resolution scale, Screen Recorder.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Click for more →",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.clickable { onNavigate(ZalithNavTab.ASSETS) }
                    )
                }
            }

            Card(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Daily Hours & Engine Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "• Active Renderer: wgpu (${config.wgpuBackend}) • Preset: ${config.graphicsPreset}
" +
                            "• Distant Terrain: ${config.distantTerrainChunks} chunks • Resolution: ${config.resolutionScalePercent}%
" +
                            "• Active Cape: ${activeAccount.selectedCapeName} (${if (activeAccount.slimSkinModel) "Slim" else "Classic"} model)
" +
                            "• Playtime Weekly Grid: Mon 1.5h | Tue 2.1h | Wed 1.8h | Thu 3.2h",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Click for more →",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onNavigate(ZalithNavTab.SETTINGS) }
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountsWardrobeScreen(
    accounts: List<ZalithAccount>,
    capes: List<CapeItem>,
    onSelectAccount: (String) -> Unit,
    onAddAccount: (String, AccountType, Boolean, Boolean) -> Unit,
    onSelectCape: (String) -> Unit,
    onUploadCapeFromGallery: () -> Unit
) {
    var newUsername by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(AccountType.OFFLINE) }
    var enableChroma by remember { mutableStateOf(true) }
    var slimModel by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Accounts & Auth (26.3 Online / Offline / Ely.by)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                accounts.forEach { acc ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (acc.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectAccount(acc.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = acc.username + if (acc.chromaNameEnabled) " [CHROMA]" else "",
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "${acc.accountType.badge} • Cape: ${acc.selectedCapeName}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            RadioButton(selected = acc.isActive, onClick = { onSelectAccount(acc.id) })
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                OutlinedTextField(
                    value = newUsername,
                    onValueChange = { newUsername = it },
                    label = { Text("New Player Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    AccountType.entries.forEach { type ->
                        FilterChip(
                            selected = selectedType == type,
                            onClick = { selectedType = type },
                            label = { Text(type.name, fontSize = 10.sp) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = enableChroma, onCheckedChange = { enableChroma = it })
                    Text("Chroma Name", fontSize = 12.sp)
                    Spacer(Modifier.width(12.dp))
                    Checkbox(checked = slimModel, onCheckedChange = { slimModel = it })
                    Text("Slim Skin (Alex 3px)", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        if (newUsername.isNotBlank()) {
                            onAddAccount(newUsername.trim(), selectedType, enableChroma, slimModel)
                            newUsername = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Add Account Profile")
                }
            }
        }

        Card(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Capes & Wardrobe (ZL2+ 1.3)",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    FilledTonalButton(onClick = onUploadCapeFromGallery) {
                        Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Upload Cape from Gallery", fontSize = 11.sp)
                    }
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(capes) { cape ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelectCape(cape.name) }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        modifier = Modifier
                                            .size(28.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(Color(cape.accentColorHex))
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text(cape.name, fontWeight = FontWeight.SemiBold)
                                        Text(cape.source, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Icon(
                                    imageVector = if (cape.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VersionAssetsScreen(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    onPickServerJar: () -> Unit,
    onPickEngineZip: () -> Unit
) {
    val context = LocalContext.current
    var filterTab by remember { mutableStateOf("Stable 26.3") }
    val home = remember { HorizonNativeBridge.ensureHomeDirectories(context) }
    val hasEngineZip = remember(config) { File(home, "engine-data.zip").exists() }
    val hasServerJar = remember(config) { File(home, ".local/horizon-client/26.3/server-26.3.jar").exists() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("Stable 26.3", "Horizon Nightly (0.2.97)", "Android Port (0.7.4)").forEach { pill ->
                FilterChip(
                    selected = filterTab == pill,
                    onClick = { filterTab = pill },
                    label = { Text(pill) }
                )
            }
        }

        Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Minecraft: Java Edition 26.3 Asset Preparation (horizon-prepare & --public mode)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Horizon runs 100% natively in Rust without a JVM. For legal compliance, it contains no proprietary Mojang assets in the repository. You can either enable Public On-Device Asset Streaming (--public mode from Vasysik/horizon-android) or import your own Minecraft 26.3 server.jar / engine-data.zip.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Public On-Device Asset Streaming (HORIZON_ANDROID_PUBLIC=1)", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Automatically prepares & downloads required 26.3 textures/sounds on device launch",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = config.publicOnDeviceAssetDownload,
                        onCheckedChange = { onConfigChange(config.copy(publicOnDeviceAssetDownload = it)) }
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onPickEngineZip) {
                        Icon(Icons.Default.Archive, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (hasEngineZip) "Update engine-data.zip (Present)" else "Import engine-data.zip")
                    }
                    FilledTonalButton(onClick = onPickServerJar) {
                        Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (hasServerJar) "26.3 server.jar Ready" else "Import 26.3 server.jar")
                    }
                }
            }
        }
    }
}

@Composable
private fun ContentFileManagerScreen() {
    val context = LocalContext.current
    val home = remember { HorizonNativeBridge.ensureHomeDirectories(context) }
    var currentFolder by remember { mutableStateOf("saves") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    val folders = listOf(
        "saves" to "Worlds (Anvil 1.8–26.3)",
        "resourcepacks" to "Resource Packs",
        "datapacks" to "Data Packs",
        "mods" to "Horizon Wasm Mods (.wasm)",
        "shaderpacks" to "Shader Packs"
    )

    val dirFile = File(home, currentFolder).apply { mkdirs() }
    val children = remember(currentFolder, refreshTrigger) {
        dirFile.listFiles()?.toList().orEmpty()
    }

    val fileImporter = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val name = "Imported_${System.currentTimeMillis()}.pack"
            HorizonNativeBridge.importUriToDestination(context, uri, currentFolder, name)
            refreshTrigger += 1
        }
    }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                folders.forEach { (folderKey, label) ->
                    FilterChip(
                        selected = currentFolder == folderKey,
                        onClick = { currentFolder = folderKey },
                        label = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilledTonalButton(
                    onClick = {
                        File(dirFile, "New_Folder_${children.size + 1}").mkdirs()
                        refreshTrigger += 1
                    }
                ) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("New Folder", fontSize = 11.sp)
                }
                Button(onClick = { fileImporter.launch("*/*") }) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Import File", fontSize = 11.sp)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = "SAF DocumentsProvider Bridge: content://dev.horizon.android.documents/tree/root/$currentFolder",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(Modifier.height(8.dp))
                if (children.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No items in /$currentFolder yet. Use 'Import File', 'New Folder', or the Android Files app (HorizonZ DocumentsProvider).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(children) { item ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(Modifier.width(10.dp))
                                        Column {
                                            Text(item.name, fontWeight = FontWeight.SemiBold)
                                            Text("${item.length()} bytes", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                    IconButton(
                                        onClick = {
                                            item.deleteRecursively()
                                            refreshTrigger += 1
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete")
                                    }
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
private fun ControlMapEditorScreen(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Touch Overlay, HUD Editor & Gamepad Mapping (bedrock-controls.json)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Directly configures crates/horizon-client/src/touch* and crates/horizon-client/src/game/android_input.rs.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = config.splitTouchControls,
                        onCheckedChange = { onConfigChange(config.copy(splitTouchControls = it)) }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Split Touch Crosshair")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = config.hideControllerWhileLoading,
                        onCheckedChange = { onConfigChange(config.copy(hideControllerWhileLoading = it)) }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Hide Controller While Loading (ZL2+ 1.2)")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = config.autoJump,
                        onCheckedChange = { onConfigChange(config.copy(autoJump = it)) }
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Auto-Jump")
                }
            }

            Text("D-Pad & Touch Button Scale: ${(config.dpadScale * 100).toInt()}%")
            Slider(
                value = config.dpadScale,
                onValueChange = { onConfigChange(config.copy(dpadScale = it)) },
                valueRange = 0.5f..1.75f
            )

            Text("Touch HUD Opacity: ${(config.dpadOpacity * 100).toInt()}%")
            Slider(
                value = config.dpadOpacity,
                onValueChange = { onConfigChange(config.copy(dpadOpacity = it)) },
                valueRange = 0.2f..1.0f
            )
        }
    }
}

@Composable
private fun ZalithSettingsScreen(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    useDynamicColor: Boolean,
    onToggleDynamicColor: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "HorizonZ & ZalithLauncher2Plus Settings Remake",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("wgpu Backend:", fontWeight = FontWeight.SemiBold)
                listOf("Vulkan", "GLES3").forEach { backend ->
                    FilterChip(
                        selected = config.wgpuBackend == backend,
                        onClick = { onConfigChange(config.copy(wgpuBackend = backend)) },
                        label = { Text(backend) }
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text("Preset:", fontWeight = FontWeight.SemiBold)
                listOf("Vanilla", "Fast", "Fancy", "Ultra").forEach { preset ->
                    FilterChip(
                        selected = config.graphicsPreset == preset,
                        onClick = { onConfigChange(config.copy(graphicsPreset = preset)) },
                        label = { Text(preset) }
                    )
                }
            }

            Text("Resolution Scale: ${config.resolutionScalePercent}% (ZL2+ Default: 70%)")
            Slider(
                value = config.resolutionScalePercent.toFloat(),
                onValueChange = { onConfigChange(config.copy(resolutionScalePercent = it.toInt())) },
                valueRange = 40f..100f
            )

            Text("Horizon Distant Terrain: ${config.distantTerrainChunks} Chunks (Up to 1024 Chunks)")
            Slider(
                value = config.distantTerrainChunks.toFloat(),
                onValueChange = { onConfigChange(config.copy(distantTerrainChunks = it.toInt())) },
                valueRange = 16f..1024f
            )

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.enableMinimap,
                        onCheckedChange = { onConfigChange(config.copy(enableMinimap = it)) }
                    )
                    Text("Horizon Minimap")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.enableZoom,
                        onCheckedChange = { onConfigChange(config.copy(enableZoom = it)) }
                    )
                    Text("Zoom Key")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = config.closedCaptions,
                        onCheckedChange = { onConfigChange(config.copy(closedCaptions = it)) }
                    )
                    Text("Subtitles / Captions")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useDynamicColor,
                        onCheckedChange = onToggleDynamicColor
                    )
                    Text("Android 12+ Material You Wallpaper Color")
                }
            }
        }
    }
}

@Composable
private fun ZalithRightLaunchDock(
    config: HorizonConfigState,
    activeAccount: ZalithAccount,
    onLaunchGame: () -> Unit
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxHeight()
            .width(220.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Launch Instance",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Horizon Rust 26.3", fontWeight = FontWeight.Bold)
                        Text("Engine: v0.2.97 • Port: v0.7.4", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Backend: wgpu (${config.wgpuBackend})", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                        Text("Resolution: ${config.resolutionScalePercent}%", fontSize = 11.sp)
                        Text("Distant Chunks: ${config.distantTerrainChunks}", fontSize = 11.sp)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Player: ${activeAccount.username}", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                        Text("Cape: ${activeAccount.selectedCapeName}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Button(
                onClick = onLaunchGame,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(58.dp)
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "PLAY 26.3",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp
                )
            }
        }
    }
}
