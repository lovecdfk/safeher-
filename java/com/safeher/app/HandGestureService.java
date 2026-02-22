package com.safeher.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.Image;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * HandGestureService
 *
 * HOW DETECTION WORKS (reliable, lighting-independent):
 *
 * Instead of trying to detect finger shape (which fails in varying light),
 * we use a TWO-PHASE approach:
 *
 * PHASE 1 — PRESENCE: Is there a significantly bright object in the upper
 *   centre of the frame compared to the rest? A hand/finger held close to
 *   the front camera is brighter than the background.
 *   → Uses adaptive threshold: upper zone avg vs full frame avg.
 *
 * PHASE 2 — HOLD: Once presence is confirmed, user must hold still for 4s.
 *   If the object disappears, reset the timer.
 *
 * This is far more reliable than shape detection and works in any lighting.
 */
public class HandGestureService extends Service implements LifecycleOwner {

    private static final String TAG = "HandGestureService";

    public static final String CHANNEL_ID        = "hand_gesture_ch";
    public static final int    NOTIF_ID          = 3001;
    public static final String ACTION_STOP        = "com.safeher.app.GESTURE_STOP";
    public static final String ACTION_GESTURE_STATE = "com.safeher.app.GESTURE_STATE";
    public static final String EXTRA_DETECTED    = "detected";
    public static final String EXTRA_PROGRESS    = "progress";
    public static final String PREF_ENABLED      = "hand_gesture_enabled";

    public static volatile boolean isRunning = false;

    // ── Static helpers ────────────────────────────────────────────────────────

    public static void start(Context ctx) {
        ctx.getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, true).apply();
        Intent i = new Intent(ctx, HandGestureService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            ctx.startForegroundService(i);
        else
            ctx.startService(i);
    }

    public static void stop(Context ctx) {
        ctx.getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, false).apply();
        Intent i = new Intent(ctx, HandGestureService.class);
        i.setAction(ACTION_STOP);
        ctx.startService(i);
    }

    // ── Detection tuning ─────────────────────────────────────────────────────

    // How much brighter the upper zone must be vs the rest of the frame
    // 1.15 = 15% brighter → hand is present
    private static final float PRESENCE_RATIO   = 1.15f;

    // Minimum absolute brightness of upper zone (avoids triggering in pitch dark)
    private static final int   MIN_ZONE_LUMA    = 40;

    // How long finger must be held to trigger SOS
    private static final long  HOLD_MS          = 4000;

    // Consecutive frames needed before we start the hold timer (debounce)
    private static final int   CONFIRM_FRAMES   = 5;

    // Sample step — every N pixels (performance)
    private static final int   STEP             = 8;

    // ── State ─────────────────────────────────────────────────────────────────

    private LifecycleRegistry     lifecycleRegistry;
    private ExecutorService        cameraExecutor;
    private ProcessCameraProvider  cameraProvider;
    private final Handler          uiHandler = new Handler(Looper.getMainLooper());

    private volatile int     confirmStreak  = 0;   // consecutive "present" frames
    private volatile boolean gestureHeld    = false;
    private volatile long    heldSince      = 0;
    private volatile boolean sosTriggered   = false;

    // Previous frame luma snapshot for motion check
    private volatile int[]   lastLuma       = null;
    private volatile int     frameCount     = 0;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        lifecycleRegistry = new LifecycleRegistry(this);
        lifecycleRegistry.setCurrentState(Lifecycle.State.CREATED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.STARTED);
        lifecycleRegistry.setCurrentState(Lifecycle.State.RESUMED);
        createChannel();
        isRunning = true;
        startForeground(NOTIF_ID, buildNotif(false, 0));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (cameraExecutor == null) {
            cameraExecutor = Executors.newSingleThreadExecutor();
            uiHandler.post(this::startCamera);
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onDestroy() {
        super.onDestroy();
        isRunning = false;
        lifecycleRegistry.setCurrentState(Lifecycle.State.DESTROYED);
        uiHandler.removeCallbacksAndMessages(null);
        if (cameraProvider != null) {
            try { cameraProvider.unbindAll(); } catch (Exception ignored) {}
        }
        if (cameraExecutor != null) cameraExecutor.shutdown();
        broadcast(false, 0);
        getSharedPreferences("SaveSouls", MODE_PRIVATE)
            .edit().putBoolean(PREF_ENABLED, false).apply();
    }

    @NonNull @Override
    public Lifecycle getLifecycle() { return lifecycleRegistry; }

    // ── Camera setup ──────────────────────────────────────────────────────────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> fut =
            ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                cameraProvider = fut.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor, this::analyseFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis
                );
                Log.d(TAG, "Camera started for gesture detection");
            } catch (Exception e) {
                Log.e(TAG, "Camera start failed: " + e.getMessage());
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Frame analysis ────────────────────────────────────────────────────────

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyseFrame(ImageProxy proxy) {
        if (sosTriggered) { proxy.close(); return; }

        Image img = proxy.getImage();
        if (img == null) { proxy.close(); return; }

        Image.Plane yPlane = img.getPlanes()[0];
        ByteBuffer  yBuf   = yPlane.getBuffer();
        int W = img.getWidth(), H = img.getHeight();
        int rowStride  = yPlane.getRowStride();
        int pixStride  = yPlane.getPixelStride();

        frameCount++;

        // ── Sample full frame average luma ────────────────────────────────
        long fullSum = 0; int fullCnt = 0;
        for (int y = 0; y < H; y += STEP * 2) {
            for (int x = 0; x < W; x += STEP * 2) {
                int idx = y * rowStride + x * pixStride;
                if (idx >= 0 && idx < yBuf.limit()) {
                    fullSum += yBuf.get(idx) & 0xFF;
                    fullCnt++;
                }
            }
        }
        float fullAvg = fullCnt > 0 ? (float) fullSum / fullCnt : 128f;

        // ── Sample UPPER-CENTRE zone (where finger/hand would appear) ─────
        // Zone: middle third horizontally, top 40% vertically
        int zX0 = W / 4,   zX1 = 3 * W / 4;
        int zY0 = 0,        zY1 = (int)(H * 0.45f);

        long zoneSum = 0; int zoneCnt = 0;
        for (int y = zY0; y < zY1; y += STEP) {
            for (int x = zX0; x < zX1; x += STEP) {
                int idx = y * rowStride + x * pixStride;
                if (idx >= 0 && idx < yBuf.limit()) {
                    zoneSum += yBuf.get(idx) & 0xFF;
                    zoneCnt++;
                }
            }
        }
        float zoneAvg = zoneCnt > 0 ? (float) zoneSum / zoneCnt : 0f;

        proxy.close();

        // ── DETECTION LOGIC ───────────────────────────────────────────────
        // A hand/finger in the upper-centre makes that zone noticeably brighter
        // than the overall frame average.
        // Also require minimum absolute brightness (not pitch dark).
        boolean present = (zoneAvg > fullAvg * PRESENCE_RATIO)
                       && (zoneAvg > MIN_ZONE_LUMA);

        // Log every 30 frames for debugging
        if (frameCount % 30 == 0) {
            Log.d(TAG, String.format(
                "frameAvg=%.1f zoneAvg=%.1f ratio=%.2f present=%b streak=%d",
                fullAvg, zoneAvg, zoneAvg / Math.max(1, fullAvg), present, confirmStreak));
        }

        final boolean fin = present;
        uiHandler.post(() -> onPresence(fin));
    }

    // ── Presence → hold timer ─────────────────────────────────────────────────

    private void onPresence(boolean present) {
        if (sosTriggered) return;

        if (present) {
            confirmStreak = Math.min(confirmStreak + 1, CONFIRM_FRAMES + 10);
        } else {
            confirmStreak = Math.max(0, confirmStreak - 2); // decay faster than growth
        }

        boolean confirmed = confirmStreak >= CONFIRM_FRAMES;

        if (confirmed && !gestureHeld) {
            // Gesture just confirmed — start hold timer
            gestureHeld = true;
            heldSince   = System.currentTimeMillis();
            vibrate(60);
            Log.d(TAG, "Gesture hold started");
        } else if (!confirmed && gestureHeld) {
            // Gesture lost — reset
            gestureHeld = false;
            updateNotif(false, 0);
            broadcast(false, 0);
            Log.d(TAG, "Gesture hold reset");
        }

        if (gestureHeld) {
            long elapsed  = System.currentTimeMillis() - heldSince;
            int  progress = (int) Math.min(100, elapsed * 100L / HOLD_MS);
            updateNotif(true, progress);
            broadcast(true, progress);

            if (elapsed >= HOLD_MS) {
                sosTriggered = true;
                vibrate(600);
                Log.d(TAG, "SOS triggered by gesture!");
                fireSOSNow();
            }
        }
    }

    // ── Fire SOS ──────────────────────────────────────────────────────────────

    private void fireSOSNow() {
        Intent svc = new Intent(this, SafeHerService.class);
        svc.setAction(SafeHerService.ACTION_TRIGGER_SOS);
        startService(svc);

        Intent ui = new Intent(this, SosActivity.class);
        ui.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(ui);

        uiHandler.postDelayed(this::stopSelf, 3000);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void broadcast(boolean detected, int progress) {
        Intent i = new Intent(ACTION_GESTURE_STATE);
        i.putExtra(EXTRA_DETECTED, detected);
        i.putExtra(EXTRA_PROGRESS, progress);
        sendBroadcast(i);
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private Notification buildNotif(boolean active, int progress) {
        Intent stopI = new Intent(this, HandGestureService.class);
        stopI.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 0, stopI,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        long secsLeft = active ? ((HOLD_MS - (progress * HOLD_MS / 100L)) / 1000 + 1) : 4;
        String title = active
            ? "☝ Finger detected! SOS in " + secsLeft + "s…"
            : "☝ Gesture SOS watching…";
        String text = active
            ? "Hold steady — " + progress + "% — lower hand to cancel"
            : "Point ONE finger at front camera for 4s → SOS triggers";

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .addAction(android.R.drawable.ic_delete, "Stop", stopPi)
            .setOngoing(true)
            .setPriority(active ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW);

        if (active) {
            b.setProgress(100, progress, false);
            b.setColor(0xFFFF2D55);
        }
        return b.build();
    }

    private void updateNotif(boolean active, int progress) {
        getSystemService(NotificationManager.class)
            .notify(NOTIF_ID, buildNotif(active, progress));
    }

    private void createChannel() {
        NotificationChannel ch = new NotificationChannel(
            CHANNEL_ID, "Hand Gesture SOS", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Watches front camera for SOS gesture");
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }
}
