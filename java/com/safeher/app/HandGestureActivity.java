package com.safeher.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.Image;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ExperimentalGetImage;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HandGestureActivity extends AppCompatActivity {

    private static final String TAG        = "HandGestureActivity";
    private static final int    CAMERA_REQ = 201;
    private static final long   HOLD_MS    = 4000;

    // Same detection logic as HandGestureService
    private static final float PRESENCE_RATIO  = 1.15f;
    private static final int   MIN_ZONE_LUMA   = 40;
    private static final int   CONFIRM_FRAMES  = 5;
    private static final int   STEP            = 8;

    // UI
    private PreviewView previewView;
    private ImageView   ivFinger, ivRipple;
    private TextView    tvBack, tvStatus, tvCountdown, tvBgStatus, tvDebug;
    private ProgressBar progressBar;
    private View        layoutCountdown, layoutIdle;
    private Switch      switchEnable, switchBg;

    // Camera
    private ExecutorService       cameraExecutor;
    private ProcessCameraProvider cameraProvider;

    // Detection state
    private volatile boolean enabled       = true;
    private volatile int     confirmStreak = 0;
    private volatile boolean gestureHeld   = false;
    private volatile boolean isCounting    = false;

    private CountDownTimer holdTimer;
    private final Handler  ui = new Handler(Looper.getMainLooper());
    private Animation pulseAnim, rippleAnim;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_hand_gesture);

        previewView     = findViewById(R.id.previewViewGesture);
        ivFinger        = findViewById(R.id.ivFingerVector);
        ivRipple        = findViewById(R.id.ivRipple);
        tvBack          = findViewById(R.id.tvGestureBack);
        tvStatus        = findViewById(R.id.tvGestureStatus);
        tvCountdown     = findViewById(R.id.tvGestureCountdown);
        tvBgStatus      = findViewById(R.id.tvBgGestureStatus);
        tvDebug         = findViewById(R.id.tvGestureDebug);
        progressBar     = findViewById(R.id.progressGestureRing);
        layoutCountdown = findViewById(R.id.layoutGestureOverlay);
        layoutIdle      = findViewById(R.id.layoutGestureReady);
        switchEnable    = findViewById(R.id.switchGestureEnable);
        switchBg        = findViewById(R.id.switchBgGesture);

        pulseAnim  = AnimationUtils.loadAnimation(this, R.anim.pulse);
        rippleAnim = AnimationUtils.loadAnimation(this, R.anim.ripple);
        ivFinger.startAnimation(pulseAnim);

        tvBack.setOnClickListener(v -> finish());

        switchEnable.setChecked(true);
        switchEnable.setOnCheckedChangeListener((btn, on) -> {
            enabled = on;
            if (!on) { cancelHold(); ivFinger.clearAnimation(); }
            else { ivFinger.startAnimation(pulseAnim); }
        });

        boolean bgRunning = HandGestureService.isRunning;
        switchBg.setChecked(bgRunning);
        updateBgLabel(bgRunning);
        switchBg.setOnCheckedChangeListener((btn, on) -> {
            if (on) HandGestureService.start(this);
            else    HandGestureService.stop(this);
            updateBgLabel(on);
        });

        cameraExecutor = Executors.newSingleThreadExecutor();
        requestCameraOrStart();
    }

    private void updateBgLabel(boolean on) {
        if (tvBgStatus == null) return;
        tvBgStatus.setText(on
            ? "● Running — watching in background"
            : "Off — only detects while this screen is open");
    }

    // ── Camera ────────────────────────────────────────────────────────────────

    private void requestCameraOrStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) startCamera();
        else
            ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.CAMERA}, CAMERA_REQ);
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> fut =
            ProcessCameraProvider.getInstance(this);
        fut.addListener(() -> {
            try {
                cameraProvider = fut.get();
                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(previewView.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build();
                analysis.setAnalyzer(cameraExecutor, this::analyseFrame);

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis);
            } catch (Exception e) {
                Log.e(TAG, "Camera error: " + e.getMessage());
                ui.post(() -> tvStatus.setText("Camera error: " + e.getMessage()));
            }
        }, ContextCompat.getMainExecutor(this));
    }

    // ── Frame analysis (same logic as HandGestureService) ─────────────────────

    @OptIn(markerClass = ExperimentalGetImage.class)
    private void analyseFrame(ImageProxy proxy) {
        if (!enabled || isCounting) { proxy.close(); return; }

        Image img = proxy.getImage();
        if (img == null) { proxy.close(); return; }

        Image.Plane yPlane = img.getPlanes()[0];
        ByteBuffer  yBuf   = yPlane.getBuffer();
        int W = img.getWidth(), H = img.getHeight();
        int rowStride = yPlane.getRowStride();
        int pixStride = yPlane.getPixelStride();

        // Full frame average
        long fullSum = 0; int fullCnt = 0;
        for (int y = 0; y < H; y += STEP * 2) {
            for (int x = 0; x < W; x += STEP * 2) {
                int idx = y * rowStride + x * pixStride;
                if (idx >= 0 && idx < yBuf.limit()) {
                    fullSum += yBuf.get(idx) & 0xFF; fullCnt++;
                }
            }
        }
        float fullAvg = fullCnt > 0 ? (float) fullSum / fullCnt : 128f;

        // Upper-centre zone (where user holds their finger)
        int zX0 = W / 4, zX1 = 3 * W / 4;
        int zY0 = 0,      zY1 = (int)(H * 0.45f);
        long zoneSum = 0; int zoneCnt = 0;
        for (int y = zY0; y < zY1; y += STEP) {
            for (int x = zX0; x < zX1; x += STEP) {
                int idx = y * rowStride + x * pixStride;
                if (idx >= 0 && idx < yBuf.limit()) {
                    zoneSum += yBuf.get(idx) & 0xFF; zoneCnt++;
                }
            }
        }
        float zoneAvg = zoneCnt > 0 ? (float) zoneSum / zoneCnt : 0f;

        proxy.close();

        boolean present = (zoneAvg > fullAvg * PRESENCE_RATIO) && (zoneAvg > MIN_ZONE_LUMA);
        float ratio = fullAvg > 0 ? zoneAvg / fullAvg : 0f;

        ui.post(() -> {
            // Show live debug info so user can see what's happening
            if (tvDebug != null) {
                tvDebug.setText(String.format(
                    "zone=%.0f  frame=%.0f  ratio=%.2f  %s",
                    zoneAvg, fullAvg, ratio, present ? "✅ DETECTED" : "—"));
            }
            onPresence(present);
        });
    }

    // ── Gesture hold logic ────────────────────────────────────────────────────

    private void onPresence(boolean present) {
        if (isCounting) return;

        if (present) {
            confirmStreak = Math.min(confirmStreak + 1, CONFIRM_FRAMES + 10);
        } else {
            confirmStreak = Math.max(0, confirmStreak - 2);
        }

        boolean confirmed = confirmStreak >= CONFIRM_FRAMES;

        if (confirmed && !gestureHeld) {
            gestureHeld = true;
            startHoldCountdown();
        } else if (!confirmed && gestureHeld) {
            gestureHeld = false;
            cancelHold();
        }

        if (!gestureHeld) {
            tvStatus.setText(confirmed
                ? "☝ Keep holding…"
                : "☝ Point one finger at the camera");
        }
    }

    private void startHoldCountdown() {
        isCounting = true;
        layoutCountdown.setVisibility(View.VISIBLE);
        layoutIdle.setVisibility(View.GONE);
        ivFinger.clearAnimation();
        ivRipple.setVisibility(View.VISIBLE);
        if (rippleAnim != null) ivRipple.startAnimation(rippleAnim);
        vibrate(80);

        holdTimer = new CountDownTimer(HOLD_MS, 50) {
            @Override public void onTick(long msLeft) {
                progressBar.setProgress((int)((HOLD_MS - msLeft) * 100f / HOLD_MS));
                long s = msLeft / 1000 + 1;
                tvCountdown.setText(String.valueOf(s));
                tvStatus.setText("☝ SOS in " + s + "s — lower hand to cancel");
                if (confirmStreak < 2) { cancel(); resetToIdle("Hand lost — try again"); }
            }
            @Override public void onFinish() {
                isCounting = false;
                triggerSOS();
            }
        }.start();
    }

    private void resetToIdle(String msg) {
        isCounting    = false;
        gestureHeld   = false;
        confirmStreak = 0;
        progressBar.setProgress(0);
        layoutCountdown.setVisibility(View.GONE);
        layoutIdle.setVisibility(View.VISIBLE);
        tvStatus.setText(msg);
        if (ivRipple != null) { ivRipple.clearAnimation(); ivRipple.setVisibility(View.GONE); }
        if (enabled) ivFinger.startAnimation(pulseAnim);
    }

    private void cancelHold() {
        if (holdTimer != null) holdTimer.cancel();
        resetToIdle("☝ Point one finger at the camera");
    }

    private void triggerSOS() {
        vibrate(600);
        tvStatus.setText("🆘 SOS TRIGGERED!");
        Toast.makeText(this, "🆘 SOS via finger gesture!", Toast.LENGTH_LONG).show();
        Intent svc = new Intent(this, SafeHerService.class);
        svc.setAction(SafeHerService.ACTION_TRIGGER_SOS);
        startService(svc);
        ui.postDelayed(() -> {
            startActivity(new Intent(this, SosActivity.class));
            finish();
        }, 1200);
    }

    private void vibrate(long ms) {
        Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v != null && v.hasVibrator())
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] perms, @NonNull int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == CAMERA_REQ && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED)
            startCamera();
        else
            tvStatus.setText("Camera permission required");
    }

    @Override protected void onResume() {
        super.onResume();
        if (switchBg != null) { boolean r = HandGestureService.isRunning; switchBg.setChecked(r); updateBgLabel(r); }
    }

    @Override protected void onPause()   { super.onPause();   cancelHold(); }
    @Override protected void onDestroy() {
        super.onDestroy();
        if (cameraExecutor != null) cameraExecutor.shutdown();
        if (holdTimer != null) holdTimer.cancel();
    }
}
