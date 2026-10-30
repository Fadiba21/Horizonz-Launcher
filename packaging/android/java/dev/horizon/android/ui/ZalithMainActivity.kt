@file:OptIn(ExperimentalMaterial3Api::class)

package dev.horizon.android.ui

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.horizon.android.R
import dev.horizon.android.bridge.*
import dev.horizon.android.model.*
import dev.horizon.android.ui.theme.HorizonZalithTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    R.drawable.mc_wall_12,
    R.drawable.mc_wall_13,
    R.drawable.mc_wall_14,
    R.drawable.mc_wall_15,
    R.drawable.mc_wall_16,
    R.drawable.mc_wall_17,
    R.drawable.mc_wall_18
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

enum class AccountSubCategory(val label: String, val icon: ImageVector) {
    SKIN_STUDIO("Skin Studio", Icons.Default.Checkroom),
    PROFILES("Accounts", Icons.Default.People)
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
            val scope = rememberCoroutineScope()
            var config by remember { mutableStateOf<HorizonConfigState>(HorizonNativeBridge.loadSavedConfig(context)) }
            var accounts by remember { mutableStateOf<List<ZalithAccount>>(HorizonNativeBridge.loadSavedAccounts(context)) }
            val activeAccount = accounts.firstOrNull { it.isActive } ?: accounts.first()

            var isLauncherHibernated by remember { mutableStateOf(false) }
            var isLaunchingSplash by remember { mutableStateOf(false) }
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
                            isLaunchingSplash = false
                            config = HorizonNativeBridge.loadSavedConfig(context)
                            accounts = HorizonNativeBridge.loadSavedAccounts(context)
                        }
                        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                            // Do NOT hibernate on generic ON_PAUSE (e.g. when Photo Picker opens!),
                            // otherwise rememberLauncherForActivityResult gets unmounted!
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
                            isHibernated = isLauncherHibernated || isLaunchingSplash,
                            onConfigChange = { updated ->
                                config = updated
                                HorizonNativeBridge.saveConfig(context, updated, activeAccount)
                            },
                            onAccountsChange = { updatedList ->
                                accounts = updatedList
                                HorizonNativeBridge.saveAccounts(context, updatedList, config)
                            },
                            onEnterGameHibernation = {
                                isLaunchingSplash = true
                                scope.launch {
                                    delay(320L)
                                    isLauncherHibernated = true
                                    HorizonNativeBridge.launchNativeGame(context, config, activeAccount)
                                }
                            }
                        )

                        AnimatedVisibility(
                            visible = showUpdateBanner && releaseUpdateInfo != null && !isLauncherHibernated && !isLaunchingSplash,
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
    pressedScale: Float = 0.96f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) pressedScale else 1f,
        animationSpec = tween(durationMillis = 120, easing = FastOutSlowInEasing),
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
 * Pure Black Minecraft Java Edition Style Loading Splash Screen ("HORIZONZ")
 * Displayed immediately when pressing PLAY and seamlessly continued by HorizonActivity's native overlay!
 */
@Composable
fun HorizonZBlackSplashOverlay() {
    var progress by remember { mutableFloatStateOf(0.14f) }
    LaunchedEffect(Unit) {
        while (progress < 0.30f) {
            delay(30L)
            progress = (progress + 0.015f).coerceAtMost(0.30f)
        }
    }

    val glyphH = arrayOf(
        intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1,1), intArrayOf(1,1,1,1,1,1,1,1), intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1)
    )
    // Iconic 8x8 Minecraft Creeper Face as the letter 'O'!
    val glyphCreeperO = arrayOf(
        intArrayOf(1,1,3,1,1,3,1,1), intArrayOf(1,3,1,1,3,1,1,1), intArrayOf(1,2,2,1,1,2,2,1),
        intArrayOf(1,2,2,1,1,2,2,1), intArrayOf(1,1,1,2,2,1,1,1), intArrayOf(1,1,2,2,2,2,1,1),
        intArrayOf(1,1,2,2,2,2,1,1), intArrayOf(1,1,2,1,1,2,1,1)
    )
    val glyphR = arrayOf(
        intArrayOf(1,1,1,1,1,1,1,0), intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,1,1,1,1,1,0), intArrayOf(1,1,1,1,1,0,0,0), intArrayOf(1,1,0,0,1,1,0,0),
        intArrayOf(1,1,0,0,0,1,1,0), intArrayOf(1,1,0,0,0,0,1,1)
    )
    val glyphI = arrayOf(
        intArrayOf(1,1,1,1,1,1,1,1), intArrayOf(0,0,0,1,1,0,0,0), intArrayOf(0,0,0,1,1,0,0,0),
        intArrayOf(0,0,0,1,1,0,0,0), intArrayOf(0,0,0,1,1,0,0,0), intArrayOf(0,0,0,1,1,0,0,0),
        intArrayOf(0,0,0,1,1,0,0,0), intArrayOf(1,1,1,1,1,1,1,1)
    )
    val glyphZ = arrayOf(
        intArrayOf(1,1,1,1,1,1,1,1), intArrayOf(0,0,0,0,0,1,1,1), intArrayOf(0,0,0,0,1,1,1,0),
        intArrayOf(0,0,0,1,1,1,0,0), intArrayOf(0,0,1,1,1,0,0,0), intArrayOf(0,1,1,1,0,0,0,0),
        intArrayOf(1,1,1,0,0,0,0,0), intArrayOf(1,1,1,1,1,1,1,1)
    )
    val glyphO = arrayOf(
        intArrayOf(0,1,1,1,1,1,1,0), intArrayOf(1,1,1,0,0,1,1,1), intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1),
        intArrayOf(1,1,1,0,0,1,1,1), intArrayOf(0,1,1,1,1,1,1,0)
    )
    val glyphN = arrayOf(
        intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,1,0,0,0,1,1), intArrayOf(1,1,1,1,0,0,1,1),
        intArrayOf(1,1,0,1,1,0,1,1), intArrayOf(1,1,0,0,1,1,1,1), intArrayOf(1,1,0,0,0,1,1,1),
        intArrayOf(1,1,0,0,0,0,1,1), intArrayOf(1,1,0,0,0,0,1,1)
    )
    val word = remember { arrayOf(glyphH, glyphCreeperO, glyphR, glyphI, glyphZ, glyphO, glyphN, glyphZ) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val letterCols = 8
            val letterRows = 8
            val gapCols = 1
            val totalCols = word.size * letterCols + (word.size - 1) * gapCols
            val pxSize = (w * 0.50f / totalCols).coerceIn(4f, 6.2.dp.toPx())
            val logoW = totalCols * pxSize
            val logoH = letterRows * pxSize
            val startX = (w - logoW) / 2f
            val startY = (h * 0.41f) - (logoH / 2f)
            val depthOff = (pxSize * 0.48f).coerceAtLeast(2f)

            // Pass 1: 3D Blocky Bottom Extrusion Shadow
            for (l in word.indices) {
                val glyph = word[l]
                val lx = startX + l * (letterCols + gapCols) * pxSize
                val shadowColor = when (l) {
                    1 -> Color(0xFF1B4D14)
                    word.lastIndex -> Color(0xFF1D4E63)
                    else -> Color(0xFF2A3238)
                }
                for (r in 0 until letterRows) {
                    for (c in 0 until letterCols) {
                        if (glyph[r][c] != 0) {
                            drawRect(
                                color = shadowColor,
                                topLeft = Offset(lx + c * pxSize + depthOff * 0.6f, startY + r * pxSize + depthOff),
                                size = Size(pxSize + 0.5f, pxSize + 0.5f)
                            )
                        }
                    }
                }
            }

            // Pass 2: Foreground Block Letters + Creeper Face 'O'
            for (l in word.indices) {
                val glyph = word[l]
                val lx = startX + l * (letterCols + gapCols) * pxSize
                for (r in 0 until letterRows) {
                    for (c in 0 until letterCols) {
                        val cell = glyph[r][c]
                        if (cell == 0) continue
                        val blockColor = when {
                            l == 1 -> when (cell) {
                                2 -> Color(0xFF111712)
                                3 -> Color(0xFF72E052)
                                else -> if (r == 0) Color(0xFF6CE04E) else Color(0xFF49AC30)
                            }
                            l == word.lastIndex -> if (r == 0) Color(0xFFC2F0FF) else SkyBlueAccent
                            else -> if (r == 0) Color.White else if (r >= 6) Color(0xFFD0D8DE) else Color(0xFFF2F6F8)
                        }
                        drawRect(
                            color = blockColor,
                            topLeft = Offset(lx + c * pxSize, startY + r * pxSize),
                            size = Size(pxSize + 0.5f, pxSize + 0.5f)
                        )
                    }
                }
            }

            // Classic Minecraft Java Progress Bar
            val barW = (w * 0.54f).coerceAtMost(400.dp.toPx())
            val barH = 14.dp.toPx()
            val barLeft = (w - barW) / 2f
            val barTop = h * 0.72f
            val strokeW = 1.8.dp.toPx()
            val pad = 2.8.dp.toPx()

            drawRect(
                color = Color.White,
                topLeft = Offset(barLeft, barTop),
                size = Size(barW, barH),
                style = Stroke(width = strokeW)
            )
            val fillW = ((barW - pad * 2f) * progress).coerceAtLeast(0f)
            if (fillW > 0f) {
                drawRect(
                    color = Color.White,
                    topLeft = Offset(barLeft + pad, barTop + pad),
                    size = Size(fillW, barH - pad * 2f)
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 34.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Initializing HorizonZ Rust Engine (${(progress * 100).toInt()}%)",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFD5DEE5)
            )
        }
    }
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
        drawRect(color = Color(0xFF79553A), size = Size(w, h))
        drawRect(color = Color(0xFF569E3E), size = Size(w, h * 0.36f))
        drawRect(color = Color(0xFF74C454), size = Size(w, h * 0.12f))
        val step = w / 6f
        for (i in 0..5) {
            val fringeH = if (i % 2 == 0) h * 0.48f else h * 0.38f
            drawRect(
                color = Color(0xFF569E3E),
                topLeft = Offset(i * step, 0f),
                size = Size(step + 1f, fringeH)
            )
        }
        drawRect(color = Color(0xFF5E402A), topLeft = Offset(w * 0.2f, h * 0.62f), size = Size(step, step))
        drawRect(color = Color(0xFF91684A), topLeft = Offset(w * 0.6f, h * 0.55f), size = Size(step, step))
        drawRect(color = Color(0xFF5E402A), topLeft = Offset(w * 0.45f, h * 0.78f), size = Size(step, step))
    }
}

/**
 * Renders the 8x8 Face + Hat Overlay of a 64x64 Minecraft Skin with crisp nearest-neighbor pixel art!
 */
@Composable
fun MinecraftSkinHeadAvatar(
    skinBitmap: Bitmap,
    size: Dp = 34.dp,
    cornerRadius: Dp = 8.dp
) {
    val imgBmp = remember(skinBitmap) { skinBitmap.asImageBitmap() }
    Canvas(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Color(0xFF1A2228))
    ) {
        val dstW = this.size.width.toInt().coerceAtLeast(1)
        val dstH = this.size.height.toInt().coerceAtLeast(1)
        // Base Head Front (8,8) size 8x8
        drawImage(
            image = imgBmp,
            srcOffset = IntOffset(8, 8),
            srcSize = IntSize(8, 8),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None
        )
        // Hat / Outer Layer Front (40,8) size 8x8
        drawImage(
            image = imgBmp,
            srcOffset = IntOffset(40, 8),
            srcSize = IntSize(8, 8),
            dstOffset = IntOffset(0, 0),
            dstSize = IntSize(dstW, dstH),
            filterQuality = FilterQuality.None
        )
    }
}

/**
 * Renders the Full Standing 2D Minecraft Player Model (Front or Back View) from any 64x64 Skin Bitmap
 * with crisp nearest-neighbor pixel art, Hat/Jacket/Sleeve/Pants overlays, and Slim (3px) vs Wide (4px) arms!
 */
@Composable
fun MinecraftSkinFullBodyCanvas(
    skinBitmap: Bitmap,
    slimArms: Boolean,
    showBack: Boolean,
    modifier: Modifier = Modifier
) {
    val imgBmp = remember(skinBitmap) { skinBitmap.asImageBitmap() }
    Canvas(modifier = modifier) {
        val totalUnitsW = 16f
        val totalUnitsH = 32f
        val scale = minOf(size.width / (totalUnitsW + 4f), size.height / (totalUnitsH + 4f))
        val modelW = totalUnitsW * scale
        val modelH = totalUnitsH * scale
        val originX = (size.width - modelW) / 2f
        val originY = (size.height - modelH) / 2f

        // Subtle floor shadow oval under the character's feet
        drawOval(
            color = Color.Black.copy(alpha = 0.42f),
            topLeft = Offset(originX + 2f * scale, originY + 30.5f * scale),
            size = Size(12f * scale, 2.5f * scale)
        )

        fun part(sx: Int, sy: Int, sw: Int, sh: Int, dxUnits: Float, dyUnits: Float, dwUnits: Float, dhUnits: Float) {
            drawImage(
                image = imgBmp,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(sw, sh),
                dstOffset = IntOffset((originX + dxUnits * scale).toInt(), (originY + dyUnits * scale).toInt()),
                dstSize = IntSize((dwUnits * scale).toInt().coerceAtLeast(1), (dhUnits * scale).toInt().coerceAtLeast(1)),
                filterQuality = FilterQuality.None
            )
        }

        val armW = if (slimArms) 3 else 4
        val armWf = armW.toFloat()
        val leftArmDx = 12f
        val rightArmDx = if (slimArms) 1f else 0f

        if (!showBack) {
            // FRONT VIEW
            // Head (8,8,8,8) + Hat (40,8,8,8) at x=4, y=0
            part(8, 8, 8, 8, 4f, 0f, 8f, 8f)
            part(40, 8, 8, 8, 4f, 0f, 8f, 8f)

            // Torso (20,20,8,12) + Jacket (20,36,8,12) at x=4, y=8
            part(20, 20, 8, 12, 4f, 8f, 8f, 12f)
            part(20, 36, 8, 12, 4f, 8f, 8f, 12f)

            // Right Arm (44,20,armW,12) + Sleeve (44,36,armW,12)
            part(44, 20, armW, 12, rightArmDx, 8f, armWf, 12f)
            part(44, 36, armW, 12, rightArmDx, 8f, armWf, 12f)

            // Left Arm (36,52,armW,12) + Sleeve (52,52,armW,12)
            part(36, 52, armW, 12, leftArmDx, 8f, armWf, 12f)
            part(52, 52, armW, 12, leftArmDx, 8f, armWf, 12f)

            // Right Leg (4,20,4,12) + Pants (4,36,4,12) at x=4, y=20
            part(4, 20, 4, 12, 4f, 20f, 4f, 12f)
            part(4, 36, 4, 12, 4f, 20f, 4f, 12f)

            // Left Leg (20,52,4,12) + Pants (4,52,4,12) at x=8, y=20
            part(20, 52, 4, 12, 8f, 20f, 4f, 12f)
            part(4, 52, 4, 12, 8f, 20f, 4f, 12f)
        } else {
            // BACK VIEW
            // Head Back (24,8,8,8) + Hat Back (56,8,8,8)
            part(24, 8, 8, 8, 4f, 0f, 8f, 8f)
            part(56, 8, 8, 8, 4f, 0f, 8f, 8f)

            // Torso Back (32,20,8,12) + Jacket Back (32,36,8,12)
            part(32, 20, 8, 12, 4f, 8f, 8f, 12f)
            part(32, 36, 8, 12, 4f, 8f, 8f, 12f)

            // Back Left/Right Arms
            val rArmBackX = if (slimArms) 51 else 52
            val lArmBackX = if (slimArms) 43 else 44
            part(lArmBackX, 52, armW, 12, rightArmDx, 8f, armWf, 12f)
            part(rArmBackX, 20, armW, 12, leftArmDx, 8f, armWf, 12f)

            // Back Legs
            part(28, 52, 4, 12, 4f, 20f, 4f, 12f)
            part(12, 20, 4, 12, 8f, 20f, 4f, 12f)
        }
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
                animationSpec = tween(200),
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
    var storeInitialCategory by remember { mutableStateOf(StoreCategory.MOD) }
    var installStatus by remember { mutableStateOf<AssetInstallStatus>(HorizonNativeBridge.pollInstallProgress(context, config.enableSystemLog)) }
    var skinDetectionBanner by remember { mutableStateOf<String?>(null) }
    var skinReloadCounter by remember { mutableIntStateOf(0) }

    // Cache the active account's 64x64 skin bitmap for instant rendering on Dashboard & Skin Studio
    val activeSkinBitmap = remember(activeAccount.id, activeAccount.skinPath, activeAccount.slimSkinModel, skinReloadCounter) {
        HorizonNativeBridge.loadOrGenerateAccountSkinBitmap(context, activeAccount)
    }

    // Hoisted skinPicker at root screen level so it is NEVER disposed when Google Photos / File Picker opens!
    val skinPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val res = HorizonNativeBridge.detectAndImportSkinFromUri(context, uri, activeAccount, config)
            if (res.success && res.skinFile != null) {
                val updatedList = accounts.map { acc ->
                    if (acc.id == activeAccount.id) {
                        acc.copy(
                            skinPath = res.skinFile.absolutePath,
                            slimSkinModel = res.slimModel,
                            skinSourceLabel = res.sourceLabel
                        )
                    } else acc
                }
                onAccountsChange(updatedList)
                skinReloadCounter += 1
                skinDetectionBanner = res.message
                Toast.makeText(context, res.message, Toast.LENGTH_SHORT).show()
            } else {
                skinDetectionBanner = res.message
                Toast.makeText(context, res.message, Toast.LENGTH_LONG).show()
            }
        }
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

    val handleLaunchOrDownload = {
        if (!installStatus.ready && !installStatus.running) {
            triggerDownload()
        } else if (!installStatus.running) {
            onEnterGameHibernation()
        } else {
            selectedTab = ZalithNavTab.VERSIONS
        }
    }

    // Smooth Morph / Connected Width Animation for the Right Launch Sidebar:
    // Includes the 10.dp gap INSIDE the animated 192.dp width (10.dp start padding + 182.dp card)
    // so when it morphs to 0.dp on Store/Files/Skins/Settings, there is ZERO Row gap jump ("jedug")!
    val showRightSidebar = selectedTab == ZalithNavTab.HOME || selectedTab == ZalithNavTab.VERSIONS
    val sidebarMorphWidth by animateDpAsState(
        targetValue = if (showRightSidebar) 192.dp else 0.dp,
        animationSpec = tween(durationMillis = 270, easing = FastOutSlowInEasing),
        label = "SidebarMorphWidth"
    )
    val sidebarMorphAlpha by animateFloatAsState(
        targetValue = if (showRightSidebar) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "SidebarMorphAlpha"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF14181B))
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.displayCutout))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Clean, Zero-Duplicate Top Bar:
                // Left: Back button (only when not on HOME) + Title
                // Right: Folder + Log (no duplicate Profile, Settings, or Download buttons!)
                ZalithReferenceTopBar(
                    selectedTab = selectedTab,
                    installStatus = installStatus,
                    onNavigateHome = { selectedTab = ZalithNavTab.HOME },
                    onOpenDataFolder = { HorizonNativeBridge.openSystemFilesApp(context) },
                    onOpenLogs = { selectedTab = ZalithNavTab.LOGS }
                )

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 4.dp)
                ) {
                    ZalithIconRail(
                        selectedTab = selectedTab,
                        onSelectTab = { selectedTab = it }
                    )

                    Spacer(Modifier.width(10.dp))

                    // Main Morphing Content Area:
                    // Smoothly expands/contracts its width in sync with `sidebarMorphWidth` and morphs
                    // between pages with scale + slide + fade!
                    AnimatedContent(
                        targetState = selectedTab,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        transitionSpec = {
                            val forward = targetState.ordinal >= initialState.ordinal
                            (fadeIn(animationSpec = tween(250, easing = FastOutSlowInEasing)) +
                                scaleIn(
                                    initialScale = 0.96f,
                                    animationSpec = tween(250, easing = FastOutSlowInEasing)
                                ) +
                                slideInVertically(
                                    animationSpec = tween(250, easing = FastOutSlowInEasing),
                                    initialOffsetY = { fullH -> if (forward) fullH / 14 else -fullH / 14 }
                                ))
                                .togetherWith(
                                    fadeOut(animationSpec = tween(180, easing = FastOutLinearInEasing)) +
                                        scaleOut(
                                            targetScale = 0.97f,
                                            animationSpec = tween(180, easing = FastOutLinearInEasing)
                                        )
                                )
                                .using(sizeTransform = null)
                        },
                        label = "MainTabMorphTransition"
                    ) { tab ->
                        Box(modifier = Modifier.fillMaxSize()) {
                            when (tab) {
                                ZalithNavTab.HOME -> CleanHomeViewport(
                                    logEnabled = config.enableSystemLog,
                                    isHibernated = isHibernated
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
                                    logEnabled = config.enableSystemLog,
                                    initialCategory = storeInitialCategory
                                )

                                ZalithNavTab.FILES -> CleanFilesViewport(
                                    profileSlot = config.profile,
                                    logEnabled = config.enableSystemLog,
                                    onNavigateToStoreCategory = { targetCat ->
                                        storeInitialCategory = targetCat
                                        selectedTab = ZalithNavTab.STORE
                                    },
                                    onQuickPlayWorld = {
                                        handleLaunchOrDownload()
                                    }
                                )

                                ZalithNavTab.ACCOUNTS -> CleanAccountsAndSkinsViewport(
                                    accounts = accounts,
                                    activeAccount = activeAccount,
                                    activeSkinBitmap = activeSkinBitmap,
                                    config = config,
                                    detectionBanner = skinDetectionBanner,
                                    onLaunchSkinPicker = { skinPicker.launch("image/*") },
                                    onUpdateBanner = { skinDetectionBanner = it },
                                    onSkinUpdated = { skinReloadCounter += 1 },
                                    onAccountsChange = onAccountsChange
                                )

                                ZalithNavTab.LOGS -> CleanLogsViewport(
                                    logEnabled = config.enableSystemLog
                                )

                                ZalithNavTab.SETTINGS -> CleanSettingsViewport(
                                    config = config,
                                    onConfigChange = onConfigChange,
                                    onOpenLogs = { selectedTab = ZalithNavTab.LOGS }
                                )
                            }
                        }
                    }

                    // Smoothly Morphing Right Launch Sidebar Container (0.dp <-> 192.dp with zero layout jolt!)
                    if (sidebarMorphWidth > 0.5.dp) {
                        Box(
                            modifier = Modifier
                                .width(sidebarMorphWidth)
                                .fillMaxHeight()
                                .padding(start = 10.dp)
                                .graphicsLayer {
                                    alpha = sidebarMorphAlpha
                                    translationX = (1f - sidebarMorphAlpha) * 36f
                                }
                        ) {
                            ZalithLaunchSidebar(
                                activeAccount = activeAccount,
                                activeSkinBitmap = activeSkinBitmap,
                                installStatus = installStatus,
                                onOpenAccountScreen = { selectedTab = ZalithNavTab.ACCOUNTS },
                                onOpenVersions = { selectedTab = ZalithNavTab.VERSIONS },
                                onLaunchOrDownload = handleLaunchOrDownload
                            )
                        }
                    }
                }
            }
        }

        // Render the Black "HORIZONZ" Splash Screen as a top-level overlay when launching the game
        // (without unmounting the Compose tree underneath!)
        AnimatedVisibility(
            visible = isHibernated,
            enter = fadeIn(tween(180)),
            exit = fadeOut(tween(220))
        ) {
            HorizonZBlackSplashOverlay()
        }
    }
}

/**
 * Clean Top Bar without duplicate buttons:
 * - Left: `<- Back` pill (only when not on Dashboard) + Title (`HorizonZ` or `HorizonZ — <Page>`)
 * - Right: `Folder` + `Log` (Profile is in Right Sidebar / Left Rail; Settings & Downloads are in Left Rail)
 */
@Composable
private fun ZalithReferenceTopBar(
    selectedTab: ZalithNavTab,
    installStatus: AssetInstallStatus,
    onNavigateHome: () -> Unit,
    onOpenDataFolder: () -> Unit,
    onOpenLogs: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AnimatedVisibility(
                visible = selectedTab != ZalithNavTab.HOME,
                enter = fadeIn(tween(180)) + expandHorizontally(tween(200)),
                exit = fadeOut(tween(150)) + shrinkHorizontally(tween(180))
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = CardSlateBg,
                    modifier = Modifier.animatedPressScale(onClick = onNavigateHome)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = SkyBlueAccent,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = "Back",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = {
                    fadeIn(tween(180)).togetherWith(fadeOut(tween(140))).using(sizeTransform = null)
                },
                label = "TopBarTitleAnim"
            ) { tab ->
                Text(
                    text = if (tab == ZalithNavTab.HOME) "HorizonZ" else "HorizonZ — ${tab.title}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
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
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = CardSlateBg,
                modifier = Modifier.animatedPressScale(onClick = onOpenDataFolder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = "Data Folder",
                        tint = Color(0xFFD5DEE5),
                        modifier = Modifier.size(15.dp)
                    )
                    Text("Folder", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Color.White)
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = if (selectedTab == ZalithNavTab.LOGS) SubRailCapsuleActive else CardSlateBg,
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
    val railTabs = remember {
        listOf(
            ZalithNavTab.HOME,
            ZalithNavTab.VERSIONS,
            ZalithNavTab.STORE,
            ZalithNavTab.FILES,
            ZalithNavTab.ACCOUNTS,
            ZalithNavTab.SETTINGS
        )
    }
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
            railTabs.forEach { tab ->
                val selected = tab == selectedTab
                val icon = when (tab) {
                    ZalithNavTab.HOME -> Icons.Default.Home
                    ZalithNavTab.VERSIONS -> Icons.Default.Download
                    ZalithNavTab.STORE -> Icons.Default.Storefront
                    ZalithNavTab.FILES -> Icons.Default.Extension
                    ZalithNavTab.ACCOUNTS -> Icons.Default.Checkroom
                    ZalithNavTab.LOGS -> Icons.Default.Terminal
                    ZalithNavTab.SETTINGS -> Icons.Default.Settings
                }
                val pillColor by animateColorAsState(
                    targetValue = if (selected) SubRailCapsuleActive else Color.Transparent,
                    animationSpec = tween(200),
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
 * - 18 Authentic HD 16:9 Minecraft Wallpapers cycling every 5 seconds with smooth crossfade.
 * - Zero duplicate navigation buttons on top-right (only a single clean `Wallpaper` button).
 */
@Composable
private fun CleanHomeViewport(
    logEnabled: Boolean,
    isHibernated: Boolean
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
                    fadeIn(animationSpec = tween(900)).togetherWith(fadeOut(animationSpec = tween(900))).using(sizeTransform = null)
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
                                Color.Black.copy(alpha = 0.08f),
                                Color.Black.copy(alpha = 0.68f)
                            )
                        )
                    )
            )

            // Single non-duplicated Wallpaper button in top-right corner
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
            ) {
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

                AnimatedContent(
                    targetState = MINECRAFT_SPLASH_QUOTES[quoteIndex],
                    transitionSpec = {
                        (slideInVertically(tween(340)) { h -> h / 2 } + fadeIn(tween(340)))
                            .togetherWith(
                                slideOutVertically(tween(260)) { h -> -h / 2 } + fadeOut(tween(240))
                            )
                            .using(sizeTransform = null)
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
        color = Color(0xFF181E22).copy(alpha = 0.76f),
        modifier = Modifier.animatedPressScale(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
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

/**
 * Full-Viewport Account Profile & Skin Studio (`ZalithNavTab.ACCOUNTS`):
 * - Clean, well-proportioned cards with zero vertical badge wrapping (`In-Game Ready` stays on 1 line!).
 * - Uses hoisted `onLaunchSkinPicker` so Google Photos selection always succeeds and updates immediately!
 */
@Composable
private fun CleanAccountsAndSkinsViewport(
    accounts: List<ZalithAccount>,
    activeAccount: ZalithAccount,
    activeSkinBitmap: Bitmap,
    config: HorizonConfigState,
    detectionBanner: String?,
    onLaunchSkinPicker: () -> Unit,
    onUpdateBanner: (String?) -> Unit,
    onSkinUpdated: () -> Unit,
    onAccountsChange: (List<ZalithAccount>) -> Unit
) {
    val context = LocalContext.current
    var selectedSubTab by remember { mutableStateOf(AccountSubCategory.SKIN_STUDIO) }
    var showBackView by remember { mutableStateOf(false) }
    var newAccountName by remember { mutableStateOf("") }
    var selectedAccountType by remember { mutableStateOf(AccountType.OFFLINE) }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ZalithSubCategoryRail(
            items = AccountSubCategory.entries,
            selectedItem = selectedSubTab,
            itemLabel = { it.label },
            itemIcon = { it.icon },
            onSelect = { selectedSubTab = it }
        )

        AnimatedContent(
            targetState = selectedSubTab,
            transitionSpec = {
                (fadeIn(tween(200, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(200, easing = FastOutSlowInEasing)) { h -> h / 14 })
                    .togetherWith(fadeOut(tween(150)))
                    .using(sizeTransform = null)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            label = "AccountSubTabTransition"
        ) { subTab ->
            when (subTab) {
                AccountSubCategory.SKIN_STUDIO -> {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Left Card: Interactive 2D Full-Body Minecraft Character Preview
                        Surface(
                            shape = RoundedCornerShape(22.dp),
                            color = CardSlateBg,
                            modifier = Modifier
                                .width(196.dp)
                                .fillMaxHeight()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        MinecraftSkinHeadAvatar(
                                            skinBitmap = activeSkinBitmap,
                                            size = 26.dp,
                                            cornerRadius = 6.dp
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = activeAccount.username,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = if (activeAccount.slimSkinModel) "Slim (3px)" else "Wide (4px)",
                                                fontSize = 10.sp,
                                                color = SkyBlueAccent,
                                                maxLines = 1
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = SubRailCapsuleActive,
                                        modifier = Modifier.animatedPressScale { showBackView = !showBackView }
                                    ) {
                                        Text(
                                            text = if (showBackView) "Back" else "Front",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = SkyBlueAccent,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(vertical = 6.dp)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(Color(0xFF171E24), Color(0xFF101519))
                                            )
                                        )
                                        .animatedPressScale { showBackView = !showBackView },
                                    contentAlignment = Alignment.Center
                                ) {
                                    MinecraftSkinFullBodyCanvas(
                                        skinBitmap = activeSkinBitmap,
                                        slimArms = activeAccount.slimSkinModel,
                                        showBack = showBackView,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                    )
                                }

                                Text(
                                    text = activeAccount.skinSourceLabel,
                                    fontSize = 10.sp,
                                    color = MutedSubtitleText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Right Column: Compact, Unclipped Skin Importer + Arm Model Selector + Preset List
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = CardSlateBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "Custom Skin & Photo Importer",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = "Import file Skin PNG/JPG (64×64 / 64×32) atau Foto Galeri untuk digunakan di in-game.",
                                                fontSize = 11.sp,
                                                color = MutedSubtitleText,
                                                maxLines = 2
                                            )
                                        }

                                        Spacer(Modifier.width(10.dp))

                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = SkyBlueAccent,
                                            modifier = Modifier.animatedPressScale(onClick = onLaunchSkinPicker)
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.AddPhotoAlternate,
                                                    contentDescription = "Pilih Foto / Skin",
                                                    tint = SkyBlueDarkText,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Text(
                                                    text = "Import Skin",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = SkyBlueDarkText,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }

                                    detectionBanner?.let { bannerMsg ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = SubRailCapsuleActive,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    Icons.Default.Verified,
                                                    contentDescription = null,
                                                    tint = SkyBlueAccent,
                                                    modifier = Modifier.size(15.dp)
                                                )
                                                Text(
                                                    text = bannerMsg,
                                                    fontSize = 11.sp,
                                                    color = Color.White,
                                                    modifier = Modifier.weight(1f)
                                                )
                                            }
                                        }
                                    }

                                    // Arm Model Selector (Wide 4px Steve vs Slim 3px Alex)
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Model Lengan:",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                        listOf(false to "Wide (4px Steve)", true to "Slim (3px Alex)").forEach { (isSlim, label) ->
                                            val selected = activeAccount.slimSkinModel == isSlim
                                            Surface(
                                                shape = RoundedCornerShape(50),
                                                color = if (selected) SkyBlueAccent else CardRowBg,
                                                modifier = Modifier.animatedPressScale {
                                                    val updatedList = accounts.map { acc ->
                                                        if (acc.id == activeAccount.id) acc.copy(slimSkinModel = isSlim) else acc
                                                    }
                                                    onAccountsChange(updatedList)
                                                    onSkinUpdated()
                                                }
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 11.sp,
                                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (selected) SkyBlueDarkText else Color.White,
                                                    maxLines = 1,
                                                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 5.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Built-in Preset Skins Card (including the User's Gothic Monochrome Skin!)
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = CardSlateBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp)
                                ) {
                                    Text(
                                        text = "Koleksi Skin Siap Pakai (1-Tap In-Game)",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                    BuiltInSkinPreset.entries.forEach { preset ->
                                        val presetBmp = remember(preset) { HorizonNativeBridge.generatePresetSkinBitmap(preset, context) }
                                        val isCurrent = activeAccount.skinSourceLabel == "Preset: ${preset.title}"
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (isCurrent) SubRailCapsuleActive else CardRowBg,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animatedPressScale {
                                                    val updatedAcc = HorizonNativeBridge.applyBuiltInPresetSkin(
                                                        context,
                                                        preset,
                                                        activeAccount,
                                                        config
                                                    )
                                                    val updatedList = accounts.map { if (it.id == activeAccount.id) updatedAcc else it }
                                                    onAccountsChange(updatedList)
                                                    onSkinUpdated()
                                                    onUpdateBanner("Skin '${preset.title}' aktif & siap digunakan di in-game!")
                                                    Toast.makeText(context, "Skin '${preset.title}' terpasang!", Toast.LENGTH_SHORT).show()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    MinecraftSkinHeadAvatar(
                                                        skinBitmap = presetBmp,
                                                        size = 32.dp,
                                                        cornerRadius = 7.dp
                                                    )
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Text(
                                                            text = preset.title,
                                                            fontSize = 12.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color.White,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = preset.subtitle,
                                                            fontSize = 10.sp,
                                                            color = MutedSubtitleText,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                }

                                                Spacer(Modifier.width(8.dp))

                                                Surface(
                                                    shape = RoundedCornerShape(9.dp),
                                                    color = if (isCurrent) SkyBlueAccent else Color(0xFF344149)
                                                ) {
                                                    Text(
                                                        text = if (isCurrent) "Active" else "Pakai",
                                                        fontSize = 11.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isCurrent) SkyBlueDarkText else Color.White,
                                                        maxLines = 1,
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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

                AccountSubCategory.PROFILES -> {
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
                            Text(
                                text = "Player Accounts & Microsoft Sign-In",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CardRowBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(50),
                                        color = Color(0xFF1E2428),
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
                                            if (newAccountName.isEmpty()) {
                                                Text("Enter Player Name / Gamertag...", fontSize = 12.sp, color = MutedSubtitleText)
                                            }
                                            BasicTextField(
                                                value = newAccountName,
                                                onValueChange = { newAccountName = it },
                                                singleLine = true,
                                                textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                                                cursorBrush = SolidColor(SkyBlueAccent),
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                    }

                                    AccountType.entries.forEach { t ->
                                        val isSelected = selectedAccountType == t
                                        Surface(
                                            shape = RoundedCornerShape(50),
                                            color = if (isSelected) SubRailCapsuleActive else Color(0xFF1E2428),
                                            modifier = Modifier.animatedPressScale { selectedAccountType = t }
                                        ) {
                                            Text(
                                                text = t.badge,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = if (isSelected) SkyBlueAccent else MutedSubtitleText,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = SkyBlueAccent,
                                        modifier = Modifier.animatedPressScale {
                                            if (newAccountName.isNotBlank()) {
                                                val added = ZalithAccount(
                                                    id = UUID.randomUUID().toString(),
                                                    username = newAccountName.trim(),
                                                    uuid = UUID.randomUUID().toString(),
                                                    accountType = selectedAccountType,
                                                    isActive = true
                                                )
                                                val updated = accounts.map { it.copy(isActive = false) } + added
                                                onAccountsChange(updated)
                                                newAccountName = ""
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(Icons.Default.PersonAdd, contentDescription = null, tint = SkyBlueDarkText, modifier = Modifier.size(15.dp))
                                            Text("Save Profile", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SkyBlueDarkText)
                                        }
                                    }
                                }
                            }

                            if (selectedAccountType == AccountType.MICROSOFT) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = SubRailCapsuleActive,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animatedPressScale {
                                            runCatching {
                                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.microsoft.com/link")))
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = SkyBlueAccent, modifier = Modifier.size(17.dp))
                                        Text(
                                            text = "Open Microsoft Device Link (microsoft.com/link) to authorize Xbox Live Gamertag",
                                            fontSize = 12.sp,
                                            color = Color.White
                                        )
                                    }
                                }
                            }

                            accounts.forEach { acc ->
                                val accSkinBmp = remember(acc.id, acc.skinPath, acc.slimSkinModel) {
                                    HorizonNativeBridge.loadOrGenerateAccountSkinBitmap(context, acc)
                                }
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = if (acc.isActive) SubRailCapsuleActive else CardRowBg,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .animatedPressScale {
                                            val updated = accounts.map { it.copy(isActive = it.id == acc.id) }
                                            onAccountsChange(updated)
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            MinecraftSkinHeadAvatar(
                                                skinBitmap = accSkinBmp,
                                                size = 38.dp,
                                                cornerRadius = 9.dp
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Text(
                                                        text = acc.username,
                                                        fontSize = 14.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        maxLines = 1
                                                    )
                                                    LavenderTagBadge(text = acc.accountType.badge)
                                                    if (acc.isActive) {
                                                        LavenderTagBadge(text = "Active")
                                                    }
                                                }
                                                Text(
                                                    text = "Skin: ${acc.skinSourceLabel}",
                                                    fontSize = 11.sp,
                                                    color = MutedSubtitleText,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Surface(
                                                shape = RoundedCornerShape(10.dp),
                                                color = CardSlateBg,
                                                modifier = Modifier.animatedPressScale {
                                                    val updated = accounts.map { it.copy(isActive = it.id == acc.id) }
                                                    onAccountsChange(updated)
                                                    selectedSubTab = AccountSubCategory.SKIN_STUDIO
                                                }
                                            ) {
                                                Text(
                                                    text = "Edit Skin",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = SkyBlueAccent,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                                )
                                            }

                                            if (accounts.size > 1) {
                                                Icon(
                                                    imageVector = Icons.Outlined.Delete,
                                                    contentDescription = "Delete Account",
                                                    tint = Color(0xFFFF8A80),
                                                    modifier = Modifier
                                                        .size(20.dp)
                                                        .animatedPressScale {
                                                            val remaining = accounts.filterNot { it.id == acc.id }
                                                            val fixed = if (remaining.none { it.isActive }) {
                                                                remaining.mapIndexed { idx, item -> item.copy(isActive = idx == 0) }
                                                            } else remaining
                                                            onAccountsChange(fixed)
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
}

@Composable
private fun CleanLogsViewport(
    logEnabled: Boolean
) {
    val context = LocalContext.current
    var selectedCategory by remember { mutableStateOf(LogCategoryFilter.ALL) }
    var selectedSeverity by remember { mutableStateOf(LogSeverityFilter.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var refreshTick by remember { mutableIntStateOf(0) }
    var logEntries by remember {
        mutableStateOf(HorizonNativeBridge.readStructuredLogs(context, logEnabled, LogCategoryFilter.ALL))
    }

    LaunchedEffect(logEnabled, selectedCategory, refreshTick) {
        while (true) {
            logEntries = HorizonNativeBridge.readStructuredLogs(context, logEnabled, selectedCategory)
            delay(1000L)
        }
    }

    val filteredEntries = remember(logEntries, selectedSeverity, searchQuery) {
        logEntries.filter { entry ->
            val matchSev = selectedSeverity == LogSeverityFilter.ALL || entry.severity == selectedSeverity
            val matchQuery = searchQuery.isBlank() ||
                entry.message.contains(searchQuery, ignoreCase = true) ||
                entry.sourceTag.contains(searchQuery, ignoreCase = true)
            matchSev && matchQuery
        }
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ZalithSubCategoryRail(
            items = LogCategoryFilter.entries,
            selectedItem = selectedCategory,
            itemLabel = { it.label },
            itemIcon = {
                when (it) {
                    LogCategoryFilter.ALL -> Icons.Default.Terminal
                    LogCategoryFilter.LAUNCHER -> Icons.Default.Dashboard
                    LogCategoryFilter.DOWNLOADER -> Icons.Default.CloudDownload
                    LogCategoryFilter.ENGINE -> Icons.Default.SportsEsports
                }
            },
            onSelect = { selectedCategory = it }
        )

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
                // Top Search & Action Toolbar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                                Text("Search logs (URL, speed, wgpu, skin)...", fontSize = 12.sp, color = MutedSubtitleText)
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

                    LogSeverityFilter.entries.forEach { sev ->
                        val selected = selectedSeverity == sev
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = if (selected) SubRailCapsuleActive else CardRowBg,
                            modifier = Modifier.animatedPressScale { selectedSeverity = sev }
                        ) {
                            Text(
                                text = sev.label,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (selected) SkyBlueAccent else MutedSubtitleText,
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = SkyBlueAccent,
                        modifier = Modifier.animatedPressScale {
                            val raw = filteredEntries.joinToString("\n") { "[${it.timestamp}] [${it.sourceTag}/${it.severity.label}] ${it.message}" }
                            HorizonNativeBridge.copyToClipboard(context, "HorizonZ Logs", raw)
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = SkyBlueDarkText, modifier = Modifier.size(14.dp))
                            Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SkyBlueDarkText)
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF3A2326),
                        modifier = Modifier.animatedPressScale {
                            HorizonNativeBridge.clearLogs(context)
                            refreshTick += 1
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear", tint = Color(0xFFFF8A80), modifier = Modifier.size(14.dp))
                            Text("Clear", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF8A80))
                        }
                    }
                }

                // Log Console List
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF12171B))
                        .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        val badgeColor = when (entry.severity) {
                            LogSeverityFilter.ERR -> Color(0xFFFF5252)
                            LogSeverityFilter.WARN -> Color(0xFFFFB74D)
                            LogSeverityFilter.DL -> Color(0xFF66BB6A)
                            else -> SkyBlueAccent
                        }
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF1A2126),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = entry.timestamp,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    color = MutedSubtitleText
                                )
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = badgeColor.copy(alpha = 0.18f)
                                ) {
                                    Text(
                                        text = "${entry.sourceTag} • ${entry.severity.label}",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                                Text(
                                    text = entry.message,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = Color(0xFFE1E8ED),
                                    modifier = Modifier.weight(1f)
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
                                enter = expandVertically(tween(200)) + fadeIn(tween(180)),
                                exit = shrinkVertically(tween(180)) + fadeOut(tween(150))
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
 * Store Viewport styled after `429665.jpg` & `429667.jpg`.
 */
@Composable
private fun CleanStoreViewport(
    profileSlot: Int,
    logEnabled: Boolean,
    initialCategory: StoreCategory = StoreCategory.MOD
) {
    val context = LocalContext.current
    var selectedCategory by remember(initialCategory) { mutableStateOf(initialCategory) }
    var searchQuery by remember { mutableStateOf("") }
    var gameVersionQuery by remember { mutableStateOf("") }
    var targetPlatform by remember { mutableStateOf(StoreTargetPlatform.MODRINTH) }
    var sortOption by remember { mutableStateOf(StoreSortOption.RELEVANCE) }
    var selectedCategories by remember { mutableStateOf<Set<String>>(emptySet()) }
    var selectedLoader by remember { mutableStateOf("None Selected") }
    var currentPage by remember { mutableIntStateOf(1) }
    var totalPages by remember { mutableIntStateOf(1) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    // Expandable filter accordion states in Right Sidebar (matching 429798.mp4 00:22–00:29)
    var expandPlatform by remember { mutableStateOf(false) }
    var expandSortBy by remember { mutableStateOf(false) }
    var expandCategory by remember { mutableStateOf(false) }
    var expandLoader by remember { mutableStateOf(false) }

    // Drill-down selected project for Version Accordion & Right Detail Card (matching 429798.mp4 00:10–00:16 & 00:33–00:39)
    var selectedProject by remember { mutableStateOf<StoreCatalogItem?>(null) }
    var projectVersions by remember { mutableStateOf<List<StoreVersionRelease>>(emptyList()) }
    var isLoadingVersions by remember { mutableStateOf(false) }
    var showOnlyMcReleases by remember { mutableStateOf(true) }
    var searchMcVersionsQuery by remember { mutableStateOf("") }
    var expandedMcVerGroups by remember { mutableStateOf<Set<String>>(emptySet()) }

    var isLoading by remember { mutableStateOf(false) }
    var catalogItems by remember { mutableStateOf<List<StoreCatalogItem>>(emptyList()) }
    var favoriteIds by remember { mutableStateOf(HorizonNativeBridge.getFavoriteProjectIds(context)) }
    var storeProgress by remember { mutableStateOf(StoreDownloadProgress()) }
    var pendingIncompatibleItem by remember { mutableStateOf<StoreCatalogItem?>(null) }
    var showCancelStoreDialog by remember { mutableStateOf(false) }

    val modpackImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            HorizonNativeBridge.importUriToDestination(
                context,
                uri,
                "mods",
                "modpack_${System.currentTimeMillis()}.mrpack",
                logEnabled
            )
            Toast.makeText(context, "Modpack berhasil diimpor!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(
        selectedCategory,
        searchQuery,
        gameVersionQuery,
        targetPlatform,
        sortOption,
        selectedCategories,
        selectedLoader,
        currentPage,
        refreshTrigger
    ) {
        isLoading = true
        val pageRes = withContext(Dispatchers.IO) {
            HorizonNativeBridge.searchStoreProjects(
                category = selectedCategory,
                searchQuery = searchQuery,
                gameVersionFilter = gameVersionQuery,
                targetPlatform = targetPlatform,
                sortOption = sortOption,
                selectedCategories = selectedCategories,
                selectedLoader = if (selectedLoader == "None Selected") null else selectedLoader,
                page = currentPage
            )
        }
        catalogItems = pageRes.items
        totalPages = pageRes.totalPages
        isLoading = false
    }

    LaunchedEffect(selectedProject) {
        val proj = selectedProject ?: return@LaunchedEffect
        isLoadingVersions = true
        val vers = withContext(Dispatchers.IO) {
            HorizonNativeBridge.fetchProjectVersionReleases(proj)
        }
        projectVersions = vers
        val firstVerGroup = vers.firstOrNull()?.primaryMcVersion ?: "26.2"
        expandedMcVerGroups = setOf(firstVerGroup)
        isLoadingVersions = false
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
        // 1. Left Sub-Navigation Rail (Mod, Resource Pack, Saves, Shader Pack, Modpack — matching 429798.mp4)
        ZalithSubCategoryRail(
            items = StoreCategory.entries,
            selectedItem = selectedCategory,
            itemLabel = { it.label },
            itemIcon = {
                when (it) {
                    StoreCategory.MOD -> Icons.Default.Extension
                    StoreCategory.RESOURCE_PACK -> Icons.Default.Brush
                    StoreCategory.SAVES -> Icons.Default.Public
                    StoreCategory.SHADER_PACK -> Icons.Default.Lightbulb
                    StoreCategory.MODPACK -> Icons.Default.Inventory2
                }
            },
            onSelect = {
                selectedCategory = it
                selectedProject = null
                currentPage = 1
            }
        )

        // 2. Center + Right Columns (switches smoothly between 3-Column Browser & Project Version Accordion View)
        AnimatedContent(
            targetState = selectedProject,
            transitionSpec = {
                (fadeIn(tween(220, easing = FastOutSlowInEasing)) +
                    slideInHorizontally(tween(220, easing = FastOutSlowInEasing)) { w -> if (targetState != null) w / 12 else -w / 12 })
                    .togetherWith(fadeOut(tween(160)))
                    .using(sizeTransform = null)
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            label = "StoreMasterDetailTransition"
        ) { detailProj ->
            if (detailProj == null) {
                // ============================================================
                // MODE 1: 3-COLUMN STORE BROWSER (429798.mp4 00:00–00:09, 00:18–00:32)
                // ============================================================
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Center Project List + Floating Top-Right Pagination Bar (`< 1 / 3894 >`)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Active Download Progress Banner (if downloading)
                            AnimatedVisibility(visible = storeProgress.running) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = SubRailCapsuleActive,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp)
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
                                                    color = Color.White,
                                                    maxLines = 1
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
                                    LinearProgressIndicator(
                                        color = SkyBlueAccent,
                                        trackColor = CardSlateBg,
                                        modifier = Modifier
                                            .width(180.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(50))
                                    )
                                }
                            } else if (catalogItems.isEmpty()) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = CardSlateBg
                                    ) {
                                        Text(
                                            text = "No projects found matching your filter.",
                                            fontSize = 12.sp,
                                            color = MutedSubtitleText,
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                        )
                                    }
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(top = 42.dp, bottom = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(catalogItems, key = { "${it.platform.name}_${it.id}" }) { item ->
                                        val isFav = item.id in favoriteIds
                                        Surface(
                                            shape = RoundedCornerShape(18.dp),
                                            color = CardSlateBg,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .animatedPressScale { selectedProject = item }
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // Left 50dp Async Icon Badge
                                                StoreAsyncProjectIcon(
                                                    iconUrl = item.iconUrl,
                                                    title = item.title,
                                                    isMod = item.category == StoreCategory.MOD || item.category == StoreCategory.MODPACK,
                                                    isWorld = item.category == StoreCategory.SAVES,
                                                    size = 50.dp
                                                )

                                                Spacer(Modifier.width(12.dp))

                                                // Middle Title + "By <Author>" + 2-line Summary + Tags (matching 429798.mp4)
                                                Column(
                                                    modifier = Modifier.weight(1f),
                                                    verticalArrangement = Arrangement.spacedBy(3.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.Bottom,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = item.title,
                                                            fontSize = 14.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.White,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                        Text(
                                                            text = "By ${item.author}",
                                                            fontSize = 11.sp,
                                                            color = MutedSubtitleText,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis
                                                        )
                                                    }
                                                    Text(
                                                        text = item.summary,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFFD0D8DE),
                                                        maxLines = 2,
                                                        lineHeight = 14.sp,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    if (item.displayTags.isNotEmpty()) {
                                                        Row(
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            item.displayTags.take(5).forEach { tag ->
                                                                Text(
                                                                    text = tag,
                                                                    fontSize = 10.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = MutedSubtitleText,
                                                                    maxLines = 1
                                                                )
                                                            }
                                                        }
                                                    }
                                                }

                                                Spacer(Modifier.width(10.dp))

                                                // Right Column: Modrinth/Horizon Badge + Download Count + Favorite Button (429798.mp4)
                                                Column(
                                                    horizontalAlignment = Alignment.End,
                                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    StorePlatformBadge(platform = item.platform)

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                                                    ) {
                                                        Icon(
                                                            imageVector = Icons.Default.TrendingUp,
                                                            contentDescription = null,
                                                            tint = MutedSubtitleText,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Text(
                                                            text = item.formattedDownloads,
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.SemiBold,
                                                            color = Color(0xFFD5DEE5)
                                                        )
                                                    }

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                                                        modifier = Modifier.animatedPressScale {
                                                            favoriteIds = HorizonNativeBridge.toggleProjectFavorite(context, item.id)
                                                        }
                                                    ) {
                                                        Icon(
                                                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                            contentDescription = "Favorite",
                                                            tint = if (isFav) Color(0xFFFF6E6E) else MutedSubtitleText,
                                                            modifier = Modifier.size(13.dp)
                                                        )
                                                        Text(
                                                            text = "Favorite",
                                                            fontSize = 10.sp,
                                                            color = if (isFav) Color(0xFFFF8A80) else MutedSubtitleText
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Floating Top-Right Pagination Pill (`1 / 3894   <   >`) matching 429798.mp4 00:00–00:09!
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = Color(0xFF283036).copy(alpha = 0.94f),
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 2.dp, end = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = "$currentPage / $totalPages",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color.White
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronLeft,
                                    contentDescription = "Previous Page",
                                    tint = if (currentPage > 1) Color.White else MutedSubtitleText.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .animatedPressScale(enabled = currentPage > 1) {
                                            if (currentPage > 1) currentPage -= 1
                                        }
                                )
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "Next Page",
                                    tint = if (currentPage < totalPages) Color.White else MutedSubtitleText.copy(alpha = 0.4f),
                                    modifier = Modifier
                                        .size(18.dp)
                                        .animatedPressScale(enabled = currentPage < totalPages) {
                                            if (currentPage < totalPages) currentPage += 1
                                        }
                                )
                            }
                        }
                    }

                    // 3. Right Filter & Search Sidebar (`210.dp`, matching 429798.mp4 00:00–00:29)
                    Column(
                        modifier = Modifier
                            .width(210.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Search Name Pill Box
                        StoreRightSearchPill(
                            value = searchQuery,
                            placeholder = "Search Name",
                            onValueChange = {
                                searchQuery = it
                                currentPage = 1
                            }
                        )

                        // Game Version Pill Box
                        StoreRightSearchPill(
                            value = gameVersionQuery,
                            placeholder = "Game Version",
                            onValueChange = {
                                gameVersionQuery = it
                                currentPage = 1
                            }
                        )

                        // Import Modpack Button when on Modpack category (`00:44` in 429798.mp4)
                        if (selectedCategory == StoreCategory.MODPACK) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CardSlateBg,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .animatedPressScale { modpackImportPicker.launch("*/*") }
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "Import Modpack",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                }
                            }
                        }

                        // Target Platform Accordion Card
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = CardSlateBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expandPlatform = !expandPlatform }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Target Platform", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        StorePlatformBadge(platform = targetPlatform)
                                    }
                                    Icon(
                                        imageVector = if (expandPlatform) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                AnimatedVisibility(visible = expandPlatform) {
                                    Column(
                                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        StoreTargetPlatform.entries.forEach { plat ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        targetPlatform = plat
                                                        currentPage = 1
                                                        expandPlatform = false
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = targetPlatform == plat,
                                                    onClick = {
                                                        targetPlatform = plat
                                                        currentPage = 1
                                                        expandPlatform = false
                                                    },
                                                    colors = RadioButtonDefaults.colors(selectedColor = SkyBlueAccent),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(plat.label, fontSize = 11.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Sort By Accordion Card (`00:23` in 429798.mp4)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = CardSlateBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expandSortBy = !expandSortBy }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Sort By", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        LavenderTagBadge(text = sortOption.label)
                                    }
                                    Icon(
                                        imageVector = if (expandSortBy) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                AnimatedVisibility(visible = expandSortBy) {
                                    Column(
                                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        StoreSortOption.entries.forEach { opt ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        sortOption = opt
                                                        currentPage = 1
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                RadioButton(
                                                    selected = sortOption == opt,
                                                    onClick = {
                                                        sortOption = opt
                                                        currentPage = 1
                                                    },
                                                    colors = RadioButtonDefaults.colors(selectedColor = SkyBlueAccent),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(opt.label, fontSize = 11.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Category Checkbox Accordion Card (`00:25`–`00:26` in 429798.mp4)
                        val categoryOptions = remember {
                            listOf(
                                "World Generation",
                                "Technology",
                                "Food & Cooking",
                                "Game Mechanics",
                                "Transportation",
                                "Storage",
                                "Magic",
                                "Optimization",
                                "Utility"
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = CardSlateBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { expandCategory = !expandCategory }
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("Category", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                        LavenderTagBadge(
                                            text = if (selectedCategories.isEmpty()) "None Selected"
                                            else "${selectedCategories.size} Selected"
                                        )
                                    }
                                    Icon(
                                        imageVector = if (expandCategory) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                AnimatedVisibility(visible = expandCategory) {
                                    Column(
                                        modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(2.dp)
                                    ) {
                                        categoryOptions.forEach { catName ->
                                            val checked = catName in selectedCategories
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .clickable {
                                                        selectedCategories = if (checked) selectedCategories - catName else selectedCategories + catName
                                                        currentPage = 1
                                                    }
                                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Checkbox(
                                                    checked = checked,
                                                    onCheckedChange = {
                                                        selectedCategories = if (checked) selectedCategories - catName else selectedCategories + catName
                                                        currentPage = 1
                                                    },
                                                    colors = CheckboxDefaults.colors(
                                                        checkedColor = SkyBlueAccent,
                                                        checkmarkColor = SkyBlueDarkText
                                                    ),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Spacer(Modifier.width(6.dp))
                                                Text(catName, fontSize = 11.sp, color = Color.White)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Mod Loader Radio Accordion Card (`00:27`–`00:28` in 429798.mp4)
                        if (selectedCategory == StoreCategory.MOD || selectedCategory == StoreCategory.MODPACK) {
                            val loaderOptions = remember {
                                listOf("None Selected", "Horizon", "Fabric", "Forge", "NeoForge", "Quilt")
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CardSlateBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { expandLoader = !expandLoader }
                                            .padding(horizontal = 12.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text("Mod Loader", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                            LavenderTagBadge(text = selectedLoader)
                                        }
                                        Icon(
                                            imageVector = if (expandLoader) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                            contentDescription = null,
                                            tint = Color.White,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    AnimatedVisibility(visible = expandLoader) {
                                        Column(
                                            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            loaderOptions.forEach { ld ->
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .clickable {
                                                            selectedLoader = ld
                                                            if (ld == "Horizon") targetPlatform = StoreTargetPlatform.HORIZON
                                                            currentPage = 1
                                                        }
                                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    RadioButton(
                                                        selected = selectedLoader == ld,
                                                        onClick = {
                                                            selectedLoader = ld
                                                            if (ld == "Horizon") targetPlatform = StoreTargetPlatform.HORIZON
                                                            currentPage = 1
                                                        },
                                                        colors = RadioButtonDefaults.colors(selectedColor = SkyBlueAccent),
                                                        modifier = Modifier.size(20.dp)
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    Text(ld, fontSize = 11.sp, color = Color.White)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // ============================================================
                // MODE 2: PROJECT VERSION ACCORDION & RIGHT SUMMARY CARD
                // (Matching 429798.mp4 00:10–00:16 & 00:33–00:39)
                // ============================================================
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Center Panel: Top Filter Bar + Expandable MC Version Accordions
                    Surface(
                        shape = RoundedCornerShape(20.dp),
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
                            // Top Bar: Back to List + `[✓] Show Only MC Releases` + `Search MC Versions`
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CardRowBg,
                                    modifier = Modifier.animatedPressScale { selectedProject = null }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = SkyBlueAccent, modifier = Modifier.size(15.dp))
                                        Text("List", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = if (showOnlyMcReleases) SubRailCapsuleActive else CardRowBg,
                                    modifier = Modifier.animatedPressScale { showOnlyMcReleases = !showOnlyMcReleases }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (showOnlyMcReleases) Icons.Default.Check else Icons.Default.FilterList,
                                            contentDescription = null,
                                            tint = SkyBlueAccent,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "Show Only MC Releases",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = Color.White
                                        )
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = CardRowBg,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(32.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(horizontal = 12.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (searchMcVersionsQuery.isEmpty()) {
                                            Text("Search MC Versions", fontSize = 11.sp, color = MutedSubtitleText)
                                        }
                                        BasicTextField(
                                            value = searchMcVersionsQuery,
                                            onValueChange = { searchMcVersionsQuery = it },
                                            singleLine = true,
                                            textStyle = TextStyle(color = Color.White, fontSize = 11.sp),
                                            cursorBrush = SolidColor(SkyBlueAccent),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }

                            if (isLoadingVersions) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    LinearProgressIndicator(
                                        color = SkyBlueAccent,
                                        trackColor = CardRowBg,
                                        modifier = Modifier
                                            .width(180.dp)
                                            .height(4.dp)
                                            .clip(RoundedCornerShape(50))
                                    )
                                }
                            } else {
                                // Group versions by Minecraft version (`26.3`, `26.2`, `26.1.2`, `1.21.11`, etc.)
                                val groupedByMcVer = remember(projectVersions, showOnlyMcReleases, searchMcVersionsQuery) {
                                    val map = linkedMapOf<String, MutableList<StoreVersionRelease>>()
                                    projectVersions.forEach { rel ->
                                        rel.mcVersions.forEach { mcV ->
                                            val isRelease = !mcV.contains("w") && !mcV.contains("pre") && !mcV.contains("rc")
                                            val matchesToggle = !showOnlyMcReleases || isRelease
                                            val matchesSearch = searchMcVersionsQuery.isBlank() || mcV.contains(searchMcVersionsQuery, ignoreCase = true)
                                            if (matchesToggle && matchesSearch) {
                                                map.getOrPut(mcV) { mutableListOf() }.add(rel)
                                            }
                                        }
                                    }
                                    map
                                }

                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    groupedByMcVer.entries.forEach { (mcVer, releases) ->
                                        item(key = "mcgroup_$mcVer") {
                                            val isExpanded = mcVer in expandedMcVerGroups
                                            val isRecommended = mcVer.startsWith("26.")
                                            val primaryLoader = releases.firstOrNull()?.loaders?.firstOrNull()
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = CardRowBg,
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Column(modifier = Modifier.fillMaxWidth()) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable {
                                                                expandedMcVerGroups = if (isExpanded) expandedMcVerGroups - mcVer
                                                                else expandedMcVerGroups + mcVer
                                                            }
                                                            .padding(horizontal = 14.dp, vertical = 11.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Text(
                                                                text = mcVer,
                                                                fontSize = 13.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = Color.White
                                                            )
                                                            if (!primaryLoader.isNullOrBlank()) {
                                                                LavenderTagBadge(text = primaryLoader)
                                                            }
                                                        }

                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            if (isRecommended) {
                                                                Icon(
                                                                    imageVector = Icons.Default.Star,
                                                                    contentDescription = "Recommended",
                                                                    tint = Color.White,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                                Icon(
                                                                    imageVector = Icons.Default.CheckCircle,
                                                                    contentDescription = "Compatible",
                                                                    tint = LavenderBadgeBg,
                                                                    modifier = Modifier.size(16.dp)
                                                                )
                                                            }
                                                            Icon(
                                                                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                                contentDescription = null,
                                                                tint = Color.White,
                                                                modifier = Modifier.size(20.dp)
                                                            )
                                                        }
                                                    }

                                                    AnimatedVisibility(visible = isExpanded) {
                                                        Column(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .padding(start = 10.dp, end = 10.dp, bottom = 10.dp),
                                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                                        ) {
                                                            releases.forEach { rel ->
                                                                Surface(
                                                                    shape = RoundedCornerShape(12.dp),
                                                                    color = Color(0xFF21272B),
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .animatedPressScale {
                                                                            val dlItem = detailProj.copy(
                                                                                version = rel.versionNumber,
                                                                                supportedMcVersions = listOf(mcVer),
                                                                                downloadUrl = rel.downloadUrl,
                                                                                fileName = rel.fileName.ifBlank { detailProj.fileName },
                                                                                sizeBytes = rel.sizeBytes
                                                                            )
                                                                            if (mcVer.contains("26.3") || mcVer.contains("26.2")) {
                                                                                startItemDownload(dlItem)
                                                                            } else {
                                                                                pendingIncompatibleItem = dlItem
                                                                            }
                                                                        }
                                                                ) {
                                                                    Row(
                                                                        modifier = Modifier
                                                                            .fillMaxWidth()
                                                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                                                        verticalAlignment = Alignment.CenterVertically,
                                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                                    ) {
                                                                        Row(
                                                                            verticalAlignment = Alignment.CenterVertically,
                                                                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                                                                            modifier = Modifier.weight(1f)
                                                                        ) {
                                                                            // Green circular 'R' badge (`00:11` & `00:34` in 429798.mp4)
                                                                            Box(
                                                                                contentAlignment = Alignment.Center,
                                                                                modifier = Modifier
                                                                                    .size(28.dp)
                                                                                    .clip(CircleShape)
                                                                                    .background(Color(0xFF1E4628))
                                                                            ) {
                                                                                Text(
                                                                                    text = rel.releaseType.take(1).uppercase(),
                                                                                    fontSize = 12.sp,
                                                                                    fontWeight = FontWeight.ExtraBold,
                                                                                    color = Color(0xFF4CAF50)
                                                                                )
                                                                            }

                                                                            Column(modifier = Modifier.weight(1f)) {
                                                                                Text(
                                                                                    text = rel.name,
                                                                                    fontSize = 12.sp,
                                                                                    fontWeight = FontWeight.Bold,
                                                                                    color = Color.White,
                                                                                    maxLines = 1,
                                                                                    overflow = TextOverflow.Ellipsis
                                                                                )
                                                                                Text(
                                                                                    text = "↗ ${rel.formattedDownloads}   •   📅 ${rel.datePublished}   •   📦 ${rel.releaseType.replaceFirstChar { it.uppercase() }}",
                                                                                    fontSize = 10.sp,
                                                                                    color = MutedSubtitleText,
                                                                                    maxLines = 1
                                                                                )
                                                                            }
                                                                        }

                                                                        Icon(
                                                                            imageVector = Icons.Default.Download,
                                                                            contentDescription = "Download Version",
                                                                            tint = SkyBlueAccent,
                                                                            modifier = Modifier.size(18.dp)
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
                    }

                    // Right Project Summary Card (`00:10`–`00:16` & `00:33`–`00:39` in 429798.mp4)
                    val isFav = detailProj.id in favoriteIds
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = CardSlateBg,
                        modifier = Modifier
                            .width(224.dp)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(14.dp)
                                .verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                LavenderTagBadge(text = detailProj.category.label)

                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = CardRowBg,
                                    modifier = Modifier.animatedPressScale {
                                        favoriteIds = HorizonNativeBridge.toggleProjectFavorite(context, detailProj.id)
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                            contentDescription = "Favorite",
                                            tint = if (isFav) Color(0xFFFF6E6E) else Color.White,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Text("Favorite", fontSize = 10.sp, color = Color.White)
                                    }
                                }
                            }

                            Spacer(Modifier.height(2.dp))

                            StoreAsyncProjectIcon(
                                iconUrl = detailProj.iconUrl,
                                title = detailProj.title,
                                isMod = detailProj.category == StoreCategory.MOD || detailProj.category == StoreCategory.MODPACK,
                                isWorld = detailProj.category == StoreCategory.SAVES,
                                size = 66.dp
                            )

                            Text(
                                text = detailProj.title,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                textAlign = TextAlign.Center
                            )

                            Text(
                                text = detailProj.summary,
                                fontSize = 11.sp,
                                color = Color(0xFFD0D8DE),
                                lineHeight = 15.sp
                            )

                            Spacer(Modifier.height(4.dp))

                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = "Related Links",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )

                                if (detailProj.pageUrl.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animatedPressScale {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detailProj.pageUrl)))
                                                }
                                            }
                                            .padding(vertical = 3.dp)
                                    ) {
                                        Icon(Icons.Default.Language, contentDescription = null, tint = Color(0xFFD5DEE5), modifier = Modifier.size(16.dp))
                                        Text("Project Page", fontSize = 11.sp, color = Color(0xFFD5DEE5))
                                    }
                                }

                                if (detailProj.sourceCodeUrl.isNotBlank()) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .animatedPressScale {
                                                runCatching {
                                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(detailProj.sourceCodeUrl)))
                                                }
                                            }
                                            .padding(vertical = 3.dp)
                                    ) {
                                        Icon(Icons.Default.Code, contentDescription = null, tint = Color(0xFFD5DEE5), modifier = Modifier.size(16.dp))
                                        Text("View Source Code", fontSize = 11.sp, color = Color(0xFFD5DEE5))
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
private fun StoreRightSearchPill(
    value: String,
    placeholder: String,
    onValueChange: (String) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFF1A2024),
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .border(1.dp, Color(0xFF455159), RoundedCornerShape(50))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 12.sp, color = MutedSubtitleText)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    cursorBrush = SolidColor(SkyBlueAccent),
                    modifier = Modifier.fillMaxWidth()
                )
            }
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = placeholder,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun StorePlatformBadge(platform: StoreTargetPlatform) {
    val bgColor = when (platform) {
        StoreTargetPlatform.MODRINTH -> Color(0xFF1BD96A)
        StoreTargetPlatform.HORIZON -> SkyBlueAccent
        StoreTargetPlatform.ALL -> LavenderBadgeBg
    }
    val txtColor = Color(0xFF0A2012)
    Surface(
        shape = RoundedCornerShape(50),
        color = bgColor
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = if (platform == StoreTargetPlatform.HORIZON) Icons.Default.Bolt else Icons.Default.Explore,
                contentDescription = null,
                tint = txtColor,
                modifier = Modifier.size(11.dp)
            )
            Text(
                text = platform.label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = txtColor
            )
        }
    }
}

@Composable
private fun StoreAsyncProjectIcon(
    iconUrl: String,
    title: String,
    isMod: Boolean,
    isWorld: Boolean,
    size: Dp = 44.dp
) {
    var loadedBmp by remember(iconUrl) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(iconUrl) {
        if (iconUrl.isNotBlank()) {
            loadedBmp = withContext(Dispatchers.IO) {
                HorizonNativeBridge.loadProjectIconBitmap(iconUrl)
            }
        }
    }
    if (loadedBmp != null) {
        Image(
            bitmap = loadedBmp!!.asImageBitmap(),
            contentDescription = title,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
        )
    } else {
        StoreOrFileItemIconBadge(
            title = title,
            isMod = isMod,
            isWorld = isWorld,
            size = size
        )
    }
}

@Composable
private fun StoreOrFileItemIconBadge(
    title: String,
    isMod: Boolean,
    isWorld: Boolean,
    size: Dp = 44.dp
) {
    val bgBrush = when {
        isWorld -> Brush.linearGradient(listOf(Color(0xFF43A047), Color(0xFF5D4037)))
        isMod -> Brush.linearGradient(listOf(Color(0xFF3949AB), Color(0xFF26A69A)))
        else -> Brush.linearGradient(listOf(Color(0xFF5E35B1), Color(0xFF00897B)))
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(bgBrush)
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
    ) {
        if (isWorld) {
            MinecraftGrassCubeIcon(size = size * 0.76f)
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

enum class FilesSortMode(val label: String) {
    BY_NAME("Sort by Name"),
    BY_FILE_NAME("Sort by File Name"),
    BY_LAST_MODIFIED("Sort by Last Played")
}

/**
 * Manage Versions / Files Viewport matching 429799.mp4 100%:
 * - Left Sub-Navigation Rail: `Mods`, `Saves`, `Resource Packs`, `Shader Packs`, `Screenshots`
 * - Top Action Toolbar: Filter (`▽` on Mods), Sort (`≡↓` with floating popup menu `Sort by Name` / `Sort by File Name`),
 *   Pill Search Bar, `[ ] Available` checkbox, `+ Import`, `↓ Download` (switches to Store tab), `↻` Refresh.
 * - Floating `Save Info` Popover (`00:12` in 429799.mp4) with copyable `World Seed`.
 * - Floating `Resource Pack Information` Popover (`00:19` in 429799.mp4) with `Format Version` from `pack.mcmeta`.
 * - Floating `•••` More Options Menu (`Quick Play`, `Rename`, `Backup Save`, `Delete` on Saves; `Rename`, `Delete` on Packs).
 */
@Composable
private fun CleanFilesViewport(
    profileSlot: Int,
    logEnabled: Boolean,
    onNavigateToStoreCategory: (StoreCategory) -> Unit,
    onQuickPlayWorld: () -> Unit
) {
    val context = LocalContext.current
    val home = remember(profileSlot) {
        HorizonNativeBridge.getProfileHome(HorizonNativeBridge.ensureHomeDirectories(context), profileSlot)
    }
    var currentFolder by remember { mutableStateOf("mods") }
    var searchQuery by remember { mutableStateOf("") }
    var filterEnabledOnly by remember { mutableStateOf(false) }
    var sortMode by remember { mutableStateOf(FilesSortMode.BY_NAME) }
    var showSortDropdown by remember { mutableStateOf(false) }
    var refreshCount by remember { mutableIntStateOf(0) }

    // For Rename Dialog
    var renameTargetFile by remember { mutableStateOf<File?>(null) }
    var renameInputText by remember { mutableStateOf("") }

    val categories = listOf(
        Triple("mods", "Mods", Icons.Default.Extension),
        Triple("saves", "Saves", Icons.Default.Public),
        Triple("resourcepacks", "Resource Packs", Icons.Default.Brush),
        Triple("shaderpacks", "Shader Packs", Icons.Default.Lightbulb),
        Triple("screenshots", "Screenshots", Icons.Default.Image)
    )

    val dirFile = File(home, currentFolder).apply { mkdirs() }
    val rawList = remember(currentFolder, refreshCount, profileSlot) {
        dirFile.listFiles()?.toList().orEmpty()
    }

    val filteredList = remember(rawList, searchQuery, filterEnabledOnly, currentFolder, sortMode, refreshCount) {
        val base = rawList.filter { file ->
            val matchesQuery = searchQuery.isBlank() || file.name.contains(searchQuery, ignoreCase = true)
            val matchesEnabled = if (!filterEnabledOnly || currentFolder in listOf("saves", "screenshots")) true
            else HorizonNativeBridge.isContentItemEnabled(context, currentFolder, file.name, profileSlot)
            matchesQuery && matchesEnabled
        }
        when (sortMode) {
            FilesSortMode.BY_NAME -> base.sortedBy { it.nameWithoutExtension.lowercase(Locale.US) }
            FilesSortMode.BY_FILE_NAME -> base.sortedBy { it.name.lowercase(Locale.US) }
            FilesSortMode.BY_LAST_MODIFIED -> base.sortedByDescending { it.lastModified() }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val ext = when (currentFolder) {
                "mods" -> ".hmod"
                "screenshots" -> ".png"
                else -> ".zip"
            }
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

    // Rename Modal Dialog
    renameTargetFile?.let { target ->
        AlertDialog(
            onDismissRequest = { renameTargetFile = null },
            title = { Text("Rename ${target.name}", style = MaterialTheme.typography.titleMedium) },
            text = {
                OutlinedTextField(
                    value = renameInputText,
                    onValueChange = { renameInputText = it },
                    label = { Text("New Name", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (HorizonNativeBridge.renameContentFile(context, target, renameInputText, logEnabled)) {
                            refreshCount += 1
                        }
                        renameTargetFile = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SkyBlueAccent)
                ) {
                    Text("Save", color = SkyBlueDarkText, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTargetFile = null }) {
                    Text("Cancel", fontSize = 12.sp)
                }
            }
        )
    }

    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Left Sub-Navigation Rail (`Mods`, `Saves`, `Resource Packs`, `Shader Packs`, `Screenshots` — matching 429799.mp4)
        ZalithSubCategoryRail(
            items = categories,
            selectedItem = categories.first { it.first == currentFolder },
            itemLabel = { it.second },
            itemIcon = { it.third },
            onSelect = { currentFolder = it.first }
        )

        // Right Main Rounded Slate Container (`429799.mp4` style)
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
                    if (currentFolder == "mods") {
                        Icon(
                            imageVector = Icons.Default.FilterAlt,
                            contentDescription = "Filter",
                            tint = if (filterEnabledOnly) SkyBlueAccent else Color.White,
                            modifier = Modifier
                                .size(19.dp)
                                .animatedPressScale { filterEnabledOnly = !filterEnabledOnly }
                        )
                    }

                    // Sort Icon with Floating Dropdown (`Sort by Name` / `Sort by File Name` — matching 00:16 of 429799.mp4)
                    Box {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort",
                            tint = Color.White,
                            modifier = Modifier
                                .size(19.dp)
                                .animatedPressScale { showSortDropdown = true }
                        )
                        DropdownMenu(
                            expanded = showSortDropdown,
                            onDismissRequest = { showSortDropdown = false },
                            modifier = Modifier.background(Color(0xFF283036))
                        ) {
                            FilesSortMode.entries.forEach { mode ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = mode.label,
                                            fontSize = 12.sp,
                                            fontWeight = if (sortMode == mode) FontWeight.Bold else FontWeight.Normal,
                                            color = if (sortMode == mode) SkyBlueAccent else Color.White
                                        )
                                    },
                                    onClick = {
                                        sortMode = mode
                                        showSortDropdown = false
                                    }
                                )
                            }
                        }
                    }

                    // Pill Search Input
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = CardRowBg,
                        modifier = Modifier
                            .weight(1f)
                            .height(34.dp)
                            .border(1.dp, Color(0xFF3E4A52), RoundedCornerShape(50))
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

                    // `[ ] Available` checkbox shown on Saves & Resource Packs (matching 00:00, 00:11, 00:18 in 429799.mp4)
                    if (currentFolder in listOf("saves", "resourcepacks")) {
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

                    // `↓ Download` action button (switches directly to Store tab with matching category!)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .animatedPressScale {
                                val targetCat = when (currentFolder) {
                                    "mods" -> StoreCategory.MOD
                                    "saves" -> StoreCategory.SAVES
                                    "resourcepacks" -> StoreCategory.RESOURCE_PACK
                                    "shaderpacks" -> StoreCategory.SHADER_PACK
                                    else -> StoreCategory.MOD
                                }
                                onNavigateToStoreCategory(targetCat)
                            }
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
                        (fadeIn(tween(190)) + slideInVertically(tween(190)) { h -> h / 14 })
                            .togetherWith(fadeOut(tween(140)))
                            .using(sizeTransform = null)
                    },
                    modifier = Modifier.fillMaxSize(),
                    label = "FilesFolderTransition"
                ) { folder ->
                    if (filteredList.isEmpty()) {
                        // Centered dark pill empty state (`00:25` in 429799.mp4: "No shader packs for this version")
                        val emptyLabel = when (folder) {
                            "mods" -> "No mods for this version"
                            "saves" -> "No saves for this version"
                            "resourcepacks" -> "No resource packs for this version"
                            "shaderpacks" -> "No shader packs for this version"
                            "screenshots" -> "No screenshots for this version"
                            else -> "No items for this version"
                        }
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = CardRowBg
                            ) {
                                Text(
                                    text = emptyLabel,
                                    fontSize = 12.sp,
                                    color = Color(0xFFD5DEE5),
                                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                                )
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredList, key = { it.absolutePath }) { file ->
                                FileManagerRowCard(
                                    file = file,
                                    folder = folder,
                                    profileSlot = profileSlot,
                                    logEnabled = logEnabled,
                                    refreshCount = refreshCount,
                                    onTriggerRefresh = { refreshCount += 1 },
                                    onRequestRename = { target ->
                                        renameInputText = target.nameWithoutExtension
                                        renameTargetFile = target
                                    },
                                    onQuickPlayWorld = onQuickPlayWorld
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
private fun FileManagerRowCard(
    file: File,
    folder: String,
    profileSlot: Int,
    logEnabled: Boolean,
    refreshCount: Int,
    onTriggerRefresh: () -> Unit,
    onRequestRename: (File) -> Unit,
    onQuickPlayWorld: () -> Unit
) {
    val context = LocalContext.current
    var showInfoPopover by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }

    val isToggleable = folder in listOf("mods", "shaderpacks", "resourcepacks")
    val isEnabled = remember(file.name, refreshCount, folder) {
        if (isToggleable) {
            HorizonNativeBridge.isContentItemEnabled(context, folder, file.name, profileSlot)
        } else true
    }

    var localIconBmp by remember(file.absolutePath, file.lastModified()) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(file.absolutePath, file.lastModified()) {
        localIconBmp = withContext(Dispatchers.IO) {
            HorizonNativeBridge.loadLocalContentIconBitmap(file)
        }
    }

    val saveDetail = remember(file.absolutePath, file.lastModified(), folder) {
        if (folder == "saves") HorizonNativeBridge.inspectWorldSave(file) else null
    }
    val packDetail = remember(file.absolutePath, file.lastModified(), folder) {
        if (folder in listOf("resourcepacks", "shaderpacks")) HorizonNativeBridge.inspectResourcePack(file) else null
    }

    val displayTitle = when {
        saveDetail != null -> saveDetail.displayName
        packDetail != null -> packDetail.title
        else -> file.nameWithoutExtension.replace("-", " ").replace("_", " ")
    }

    val subtitleText = when {
        saveDetail != null -> "Last played: ${saveDetail.lastPlayedFormatted}"
        packDetail != null -> packDetail.description
        else -> "File Name: ${file.name}"
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
            // Left 44dp Icon (from pack.png / icon.png or badge)
            if (localIconBmp != null) {
                Image(
                    bitmap = localIconBmp!!.asImageBitmap(),
                    contentDescription = displayTitle,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(10.dp))
                )
            } else {
                StoreOrFileItemIconBadge(
                    title = displayTitle,
                    isMod = folder == "mods",
                    isWorld = folder == "saves",
                    size = 44.dp
                )
            }

            Spacer(Modifier.width(12.dp))

            // Middle Title + Lavender Badges + Subtitle (matching 429799.mp4)
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
                            if (file.name.endsWith(".hmod", true)) {
                                LavenderTagBadge(text = "Horizon")
                                LavenderTagBadge(text = "26.3")
                            } else {
                                LavenderTagBadge(text = "Fabric")
                                if (file.name.contains("quilt", true) || file.name.contains("config", true)) {
                                    LavenderTagBadge(text = "Quilt")
                                }
                            }
                        }
                        "saves" -> {
                            LavenderTagBadge(text = "26.3")
                            LavenderTagBadge(text = saveDetail?.gameMode ?: "Survival Mode")
                        }
                    }
                }
                Text(
                    text = subtitleText,
                    fontSize = 11.sp,
                    color = MutedSubtitleText,
                    maxLines = 2,
                    lineHeight = 14.sp,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.width(10.dp))

            // Right Action Icons (matching 429799.mp4: Mods has `↻ ☑ ⓘ 🗑`; Saves/ResourcePacks/Shaders have `ⓘ •••`)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (folder == "mods") {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Reload",
                        tint = Color(0xFFD5DEE5),
                        modifier = Modifier
                            .size(19.dp)
                            .animatedPressScale(onClick = onTriggerRefresh)
                    )

                    Checkbox(
                        checked = isEnabled,
                        onCheckedChange = {
                            HorizonNativeBridge.toggleContentItem(context, folder, file.name, profileSlot, logEnabled)
                            onTriggerRefresh()
                        },
                        colors = CheckboxDefaults.colors(
                            checkedColor = SkyBlueAccent,
                            checkmarkColor = SkyBlueDarkText,
                            uncheckedColor = MutedSubtitleText
                        ),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // `ⓘ` Info Icon with Floating Popover Card (`00:12` Save Info & `00:19` Resource Pack Information in 429799.mp4!)
                Box {
                    Icon(
                        imageVector = Icons.Outlined.Info,
                        contentDescription = "Info",
                        tint = Color(0xFFD5DEE5),
                        modifier = Modifier
                            .size(20.dp)
                            .animatedPressScale { showInfoPopover = true }
                    )

                    DropdownMenu(
                        expanded = showInfoPopover,
                        onDismissRequest = { showInfoPopover = false },
                        modifier = Modifier
                            .widthIn(min = 250.dp, max = 320.dp)
                            .background(Color(0xFF283036))
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            if (saveDetail != null) {
                                // Exact `Save Info` Popover from 00:12 of 429799.mp4!
                                Text(
                                    text = "Save Info",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("File Name: ${saveDetail.folderName}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text("Game Mode: ${saveDetail.gameMode}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text("Difficulty: ${saveDetail.difficulty}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text("Play Time: ${saveDetail.playTimeFormatted}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "World Seed: ${saveDetail.worldSeed}",
                                        fontSize = 12.sp,
                                        color = Color(0xFFE0E6EB),
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy Seed",
                                        tint = Color.White,
                                        modifier = Modifier
                                            .size(15.dp)
                                            .animatedPressScale {
                                                HorizonNativeBridge.copyToClipboard(context, "World Seed", saveDetail.worldSeed)
                                            }
                                    )
                                }
                            } else if (packDetail != null) {
                                // Exact `Resource Pack Information` Popover from 00:19 of 429799.mp4!
                                Text(
                                    text = if (folder == "shaderpacks") "Shader Pack Information" else "Resource Pack Information",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("Resource Pack Type: ${packDetail.packType}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text("File Name: ${packDetail.fileName}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text("File Size: ${packDetail.fileSizeFormatted}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text("Format Version: ${packDetail.formatVersion}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                            } else {
                                Text(
                                    text = "Mod Information",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                                Spacer(Modifier.height(2.dp))
                                Text("File Name: ${file.name}", fontSize = 12.sp, color = Color(0xFFE0E6EB))
                                Text(
                                    text = "File Size: ${String.format(Locale.US, "%.2f KB", file.length() / 1024.0)}",
                                    fontSize = 12.sp,
                                    color = Color(0xFFE0E6EB)
                                )
                                Text(
                                    text = "Status: ${if (isEnabled) "Enabled" else "Disabled"}",
                                    fontSize = 12.sp,
                                    color = Color(0xFFE0E6EB)
                                )
                            }
                        }
                    }
                }

                if (folder == "mods") {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = "Delete",
                        tint = Color(0xFFD5DEE5),
                        modifier = Modifier
                            .size(20.dp)
                            .animatedPressScale {
                                file.deleteRecursively()
                                onTriggerRefresh()
                            }
                    )
                } else {
                    // `•••` More Options Button (`00:14` on Saves & `00:22` on Resource Packs in 429799.mp4!)
                    Box {
                        Icon(
                            imageVector = Icons.Default.MoreHoriz,
                            contentDescription = "More Options",
                            tint = Color(0xFFD5DEE5),
                            modifier = Modifier
                                .size(20.dp)
                                .animatedPressScale { showMoreMenu = true }
                        )

                        DropdownMenu(
                            expanded = showMoreMenu,
                            onDismissRequest = { showMoreMenu = false },
                            modifier = Modifier.background(Color(0xFF283036))
                        ) {
                            if (folder == "saves") {
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) },
                                    text = { Text("Quick Play", fontSize = 12.sp, color = Color.White) },
                                    onClick = {
                                        showMoreMenu = false
                                        onQuickPlayWorld()
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) },
                                    text = { Text("Rename", fontSize = 12.sp, color = Color.White) },
                                    onClick = {
                                        showMoreMenu = false
                                        onRequestRename(file)
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Save, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) },
                                    text = { Text("Backup Save", fontSize = 12.sp, color = Color.White) },
                                    onClick = {
                                        showMoreMenu = false
                                        HorizonNativeBridge.backupWorldSave(context, file, logEnabled)
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(17.dp)) },
                                    text = { Text("Delete", fontSize = 12.sp, color = Color(0xFFFF8A80)) },
                                    onClick = {
                                        showMoreMenu = false
                                        file.deleteRecursively()
                                        onTriggerRefresh()
                                    }
                                )
                            } else {
                                if (isToggleable) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                if (isEnabled) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                                contentDescription = null,
                                                tint = SkyBlueAccent,
                                                modifier = Modifier.size(17.dp)
                                            )
                                        },
                                        text = { Text(if (isEnabled) "Disable Pack" else "Enable Pack", fontSize = 12.sp, color = Color.White) },
                                        onClick = {
                                            showMoreMenu = false
                                            HorizonNativeBridge.toggleContentItem(context, folder, file.name, profileSlot, logEnabled)
                                            onTriggerRefresh()
                                        }
                                    )
                                }
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(17.dp)) },
                                    text = { Text("Rename", fontSize = 12.sp, color = Color.White) },
                                    onClick = {
                                        showMoreMenu = false
                                        onRequestRename(file)
                                    }
                                )
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = Color(0xFFFF8A80), modifier = Modifier.size(17.dp)) },
                                    text = { Text("Delete", fontSize = 12.sp, color = Color(0xFFFF8A80)) },
                                    onClick = {
                                        showMoreMenu = false
                                        file.deleteRecursively()
                                        onTriggerRefresh()
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
        ZalithSubCategoryRail(
            items = SettingsSubCategory.entries,
            selectedItem = selectedSection,
            itemLabel = { it.label },
            itemIcon = { it.icon },
            onSelect = { selectedSection = it }
        )

        AnimatedContent(
            targetState = selectedSection,
            transitionSpec = {
                (fadeIn(tween(190, easing = FastOutSlowInEasing)) +
                    slideInVertically(tween(190, easing = FastOutSlowInEasing)) { h -> h / 14 })
                    .togetherWith(fadeOut(tween(140)))
                    .using(sizeTransform = null)
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
    activeSkinBitmap: Bitmap,
    installStatus: AssetInstallStatus,
    onOpenAccountScreen: () -> Unit,
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
                    .animatedPressScale(onClick = onOpenAccountScreen)
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    MinecraftSkinHeadAvatar(
                        skinBitmap = activeSkinBitmap,
                        size = 34.dp,
                        cornerRadius = 9.dp
                    )
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
                            text = if (activeAccount.slimSkinModel) "${activeAccount.accountType.badge} • Slim"
                            else "${activeAccount.accountType.badge} • Wide",
                            fontSize = 10.sp,
                            color = SkyBlueAccent,
                            maxLines = 1
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
