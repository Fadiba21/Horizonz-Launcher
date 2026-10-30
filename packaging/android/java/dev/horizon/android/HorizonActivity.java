package dev.horizon.android;

import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;

import dev.horizon.android.ui.ZalithMainActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * NativeActivity for the Vasysik/horizon-android + horizon-rust/horizon 26.3 engine.
 * Includes:
 * - Guaranteed single-shot launch & clean return to ZalithMainActivity on game exit.
 * - Pure Black Minecraft Java Style "HORIZONZ" Pixel-Art Loading Splash Screen Overlay
 *   synced in real time with `zalith-game-boot.txt` until the Main Menu is ready!
 */
public final class HorizonActivity extends NativeActivity {
    private static final String TAG = "HorizonActivity";
    public static final int SAF_DOCUMENT_REQUEST_CODE = 4101;
    private volatile boolean watchdogRunning = true;
    private HorizonSplashOverlayView splashOverlayView;

    static {
        try {
            System.loadLibrary("horizon_android");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "libhorizon_android.so could not be preloaded: " + e.getMessage());
        }
    }

    private native void nativeDocumentResult(int request, int result, String uri);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        File homeDir = getFilesDir();
        File tokenFile = new File(homeDir, "zalith-launch-token.txt");
        File exitFlag = new File(homeDir, "zalith-game-exited.flag");

        // Guard against Android ActivityManager auto-restarting HorizonActivity after Quit Game!
        if (savedInstanceState != null || exitFlag.exists() || !tokenFile.exists()) {
            Log.i(TAG, "No active launch token (or exit flag present) — returning to ZalithMainActivity.");
            exitFlag.delete();
            tokenFile.delete();
            returnToLauncherAndFinish();
            super.onCreate(null);
            return;
        }

        // Consume the one-time launch token immediately so any subsequent auto-restart is blocked
        tokenFile.delete();
        exitFlag.delete();

        // Initialize boot progress file before starting native engine
        writeBootMilestone(homeDir, "12|Initializing HorizonZ Native Runtime...");

        appendCrossProcessLog(homeDir, "[GameProcess] Starting HorizonActivity (:game process) for Minecraft 26.3.");
        ensureEngineDataExtracted();
        applyZalithLaunchEnvironment(getIntent());
        applyRequestedOrientationFromIntent(getIntent());
        enableImmersiveFullscreen();
        startExitWatchdog(homeDir);
        super.onCreate(savedInstanceState);

        // Attach pure-black Minecraft Java style "HORIZONZ" Loading Splash Overlay on top of NativeActivity
        attachHorizonSplashOverlay(homeDir);
    }

    private void attachHorizonSplashOverlay(final File homeDir) {
        try {
            splashOverlayView = new HorizonSplashOverlayView(this, homeDir);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            );
            addContentView(splashOverlayView, lp);
            splashOverlayView.bringToFront();
        } catch (Exception e) {
            Log.w(TAG, "Could not attach splash overlay: " + e.getMessage());
        }
    }

    private void writeBootMilestone(File homeDir, String content) {
        try {
            File bootFile = new File(homeDir, "zalith-game-boot.txt");
            try (FileOutputStream fos = new FileOutputStream(bootFile, false)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void startExitWatchdog(final File homeDir) {
        Thread watchdog = new Thread(() -> {
            File exitFlag = new File(homeDir, "zalith-game-exited.flag");
            while (watchdogRunning) {
                try {
                    if (exitFlag.exists()) {
                        exitFlag.delete();
                        appendCrossProcessLog(homeDir, "[GameProcess] Quit Game detected — closing :game activity and returning to HorizonZ Launcher.");
                        runOnUiThread(this::returnToLauncherAndFinish);
                        break;
                    }
                    Thread.sleep(150L);
                } catch (InterruptedException ignored) {
                    break;
                }
            }
        }, "HorizonZ-ExitWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void returnToLauncherAndFinish() {
        watchdogRunning = false;
        if (splashOverlayView != null) {
            splashOverlayView.stopPolling();
        }
        try {
            Intent backIntent = new Intent(this, ZalithMainActivity.class);
            backIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(backIntent);
        } catch (Exception ignored) {
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        } catch (Exception ignored) {
            finish();
        }
        new Thread(() -> {
            try {
                Thread.sleep(180L);
            } catch (InterruptedException ignored) {
            }
            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(0);
        }).start();
    }

    @Override
    protected void onDestroy() {
        watchdogRunning = false;
        if (splashOverlayView != null) {
            splashOverlayView.stopPolling();
        }
        super.onDestroy();
    }

    private void applyRequestedOrientationFromIntent(Intent intent) {
        if (intent == null) return;
        String orient = intent.getStringExtra("HORIZON_ORIENTATION");
        if (orient == null) return;
        switch (orient) {
            case "landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                break;
            case "reverse-landscape":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                break;
            case "portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                break;
            case "reverse-portrait":
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                break;
            case "auto":
            default:
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
                break;
        }
    }

    private void appendCrossProcessLog(File homeDir, String msg) {
        try {
            File logFile = new File(homeDir, "zalith-system.log");
            String line = msg + "\n";
            try (FileOutputStream fos = new FileOutputStream(logFile, true)) {
                fos.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureEngineDataExtracted() {
        try {
            File homeDir = getFilesDir();
            if (homeDir == null) return;
            if (!homeDir.exists()) homeDir.mkdirs();
            File engineZip = new File(homeDir, "engine-data.zip");
            if (!engineZip.exists() || engineZip.length() < 1024) {
                try (InputStream in = getAssets().open("engine-data.zip");
                     FileOutputStream out = new FileOutputStream(engineZip)) {
                    byte[] buf = new byte[65536];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not pre-extract engine-data.zip: " + e.getMessage());
        }
    }

    private void applyZalithLaunchEnvironment(Intent intent) {
        try {
            File homeDir = getFilesDir();
            if (homeDir != null) {
                Os.setenv("HORIZON_HOME", homeDir.getAbsolutePath(), true);
                Os.setenv("HORIZON_DIRECT_GAME", "1", true);
            }
            if (intent != null) {
                boolean publicMode = intent.getBooleanExtra("HORIZON_ANDROID_PUBLIC", true);
                Os.setenv("HORIZON_ANDROID_PUBLIC", publicMode ? "1" : "0", true);

                boolean disableAudio = intent.getBooleanExtra("HORIZON_NO_AUDIO", false);
                if (disableAudio) {
                    Os.setenv("HORIZON_NO_AUDIO", "1", true);
                    Os.setenv("ALSOFT_DISABLE_EVENTS", "1", true);
                }

                boolean disableDiscord = intent.getBooleanExtra("HORIZON_NO_DISCORD", false);
                if (disableDiscord) {
                    Os.setenv("HORIZON_NO_DISCORD", "1", true);
                }

                String playerName = intent.getStringExtra("HORIZON_PLAYER_NAME");
                if (playerName != null && !playerName.isEmpty()) {
                    Os.setenv("HORIZON_PLAYER_NAME", playerName, true);
                }

                String renderBackend = intent.getStringExtra("HORIZON_WGPU_BACKEND");
                if (renderBackend != null && !renderBackend.isEmpty()) {
                    Os.setenv("WGPU_BACKEND", renderBackend, true);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to set environment variables for Horizon native engine", e);
        }
    }

    private void enableImmersiveFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        View decorView = getWindow().getDecorView();
        decorView.setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_FULLSCREEN
        );
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAF_DOCUMENT_REQUEST_CODE) {
            String uriString = null;
            if (data != null && data.getData() != null) {
                Uri uri = data.getData();
                try {
                    final int takeFlags = data.getFlags()
                        & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    getContentResolver().takePersistableUriPermission(uri, takeFlags);
                } catch (SecurityException ignored) {
                }
                uriString = uri.toString();
            }
            try {
                nativeDocumentResult(request, result, uriString);
            } catch (UnsatisfiedLinkError err) {
                Log.e(TAG, "nativeDocumentResult JNI symbol not linked: " + err.getMessage());
            }
        }
    }

    /**
     * Pure Black Minecraft Java Edition Style Loading Splash Overlay:
     * - Draws "HORIZONZ" using an 8-bit blocky Minecraft pixel font with 3D pixel depth shadow.
     * - Draws the classic Minecraft Java Edition rectangular white-bordered progress bar below.
     * - Polls `zalith-game-boot.txt` written by `crates/horizon-client` so the loading bar tracks
     *   real game initialization and smoothly fades out once the Main Menu (`100|Main Menu Ready`) is loaded!
     */
    private static final class HorizonSplashOverlayView extends View {
        private final File bootFile;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pixelFacePaint = new Paint();
        private final Paint pixelShadowPaint = new Paint();
        private final Paint pixelHighlightPaint = new Paint();
        private final Paint barBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint barFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint subTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect = new RectF();

        private float displayedProgress = 0.08f;
        private float targetProgress = 0.15f;
        private String stageLabel = "Initializing HorizonZ Rust Engine...";
        private boolean engineReady = false;
        private long createdTimeMs = System.currentTimeMillis();
        private long readyTimestampMs = 0L;
        private float overlayAlpha = 1.0f;
        private boolean active = true;

        // 5x7 Blocky Minecraft Pixel Font Glyphs for H O R I Z N
        private static final int[][] GLYPH_H = {
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,1,1,1,1},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,0,0,0,1}
        };
        private static final int[][] GLYPH_O = {
            {0,1,1,1,0},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {0,1,1,1,0}
        };
        private static final int[][] GLYPH_R = {
            {1,1,1,1,0},
            {1,0,0,0,1},
            {1,0,0,0,1},
            {1,1,1,1,0},
            {1,0,1,0,0},
            {1,0,0,1,0},
            {1,0,0,0,1}
        };
        private static final int[][] GLYPH_I = {
            {1,1,1,1,1},
            {0,0,1,0,0},
            {0,0,1,0,0},
            {0,0,1,0,0},
            {0,0,1,0,0},
            {0,0,1,0,0},
            {1,1,1,1,1}
        };
        private static final int[][] GLYPH_Z = {
            {1,1,1,1,1},
            {0,0,0,0,1},
            {0,0,0,1,0},
            {0,0,1,0,0},
            {0,1,0,0,0},
            {1,0,0,0,0},
            {1,1,1,1,1}
        };
        private static final int[][] GLYPH_N = {
            {1,0,0,0,1},
            {1,1,0,0,1},
            {1,0,1,0,1},
            {1,0,1,0,1},
            {1,0,0,1,1},
            {1,0,0,0,1},
            {1,0,0,0,1}
        };

        private static final int[][][] WORD_HORIZONZ = {
            GLYPH_H, GLYPH_O, GLYPH_R, GLYPH_I, GLYPH_Z, GLYPH_O, GLYPH_N, GLYPH_Z
        };

        private final Runnable frameLoop = new Runnable() {
            @Override
            public void run() {
                if (!active) return;
                pollBootMilestone();
                updateAnimationState();
                invalidate();
                if (active) {
                    handler.postDelayed(this, 16L);
                }
            }
        };

        HorizonSplashOverlayView(Context context, File homeDir) {
            super(context);
            this.bootFile = new File(homeDir, "zalith-game-boot.txt");

            bgPaint.setColor(Color.BLACK);
            bgPaint.setStyle(Paint.Style.FILL);

            pixelFacePaint.setColor(Color.WHITE);
            pixelFacePaint.setStyle(Paint.Style.FILL);

            pixelShadowPaint.setColor(Color.parseColor("#2A353D"));
            pixelShadowPaint.setStyle(Paint.Style.FILL);

            pixelHighlightPaint.setColor(Color.parseColor("#8BE0FF"));
            pixelHighlightPaint.setStyle(Paint.Style.FILL);

            barBorderPaint.setColor(Color.WHITE);
            barBorderPaint.setStyle(Paint.Style.STROKE);

            barFillPaint.setColor(Color.WHITE);
            barFillPaint.setStyle(Paint.Style.FILL);

            textPaint.setColor(Color.parseColor("#D5DEE5"));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setFakeBoldText(true);

            subTextPaint.setColor(Color.parseColor("#8BE0FF"));
            subTextPaint.setTextAlign(Paint.Align.CENTER);
            subTextPaint.setFakeBoldText(true);

            handler.post(frameLoop);
        }

        void stopPolling() {
            active = false;
            handler.removeCallbacks(frameLoop);
        }

        private void pollBootMilestone() {
            if (!bootFile.exists()) return;
            try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(bootFile), StandardCharsets.UTF_8)
            )) {
                String line = br.readLine();
                if (line != null && !line.isEmpty()) {
                    int sep = line.indexOf('|');
                    if (sep > 0) {
                        int pct = Integer.parseInt(line.substring(0, sep).trim());
                        String msg = line.substring(sep + 1).trim();
                        if (!msg.isEmpty()) {
                            stageLabel = msg;
                        }
                        if (pct >= 100) {
                            engineReady = true;
                            targetProgress = 1.0f;
                        } else {
                            targetProgress = Math.max(targetProgress, Math.min(0.95f, pct / 100.0f));
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        private void updateAnimationState() {
            long now = System.currentTimeMillis();
            long elapsed = now - createdTimeMs;

            if (!engineReady) {
                // Smoothly advance target progress through realistic stages while Rust engine loads resources
                if (elapsed > 500L && targetProgress < 0.32f) {
                    targetProgress = 0.32f;
                    stageLabel = "Creating Vulkan / WGPU Surface...";
                } else if (elapsed > 1200L && targetProgress < 0.58f) {
                    targetProgress = 0.58f;
                    stageLabel = "Loading Minecraft 26.3 Registries & Atlases...";
                } else if (elapsed > 2200L && targetProgress < 0.82f) {
                    targetProgress = 0.82f;
                    stageLabel = "Applying Custom Player Skin & Audio Engine...";
                } else if (elapsed > 3200L && targetProgress < 0.94f) {
                    targetProgress = Math.min(0.94f, targetProgress + 0.0018f);
                    stageLabel = "Preparing Panorama & Main Menu...";
                }
            }

            // Smooth interpolation toward targetProgress
            float speed = engineReady ? 0.14f : 0.08f;
            displayedProgress += (targetProgress - displayedProgress) * speed;

            // Ensure splash stays visible at least 1.6s so the user gets a smooth full progress bar
            if (engineReady && displayedProgress >= 0.985f && elapsed >= 1650L) {
                displayedProgress = 1.0f;
                stageLabel = "Minecraft 26.3 Ready";
                if (readyTimestampMs == 0L) {
                    readyTimestampMs = now;
                }
                long sinceReady = now - readyTimestampMs;
                if (sinceReady > 220L) {
                    float fadeProgress = (sinceReady - 220L) / 420.0f;
                    overlayAlpha = Math.max(0.0f, 1.0f - fadeProgress);
                    if (overlayAlpha <= 0.01f) {
                        active = false;
                        setVisibility(View.GONE);
                        if (getParent() instanceof ViewGroup) {
                            ((ViewGroup) getParent()).removeView(this);
                        }
                    }
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;

            int alphaInt = Math.max(0, Math.min(255, (int) (overlayAlpha * 255.0f)));
            bgPaint.setAlpha(alphaInt);
            canvas.drawRect(0, 0, w, h, bgPaint);

            float density = getResources().getDisplayMetrics().density;
            // Draw blocky Minecraft pixel-art "HORIZONZ" title in the center
            int letterCols = 5;
            int letterRows = 7;
            int letterGapCols = 1;
            int totalCols = WORD_HORIZONZ.length * letterCols + (WORD_HORIZONZ.length - 1) * letterGapCols;

            float pixelSize = Math.max(6f, Math.min(w * 0.56f / totalCols, h * 0.022f));
            float logoWidth = totalCols * pixelSize;
            float logoHeight = letterRows * pixelSize;
            float startX = (w - logoWidth) / 2f;
            float startY = (h * 0.40f) - (logoHeight / 2f);
            float shadowOffset = Math.max(2f, pixelSize * 0.36f);

            pixelShadowPaint.setAlpha(alphaInt);
            pixelFacePaint.setAlpha(alphaInt);
            pixelHighlightPaint.setAlpha(alphaInt);

            for (int l = 0; l < WORD_HORIZONZ.length; l++) {
                int[][] glyph = WORD_HORIZONZ[l];
                float letterX = startX + l * (letterCols + letterGapCols) * pixelSize;
                // Highlight the final 'Z' in sky-blue Minecraft accent, others in crisp white
                Paint mainPaint = (l == WORD_HORIZONZ.length - 1) ? pixelHighlightPaint : pixelFacePaint;

                // Pass 1: 3D Blocky Shadow
                for (int r = 0; r < letterRows; r++) {
                    for (int c = 0; c < letterCols; c++) {
                        if (glyph[r][c] == 1) {
                            float px = letterX + c * pixelSize;
                            float py = startY + r * pixelSize;
                            canvas.drawRect(
                                px + shadowOffset,
                                py + shadowOffset,
                                px + pixelSize + shadowOffset + 0.5f,
                                py + pixelSize + shadowOffset + 0.5f,
                                pixelShadowPaint
                            );
                        }
                    }
                }

                // Pass 2: Crisp Foreground Pixel Blocks
                for (int r = 0; r < letterRows; r++) {
                    for (int c = 0; c < letterCols; c++) {
                        if (glyph[r][c] == 1) {
                            float px = letterX + c * pixelSize;
                            float py = startY + r * pixelSize;
                            canvas.drawRect(
                                px,
                                py,
                                px + pixelSize + 0.5f,
                                py + pixelSize + 0.5f,
                                mainPaint
                            );
                        }
                    }
                }
            }

            // Subtitle under HORIZONZ logo
            subTextPaint.setTextSize(11.5f * density);
            subTextPaint.setAlpha((int) (alphaInt * 0.88f));
            canvas.drawText(
                "MINECRAFT 26.3  •  NATIVE RUST ENGINE",
                w / 2f,
                startY + logoHeight + (22f * density),
                subTextPaint
            );

            // Classic Minecraft Java Edition Progress Bar (Outer white frame + inner gap + solid bar)
            float barWidth = Math.min(w * 0.58f, 440f * density);
            float barHeight = 16f * density;
            float barLeft = (w - barWidth) / 2f;
            float barTop = h * 0.72f;
            float borderStroke = Math.max(2f, 2.0f * density);
            float innerPad = Math.max(3f, 3.2f * density);

            barBorderPaint.setStrokeWidth(borderStroke);
            barBorderPaint.setAlpha(alphaInt);
            tmpRect.set(barLeft, barTop, barLeft + barWidth, barTop + barHeight);
            canvas.drawRect(tmpRect, barBorderPaint);

            float maxFillWidth = (barWidth - innerPad * 2f);
            float fillWidth = Math.max(0f, Math.min(maxFillWidth, maxFillWidth * displayedProgress));
            if (fillWidth > 0f) {
                barFillPaint.setAlpha(alphaInt);
                tmpRect.set(
                    barLeft + innerPad,
                    barTop + innerPad,
                    barLeft + innerPad + fillWidth,
                    barTop + barHeight - innerPad
                );
                canvas.drawRect(tmpRect, barFillPaint);
            }

            // Stage text & Percentage below the loading bar
            int pctInt = Math.max(1, Math.min(100, Math.round(displayedProgress * 100f)));
            textPaint.setTextSize(11.5f * density);
            textPaint.setAlpha((int) (alphaInt * 0.85f));
            canvas.drawText(
                stageLabel + " (" + pctInt + "%)",
                w / 2f,
                barTop + barHeight + (19f * density),
                textPaint
            );
        }
    }
}
