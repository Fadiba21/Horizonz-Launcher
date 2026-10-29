@file:OptIn(ExperimentalMaterial3Api::class)

package dev.horizon.android.ui

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.bridge.HorizonNativeBridge
import dev.horizon.android.model.*
import dev.horizon.android.ui.theme.HorizonZalithTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

class ZalithMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        HorizonNativeBridge.ensureHomeDirectories(this)

        setContent {
            var config by remember { mutableStateOf(HorizonConfigState()) }

            HorizonZalithTheme(
                palette = config.themePalette,
                useDynamicColor = config.useDynamicColor // Default true: Material 3 (You) Android
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZalithCleanLauncherScreen(
                        config = config,
                        onConfigChange = { config = it }
                    )
                }
            }
        }
    }
}

/**
 * Spring-physics press scale + subtle morph animation modifier for tactile Android Material 3 feel.
 */
fun Modifier.animatedPressScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "PressScale"
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

@Composable
fun ZalithCleanLauncherScreen(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ZalithNavTab.HOME) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf(HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)) }

    // Poll official_install progress every 600ms
    LaunchedEffect(config.enableSystemLog) {
        while (true) {
            installStatus = HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)
            delay(600L)
        }
    }

    var accounts by remember {
        mutableStateOf(
            listOf(
                ZalithAccount(
                    id = "1",
                    username = "Steve",
                    uuid = "8667ba71-b85a-4004-af54-457a9734eed7",
                    accountType = AccountType.OFFLINE,
                    isActive = true
                ),
                ZalithAccount(
                    id = "2",
                    username = "Alex",
                    uuid = "ec561538-f3fd-461d-aff5-086b22154bce",
                    accountType = AccountType.MICROSOFT,
                    isActive = false
                )
            )
        )
    }
    val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.first()

    val triggerDownload = {
        selectedTab = ZalithNavTab.VERSIONS
        val started = HorizonNativeBridge.startMinecraft26Download(context, config.enableSystemLog)
        if (!started) {
            HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
        }
    }

    // Root container with safe displayCutout + systemBars padding so UI never hits camera punch-hole
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
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Clean TopBar (no duplicate buttons)
            ZalithMinimalTopBar(
                installStatus = installStatus,
                onOpenLogs = { showLogDialog = true }
            )

            // 2. Main 3-Zone Layout: Left Icon Rail + Center Viewport + Right Launch Sidebar
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ZalithIconRail(
                    selectedTab = selectedTab,
                    onSelectTab = { selectedTab = it }
                )

                // Smooth Morph + Slide + Fade Page Transition
                AnimatedContent(
                    targetState = selectedTab,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    transitionSpec = {
                        (slideInVertically(
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMediumLow
                            )
                        ) { height -> height / 5 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.96f, animationSpec = tween(220)))
                            .togetherWith(
                                slideOutVertically(tween(160)) { height -> -height / 6 } + fadeOut(tween(150))
                            )
                    },
                    label = "TabContentTransition"
                ) { tab ->
                    when (tab) {
                        ZalithNavTab.HOME -> CleanHomeViewport(
                            config = config,
                            installStatus = installStatus
                        )
                        ZalithNavTab.VERSIONS -> CleanVersionsViewport(
                            context = context,
                            installStatus = installStatus,
                            onStartDownload = triggerDownload
                        )
                        ZalithNavTab.FILES -> CleanFilesViewport(
                            logEnabled = config.enableSystemLog
                        )
                        ZalithNavTab.SETTINGS -> CleanSettingsViewport(
                            config = config,
                            onConfigChange = onConfigChange
                        )
                    }
                }

                ZalithLaunchSidebar(
                    activeAccount = activeAccount,
                    installStatus = installStatus,
                    onOpenAccountDialog = { showAccountDialog = true },
                    onOpenVersions = { selectedTab = ZalithNavTab.VERSIONS },
                    onLaunchOrDownload = {
                        if (!installStatus.ready && !installStatus.running) {
                            triggerDownload()
                        } else if (!installStatus.running) {
                            HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
                        }
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
                HorizonNativeBridge.appendSystemLog("Switched active profile to ID=$id", config.enableSystemLog)
            },
            onAddAccount = { name, type ->
                val added = ZalithAccount(
                    id = UUID.randomUUID().toString(),
                    username = name,
                    uuid = UUID.randomUUID().toString(),
                    accountType = type,
                    isActive = true
                )
                accounts = accounts.map { it.copy(isActive = false) } + added
                HorizonNativeBridge.appendSystemLog("Added profile '$name' (${type.badge})", config.enableSystemLog)
            },
            onDismiss = { showAccountDialog = false }
        )
    }

    if (showLogDialog) {
        SystemLogModal(
            logEnabled = config.enableSystemLog,
            onDismiss = { showLogDialog = false }
        )
    }
}

@Composable
private fun ZalithMinimalTopBar(
    installStatus: AssetInstallStatus,
    onOpenLogs: () -> Unit
) {
    val dotColor by animateColorAsState(
        targetValue = if (installStatus.ready) Color(0xFF66BB6A) else MaterialTheme.colorScheme.primary,
        animationSpec = tween(350),
        label = "StatusDotColor"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "HorizonZ",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Text(
                    text = if (installStatus.ready) "Ready"
                    else if (installStatus.running) "Downloading ${installStatus.percent}%"
                    else "26.3",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                )
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.animatedPressScale(onClick = onOpenLogs)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    Icons.Default.Terminal,
                    contentDescription = "System Log",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text("Log", fontSize = 12.sp, fontWeight = FontWeight.Medium)
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
        shape = RoundedCornerShape(20.dp),
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
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            ZalithNavTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val icon = when (tab) {
                    ZalithNavTab.HOME -> Icons.Default.Home
                    ZalithNavTab.VERSIONS -> Icons.Default.Download
                    ZalithNavTab.FILES -> Icons.Default.Folder
                    ZalithNavTab.SETTINGS -> Icons.Default.Settings
                }
                val cornerRadius by animateDpAsState(
                    targetValue = if (selected) 16.dp else 12.dp,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "NavCornerMorph"
                )
                val bgColor by animateColorAsState(
                    targetValue = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    animationSpec = tween(220),
                    label = "NavBgColor"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(cornerRadius))
                        .background(bgColor)
                        .animatedPressScale { onSelectTab(tab) }
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
    installStatus: AssetInstallStatus
) {
    var quoteIndex by remember { mutableIntStateOf((MINECRAFT_SPLASH_QUOTES.indices).random()) }

    // Automatically cycle random Minecraft splash quotes every 4.5 seconds with smooth slide+fade animation
    LaunchedEffect(Unit) {
        while (true) {
            delay(4500L)
            var next = (MINECRAFT_SPLASH_QUOTES.indices).random()
            if (next == quoteIndex) {
                next = (quoteIndex + 1) % MINECRAFT_SPLASH_QUOTES.size
            }
            quoteIndex = next
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
        ) {
            Column(
                modifier = Modifier.align(Alignment.CenterStart),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Minecraft",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // Animated Minecraft Splash Quote (cycles automatically or when tapped)
                AnimatedContent(
                    targetState = MINECRAFT_SPLASH_QUOTES[quoteIndex],
                    transitionSpec = {
                        (slideInVertically(tween(380)) { h -> h / 2 } + fadeIn(tween(380)))
                            .togetherWith(
                                slideOutVertically(tween(300)) { h -> -h / 2 } + fadeOut(tween(280))
                            )
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.88f)
                        .animatedPressScale {
                            quoteIndex = (quoteIndex + 1) % MINECRAFT_SPLASH_QUOTES.size
                        },
                    label = "MinecraftSplashQuote"
                ) { quote ->
                    Text(
                        text = "“$quote”",
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(4.dp))

                // Subtle status chips (no duplicate navigation or duplicate download buttons)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusBadgePill(label = "26.3 Native")
                    StatusBadgePill(label = config.wgpuBackend)
                    StatusBadgePill(label = "${config.resolutionScalePercent}% Scale")
                    StatusBadgePill(
                        label = if (installStatus.ready) "Assets Ready"
                        else if (installStatus.running) "Installing ${installStatus.percent}%"
                        else "Assets Needed"
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBadgePill(label: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun CleanVersionsViewport(
    context: android.content.Context,
    installStatus: AssetInstallStatus,
    onStartDownload: () -> Unit
) {
    var logExpanded by remember { mutableStateOf(true) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (logExpanded) 180f else 0f,
        animationSpec = tween(250),
        label = "ChevronRotation"
    )
    val animatedProgress by animateFloatAsState(
        targetValue = installStatus.progressFraction,
        animationSpec = tween(300),
        label = "DownloadProgress"
    )

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Game & Assets", style = MaterialTheme.typography.titleMedium)

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Minecraft 26.3",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = if (installStatus.ready) "All game files and assets installed"
                                else if (installStatus.running) installStatus.stage
                                else "Download official game & assets to play",
                                fontSize = 12.sp,
                                color = if (installStatus.ready) Color(0xFF66BB6A)
                                else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(Modifier.width(12.dp))

                        Button(
                            onClick = onStartDownload,
                            enabled = !installStatus.running,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.height(42.dp)
                        ) {
                            Icon(
                                imageVector = if (installStatus.ready) Icons.Default.CheckCircle else Icons.Default.Download,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = if (installStatus.running) "${installStatus.percent}%"
                                else if (installStatus.ready) "Re-verify"
                                else "Download",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    // Progress Indicator
                    if (installStatus.running || installStatus.done > 0L || installStatus.error.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = installStatus.stage,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = if (installStatus.total > 1L) "${installStatus.done} / ${installStatus.total} (${installStatus.percent}%)"
                                    else "${installStatus.percent}%",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(7.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                        }
                    }

                    if (installStatus.error.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFF5252).copy(alpha = 0.14f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = installStatus.error,
                                fontSize = 12.sp,
                                color = Color(0xFFFF6E6E),
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    // Expandable / Minimizable Download Process Log directly below Progress Indicator
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { logExpanded = !logExpanded }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Subject,
                                        contentDescription = null,
                                        modifier = Modifier.size(15.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Text(
                                        text = "Download Process Log",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    TextButton(
                                        onClick = {
                                            val textToCopy = if (installStatus.logs.isNotEmpty()) {
                                                installStatus.logs.joinToString("\n")
                                            } else {
                                                "Stage: ${installStatus.stage} (${installStatus.percent}%)"
                                            }
                                            HorizonNativeBridge.copyToClipboard(context, "Download Log", textToCopy)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy", fontSize = 11.sp)
                                    }

                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = if (logExpanded) "Minimize" else "Expand",
                                        modifier = Modifier
                                            .size(20.dp)
                                            .rotate(chevronRotation)
                                    )
                                }
                            }

                            AnimatedVisibility(
                                visible = logExpanded,
                                enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
                                exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
                            ) {
                                val logContent = if (installStatus.logs.isNotEmpty()) {
                                    installStatus.logs.joinToString("\n")
                                } else if (installStatus.ready) {
                                    "[OK] Minecraft 26.3 assets, worldgen tables, and touch controls verified."
                                } else {
                                    "Waiting to start download... Tap 'Download' or 'INSTALL' to begin."
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 64.dp, max = 140.dp)
                                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF0A0D12))
                                        .padding(8.dp)
                                        .verticalScroll(rememberScrollState())
                                ) {
                                    Text(
                                        text = logContent,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = Color(0xFFA5D6A7)
                                    )
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
private fun CleanFilesViewport(logEnabled: Boolean) {
    val context = LocalContext.current
    val home = remember { HorizonNativeBridge.ensureHomeDirectories(context) }
    var currentFolder by remember { mutableStateOf("saves") }
    var refreshCount by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        "saves" to "Worlds",
        "resourcepacks" to "Resource Packs",
        "mods" to "Mods",
        "shaderpacks" to "Shaders"
    )

    val dirFile = File(home, currentFolder).apply { mkdirs() }
    val itemsList = remember(currentFolder, refreshCount) {
        dirFile.listFiles()?.toList().orEmpty()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            HorizonNativeBridge.importUriToDestination(
                context,
                uri,
                currentFolder,
                "imported_${System.currentTimeMillis()}.zip",
                logEnabled
            )
            refreshCount += 1
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
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
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    tabs.forEach { (folder, label) ->
                        FilterChip(
                            selected = currentFolder == folder,
                            onClick = { currentFolder = folder },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
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
                    Text("No files in this folder yet", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Text(
                                    text = file.name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = {
                                        file.deleteRecursively()
                                        refreshCount += 1
                                    },
                                    modifier = Modifier.size(30.dp)
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
    onConfigChange: (HorizonConfigState) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Settings", style = MaterialTheme.typography.titleMedium)

            // Row 1: Renderer & Graphics Preset
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Renderer", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    listOf("Vulkan", "GLES3").forEach { item ->
                        FilterChip(
                            selected = config.wgpuBackend == item,
                            onClick = { onConfigChange(config.copy(wgpuBackend = item)) },
                            label = { Text(item, fontSize = 11.sp) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Quality", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    listOf("Fast", "Fancy", "Ultra").forEach { preset ->
                        FilterChip(
                            selected = config.graphicsPreset == preset,
                            onClick = { onConfigChange(config.copy(graphicsPreset = preset)) },
                            label = { Text(preset, fontSize = 11.sp) }
                        )
                    }
                }
            }

            // Sliders Row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Resolution Scale: ${config.resolutionScalePercent}%", fontSize = 12.sp)
                    Slider(
                        value = config.resolutionScalePercent.toFloat(),
                        onValueChange = { onConfigChange(config.copy(resolutionScalePercent = it.toInt())) },
                        valueRange = 40f..100f
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Render Distance: ${config.renderDistanceChunks} Chunks", fontSize = 12.sp)
                    Slider(
                        value = config.renderDistanceChunks.toFloat(),
                        onValueChange = { onConfigChange(config.copy(renderDistanceChunks = it.toInt())) },
                        valueRange = 4f..32f
                    )
                }
            }

            // Feature Switches (Material You default, Disable/Enable System Logs, Split Touch, Auto Jump, Minimap)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = config.useDynamicColor,
                    onClick = { onConfigChange(config.copy(useDynamicColor = !config.useDynamicColor)) },
                    label = { Text("Material You", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = config.enableSystemLog,
                    onClick = { onConfigChange(config.copy(enableSystemLog = !config.enableSystemLog)) },
                    label = { Text("System Logs", fontSize = 11.sp) }
                )
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
            }
        }
    }
}

@Composable
private fun ZalithLaunchSidebar(
    activeAccount: ZalithAccount,
    installStatus: AssetInstallStatus,
    onOpenAccountDialog: () -> Unit,
    onOpenVersions: () -> Unit,
    onLaunchOrDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        modifier = Modifier
            .fillMaxHeight()
            .width(192.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Account Card
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .animatedPressScale(onClick = onOpenAccountDialog)
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

            // Bottom Version Card + Primary PLAY / INSTALL Button
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animatedPressScale(onClick = onOpenVersions)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Minecraft 26.3", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(
                                text = if (installStatus.ready) "Ready" else "Needs Download",
                                fontSize = 11.sp,
                                color = if (installStatus.ready) Color(0xFF66BB6A) else MaterialTheme.colorScheme.primary
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                val btnColor by animateColorAsState(
                    targetValue = if (installStatus.ready) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.secondary,
                    animationSpec = tween(250),
                    label = "PlayButtonColor"
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (installStatus.running) MaterialTheme.colorScheme.surface else btnColor,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                        .animatedPressScale(enabled = !installStatus.running, onClick = onLaunchOrDownload)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (installStatus.ready) Icons.Default.PlayArrow else Icons.Default.Download,
                            contentDescription = null,
                            tint = if (installStatus.running) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (installStatus.running) "${installStatus.percent}%"
                            else if (installStatus.ready) "PLAY"
                            else "INSTALL",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = if (installStatus.running) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemLogModal(
    logEnabled: Boolean,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var logText by remember { mutableStateOf(HorizonNativeBridge.readFullSystemLogs(context, logEnabled)) }

    // Auto-refresh while dialog is open
    LaunchedEffect(logEnabled) {
        while (true) {
            logText = HorizonNativeBridge.readFullSystemLogs(context, logEnabled)
            delay(1000L)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System & Game Log", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(
                        onClick = {
                            HorizonNativeBridge.copyToClipboard(context, "HorizonZ System Log", logText)
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Copy", fontSize = 12.sp)
                    }
                    TextButton(
                        onClick = {
                            HorizonNativeBridge.clearLogs(context)
                            logText = HorizonNativeBridge.readFullSystemLogs(context, logEnabled)
                        }
                    ) {
                        Text("Clear", fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .clip(RoundedCornerShape(12.dp))
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
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun AccountManagerModal(
    accounts: List<ZalithAccount>,
    onSelectAccount: (String) -> Unit,
    onAddAccount: (String, AccountType) -> Unit,
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
                            .animatedPressScale { onSelectAccount(acc.id) }
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
                    placeholder = { Text("New Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    AccountType.entries.forEach { t ->
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
                        onAddAccount(newName.trim(), selectedType)
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
