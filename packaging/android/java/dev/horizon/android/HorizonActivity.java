package dev.horizon.android;

import android.app.NativeActivity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;

import dev.horizon.android.ui.ZalithMainActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * NativeActivity for the Vasysik/horizon-android + horizon-rust/horizon 26.3 engine.
 * Guaranteed single-shot launch & clean return to ZalithMainActivity on game exit.
 */
public final class HorizonActivity extends NativeActivity {
    private static final String TAG = "HorizonActivity";
    public static final int SAF_DOCUMENT_REQUEST_CODE = 4101;
    private volatile boolean watchdogRunning = true;

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

        appendCrossProcessLog(homeDir, "[GameProcess] Starting HorizonActivity (:game process) for Minecraft 26.3.");
        ensureEngineDataExtracted();
        applyZalithLaunchEnvironment(getIntent());
        applyRequestedOrientationFromIntent(getIntent());
        enableImmersiveFullscreen();
        startExitWatchdog(homeDir);
        super.onCreate(savedInstanceState);
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
}
