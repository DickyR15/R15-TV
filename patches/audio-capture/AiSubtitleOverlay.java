package com.fongmi.android.tv.ai.subtitle;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.ui.PlayerView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Small, opt-in in-player audio-capture and local ASR proof of concept. */
public final class AiSubtitleOverlay {
    private static final Map<PlayerView, AiSubtitleOverlay> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final FragmentActivity activity;
    private final PlayerView playerView;
    private final TextView status;
    private final TextView translationStatus;
    private final TextView caption;
    private final Button toggle;
    private final LinearLayout panel;
    private final ActivityResultLauncher<String> audioPermissionLauncher;
    private final ActivityResultLauncher<Intent> projectionPermissionLauncher;

    private boolean enabled;
    private AiSubtitlePlaybackCapture capture;
    private AiSubtitleTranslationEngine translationEngine;

    private AiSubtitleOverlay(FragmentActivity activity, PlayerView playerView) {
        this.activity = activity;
        this.playerView = playerView;
        status = new TextView(activity);
        translationStatus = new TextView(activity);
        caption = new TextView(activity);
        toggle = new Button(activity);
        panel = new LinearLayout(activity);

        // Registration occurs during VideoActivity view initialization, before STARTED.
        audioPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                granted -> {
                    if (!enabled) return;
                    if (granted) requestPlaybackCaptureConsent();
                    else resetAfterDenial("未授予錄音權限，字幕未啟動");
                });

        projectionPermissionLauncher = activity.registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (!enabled) return;
                    if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
                        resetAfterDenial("未允許音訊擷取，字幕未啟動");
                        return;
                    }
                    startCapture(result.getResultCode(), result.getData());
                });
        buildView();
    }

    public static void attach(FragmentActivity activity, PlayerView playerView) {
        if (activity == null || playerView == null || activity.isFinishing()) return;
        synchronized (INSTANCES) {
            if (INSTANCES.containsKey(playerView)) return;
            AiSubtitleOverlay overlay = new AiSubtitleOverlay(activity, playerView);
            INSTANCES.put(playerView, overlay);
            overlay.attachView();
        }
    }

    public static void detach(PlayerView playerView) {
        if (playerView == null) return;
        AiSubtitleOverlay overlay;
        synchronized (INSTANCES) {
            overlay = INSTANCES.remove(playerView);
        }
        if (overlay != null) overlay.detachView();
    }

    private void buildView() {
        int pad = dp(10);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, dp(4), pad, dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD9101010);
        background.setCornerRadius(dp(12));
        panel.setBackground(background);

        status.setText("即時字幕實驗版（MPV／M3U 音訊擷取測試）");
        status.setTextColor(0xFFBFD7FF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(2), dp(8), dp(2));
        panel.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        translationStatus.setText("本機翻譯會在首次使用時下載模型");
        translationStatus.setTextColor(0xFFBFD7FF);
        translationStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        translationStatus.setGravity(Gravity.CENTER);
        translationStatus.setPadding(dp(8), dp(2), dp(8), dp(2));
        panel.addView(translationStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        caption.setText("辨識出的英文會即時顯示，句段結束後更新為繁體中文字幕。");
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(3);
        caption.setPadding(dp(12), dp(4), dp(12), dp(4));
        panel.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        toggle.setText("啟動即時字幕測試");
        toggle.setAllCaps(false);
        toggle.setFocusable(true);
        toggle.setOnClickListener(view -> toggleCapture());
        panel.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void attachView() {
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay == null) {
            status.setText("字幕測試：找不到播放器的疊加顯示區");
            return;
        }
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.setMargins(dp(24), 0, dp(24), dp(66));
        overlay.addView(panel, params);
        panel.bringToFront();
    }

    private void toggleCapture() {
        if (enabled) {
            stopCapture("即時字幕已關閉");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status.setText("此功能需要 Android 10 以上");
            return;
        }
        enabled = true;
        toggle.setText("停止即時字幕測試");
        status.setText("等待音訊擷取授權…");
        caption.setText("正在準備本機語音辨識");
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPlaybackCaptureConsent();
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void requestPlaybackCaptureConsent() {
        if (!enabled || activity.isFinishing()) return;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("系統沒有提供 MediaProjectionManager");
            projectionPermissionLauncher.launch(manager.createScreenCaptureIntent());
        } catch (Throwable error) {
            resetAfterDenial("無法啟動音訊授權：" + readableMessage(error));
        }
    }

    private void startCapture(int resultCode, Intent data) {
        MediaProjection projection = null;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("系統沒有提供 MediaProjectionManager");
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("系統未建立音訊擷取工作階段");

            translationEngine = new AiSubtitleTranslationEngine(activity,
                    new AiSubtitleTranslationEngine.Listener() {
                        @Override
                        public void onStatus(String message) {
                            if (enabled) translationStatus.setText(message);
                        }

                        @Override
                        public void onTranslatedSubtitle(String source, String translation) {
                            if (enabled && translation != null && !translation.isEmpty()) {
                                caption.setText(translation);
                            }
                        }
                    });
            translationEngine.start();

            AiSubtitleEngine.start(activity, new AiSubtitleEngine.Listener() {
                @Override
                public void onStatus(String message) {
                    if (enabled) caption.setText(message);
                }

                @Override
                public void onSubtitle(String text) {
                    if (enabled) caption.setText(text);
                }

                @Override
                public void onFinalSubtitle(String text) {
                    AiSubtitleTranslationEngine translator = translationEngine;
                    if (enabled && translator != null) translator.translateFinalPhrase(text);
                }
            });

            capture = AiSubtitlePlaybackCapture.start(activity, projection,
                    new AiSubtitlePlaybackCapture.Listener() {
                        @Override
                        public void onStatus(String message) {
                            if (!enabled) return;
                            status.setText(message);
                            if ("系統已停止音訊擷取".equals(message)
                                    || message.startsWith("音訊讀取失敗：")
                                    || message.startsWith("音訊擷取讀取錯誤")) {
                                stopCapture(message);
                            }
                        }

                        @Override
                        public void onLevel(float dbfs) {
                            if (enabled) status.setText(String.format(
                                    java.util.Locale.ROOT,
                                    "MPV 音訊擷取中 · 音量 %.0f dBFS", dbfs));
                        }
                    });
        } catch (Throwable error) {
            try {
                if (projection != null) projection.stop();
            } catch (Throwable ignored) {
            }
            AiSubtitleEngine.stop();
            resetAfterDenial("啟動失敗：" + readableMessage(error));
        }
    }

    private void resetAfterDenial(String message) {
        enabled = false;
        closeTranslationEngine();
        AiSubtitleEngine.stop();
        toggle.setText("啟動即時字幕測試");
        status.setText(message);
    }

    private void stopCapture(String message) {
        enabled = false;
        AiSubtitlePlaybackCapture current = capture;
        capture = null;
        if (current != null) current.stop();
        AiSubtitleEngine.stop();
        closeTranslationEngine();
        toggle.setText("啟動即時字幕測試");
        status.setText(message);
        caption.setText("即時字幕已關閉");
    }

    private void closeTranslationEngine() {
        AiSubtitleTranslationEngine current = translationEngine;
        translationEngine = null;
        if (current != null) current.close();
    }

    private void detachView() {
        stopCapture("播放器已關閉");
        if (panel.getParent() instanceof FrameLayout) {
            ((FrameLayout) panel.getParent()).removeView(panel);
        }
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
