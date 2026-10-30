@file:OptIn(ExperimentalMaterial3Api::class)

package dev.horizon.android.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.horizon.android.R
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.bridge.HorizonNativeBridge
import dev.horizon.android.model.*
import dev.horizon.android.ui.theme.HorizonZalithTheme
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID

private val DASHBOARD_WALLPAPERS = listOf(
    R.drawable.mc_wall_01,
    R.drawable.mc_wall_02,
    R.drawable.mc_wall_03,
    R.drawable.mc_wall_04,
    R.drawable.mc_wall_05,
    R.drawable.mc_wall_06,
    R.drawable.mc_wall_07,
    R.drawable.mc_wall_08,
    R.drawable.mc_wall_09,
    R.drawable.mc_wall_10,
    R.drawable.mc_wall_11,
    R.drawable.mc_wall_12
)

class ZalithMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        HorizonNativeBridge.ensureHomeDirectories(this)

        if (Build.VERSION.SDK_INT >= 33) {
            try {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
            } catch (ignored: Exception) {
            }
        }

        setContent {
            val context = LocalContext.current
            val lifecycleOwner = LocalLifecycleOwner.current
            var config by remember { mutableStateOf(HorizonNativeBridge.loadSavedConfig(context)) }
            var accounts by remember { mutableStateOf(HorizonNativeBridge.loadSavedAccounts(context)) }
            val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.first()

            // Reload config & options.txt whenever returning from :game process!
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        config = HorizonNativeBridge.loadSavedConfig(context)
                        accounts = HorizonNativeBridge.loadSavedAccounts(context)
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            HorizonZalithTheme(
                palette = config.themePalette,
                useDynamicColor = config.useDynamicColor
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ZalithCleanLauncherScreen(
                        config = config,
                        accounts = accounts,
                        activeAccount = activeAccount,
                        onConfigChange = { updated ->
                            config = updated
                            HorizonNativeBridge.saveConfig(context, updated, activeAccount)
                        },
                        onAccountsChange = { updatedList ->
                            accounts = updatedList
                            HorizonNativeBridge.saveAccounts(context, updatedList, config)
                        }
                    )
                }
            }
        }
    }
}

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
    accounts: List<ZalithAccount>,
    activeAccount: ZalithAccount,
    onConfigChange: (HorizonConfigState) -> Unit,
    onAccountsChange: (List<ZalithAccount>) -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ZalithNavTab.HOME) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf(HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)) }

    LaunchedEffect(config.enableSystemLog) {
        while (true) {
            installStatus = HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)
            delay(500L)
        }
    }

    val triggerDownload = {
        selectedTab = ZalithNavTab.VERSIONS
        val started = HorizonNativeBridge.startMinecraft26Download(context, config.enableSystemLog)
        if (!started && installStatus.ready) {
            HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
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
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.displayCutout))
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ZalithMinimalTopBar(
                installStatus = installStatus,
                onOpenLogs = { showLogDialog = true }
            )

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
                        ZalithNavTab.HOME -> CleanHomeViewport(logEnabled = config.enableSystemLog)
                        ZalithNavTab.VERSIONS -> CleanVersionsViewport(
                            context = context,
                            installStatus = installStatus,
                            onStartDownload = triggerDownload,
                            onRepairAssets = {
                                HorizonNativeBridge.repairAndVerifyAssets(context, config.enableSystemLog)
                            }
                        )
                        ZalithNavTab.FILES -> CleanFilesViewport(
                            profileSlot = config.profile,
                            logEnabled = config.enableSystemLog
                        )
                        ZalithNavTab.SETTINGS -> CleanSettingsViewport(
                            config = config,
                            onConfigChange = onConfigChange,
                            onOpenLogs = { showLogDialog = true }
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
                val updated = accounts.map { it.copy(isActive = it.id == id) }
                onAccountsChange(updated)
                HorizonNativeBridge.appendSystemLog("Switched active profile to ID=$id", config.enableSystemLog, context)
            },
            onAddAccount = { name, type ->
                val added = ZalithAccount(
                    id = UUID.randomUUID().toString(),
                    username = name,
                    uuid = UUID.randomUUID().toString(),
                    accountType = type,
                    isActive = true
                )
                val updated = accounts.map { it.copy(isActive = false) } + added
                onAccountsChange(updated)
                HorizonNativeBridge.appendSystemLog("Saved profile '$name' (${type.badge})", config.enableSystemLog, context)
            },
            onDeleteAccount = { id ->
                if (accounts.size > 1) {
                    val remaining = accounts.filterNot { it.id == id }
                    val fixed = if (remaining.none { it.isActive }) {
                        remaining.mapIndexed { idx, acc -> acc.copy(isActive = idx == 0) }
                    } else remaining
                    onAccountsChange(fixed)
                }
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
            if (installStatus.running && installStatus.speedText.isNotBlank()) {
                Spacer(Modifier.width(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.75f)
                ) {
                    Text(
                        text = "${installStatus.percent}% • ${installStatus.speedText}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
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

/**
 * Main Dashboard Card:
 * - Cycles 12 HD 16:9 Minecraft Wallpapers (fetched from Lxtharia/minegrub-theme & usman-369/wallpapers + custom user imports)
 *   every 5 seconds with smooth crossfade and `ContentScale.Crop` (normal 16:9 aspect ratio, zero distortion).
 * - Includes a discrete top-right button to import any additional HD wallpaper from the phone's gallery.
 */
@Composable
private fun CleanHomeViewport(logEnabled: Boolean) {
    val context = LocalContext.current
    var customWallpapers by remember { mutableStateOf(HorizonNativeBridge.getCustomWallpapers(context)) }
    val totalCount = DASHBOARD_WALLPAPERS.size + customWallpapers.size
    var quoteIndex by remember { mutableIntStateOf((MINECRAFT_SPLASH_QUOTES.indices).random()) }
    var wallpaperIndex by remember { mutableIntStateOf((0 until totalCount).random()) }

    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            HorizonNativeBridge.importUriToDestination(
                context,
                uri,
                "custom_wallpapers",
                "mc_custom_${System.currentTimeMillis()}.jpg",
                logEnabled
            )
            customWallpapers = HorizonNativeBridge.getCustomWallpapers(context)
            wallpaperIndex = DASHBOARD_WALLPAPERS.size
        }
    }

    // Cycle HD Minecraft Wallpaper every 5 seconds
    LaunchedEffect(totalCount) {
        while (true) {
            delay(5000L)
            wallpaperIndex = (wallpaperIndex + 1) % totalCount.coerceAtLeast(1)
        }
    }

    // Cycle Minecraft Splash Quote every 5 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000L)
            var next = (MINECRAFT_SPLASH_QUOTES.indices).random()
            if (next == quoteIndex) {
                next = (quoteIndex + 1) % MINECRAFT_SPLASH_QUOTES.size
            }
            quoteIndex = next
        }
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxSize()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = wallpaperIndex % totalCount.coerceAtLeast(1),
                transitionSpec = {
                    fadeIn(animationSpec = tween(900)).togetherWith(fadeOut(animationSpec = tween(900)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "MinecraftWallpaperCarousel"
            ) { idx ->
                if (idx >= DASHBOARD_WALLPAPERS.size && (idx - DASHBOARD_WALLPAPERS.size) < customWallpapers.size) {
                    val customFile = customWallpapers[idx - DASHBOARD_WALLPAPERS.size]
                    val bmp = remember(customFile.absolutePath) {
                        runCatching { BitmapFactory.decodeFile(customFile.absolutePath)?.asImageBitmap() }.getOrNull()
                    }
                    if (bmp != null) {
                        Image(
                            bitmap = bmp,
                            contentDescription = "Custom Minecraft Wallpaper",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Image(
                            painter = painterResource(id = DASHBOARD_WALLPAPERS[0]),
                            contentDescription = "Minecraft Wallpaper",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    val resId = DASHBOARD_WALLPAPERS[idx % DASHBOARD_WALLPAPERS.size]
                    Image(
                        painter = painterResource(id = resId),
                        contentDescription = "Minecraft Wallpaper",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // Subtle dark gradient scrim at bottom for crisp text legibility
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.60f)
                            )
                        )
                    )
            )

            // Top-right button to add custom wallpaper or skip to next wallpaper
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Black.copy(alpha = 0.42f),
                    modifier = Modifier.animatedPressScale { wallpaperPicker.launch("image/*") }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Wallpaper, contentDescription = "Add Wallpaper", tint = Color.White, modifier = Modifier.size(14.dp))
                        Text("Wallpaper", fontSize = 11.sp, color = Color.White)
                    }
                }
            }

            // Clean Title + Animated Minecraft Splash Quote
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Minecraft",
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                AnimatedContent(
                    targetState = MINECRAFT_SPLASH_QUOTES[quoteIndex],
                    transitionSpec = {
                        (slideInVertically(tween(380)) { h -> h / 2 } + fadeIn(tween(380)))
                            .togetherWith(
                                slideOutVertically(tween(300)) { h -> -h / 2 } + fadeOut(tween(280))
                            )
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.90f)
                        .animatedPressScale {
                            quoteIndex = (quoteIndex + 1) % MINECRAFT_SPLASH_QUOTES.size
                            wallpaperIndex = (wallpaperIndex + 1) % totalCount.coerceAtLeast(1)
                        },
                    label = "MinecraftSplashQuote"
                ) { quote ->
                    Text(
                        text = "“$quote”",
                        fontSize = 14.sp,
                        fontStyle = FontStyle.Italic,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFFFE082)
                    )
                }
            }
        }
    }
}

@Composable
private fun CleanVersionsViewport(
    context: android.content.Context,
    installStatus: AssetInstallStatus,
    onStartDownload: () -> Unit,
    onRepairAssets: () -> Unit
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Game & Assets", style = MaterialTheme.typography.titleMedium)
                if (!installStatus.running) {
                    TextButton(onClick = onRepairAssets) {
                        Icon(Icons.Default.Build, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Repair / Verify Assets", fontSize = 11.sp)
                    }
                }
            }

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
                                text = if (installStatus.ready) "All game files, worldgen tables, and 60 touch controls installed"
                                else if (installStatus.running) installStatus.stage
                                else "Download official game & assets (supports background download)",
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

                    if (installStatus.running || installStatus.done > 0L || installStatus.error.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = installStatus.stage,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val rightLabel = buildString {
                                    if (installStatus.speedText.isNotBlank()) {
                                        append(installStatus.speedText)
                                        append(" • ")
                                    }
                                    if (installStatus.detail.isNotBlank()) {
                                        append(installStatus.detail)
                                        append(" ")
                                    } else if (installStatus.total > 1L) {
                                        append("${installStatus.done} / ${installStatus.total} ")
                                    }
                                    append("(${installStatus.percent}%)")
                                }
                                Text(
                                    text = rightLabel,
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
                            if (installStatus.currentUrl.isNotBlank()) {
                                Text(
                                    text = "Link: ${installStatus.currentUrl}",
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
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
                                        text = "Download Process Log (URL & Speed)",
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
                                    "[OK] Minecraft 26.3 assets, worldgen tables, and 60/60 touch controls verified."
                                } else {
                                    "Tap 'Download' or 'INSTALL' to begin. Background notification will show live speed & percentage."
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 80.dp, max = 165.dp)
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
private fun CleanFilesViewport(profileSlot: Int, logEnabled: Boolean) {
    val context = LocalContext.current
    val home = remember(profileSlot) {
        HorizonNativeBridge.getProfileHome(HorizonNativeBridge.ensureHomeDirectories(context), profileSlot)
    }
    var currentFolder by remember { mutableStateOf("saves") }
    var refreshCount by remember { mutableIntStateOf(0) }

    val tabs = listOf(
        "saves" to "Worlds",
        "resourcepacks" to "Resource Packs",
        "mods" to "Mods (.hmod)",
        "shaderpacks" to "Shaders"
    )

    val dirFile = File(home, currentFolder).apply { mkdirs() }
    val itemsList = remember(currentFolder, refreshCount, profileSlot) {
        dirFile.listFiles()?.toList().orEmpty()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ext = if (currentFolder == "mods") ".hmod" else ".zip"
            HorizonNativeBridge.importUriToDestination(
                context,
                uri,
                currentFolder,
                "imported_${System.currentTimeMillis()}$ext",
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
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
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
                OutlinedButton(
                    onClick = { HorizonNativeBridge.openSystemFilesApp(context) },
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Open Folder", fontSize = 11.sp)
                }
                Spacer(Modifier.width(6.dp))
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
                    Text(
                        "No items in $currentFolder yet — tap Import or Open Folder to add files.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(itemsList) { file ->
                        val isToggleable = currentFolder in listOf("mods", "shaderpacks", "resourcepacks")
                        val isEnabled = remember(file.name, refreshCount, currentFolder) {
                            if (isToggleable) {
                                HorizonNativeBridge.isContentItemEnabled(context, currentFolder, file.name, profileSlot)
                            } else true
                        }
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = file.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${file.length() / 1024} KB" + if (isToggleable) {
                                            if (isEnabled) " • Active in Minecraft" else " • Disabled"
                                        } else "",
                                        fontSize = 11.sp,
                                        color = if (isEnabled) Color(0xFF66BB6A) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isToggleable) {
                                    Switch(
                                        checked = isEnabled,
                                        onCheckedChange = {
                                            HorizonNativeBridge.toggleContentItem(context, currentFolder, file.name, profileSlot, logEnabled)
                                            refreshCount += 1
                                        }
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
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

/**
 * Complete Settings Menu synchronized 100% with:
 * 1) Upstream `Vasysik/horizon-android` (`crates/horizon-client/src/android/catalog.rs` -> `android-launcher.json`)
 * 2) Touch Keypad Runtime (`horizon-keypad.json`)
 * 3) Two-way Minecraft In-Game `options.txt` (`renderDistance`, `simulationDistance`, `maxFps`, `guiScale`, `enableVsync`, `fullscreen`)
 */
@Composable
private fun CleanSettingsViewport(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val scalePresets = listOf(50, 60, 75, 85, 100)

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
            Text("Horizon Engine, Runtime & Minecraft Settings", style = MaterialTheme.typography.titleMedium)

            // 1. Graphics Renderer & Render Scale (`renderer` & `render_scale` in android-launcher.json)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Graphics Renderer Backend (Native Runtime)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        HorizonRendererMode.entries.forEach { mode ->
                            FilterChip(
                                selected = config.renderer == mode,
                                onClick = { onConfigChange(config.copy(renderer = mode)) },
                                label = { Text("${mode.label} (${mode.serdeKey})", fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Render Resolution Scale: ${config.renderScalePercent}%", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            scalePresets.forEach { preset ->
                                AssistChip(
                                    onClick = { onConfigChange(config.copy(renderScalePercent = preset)) },
                                    label = { Text("$preset%", fontSize = 10.sp) }
                                )
                            }
                        }
                    }
                    Slider(
                        value = config.renderScalePercent.toFloat(),
                        onValueChange = { onConfigChange(config.copy(renderScalePercent = it.toInt())) },
                        valueRange = 25f..100f
                    )
                }
            }

            // 2. Two-Way Synced Minecraft In-Game Options (`options.txt`)
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Minecraft In-Game Settings Sync (options.txt)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)

                    Text("Render Distance: ${config.mcRenderDistance} Chunks | Simulation Distance: ${config.mcSimulationDistance} Chunks", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = config.mcRenderDistance.toFloat(),
                            onValueChange = { onConfigChange(config.copy(mcRenderDistance = it.toInt())) },
                            valueRange = 2f..32f,
                            modifier = Modifier.weight(1f)
                        )
                        Slider(
                            value = config.mcSimulationDistance.toFloat(),
                            onValueChange = { onConfigChange(config.copy(mcSimulationDistance = it.toInt())) },
                            valueRange = 5f..32f,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Text("Max Framerate: ${if (config.mcMaxFps >= 260) "Unlimited (260)" else "${config.mcMaxFps} FPS"} | GUI Scale: ${if (config.mcGuiScale == 0) "Auto" else "${config.mcGuiScale}x"}", fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                        Slider(
                            value = config.mcMaxFps.toFloat(),
                            onValueChange = { onConfigChange(config.copy(mcMaxFps = (it.toInt() / 10) * 10)) },
                            valueRange = 30f..260f,
                            modifier = Modifier.weight(1f)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            (0..4).forEach { gs ->
                                FilterChip(
                                    selected = config.mcGuiScale == gs,
                                    onClick = { onConfigChange(config.copy(mcGuiScale = gs)) },
                                    label = { Text(if (gs == 0) "Auto" else "${gs}x", fontSize = 10.sp) }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = config.mcVsync,
                            onClick = { onConfigChange(config.copy(mcVsync = !config.mcVsync)) },
                            label = { Text("VSync (${if (config.mcVsync) "ON" else "OFF"})", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = config.mcFullscreen,
                            onClick = { onConfigChange(config.copy(mcFullscreen = !config.mcFullscreen)) },
                            label = { Text("Fullscreen (${if (config.mcFullscreen) "ON" else "OFF"})", fontSize = 11.sp) }
                        )
                    }
                }
            }

            // 3. Input Host, Touch Keypad (`horizon-keypad.json`) & Screen Orientation
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Input Mode & Touch Controls", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        HorizonInputHost.entries.forEach { mode ->
                            FilterChip(
                                selected = config.inputHost == mode,
                                onClick = { onConfigChange(config.copy(inputHost = mode)) },
                                label = { Text(mode.label, fontSize = 11.sp) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = config.keypadControls,
                            onClick = { onConfigChange(config.copy(keypadControls = !config.keypadControls)) },
                            label = { Text("On-Screen Touch Keypad (${if (config.keypadControls) "Enabled" else "Disabled"})", fontSize = 11.sp) }
                        )
                        OutlinedButton(
                            onClick = { HorizonNativeBridge.resetTouchKeypadLayout(context, config) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset Keypad Layout", fontSize = 11.sp)
                        }
                    }

                    Text("Screen Orientation", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        HorizonOrientation.entries.forEach { orient ->
                            FilterChip(
                                selected = config.orientation == orient,
                                onClick = { onConfigChange(config.copy(orientation = orient)) },
                                label = { Text(orient.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }

            // 4. Profile Slot, Content Catalog Source (Horizon / Modrinth), Server & Proxy
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Game Profile Slot", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (0..3).forEach { slot ->
                                FilterChip(
                                    selected = config.profile == slot,
                                    onClick = { onConfigChange(config.copy(profile = slot)) },
                                    label = { Text(if (slot == 0) "Default (0)" else "Profile $slot", fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Shader & Mod Catalog Source", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            HorizonContentOrigin.entries.forEach { origin ->
                                FilterChip(
                                    selected = config.shaderSource == origin,
                                    onClick = { onConfigChange(config.copy(shaderSource = origin, modSource = origin)) },
                                    label = { Text(origin.label, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = config.defaultServer,
                            onValueChange = { onConfigChange(config.copy(defaultServer = it)) },
                            label = { Text("Quick-Join Server (ip:port)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = config.proxy,
                            onValueChange = { onConfigChange(config.copy(proxy = it)) },
                            label = { Text("HTTP/SOCKS5 Proxy (Optional)", fontSize = 11.sp) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 5. Diagnostics, Performance Export, Audio Safe Mode & Material You Theme
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Diagnostics, Telemetry & Theme", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        FilterChip(
                            selected = config.enableSystemLog,
                            onClick = { onConfigChange(config.copy(enableSystemLog = !config.enableSystemLog)) },
                            label = { Text("System Logs", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = config.recordPerformance,
                            onClick = { onConfigChange(config.copy(recordPerformance = !config.recordPerformance)) },
                            label = { Text("Record Perf Telemetry", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = config.disableAudioSafeMode,
                            onClick = { onConfigChange(config.copy(disableAudioSafeMode = !config.disableAudioSafeMode)) },
                            label = { Text("Audio Safe Mode", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = config.useDynamicColor,
                            onClick = { onConfigChange(config.copy(useDynamicColor = !config.useDynamicColor)) },
                            label = { Text("Material You (Android 12+)", fontSize = 11.sp) }
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { HorizonNativeBridge.exportPerformanceReport(context, config.profile) }) {
                            Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Export Performance", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = { HorizonNativeBridge.openSystemFilesApp(context) }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open Data Folder", fontSize = 11.sp)
                        }
                        OutlinedButton(onClick = onOpenLogs) {
                            Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Open System Log", fontSize = 11.sp)
                        }
                    }
                }
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
                                text = if (installStatus.ready) "Ready"
                                else if (installStatus.running) "${installStatus.percent}% (${installStatus.speedText.ifBlank { "Downloading" }})"
                                else "Needs Download",
                                fontSize = 11.sp,
                                color = if (installStatus.ready) Color(0xFF66BB6A) else MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
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
                Text("System & Game Log (v1.8)", style = MaterialTheme.typography.titleMedium)
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
                        Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", fontSize = 12.sp)
                    }
                }
            }
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
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
    onDeleteAccount: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var newName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf(AccountType.OFFLINE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Accounts & Microsoft Sign-In", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                accounts.forEach { acc ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (acc.isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .animatedPressScale { onSelectAccount(acc.id) }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${acc.username} (${acc.accountType.badge})",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (accounts.size > 1) {
                                IconButton(
                                    onClick = { onDeleteAccount(acc.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", modifier = Modifier.size(16.dp))
                                }
                            }
                            RadioButton(selected = acc.isActive, onClick = { onSelectAccount(acc.id) })
                        }
                    }
                }

                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { Text("Enter Player Name / Gamertag") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountType.entries.forEach { t ->
                        FilterChip(
                            selected = selectedType == t,
                            onClick = { selectedType = t },
                            label = { Text(t.badge, fontSize = 11.sp) }
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    FilledTonalButton(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onAddAccount(newName.trim(), selectedType)
                                newName = ""
                            }
                        }
                    ) {
                        Text("Save", fontSize = 12.sp)
                    }
                }

                if (selectedType == AccountType.MICROSOFT) {
                    OutlinedButton(
                        onClick = {
                            try {
                                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.microsoft.com/link"))
                                context.startActivity(browserIntent)
                            } catch (ignored: Exception) {
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Open Microsoft Device Link (microsoft.com/link)", fontSize = 11.sp)
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
