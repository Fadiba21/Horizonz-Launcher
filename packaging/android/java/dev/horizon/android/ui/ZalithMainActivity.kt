@file:OptIn(ExperimentalMaterial3Api::class)

package dev.horizon.android.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.horizon.android.R
import dev.horizon.android.bridge.AssetInstallStatus
import dev.horizon.android.bridge.HorizonNativeBridge
import dev.horizon.android.bridge.StoreDownloadProgress
import dev.horizon.android.model.*
import dev.horizon.android.ui.theme.HorizonZalithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

// Reference screenshot color tokens (Zalith Dark Slate Material 3 palette)
private val CardSlateBg = Color(0xFF22282C)
private val CardRowBg = Color(0xFF2A3136)
private val SubRailCapsuleActive = Color(0xFF394952)
private val SkyBlueAccent = Color(0xFF8BE0FF)
private val SkyBlueDarkText = Color(0xFF0E2430)
private val LavenderBadgeBg = Color(0xFFD0C6F2)
private val LavenderBadgeText = Color(0xFF231D38)
private val MutedSubtitleText = Color(0xFF9EA8B0)

enum class SettingsSubCategory(val label: String, val icon: ImageVector) {
    RENDERER("Renderer", Icons.Default.DisplaySettings),
    GAME("Game", Icons.Default.RocketLaunch),
    CONTROLS("Controls", Icons.Default.Games),
    GAMEPAD("Gamepad", Icons.Default.SportsEsports),
    LAUNCHER("Launcher", Icons.Default.ViewInAr)
}

class ZalithMainActivity : ComponentActivity() {
    companion object {
        @Volatile
        private var hasCheckedUpdateThisSession = false
    }

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

            var isLauncherHibernated by remember { mutableStateOf(false) }
            var releaseUpdateInfo by remember { mutableStateOf<GithubReleaseUpdateInfo?>(null) }
            var showUpdateBanner by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) {
                if (!hasCheckedUpdateThisSession) {
                    hasCheckedUpdateThisSession = true
                    val found = withContext(Dispatchers.IO) {
                        HorizonNativeBridge.checkGithubLatestReleaseUpdate()
                    }
                    if (found != null) {
                        releaseUpdateInfo = found
                        delay(350L)
                        showUpdateBanner = true
                    }
                }
            }

            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_RESUME -> {
                            isLauncherHibernated = false
                            config = HorizonNativeBridge.loadSavedConfig(context)
                            accounts = HorizonNativeBridge.loadSavedAccounts(context)
                        }
                        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                            isLauncherHibernated = true
                        }
                        else -> {}
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
                    color = Color(0xFF14181B)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        ZalithCleanLauncherScreen(
                            config = config,
                            accounts = accounts,
                            activeAccount = activeAccount,
                            isHibernated = isLauncherHibernated,
                            onConfigChange = { updated ->
                                config = updated
                                HorizonNativeBridge.saveConfig(context, updated, activeAccount)
                            },
                            onAccountsChange = { updatedList ->
                                accounts = updatedList
                                HorizonNativeBridge.saveAccounts(context, updatedList, config)
                            },
                            onEnterGameHibernation = {
                                isLauncherHibernated = true
                                HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
                            }
                        )

                        AnimatedVisibility(
                            visible = showUpdateBanner && releaseUpdateInfo != null && !isLauncherHibernated,
                            enter = slideInVertically(
                                initialOffsetY = { fullHeight -> -fullHeight - 60 },
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                    stiffness = Spring.StiffnessMediumLow
                                )
                            ) + fadeIn(tween(260)),
                            exit = slideOutVertically(
                                targetOffsetY = { fullHeight -> -fullHeight - 60 },
                                animationSpec = tween(260, easing = FastOutLinearInEasing)
                            ) + fadeOut(tween(200)),
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .windowInsetsPadding(WindowInsets.safeDrawing)
                                .padding(top = 8.dp, start = 24.dp, end = 24.dp)
                        ) {
                            releaseUpdateInfo?.let { info ->
                                InAppUpdateBannerCard(
                                    info = info,
                                    onUpdateClick = {
                                        showUpdateBanner = false
                                        try {
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(info.downloadUrl))
                                            context.startActivity(intent)
                                        } catch (ignored: Exception) {
                                        }
                                    },
                                    onLaterClick = {
                                        showUpdateBanner = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

fun Modifier.animatedPressScale(
    enabled: Boolean = true,
    pressedScale: Float = 0.95f,
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

/**
 * Custom Canvas Minecraft Grass Block Icon (for Dashboard & item badges).
 */
@Composable
fun MinecraftGrassCubeIcon(size: Dp = 36.dp) {
    Canvas(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(9.dp))
    ) {
        val w = this.size.width
        val h = this.size.height
        // Dirt base
        drawRect(color = Color(0xFF79553A), size = Size(w, h))
        // Grass top band
        drawRect(color = Color(0xFF569E3E), size = Size(w, h * 0.36f))
        // Bright grass top lip
        drawRect(color = Color(0xFF74C454), size = Size(w, h * 0.12f))
        // Pixelated grass fringes
        val step = w / 6f
        for (i in 0..5) {
            val fringeH = if (i % 2 == 0) h * 0.48f else h * 0.38f
            drawRect(
                color = Color(0xFF569E3E),
                topLeft = Offset(i * step, 0f),
                size = Size(step + 1f, fringeH)
            )
        }
        // Subtle dirt pixel speckles
        drawRect(color = Color(0xFF5E402A), topLeft = Offset(w * 0.2f, h * 0.62f), size = Size(step, step))
        drawRect(color = Color(0xFF91684A), topLeft = Offset(w * 0.6f, h * 0.55f), size = Size(step, step))
        drawRect(color = Color(0xFF5E402A), topLeft = Offset(w * 0.45f, h * 0.78f), size = Size(step, step))
    }
}

/**
 * Zalith-style Settings Dropdown Card (`429668.jpg` style):
 * Full-width dark slate rounded card with Title, Description subtitle, `Selected: <value>`, and right dropdown arrow.
 */
@Composable
fun <T> ZalithSettingDropdownCard(
    title: String,
    description: String,
    selectedItem: T,
    options: List<T>,
    itemLabel: (T) -> String,
    onSelect: (T) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val arrowRot by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(220),
        label = "SettingDropdownArrow"
    )

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = CardSlateBg,
            modifier = Modifier
                .fillMaxWidth()
                .animatedPressScale { expanded = !expanded }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = description,
                        fontSize = 12.sp,
                        color = MutedSubtitleText
                    )
                    Text(
                        text = "Selected: ${itemLabel(selectedItem)}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFC5D1D8)
                    )
                }
                Spacer(Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(arrowRot)
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF262E34))
        ) {
            options.forEach { option ->
                val isSelected = option == selectedItem
                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(option),
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            color = if (isSelected) SkyBlueAccent else Color.White
                        )
                    },
                    trailingIcon = if (isSelected) {
                        {
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = SkyBlueAccent,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    } else null,
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

/**
 * Zalith-style Slider Card (`429668.jpg` bottom card style):
 * Title + Subtitle + thick track slider on left + Sky-Blue `<  50%  >` stepper pill on right!
 */
@Composable
fun ZalithSettingSliderCard(
    title: String,
    description: String,
    value: Int,
    valueRange: IntRange,
    step: Int = 1,
    valueSuffix: String = "%",
    onValueChange: (Int) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardSlateBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
            Text(
                text = description,
                fontSize = 12.sp,
                color = MutedSubtitleText
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Slider(
                    value = value.toFloat(),
                    onValueChange = { onValueChange(it.toInt().coerceIn(valueRange.first, valueRange.last)) },
                    valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = SkyBlueAccent,
                        activeTrackColor = SkyBlueAccent,
                        inactiveTrackColor = Color(0xFF33424B)
                    ),
                    modifier = Modifier.weight(1f)
                )

                // Sky-blue stadium stepper pill: `50%   ◀   ▶`
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = SkyBlueAccent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "$value$valueSuffix",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = SkyBlueDarkText
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowLeft,
                            contentDescription = "Decrease",
                            tint = SkyBlueDarkText,
                            modifier = Modifier
                                .size(20.dp)
                                .animatedPressScale {
                                    onValueChange((value - step).coerceIn(valueRange.first, valueRange.last))
                                }
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowRight,
                            contentDescription = "Increase",
                            tint = SkyBlueDarkText,
                            modifier = Modifier
                                .size(20.dp)
                                .animatedPressScale {
                                    onValueChange((value + step).coerceIn(valueRange.first, valueRange.last))
                                }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Zalith-style Toggle Switch Card for boolean settings.
 */
@Composable
fun ZalithSettingToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardSlateBg,
        modifier = Modifier
            .fillMaxWidth()
            .animatedPressScale { onCheckedChange(!checked) }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MutedSubtitleText
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = SkyBlueDarkText,
                    checkedTrackColor = SkyBlueAccent
                )
            )
        }
    }
}

/**
 * Vertical Sub-Navigation Rail with Capsule Active Indicator (`429668.jpg` & `429665.jpg` left rail style).
 */
@Composable
fun <T> ZalithSubCategoryRail(
    items: List<T>,
    selectedItem: T,
    itemLabel: (T) -> String,
    itemIcon: (T) -> ImageVector,
    onSelect: (T) -> Unit
) {
    Column(
        modifier = Modifier
            .width(84.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items.forEach { item ->
            val selected = item == selectedItem
            val pillBg by animateColorAsState(
                targetValue = if (selected) SubRailCapsuleActive else Color.Transparent,
                animationSpec = tween(220),
                label = "SubRailPillBg"
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .animatedPressScale { onSelect(item) }
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .width(54.dp)
                        .height(32.dp)
                        .clip(RoundedCornerShape(50))
                        .background(pillBg)
                ) {
                    Icon(
                        imageVector = itemIcon(item),
                        contentDescription = itemLabel(item),
                        tint = if (selected) SkyBlueAccent else MutedSubtitleText,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = itemLabel(item),
                    fontSize = 11.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) Color.White else MutedSubtitleText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Lavender/Periwinkle Tag Pill Badge (`Fabric`, `26.3`, `Survival Mode`, `Horizon` from `429665.jpg` & `429666.jpg`).
 */
@Composable
fun LavenderTagBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = LavenderBadgeBg
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = LavenderBadgeText,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun InAppUpdateBannerCard(
    info: GithubReleaseUpdateInfo,
    onUpdateClick: () -> Unit,
    onLaterClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = CardSlateBg,
        tonalElevation = 8.dp,
        shadowElevation = 12.dp,
        modifier = Modifier.widthIn(max = 520.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            SubRailCapsuleActive,
                            CardSlateBg
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SkyBlueAccent)
                ) {
                    Icon(
                        Icons.Default.SystemUpdate,
                        contentDescription = null,
                        tint = SkyBlueDarkText,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = "New Version!! (v${info.latestVersion})",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 13.sp,
                        color = Color.White
                    )
                    Text(
                        text = "Installed: v${HorizonNativeBridge.INSTALLED_LAUNCHER_VERSION} • ${info.releaseTitle}",
                        fontSize = 11.sp,
                        color = MutedSubtitleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(
                    onClick = onLaterClick,
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("Later", fontSize = 12.sp, color = MutedSubtitleText)
                }
                Button(
                    onClick = onUpdateClick,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlueAccent),
                    modifier = Modifier.height(34.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
                ) {
                    Text("Update", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SkyBlueDarkText)
                }
            }
        }
    }
}

@Composable
fun ZalithCleanLauncherScreen(
    config: HorizonConfigState,
    accounts: List<ZalithAccount>,
    activeAccount: ZalithAccount,
    isHibernated: Boolean,
    onConfigChange: (HorizonConfigState) -> Unit,
    onAccountsChange: (List<ZalithAccount>) -> Unit,
    onEnterGameHibernation: () -> Unit
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(ZalithNavTab.HOME) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var installStatus by remember { mutableStateOf(HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)) }

    if (isHibernated) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Minecraft 26.3 Running — Launcher UI Hibernated (0% Overhead)",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }
        return
    }

    LaunchedEffect(config.enableSystemLog, isHibernated) {
        while (!isHibernated) {
            installStatus = HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)
            delay(500L)
        }
    }

    val triggerDownload = {
        selectedTab = ZalithNavTab.VERSIONS
        val started = HorizonNativeBridge.startMinecraft26Download(context, config.enableSystemLog)
        if (!started && installStatus.ready) {
            onEnterGameHibernation()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF14181B))
            .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.displayCutout))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Header Bar inspired by reference screenshots (`<-  Home   Title   Folder  Account  Download  Settings  Log`)
            ZalithReferenceTopBar(
                selectedTab = selectedTab,
                installStatus = installStatus,
                onNavigateHome = { selectedTab = ZalithNavTab.HOME },
                onSelectTab = { selectedTab = it },
                onOpenAccounts = { showAccountDialog = true },
                onOpenDataFolder = { HorizonNativeBridge.openSystemFilesApp(context) },
                onOpenLogs = { showLogDialog = true }
            )

            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(top = 4.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                        ) { height -> height / 5 } + fadeIn(tween(220)) + scaleIn(initialScale = 0.97f, animationSpec = tween(220)))
                            .togetherWith(
                                slideOutVertically(tween(150)) { height -> -height / 6 } + fadeOut(tween(140))
                            )
                    },
                    label = "MainTabTransition"
                ) { tab ->
                    when (tab) {
                        ZalithNavTab.HOME -> CleanHomeViewport(
                            logEnabled = config.enableSystemLog,
                            isHibernated = isHibernated,
                            onOpenStore = { selectedTab = ZalithNavTab.STORE },
                            onOpenFiles = { selectedTab = ZalithNavTab.FILES },
                            onOpenSettings = { selectedTab = ZalithNavTab.SETTINGS }
                        )
                        ZalithNavTab.VERSIONS -> CleanVersionsViewport(
                            context = context,
                            installStatus = installStatus,
                            onStartDownload = triggerDownload,
                            onPauseDownload = {
                                HorizonNativeBridge.pauseMinecraft26Download(context, config.enableSystemLog)
                            },
                            onResumeDownload = {
                                HorizonNativeBridge.resumeMinecraft26Download(context, config.enableSystemLog)
                            },
                            onCancelDownload = {
                                HorizonNativeBridge.cancelMinecraft26Download(context, config.enableSystemLog)
                            },
                            onSmartRepairAssets = {
                                HorizonNativeBridge.smartRepairAssets(context, config.enableSystemLog)
                            }
                        )
                        ZalithNavTab.STORE -> CleanStoreViewport(
                            profileSlot = config.profile,
                            logEnabled = config.enableSystemLog
                        )
                        ZalithNavTab.FILES -> CleanFilesViewport(
                            profileSlot = config.profile,
                            logEnabled = config.enableSystemLog,
                            onNavigateToStore = { selectedTab = ZalithNavTab.STORE }
                        )
                        ZalithNavTab.SETTINGS -> CleanSettingsViewport(
                            config = config,
                            onConfigChange = onConfigChange,
                            onOpenLogs = { showLogDialog = true }
                        )
                    }
                }

                // Keep Launch Sidebar on Dashboard & Versions so the user can always launch/switch accounts quickly
                AnimatedVisibility(
                    visible = selectedTab == ZalithNavTab.HOME || selectedTab == ZalithNavTab.VERSIONS,
                    enter = fadeIn(tween(200)) + expandHorizontally(),
                    exit = fadeOut(tween(150)) + shrinkHorizontally()
                ) {
                    ZalithLaunchSidebar(
                        activeAccount = activeAccount,
                        installStatus = installStatus,
                        onOpenAccountDialog = { showAccountDialog = true },
                        onOpenVersions = { selectedTab = ZalithNavTab.VERSIONS },
                        onLaunchOrDownload = {
                            if (!installStatus.ready && !installStatus.running) {
                                triggerDownload()
                            } else if (!installStatus.running) {
                                onEnterGameHibernation()
                            } else {
                                selectedTab = ZalithNavTab.VERSIONS
                            }
                        }
                    )
                }
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

/**
 * Top Bar matching the 4 reference screenshots (`429668.jpg`, `429665.jpg`):
 * Left: Back arrow, Home icon, and dynamic Title (`HorizonZ — Dashboard`, `Manage Versions - Mods`, `Settings - Renderer`)
 * Right: Quick icons for Folder, Accounts, Downloads, Settings pill, and System Log!
 */
@Composable
private fun ZalithReferenceTopBar(
    selectedTab: ZalithNavTab,
    installStatus: AssetInstallStatus,
    onNavigateHome: () -> Unit,
    onSelectTab: (ZalithNavTab) -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenDataFolder: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (selectedTab != ZalithNavTab.HOME) {
                Icon(
                    imageVector = Icons.Default.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White,
                    modifier = Modifier
                        .size(20.dp)
                        .animatedPressScale(onClick = onNavigateHome)
                )
            }
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = "Home",
                tint = if (selectedTab == ZalithNavTab.HOME) SkyBlueAccent else Color.White,
                modifier = Modifier
                    .size(20.dp)
                    .animatedPressScale(onClick = onNavigateHome)
            )
            Spacer(Modifier.width(4.dp))
            AnimatedContent(
                targetState = selectedTab.title,
                transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(140))) },
                label = "TopBarTitleAnim"
            ) { title ->
                Text(
                    text = if (selectedTab == ZalithNavTab.HOME) "HorizonZ" else "HorizonZ — $title",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            if (installStatus.running) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = SubRailCapsuleActive
                ) {
                    Text(
                        text = if (installStatus.paused) "Paused ${installStatus.percent}%"
                        else "${installStatus.percent}% • ${installStatus.speedText.ifBlank { "Downloading" }}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = SkyBlueAccent,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Folder,
                contentDescription = "Data Folder",
                tint = Color(0xFFD5DEE5),
                modifier = Modifier
                    .size(19.dp)
                    .animatedPressScale(onClick = onOpenDataFolder)
            )
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = "Accounts",
                tint = Color(0xFFD5DEE5),
                modifier = Modifier
                    .size(20.dp)
                    .animatedPressScale(onClick = onOpenAccounts)
            )
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Downloads",
                tint = if (selectedTab == ZalithNavTab.VERSIONS) SkyBlueAccent else Color(0xFFD5DEE5),
                modifier = Modifier
                    .size(20.dp)
                    .animatedPressScale { onSelectTab(ZalithNavTab.VERSIONS) }
            )

            // Active pill button for Settings (`429668.jpg` top-right style)
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selectedTab == ZalithNavTab.SETTINGS) SubRailCapsuleActive else CardSlateBg,
                modifier = Modifier.animatedPressScale { onSelectTab(ZalithNavTab.SETTINGS) }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = if (selectedTab == ZalithNavTab.SETTINGS) SkyBlueAccent else Color.White,
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = "Settings",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = CardSlateBg,
                modifier = Modifier.animatedPressScale(onClick = onOpenLogs)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Terminal,
                        contentDescription = "Log",
                        tint = SkyBlueAccent,
                        modifier = Modifier.size(15.dp)
                    )
                    Text("Log", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
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
        color = CardSlateBg,
        modifier = Modifier
            .fillMaxHeight()
            .width(52.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)
        ) {
            ZalithNavTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                val icon = when (tab) {
                    ZalithNavTab.HOME -> Icons.Default.Home
                    ZalithNavTab.VERSIONS -> Icons.Default.Download
                    ZalithNavTab.STORE -> Icons.Default.Storefront
                    ZalithNavTab.FILES -> Icons.Default.Extension
                    ZalithNavTab.SETTINGS -> Icons.Default.Settings
                }
                val pillColor by animateColorAsState(
                    targetValue = if (selected) SubRailCapsuleActive else Color.Transparent,
                    animationSpec = tween(220),
                    label = "RailPillColor"
                )
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(pillColor)
                        .animatedPressScale { onSelectTab(tab) }
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tab.title,
                        tint = if (selected) SkyBlueAccent else MutedSubtitleText,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

/**
 * Main Dashboard Viewport:
 * - 12 HD 16:9 Minecraft Wallpapers cycling every 5 seconds with smooth crossfade.
 * - Clean Minecraft Grass Cube Icon + subtle Quick-Action Icon Pills (`Mods`, `Store`, `Settings`, `Wallpaper`)
 *   so the Dashboard looks authentic and refined without being crowded!
 */
@Composable
private fun CleanHomeViewport(
    logEnabled: Boolean,
    isHibernated: Boolean,
    onOpenStore: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenSettings: () -> Unit
) {
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

    LaunchedEffect(totalCount, isHibernated) {
        while (!isHibernated) {
            delay(5000L)
            wallpaperIndex = (wallpaperIndex + 1) % totalCount.coerceAtLeast(1)
        }
    }

    LaunchedEffect(isHibernated) {
        while (!isHibernated) {
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
        color = CardSlateBg,
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

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.10f),
                                Color.Black.copy(alpha = 0.68f)
                            )
                        )
                    )
            )

            // Top-Right Subtle Quick-Action Icon Pills (Mods, Store, Wallpaper)
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DashboardQuickIconPill(
                    icon = Icons.Default.Extension,
                    label = "Mods",
                    onClick = onOpenFiles
                )
                DashboardQuickIconPill(
                    icon = Icons.Default.Storefront,
                    label = "Store",
                    onClick = onOpenStore
                )
                DashboardQuickIconPill(
                    icon = Icons.Default.Wallpaper,
                    label = "Wallpaper",
                    onClick = { wallpaperPicker.launch("image/*") }
                )
            }

            // Bottom-Left Minecraft Grass Icon + Title + Animated Splash Quote
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    MinecraftGrassCubeIcon(size = 40.dp)
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Minecraft",
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                            LavenderTagBadge(text = "26.3")
                        }
                    }
                }

                AnimatedContent(
                    targetState = MINECRAFT_SPLASH_QUOTES[quoteIndex],
                    transitionSpec = {
                        (slideInVertically(tween(380)) { h -> h / 2 } + fadeIn(tween(380)))
                            .togetherWith(
                                slideOutVertically(tween(300)) { h -> -h / 2 } + fadeOut(tween(280))
                            )
                    },
                    modifier = Modifier
                        .fillMaxWidth(0.92f)
                        .animatedPressScale {
                            quoteIndex = (quoteIndex + 1) % MINECRAFT_SPLASH_QUOTES.size
                            wallpaperIndex = (wallpaperIndex + 1) % totalCount.coerceAtLeast(1)
                        },
                    label = "MinecraftSplashQuote"
                ) { quote ->
                    Text(
                        text = "“$quote”",
                        fontSize = 13.sp,
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
private fun DashboardQuickIconPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF181E22).copy(alpha = 0.72f),
        modifier = Modifier.animatedPressScale(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = SkyBlueAccent,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White
            )
        }
    }
}

@Composable
private fun CleanVersionsViewport(
    context: android.content.Context,
    installStatus: AssetInstallStatus,
    onStartDownload: () -> Unit,
    onPauseDownload: () -> Unit,
    onResumeDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onSmartRepairAssets: () -> Unit
) {
    var logExpanded by remember { mutableStateOf(true) }
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

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

    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFFFB74D)) },
            title = { Text("Batalkan Download?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Proses download aset yang sedang berjalan akan dihentikan dan file sementara yang sedang diunduh akan langsung dihapus.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirmDialog = false
                        onCancelDownload()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Ya, Batalkan & Hapus", color = Color.White, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirmDialog = false }) {
                    Text("Lanjutkan Download", fontSize = 12.sp)
                }
            }
        )
    }

    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CardSlateBg,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Game & Assets Manager", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                if (!installStatus.running) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = SubRailCapsuleActive,
                        modifier = Modifier.animatedPressScale(onClick = onSmartRepairAssets)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Build, contentDescription = null, tint = SkyBlueAccent, modifier = Modifier.size(14.dp))
                            Text("Smart Repair (Check Missing/Corrupt)", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(18.dp),
                color = CardRowBg,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            MinecraftGrassCubeIcon(size = 42.dp)
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Minecraft 26.3",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = Color.White
                                    )
                                    LavenderTagBadge(text = "Rust Native")
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = if (installStatus.ready) "All game files, worldgen tables, and 60 touch controls installed"
                                    else if (installStatus.running) installStatus.stage
                                    else "Download official game & assets (Pause / Resume / Background supported)",
                                    fontSize = 12.sp,
                                    color = if (installStatus.ready) Color(0xFF81C784) else MutedSubtitleText
                                )
                            }
                        }

                        Spacer(Modifier.width(8.dp))

                        if (installStatus.running) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = SkyBlueAccent,
                                    modifier = Modifier.animatedPressScale {
                                        if (installStatus.paused) onResumeDownload() else onPauseDownload()
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (installStatus.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                            contentDescription = null,
                                            tint = SkyBlueDarkText,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text(
                                            text = if (installStatus.paused) "Resume" else "Pause",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SkyBlueDarkText
                                        )
                                    }
                                }
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFF3A2326),
                                    modifier = Modifier.animatedPressScale { showCancelConfirmDialog = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(16.dp))
                                        Text("Cancel", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFFF8A80))
                                    }
                                }
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SkyBlueAccent,
                                modifier = Modifier.animatedPressScale(
                                    onClick = if (installStatus.ready) onSmartRepairAssets else onStartDownload
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (installStatus.ready) Icons.Default.Verified else Icons.Default.Download,
                                        contentDescription = null,
                                        tint = SkyBlueDarkText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = if (installStatus.ready) "Verify" else "Download",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SkyBlueDarkText
                                    )
                                }
                            }
                        }
                    }

                    if (installStatus.running || installStatus.done > 0L || installStatus.error.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = installStatus.stage,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (installStatus.paused) Color(0xFFFFB74D) else SkyBlueAccent
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
                                    color = MutedSubtitleText
                                )
                            }
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                color = SkyBlueAccent,
                                trackColor = Color(0xFF33424B),
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
                                    color = MutedSubtitleText,
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
                        shape = RoundedCornerShape(14.dp),
                        color = Color(0xFF1A2024),
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
                                        tint = SkyBlueAccent
                                    )
                                    Text(
                                        text = "Download Process Log (URL & Speed)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                                        modifier = Modifier.height(26.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = SkyBlueAccent, modifier = Modifier.size(13.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Copy", fontSize = 11.sp, color = SkyBlueAccent)
                                    }

                                    Icon(
                                        Icons.Default.ExpandMore,
                                        contentDescription = if (logExpanded) "Minimize" else "Expand",
                                        tint = Color.White,
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
                                    "Tap 'Download' or 'INSTALL' to begin. Supports Pause, Resume, Cancel & Background Notification."
                                }
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 75.dp, max = 155.dp)
                                        .padding(start = 10.dp, end = 10.dp, bottom = 10.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF0E1215))
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

/**
 * Store Viewport styled after `429665.jpg` & `429667.jpg`:
 * - Left Sub-Navigation Rail (`Mods` from playhorizon.dev vs `Resource Packs` from Modrinth)
 * - Top Pill Search Bar + Refresh button
 * - Item cards with 44dp rounded-square icon, Title + Lavender Version/Source badges, Subtitle, Info button, and Install button!
 */
@Composable
private fun CleanStoreViewport(
    profileSlot: Int,
    logEnabled: Boolean
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(StoreCategory.HORIZON_MODS) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var catalogItems by remember { mutableStateOf<List<StoreCatalogItem>>(emptyList()) }
    var storeProgress by remember { mutableStateOf(StoreDownloadProgress()) }
    var pendingIncompatibleItem by remember { mutableStateOf<StoreCatalogItem?>(null) }
    var infoModalItem by remember { mutableStateOf<StoreCatalogItem?>(null) }
    var showCancelStoreDialog by remember { mutableStateOf(false) }

    LaunchedEffect(selectedCategory, searchQuery, refreshTrigger) {
        isLoading = true
        val fetched = withContext(Dispatchers.IO) {
            when (selectedCategory) {
                StoreCategory.HORIZON_MODS -> HorizonNativeBridge.fetchHorizonModsCatalog(searchQuery)
                StoreCategory.MODRINTH_PACKS -> HorizonNativeBridge.fetchModrinthResourcePacks(searchQuery)
            }
        }
        catalogItems = fetched
        isLoading = false
    }

    val startItemDownload = { item: StoreCatalogItem ->
        HorizonNativeBridge.downloadAndInstallStoreItem(
            context = context,
            item = item,
            profileSlot = profileSlot,
            logEnabled = logEnabled,
            onProgress = { prog -> storeProgress = prog },
            onComplete = { _, msg ->
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    pendingIncompatibleItem?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingIncompatibleItem = null },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFFFB74D)) },
            title = { Text("Peringatan Versi Minecraft", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "Item '${item.title}' tercatat untuk Minecraft versi ${item.versionBadge} dan belum terverifikasi resmi untuk Minecraft 26.3.\n\nApakah Anda ingin tetap mengunduh dan memasangnya?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val target = item
                        pendingIncompatibleItem = null
                        startItemDownload(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlueAccent)
                ) {
                    Text("Tetap Download", fontSize = 12.sp, color = SkyBlueDarkText, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingIncompatibleItem = null }) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }

    infoModalItem?.let { item ->
        AlertDialog(
            onDismissRequest = { infoModalItem = null },
            title = { Text(item.title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Author: ${item.author}", fontSize = 12.sp)
                    Text("Version: v${item.version} (MC ${item.supportedMcVersions.joinToString(", ")})", fontSize = 12.sp)
                    Text("File: ${item.fileName}", fontSize = 12.sp)
                    Text(item.summary, fontSize = 12.sp, color = MutedSubtitleText)
                }
            },
            confirmButton = {
                TextButton(onClick = { infoModalItem = null }) { Text("Close") }
            }
        )
    }

    if (showCancelStoreDialog) {
        AlertDialog(
            onDismissRequest = { showCancelStoreDialog = false },
            icon = { Icon(Icons.Default.Cancel, contentDescription = null, tint = Color(0xFFFF6E6E)) },
            title = { Text("Batalkan Download Store?", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "File '${storeProgress.activeTitle}' yang sedang diunduh akan dihentikan dan langsung dihapus.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelStoreDialog = false
                        HorizonNativeBridge.storeDownloadCancelled.set(true)
                        HorizonNativeBridge.storeDownloadPaused.set(false)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Text("Ya, Batalkan & Hapus", color = Color.White, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelStoreDialog = false }) {
                    Text("Lanjutkan", fontSize = 12.sp)
                }
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Sub-Navigation Rail (matching `429665.jpg` & `429667.jpg`)
        ZalithSubCategoryRail(
            items = StoreCategory.entries,
            selectedItem = selectedCategory,
            itemLabel = { if (it == StoreCategory.HORIZON_MODS) "Mods" else "Resource Packs" },
            itemIcon = { if (it == StoreCategory.HORIZON_MODS) Icons.Default.Extension else Icons.Default.Brush },
            onSelect = { selectedCategory = it }
        )

        // Right Main Rounded Container (`429665.jpg` style)
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = CardSlateBg,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Search & Action Toolbar (`429665.jpg` style)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "Filter",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )

                    // Pill-shaped Search Field
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = CardRowBg,
                        modifier = Modifier
                            .weight(1f)
                            .height(36.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = if (selectedCategory == StoreCategory.HORIZON_MODS) "Search playhorizon.dev/mods..."
                                    else "Search Modrinth Resource Packs...",
                                    fontSize = 12.sp,
                                    color = MutedSubtitleText
                                )
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                cursorBrush = SolidColor(SkyBlueAccent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    Text(
                        text = if (selectedCategory == StoreCategory.HORIZON_MODS) "Source: playhorizon.dev" else "Source: Modrinth",
                        fontSize = 11.sp,
                        color = MutedSubtitleText
                    )

                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color.White,
                        modifier = Modifier
                            .size(20.dp)
                            .animatedPressScale { refreshTrigger += 1 }
                    )
                }

                // Active Store Download Bar with Pause / Resume / Cancel
                AnimatedVisibility(visible = storeProgress.running) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = SubRailCapsuleActive,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Downloading ${storeProgress.activeTitle} (${storeProgress.percent}%)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "${storeProgress.speedText} • ${storeProgress.statusText}",
                                        fontSize = 10.sp,
                                        color = MutedSubtitleText,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = SkyBlueAccent,
                                        modifier = Modifier.animatedPressScale {
                                            val nextPaused = !HorizonNativeBridge.storeDownloadPaused.get()
                                            HorizonNativeBridge.storeDownloadPaused.set(nextPaused)
                                        }
                                    ) {
                                        Text(
                                            text = if (storeProgress.paused) "Resume" else "Pause",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SkyBlueDarkText,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF3A2326),
                                        modifier = Modifier.animatedPressScale { showCancelStoreDialog = true }
                                    ) {
                                        Text(
                                            text = "Cancel",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Color(0xFFFF8A80),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                            LinearProgressIndicator(
                                progress = { (storeProgress.percent / 100f).coerceIn(0f, 1f) },
                                color = SkyBlueAccent,
                                trackColor = Color(0xFF263238),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                            )
                        }
                    }
                }

                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SkyBlueAccent, modifier = Modifier.size(28.dp))
                    }
                } else if (catalogItems.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No items found.", fontSize = 12.sp, color = MutedSubtitleText)
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(catalogItems, key = { it.id }) { item ->
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CardRowBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Left 44dp rounded-square icon badge (`429665.jpg` style)
                                    StoreOrFileItemIconBadge(
                                        title = item.title,
                                        isMod = item.category == StoreCategory.HORIZON_MODS,
                                        isWorld = false
                                    )

                                    Spacer(Modifier.width(12.dp))

                                    // Middle Title + Lavender Badges + File Name / Summary (`429665.jpg` style)
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(3.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = item.title,
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            LavenderTagBadge(
                                                text = if (item.category == StoreCategory.HORIZON_MODS) "Horizon" else "Modrinth"
                                            )
                                            LavenderTagBadge(text = item.versionBadge)
                                        }
                                        Text(
                                            text = "${item.summary} (${item.fileName})",
                                            fontSize = 11.sp,
                                            color = MutedSubtitleText,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Spacer(Modifier.width(10.dp))

                                    // Right Action Icons: Info ⓘ + Install Button
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Info,
                                            contentDescription = "Info",
                                            tint = Color(0xFFD5DEE5),
                                            modifier = Modifier
                                                .size(20.dp)
                                                .animatedPressScale { infoModalItem = item }
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = SkyBlueAccent,
                                            modifier = Modifier.animatedPressScale(enabled = !storeProgress.running) {
                                                if (item.isCompatibleWith26_3) {
                                                    startItemDownload(item)
                                                } else {
                                                    pendingIncompatibleItem = item
                                                }
                                            }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Download,
                                                    contentDescription = "Install",
                                                    tint = SkyBlueDarkText,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = "Install",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = SkyBlueDarkText
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
        }
    }
}

/**
 * 44dp Rounded-Square Icon Badge (`429665.jpg`, `429666.jpg`, `429667.jpg` left item icon style).
 */
@Composable
private fun StoreOrFileItemIconBadge(
    title: String,
    isMod: Boolean,
    isWorld: Boolean
) {
    val bgBrush = when {
        isWorld -> Brush.linearGradient(listOf(Color(0xFF43A047), Color(0xFF5D4037)))
        isMod -> Brush.linearGradient(listOf(Color(0xFF3949AB), Color(0xFF26A69A)))
        else -> Brush.linearGradient(listOf(Color(0xFF5E35B1), Color(0xFF00897B)))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bgBrush)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
    ) {
        if (isWorld) {
            MinecraftGrassCubeIcon(size = 34.dp)
        } else {
            val initials = title.trim().take(2).uppercase()
            Text(
                text = initials,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }
    }
}

/**
 * Manage Versions / Files Viewport (`429665.jpg`, `429666.jpg`, `429667.jpg` exact layout!):
 * - Left Sub-Rail: `Mods`, `Saves`, `Resource Packs`, `Shader Packs`
 * - Top Action Toolbar: Filter, Sort, Pill Search Bar, `[ ] Available` checkbox, `+ Import`, `↓ Download` (switches to Store), `↻` Refresh
 * - Item Row Cards with 44dp icon, Title + Lavender badges (`26.3`, `Horizon`, `Survival Mode`), Subtitle (`File Name:` / `Last played:`),
 *   and right action icons (`↻`, sky-blue `☑` Checkbox, `ⓘ` Info, `🗑` Delete)!
 */
@Composable
private fun CleanFilesViewport(
    profileSlot: Int,
    logEnabled: Boolean,
    onNavigateToStore: () -> Unit
) {
    val context = LocalContext.current
    val home = remember(profileSlot) {
        HorizonNativeBridge.getProfileHome(HorizonNativeBridge.ensureHomeDirectories(context), profileSlot)
    }
    var currentFolder by remember { mutableStateOf("mods") }
    var searchQuery by remember { mutableStateOf("") }
    var filterEnabledOnly by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableIntStateOf(0) }
    var selectedInfoFile by remember { mutableStateOf<File?>(null) }

    val categories = listOf(
        Triple("mods", "Mods", Icons.Default.Extension),
        Triple("saves", "Saves", Icons.Default.Public),
        Triple("resourcepacks", "Resource Packs", Icons.Default.Brush),
        Triple("shaderpacks", "Shader Packs", Icons.Default.Lightbulb)
    )

    val dirFile = File(home, currentFolder).apply { mkdirs() }
    val rawList = remember(currentFolder, refreshCount, profileSlot) {
        dirFile.listFiles()?.toList().orEmpty()
    }

    val filteredList = remember(rawList, searchQuery, filterEnabledOnly, currentFolder, refreshCount) {
        rawList.filter { file ->
            val matchesQuery = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
            val matchesEnabled = if (!filterEnabledOnly || currentFolder == "saves") true
            else HorizonNativeBridge.isContentItemEnabled(context, currentFolder, file.name, profileSlot)
            matchesQuery && matchesEnabled
        }
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

    selectedInfoFile?.let { file ->
        val dateStr = SimpleDateFormat("MMM dd, yyyy, h:mm:ss a", Locale.US).format(Date(file.lastModified()))
        AlertDialog(
            onDismissRequest = { selectedInfoFile = null },
            title = { Text(file.name, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Category: $currentFolder", fontSize = 12.sp)
                    Text("Size: ${file.length() / 1024} KB (${file.length()} bytes)", fontSize = 12.sp)
                    Text("Last Modified: $dateStr", fontSize = 12.sp)
                    Text("Path: ${file.absolutePath}", fontSize = 11.sp, color = MutedSubtitleText)
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedInfoFile = null }) { Text("Close") }
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Sub-Navigation Rail (`Mods`, `Saves`, `Resource Packs`, `Shader Packs`)
        ZalithSubCategoryRail(
            items = categories,
            selectedItem = categories.first { it.first == currentFolder },
            itemLabel = { it.second },
            itemIcon = { it.third },
            onSelect = { currentFolder = it.first }
        )

        // Right Main Rounded Slate Container (`429665.jpg`, `429666.jpg`, `429667.jpg` style)
        Surface(
            shape = RoundedCornerShape(22.dp),
            color = CardSlateBg,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Top Action Toolbar (`Filter  Sort  [ Search... ]  [ ] Available  + Import  ↓ Download  ↻`)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.FilterAlt,
                        contentDescription = "Filter",
                        tint = Color.White,
                        modifier = Modifier
                            .size(19.dp)
                            .animatedPressScale { filterEnabledOnly = !filterEnabledOnly }
                    )
                    Icon(
                        imageVector = Icons.Default.Sort,
                        contentDescription = "Sort",
                        tint = Color.White,
                        modifier = Modifier
                            .size(19.dp)
                            .animatedPressScale { refreshCount += 1 }
                    )

                    // Pill Search Input (`429665.jpg`)
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = CardRowBg,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 14.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (searchQuery.isEmpty()) {
                                Text("Search", fontSize = 12.sp, color = MutedSubtitleText)
                            }
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = { searchQuery = it },
                                singleLine = true,
                                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                cursorBrush = SolidColor(SkyBlueAccent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // `[ ] Available` checkbox (`429666.jpg` & `429667.jpg`)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.animatedPressScale { filterEnabledOnly = !filterEnabledOnly }
                    ) {
                        Checkbox(
                            checked = filterEnabledOnly,
                            onCheckedChange = { filterEnabledOnly = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = SkyBlueAccent,
                                checkmarkColor = SkyBlueDarkText,
                                uncheckedColor = MutedSubtitleText
                            ),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Available", fontSize = 11.sp, color = Color.White)
                    }

                    // `+ Import` action button
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .animatedPressScale { picker.launch("*/*") }
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "Import", tint = Color.White, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Import", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }

                    // `↓ Download` action button (opens Store tab!)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .animatedPressScale(onClick = onNavigateToStore)
                    ) {
                        Icon(Icons.Default.Download, contentDescription = "Download", tint = Color.White, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(3.dp))
                        Text("Download", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                    }

                    // `↻` Refresh icon button
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = Color.White,
                        modifier = Modifier
                            .size(19.dp)
                            .animatedPressScale { refreshCount += 1 }
                    )
                }

                // Animated List Content
                AnimatedContent(
                    targetState = currentFolder,
                    transitionSpec = {
                        (fadeIn(tween(200)) + slideInVertically(tween(200)) { h -> h / 8 })
                            .togetherWith(fadeOut(tween(140)))
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "FilesFolderTransition"
                ) { folder ->
                    if (filteredList.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "No items in $folder yet — tap '+ Import' or '↓ Download' to add content.",
                                    fontSize = 12.sp,
                                    color = MutedSubtitleText
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredList, key = { it.absolutePath }) { file ->
                                val isToggleable = folder in listOf("mods", "shaderpacks", "resourcepacks")
                                val isEnabled = remember(file.name, refreshCount, folder) {
                                    if (isToggleable) {
                                        HorizonNativeBridge.isContentItemEnabled(context, folder, file.name, profileSlot)
                                    } else true
                                }
                                val displayTitle = file.nameWithoutExtension
                                    .replace("-", " ")
                                    .replace("_", " ")
                                val formattedDate = remember(file.lastModified()) {
                                    SimpleDateFormat("MMM dd, yyyy, h:mm:ss a", Locale.US).format(Date(file.lastModified()))
                                }

                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = CardRowBg,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        StoreOrFileItemIconBadge(
                                            title = displayTitle,
                                            isMod = folder == "mods",
                                            isWorld = folder == "saves"
                                        )

                                        Spacer(Modifier.width(12.dp))

                                        Column(
                                            modifier = Modifier.weight(1f),
                                            verticalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = displayTitle,
                                                    fontSize = 14.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Color.White,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                when (folder) {
                                                    "mods" -> {
                                                        LavenderTagBadge(text = "Horizon")
                                                        LavenderTagBadge(text = "26.3")
                                                    }
                                                    "saves" -> {
                                                        LavenderTagBadge(text = "26.3")
                                                        LavenderTagBadge(text = "Survival Mode")
                                                    }
                                                    "resourcepacks" -> {
                                                        LavenderTagBadge(text = "26.3")
                                                    }
                                                    "shaderpacks" -> {
                                                        LavenderTagBadge(text = "Shader")
                                                    }
                                                }
                                            }
                                            Text(
                                                text = if (folder == "saves") "Last played: $formattedDate"
                                                else "File Name: ${file.name}",
                                                fontSize = 11.sp,
                                                color = MutedSubtitleText,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }

                                        Spacer(Modifier.width(10.dp))

                                        // Right Action Icons matching `429665.jpg` (`↻`, `☑`, `ⓘ`, `🗑`)
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = "Reload",
                                                tint = Color(0xFFD5DEE5),
                                                modifier = Modifier
                                                    .size(19.dp)
                                                    .animatedPressScale { refreshCount += 1 }
                                            )

                                            if (isToggleable) {
                                                Checkbox(
                                                    checked = isEnabled,
                                                    onCheckedChange = {
                                                        HorizonNativeBridge.toggleContentItem(context, folder, file.name, profileSlot, logEnabled)
                                                        refreshCount += 1
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = SkyBlueAccent,
                                                        checkmarkColor = SkyBlueDarkText,
                                                        uncheckedColor = MutedSubtitleText
                                                    ),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }

                                            Icon(
                                                imageVector = Icons.Outlined.Info,
                                                contentDescription = "Info",
                                                tint = Color(0xFFD5DEE5),
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .animatedPressScale { selectedInfoFile = file }
                                            )

                                            Icon(
                                                imageVector = Icons.Outlined.Delete,
                                                contentDescription = "Delete",
                                                tint = Color(0xFFD5DEE5),
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .animatedPressScale {
                                                        file.deleteRecursively()
                                                        refreshCount += 1
                                                    }
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
    }
}

/**
 * Settings Viewport matching `429668.jpg` (`Settings - Renderer` / `Game` / `Controls` / `Gamepad` / `Launcher`):
 * - Left Sub-Navigation Rail with capsule active pill on the icon (`Renderer`, `Game`, `Controls`, `Gamepad`, `Launcher`)
 * - Right Scrollable Column of full-width `ZalithSettingDropdownCard`, `ZalithSettingSliderCard` (with `< 50% >` sky-blue pill),
 *   and `ZalithSettingToggleCard`!
 */
@Composable
private fun CleanSettingsViewport(
    config: HorizonConfigState,
    onConfigChange: (HorizonConfigState) -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    var selectedSection by remember { mutableStateOf(SettingsSubCategory.RENDERER) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Sub-Navigation Rail (`Renderer`, `Game`, `Controls`, `Gamepad`, `Launcher`)
        ZalithSubCategoryRail(
            items = SettingsSubCategory.entries,
            selectedItem = selectedSection,
            itemLabel = { it.label },
            itemIcon = { it.icon },
            onSelect = { selectedSection = it }
        )

        // Right Settings Cards Column with smooth `AnimatedContent` transition (`429668.jpg` style)
        AnimatedContent(
            targetState = selectedSection,
            transitionSpec = {
                (fadeIn(tween(210)) + slideInVertically(tween(210)) { h -> h / 7 })
                    .togetherWith(fadeOut(tween(150)))
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            label = "SettingsSubSectionTransition"
        ) { section ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (section) {
                    SettingsSubCategory.RENDERER -> {
                        ZalithSettingDropdownCard(
                            title = "Graphics API",
                            description = "Set the graphics API used by Minecraft 26.3+",
                            selectedItem = config.renderer,
                            options = HorizonRendererMode.entries,
                            itemLabel = { it.label },
                            onSelect = { onConfigChange(config.copy(renderer = it)) }
                        )

                        ZalithSettingDropdownCard(
                            title = "Resolution Rule",
                            description = "Choose how the game window resolution and screen orientation are handled",
                            selectedItem = config.orientation,
                            options = HorizonOrientation.entries,
                            itemLabel = { "Percentage • ${it.label}" },
                            onSelect = { onConfigChange(config.copy(orientation = it)) }
                        )

                        ZalithSettingSliderCard(
                            title = "Resolution Scale",
                            description = "Lower values improve performance; higher values enhance graphics quality. Adjust according to your needs",
                            value = config.renderScalePercent,
                            valueRange = 25..100,
                            step = 5,
                            valueSuffix = "%",
                            onValueChange = { onConfigChange(config.copy(renderScalePercent = it)) }
                        )

                        ZalithSettingToggleCard(
                            title = "VSync (Vertical Synchronization)",
                            description = "Synchronize frame rate with display refresh rate in options.txt to prevent screen tearing",
                            checked = config.mcVsync,
                            onCheckedChange = { onConfigChange(config.copy(mcVsync = it)) }
                        )
                    }

                    SettingsSubCategory.GAME -> {
                        ZalithSettingDropdownCard(
                            title = "Game Profile Slot",
                            description = "Select which isolated profile directory (saves, mods, options.txt) is active",
                            selectedItem = config.profile,
                            options = listOf(0, 1, 2, 3),
                            itemLabel = { if (it == 0) "Default (Profile 0)" else "Profile $it" },
                            onSelect = { onConfigChange(config.copy(profile = it)) }
                        )

                        ZalithSettingSliderCard(
                            title = "Render Distance",
                            description = "Number of visible world chunks rendered around the player (synced with options.txt)",
                            value = config.mcRenderDistance,
                            valueRange = 2..32,
                            step = 1,
                            valueSuffix = " Chunks",
                            onValueChange = { onConfigChange(config.copy(mcRenderDistance = it)) }
                        )

                        ZalithSettingSliderCard(
                            title = "Simulation Distance",
                            description = "Number of active entity and block-tick chunks around the player (synced with options.txt)",
                            value = config.mcSimulationDistance,
                            valueRange = 5..32,
                            step = 1,
                            valueSuffix = " Chunks",
                            onValueChange = { onConfigChange(config.copy(mcSimulationDistance = it)) }
                        )

                        ZalithSettingSliderCard(
                            title = "Max Framerate Limit",
                            description = "Maximum FPS cap inside Minecraft (260 = Unlimited, synced with options.txt)",
                            value = config.mcMaxFps,
                            valueRange = 30..260,
                            step = 10,
                            valueSuffix = " FPS",
                            onValueChange = { onConfigChange(config.copy(mcMaxFps = it)) }
                        )

                        ZalithSettingDropdownCard(
                            title = "GUI Scale",
                            description = "In-game HUD and inventory interface scale factor (synced with options.txt)",
                            selectedItem = config.mcGuiScale,
                            options = listOf(0, 1, 2, 3, 4),
                            itemLabel = { if (it == 0) "Auto" else "${it}x" },
                            onSelect = { onConfigChange(config.copy(mcGuiScale = it)) }
                        )
                    }

                    SettingsSubCategory.CONTROLS -> {
                        ZalithSettingDropdownCard(
                            title = "Input Mode",
                            description = "Select primary input host for Minecraft 26.3 runtime",
                            selectedItem = config.inputHost,
                            options = HorizonInputHost.entries,
                            itemLabel = { it.label },
                            onSelect = { onConfigChange(config.copy(inputHost = it)) }
                        )

                        ZalithSettingToggleCard(
                            title = "On-Screen Touch Keypad",
                            description = "Enable movable virtual touch keypad overlay inside Minecraft (horizon-keypad.json)",
                            checked = config.keypadControls,
                            onCheckedChange = { onConfigChange(config.copy(keypadControls = it)) }
                        )

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = CardSlateBg,
                            modifier = Modifier
                                .fillMaxWidth()
                                .animatedPressScale { HorizonNativeBridge.resetTouchKeypadLayout(context, config) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 18.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Reset Touch Keypad Layout", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    Text("Restore on-screen touch button positions in horizon-keypad.json back to default", fontSize = 12.sp, color = MutedSubtitleText)
                                }
                                Icon(Icons.Default.Refresh, contentDescription = null, tint = SkyBlueAccent)
                            }
                        }
                    }

                    SettingsSubCategory.GAMEPAD -> {
                        ZalithSettingDropdownCard(
                            title = "Controller & Gamepad Routing",
                            description = "Configure hardware controller mode and button mapping behavior",
                            selectedItem = config.inputHost,
                            options = listOf(HorizonInputHost.AUTO, HorizonInputHost.CONTROLLER, HorizonInputHost.KEYBOARD_MOUSE),
                            itemLabel = { it.label },
                            onSelect = { onConfigChange(config.copy(inputHost = it)) }
                        )

                        ZalithSettingToggleCard(
                            title = "Audio Safe Mode",
                            description = "Disable OpenAL event callbacks if Bluetooth controllers or headsets cause audio stutter",
                            checked = config.disableAudioSafeMode,
                            onCheckedChange = { onConfigChange(config.copy(disableAudioSafeMode = it)) }
                        )
                    }

                    SettingsSubCategory.LAUNCHER -> {
                        ZalithSettingDropdownCard(
                            title = "Theme Palette",
                            description = "Choose the color scheme used across HorizonZ Launcher",
                            selectedItem = config.themePalette,
                            options = ZalithThemePalette.entries,
                            itemLabel = { it.label },
                            onSelect = { onConfigChange(config.copy(themePalette = it)) }
                        )

                        ZalithSettingToggleCard(
                            title = "Material You Dynamic Color (Android 12+)",
                            description = "Adapt launcher accent colors to your system wallpaper automatically",
                            checked = config.useDynamicColor,
                            onCheckedChange = { onConfigChange(config.copy(useDynamicColor = it)) }
                        )

                        ZalithSettingToggleCard(
                            title = "System & Game Runtime Logs",
                            description = "Record launcher, installer, and Minecraft runtime logs for troubleshooting",
                            checked = config.enableSystemLog,
                            onCheckedChange = { onConfigChange(config.copy(enableSystemLog = it)) }
                        )

                        ZalithSettingToggleCard(
                            title = "Record Performance Telemetry",
                            description = "Write frame-time and GPU benchmark metrics to performance.json during gameplay",
                            checked = config.recordPerformance,
                            onCheckedChange = { onConfigChange(config.copy(recordPerformance = it)) }
                        )

                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = CardSlateBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text("Quick Server, Proxy & Diagnostics", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
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
                                        label = { Text("HTTP/SOCKS5 Proxy", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { HorizonNativeBridge.exportPerformanceReport(context, config.profile) }) {
                                        Icon(Icons.Default.Analytics, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Export Perf", fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = { HorizonNativeBridge.openSystemFilesApp(context) }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Open Folder", fontSize = 11.sp)
                                    }
                                    OutlinedButton(onClick = onOpenLogs) {
                                        Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("System Log", fontSize = 11.sp)
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
private fun ZalithLaunchSidebar(
    activeAccount: ZalithAccount,
    installStatus: AssetInstallStatus,
    onOpenAccountDialog: () -> Unit,
    onOpenVersions: () -> Unit,
    onLaunchOrDownload: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = CardSlateBg,
        modifier = Modifier
            .fillMaxHeight()
            .width(182.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(11.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = CardRowBg,
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
                            .size(32.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SubRailCapsuleActive)
                    ) {
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = SkyBlueAccent,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = activeAccount.username,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = activeAccount.accountType.badge,
                            fontSize = 11.sp,
                            color = MutedSubtitleText
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = CardRowBg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .animatedPressScale(onClick = onOpenVersions)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Minecraft 26.3", fontWeight = FontWeight.SemiBold, fontSize = 12.sp, color = Color.White)
                            Text(
                                text = if (installStatus.ready) "Ready"
                                else if (installStatus.paused) "Paused (${installStatus.percent}%)"
                                else if (installStatus.running) "${installStatus.percent}% (${installStatus.speedText.ifBlank { "Downloading" }})"
                                else "Needs Download",
                                fontSize = 11.sp,
                                color = if (installStatus.ready) Color(0xFF81C784)
                                else if (installStatus.paused) Color(0xFFFFB74D)
                                else SkyBlueAccent,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MutedSubtitleText,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (installStatus.running) SubRailCapsuleActive else SkyBlueAccent,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .animatedPressScale(onClick = onLaunchOrDownload)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (installStatus.ready) Icons.Default.PlayArrow else Icons.Default.Download,
                            contentDescription = null,
                            tint = if (installStatus.running) SkyBlueAccent else SkyBlueDarkText
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (installStatus.paused) "PAUSED"
                            else if (installStatus.running) "${installStatus.percent}%"
                            else if (installStatus.ready) "PLAY"
                            else "INSTALL",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 15.sp,
                            color = if (installStatus.running) SkyBlueAccent else SkyBlueDarkText
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
                Text("System & Game Log (v${HorizonNativeBridge.INSTALLED_LAUNCHER_VERSION})", style = MaterialTheme.typography.titleMedium)
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
                        color = if (acc.isActive) SubRailCapsuleActive else CardRowBg,
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
                                color = Color.White,
                                modifier = Modifier.weight(1f)
                            )
                            if (accounts.size > 1) {
                                IconButton(
                                    onClick = { onDeleteAccount(acc.id) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
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
