package com.fongmi.android.tv.ai.subtitle;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
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

import com.fongmi.android.tv.ui.activity.AiSubtitleSettingsActivity;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Small, opt-in in-player audio-capture and local ASR proof of concept. */
public final class AiSubtitleOverlay {
    private static final Map<PlayerView, AiSubtitleOverlay> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final FragmentActivity activity;
    private final PlayerView playerView;
    private final TextView title;
    private final TextView status;
    private final TextView modelStatus;
    private final TextView translationStatus;
    private final TextView caption;
    private final Button modelButton;
    private final Button deleteModelButton;
    private final Button toggle;
    private final Button entryButton;
    private final Button closePanelButton;
    private final AiSubtitleModelManager modelManager;
    private final LinearLayout panel;
    private final ActivityResultLauncher<String> audioPermissionLauncher;
    private final ActivityResultLauncher<Intent> projectionPermissionLauncher;

    private boolean enabled;
    private boolean hasTranslatedCaption;
    private boolean captureConsentGrantedThisInstall;
    private boolean hasRecognizedCaption;
    private boolean whisperReady;
    private boolean inferenceInProgress;
    private boolean hasInferenceStatus;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable modelLoadWarning;
    private AiSubtitlePlaybackCapture capture;
    private AiSubtitleTranslationEngine translationEngine;

    private AiSubtitleOverlay(FragmentActivity activity, PlayerView playerView) {
        this.activity = activity;
        this.playerView = playerView;
        title = new TextView(activity);
        status = new TextView(activity);
        modelStatus = new TextView(activity);
        translationStatus = new TextView(activity);
        caption = new TextView(activity);
        modelLoadWarning = () -> {
            if (enabled && !whisperReady && !hasRecognizedCaption) {
                caption.setText("語音模型仍在載入；首次載入可能需要一些時間。若持續超過 30 秒，請停止字幕並回報。");
                caption.setVisibility(android.view.View.VISIBLE);
            }
        };
        modelButton = new Button(activity);
        deleteModelButton = new Button(activity);
        toggle = new Button(activity);
        entryButton = new Button(activity);
        closePanelButton = new Button(activity);
        modelManager = AiSubtitleModelManager.getInstance(activity);
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

    private String selectedModelId() {
        return AiSubtitleSettingsActivity.getAsrLanguage(activity);
    }

    private String selectedModelName() {
        return modelManager.getLanguageLabel(selectedModelId());
    }

    private String selectedLanguageLabel() {
        return AiSubtitleSettingsActivity.getAsrLanguageLabel(
                AiSubtitleSettingsActivity.getAsrLanguage(activity));
    }

    private void buildView() {
        int pad = dp(10);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(pad, dp(4), pad, dp(4));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD9101010);
        background.setCornerRadius(dp(12));
        panel.setBackground(background);

        title.setText("R15 TV 即時字幕");
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setGravity(Gravity.CENTER);
        title.setPadding(dp(8), dp(4), dp(8), dp(4));
        panel.addView(title, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        closePanelButton.setText("收起設定");
        closePanelButton.setAllCaps(false);
        closePanelButton.setFocusable(true);
        closePanelButton.setOnClickListener(view -> hideSettingsPanel());
        panel.addView(closePanelButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        status.setText("多語言即時辨識 · 臺灣繁體中文字幕 · OpenCC S2TWP");
        status.setTextColor(0xFFBFD7FF);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(8), dp(2), dp(8), dp(2));
        panel.addView(status, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        modelStatus.setText("語音辨識模型：" + selectedModelName() + "｜固定語言：" + selectedLanguageLabel());
        modelStatus.setTextColor(0xFFFFFFFF);
        modelStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        modelStatus.setGravity(Gravity.CENTER);
        modelStatus.setPadding(dp(8), dp(4), dp(8), dp(2));
        panel.addView(modelStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        modelButton.setText("下載 " + selectedModelName() + "（約 " + modelManager.getEstimatedSizeMb(selectedModelId()) + " MB）");
        modelButton.setAllCaps(false);
        modelButton.setFocusable(true);
        modelButton.setOnClickListener(view -> downloadModel());
        panel.addView(modelButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        deleteModelButton.setText("刪除已下載模型");
        deleteModelButton.setAllCaps(false);
        deleteModelButton.setFocusable(true);
        deleteModelButton.setOnClickListener(view -> confirmDeleteModel());
        panel.addView(deleteModelButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        translationStatus.setText("翻譯結果只輸出臺灣繁體中文。");
        translationStatus.setTextColor(0xFFBFD7FF);
        translationStatus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        translationStatus.setGravity(Gravity.CENTER);
        translationStatus.setPadding(dp(8), dp(2), dp(8), dp(2));
        panel.addView(translationStatus, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        caption.setText("");
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(3);
        caption.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable captionBackground = new GradientDrawable();
        captionBackground.setColor(0xD9101010);
        captionBackground.setCornerRadius(dp(8));
        caption.setBackground(captionBackground);
        caption.setVisibility(android.view.View.GONE);

        toggle.setText("啟動即時字幕");
        toggle.setAllCaps(false);
        toggle.setFocusable(true);
        toggle.setOnClickListener(view -> toggleCapture());
        panel.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        entryButton.setText("R15 TV 即時字幕設定");
        entryButton.setAllCaps(false);
        entryButton.setFocusable(true);
        entryButton.setContentDescription("開啟 R15 TV 即時字幕設定");
        entryButton.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        entryButton.setTextColor(Color.WHITE);
        entryButton.setPadding(dp(18), dp(10), dp(18), dp(10));
        entryButton.setMinWidth(dp(190));
        entryButton.setElevation(dp(8));
        GradientDrawable entryBackground = new GradientDrawable();
        entryBackground.setColor(0xF00B4F6C);
        entryBackground.setCornerRadius(dp(10));
        entryBackground.setStroke(dp(2), 0xFF7DD3FC);
        entryButton.setBackground(entryBackground);
        entryButton.setOnClickListener(view -> showSettingsPanel());
        updateModelControls();
    }

    private void downloadModel() {
        if (enabled) {
            modelStatus.setText("請先停止字幕，再管理語音模型");
            return;
        }
        if (modelManager.isDownloading()) return;
        modelButton.setEnabled(false);
        modelButton.setText("正在準備 " + selectedModelName() + " 下載…");
        modelStatus.setText("第一次下載需要網路；模型下載完成後可在本機辨識");
        modelManager.download(selectedModelId(), new AiSubtitleModelManager.Listener() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (activity.isFinishing()) return;
                String downloaded = formatMiB(downloadedBytes);
                if (totalBytes > 0) {
                    int percent = (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
                    modelButton.setText("下載 " + selectedModelName() + "：" + percent + "%");
                    modelStatus.setText("模型下載中 " + downloaded + " / " + formatMiB(totalBytes));
                } else {
                    modelButton.setText("下載 " + selectedModelName() + "：" + downloaded);
                    modelStatus.setText("模型下載中 " + downloaded);
                }
            }

            @Override
            public void onDownloaded(java.io.File file) {
                if (activity.isFinishing()) return;
                modelStatus.setText(selectedModelName() + " 已下載；開始字幕時才會載入模型");
                updateModelControls();
            }

            @Override
            public void onDeleted() {
                modelStatus.setText(selectedModelName() + " 已刪除");
                updateModelControls();
            }

            @Override
            public void onError(String message) {
                if (activity.isFinishing()) return;
                updateModelControls();
                modelStatus.setText("模型下載失敗：" + message);
            }
        });
    }

    private void confirmDeleteModel() {
        if (enabled || modelManager.isDownloading()) return;
        new AlertDialog.Builder(activity)
                .setTitle("刪除 " + selectedModelName() + "？")
                .setMessage("會移除約 " + modelManager.getEstimatedSizeMb(selectedModelId())
                        + " MB 的本機模型；日後可重新下載。")
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("刪除", (dialog, which) -> {
                    modelStatus.setText("正在刪除模型…");
                    modelManager.delete(selectedModelId(), new AiSubtitleModelManager.Listener() {
                        @Override
                        public void onProgress(long downloadedBytes, long totalBytes) { }

                        @Override
                        public void onDownloaded(java.io.File file) { }

                        @Override
                        public void onDeleted() {
                            modelStatus.setText(selectedModelName() + " 已刪除");
                            updateModelControls();
                        }

                        @Override
                        public void onError(String message) {
                            updateModelControls();
                            modelStatus.setText("刪除模型失敗：" + message);
                        }
                    });
                })
                .show();
    }

    private void updateModelControls() {
        boolean ready = modelManager.isDownloaded(selectedModelId());
        boolean downloading = modelManager.isDownloading();
        if (ready) {
            modelStatus.setText("語音辨識模型：" + selectedModelName() + "（已下載）｜固定語言：" + selectedLanguageLabel());
            modelButton.setVisibility(android.view.View.GONE);
            deleteModelButton.setVisibility(enabled ? android.view.View.GONE : android.view.View.VISIBLE);
        } else {
            modelButton.setVisibility(android.view.View.VISIBLE);
            deleteModelButton.setVisibility(android.view.View.GONE);
            if (!downloading) modelButton.setText("下載 " + selectedModelName() + "（約 "
                    + modelManager.getEstimatedSizeMb(selectedModelId()) + " MB）");
            modelButton.setEnabled(!downloading && !enabled);
            deleteModelButton.setEnabled(false);
            if (!downloading) modelStatus.setText("語音辨識模型：" + selectedModelName()
                    + "（尚未下載）｜固定語言：" + selectedLanguageLabel());
        }
    }

    private static String formatMiB(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void attachView() {
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay == null) {
            status.setText("字幕測試：找不到播放器的疊加顯示區");
            return;
        }
        // Keep the playback overlay focused on captions only. Settings are accessible
        // through the main Settings screen with the TV remote, not a corner button.
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        captionParams.setMargins(dp(24), 0, dp(24), dp(28));
        overlay.addView(caption, captionParams);

        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        panelParams.setMargins(dp(20), 0, dp(20), dp(84));
        panel.setVisibility(android.view.View.GONE);
        overlay.addView(panel, panelParams);

        // Subtitle settings are opened from the bottom live-player control row.
        // Do not overlay a second floating settings button on the video.

        // Reuse the overlay across media-item changes: the player view stays attached
        // while the channel changes. Start once per attached PlayerView, not per stream.
        playerView.addOnAttachStateChangeListener(new android.view.View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(android.view.View view) {
                scheduleAutoStart();
            }
            @Override public void onViewDetachedFromWindow(android.view.View view) { }
        });
        scheduleAutoStart();
    }

    private void scheduleAutoStart() {
        if (!AiSubtitleSettingsActivity.isAutoStartEnabled(activity)) return;
        playerView.post(() -> playerView.postDelayed(() -> {
            if (activity.isFinishing() || !playerView.isAttachedToWindow()
                    || !AiSubtitleSettingsActivity.isAutoStartEnabled(activity)) return;
            if (!enabled && modelManager.isDownloaded(selectedModelId()) && !modelManager.isDownloading()) {
                ensureAutoStartCapture();
            }
        }, 1500L));
    }

    private void showSettingsPanel() {
        panel.setVisibility(android.view.View.VISIBLE);
        entryButton.setVisibility(android.view.View.GONE);
        if (enabled) caption.setVisibility(android.view.View.GONE);
        panel.bringToFront();
        if (!modelManager.isDownloaded(selectedModelId())) modelButton.requestFocus();
        else toggle.requestFocus();
    }

    private void hideSettingsPanel() {
        panel.setVisibility(android.view.View.GONE);
        entryButton.setText(enabled ? "R15 TV 字幕：開啟" : "R15 TV 即時字幕設定");
        entryButton.setVisibility(android.view.View.VISIBLE);
        if (enabled) caption.setVisibility(android.view.View.VISIBLE);
        entryButton.bringToFront();
        entryButton.requestFocus();
    }

    private void ensureAutoStartCapture() {
        if (enabled || activity.isFinishing() || !playerView.isAttachedToWindow()) return;
        startCaptureInternal(false);
    }

    private void toggleCapture() {
        if (enabled) {
            stopCapture("即時字幕已關閉");
            return;
        }
        startCaptureInternal(true);
    }

    private void startCaptureInternal(boolean userInitiated) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            status.setText("此功能需要 Android 10 以上");
            return;
        }
        if (modelManager.isDownloading()) {
            modelStatus.setText("請等語音模型下載完成");
            return;
        }
        if (!modelManager.isDownloaded(selectedModelId())) {
            modelStatus.setText("請先下載所選語音模型");
            modelButton.requestFocus();
            return;
        }
        enabled = true;
        hasTranslatedCaption = false;
        hasRecognizedCaption = false;
        whisperReady = false;
        inferenceInProgress = false;
        hasInferenceStatus = false;
        mainHandler.removeCallbacks(modelLoadWarning);
        updateModelControls();
        toggle.setText("停止即時字幕");
        status.setText(userInitiated ? "等待音訊擷取授權…" : "自動啟動即時字幕…");
        caption.setText(userInitiated ? "正在等待音訊擷取授權…" : "準備啟動即時字幕…");
        caption.setVisibility(android.view.View.VISIBLE);
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // MediaProjection authorization itself is one-shot on Android 14+.
            // Ask only when we don't hold an active capture session; after channel
            // changes, the active overlay session is reused instead of rebuilding it.
            requestPlaybackCaptureConsent();
        } else if (userInitiated) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        } else {
            resetAfterDenial("首次使用需先授予音訊權限；請開啟 AI 字幕設定並啟動一次");
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
        try {
            android.os.ResultReceiver receiver = new android.os.ResultReceiver(
                    new android.os.Handler(android.os.Looper.getMainLooper())) {
                @Override
                protected void onReceiveResult(int result, android.os.Bundle resultData) {
                    if (!enabled) {
                        stopProjectionService();
                        return;
                    }
                    if (result != AiSubtitleProjectionService.RESULT_READY) {
                        String message = resultData == null ? null : resultData.getString("error");
                        resetAfterDenial("螢幕擷取前景服務啟動失敗"
                                + (message == null || message.isEmpty() ? "" : "：" + message));
                        return;
                    }
                    startCaptureWithForeground(resultCode, data);
                }
            };
            Intent serviceIntent = new Intent(activity, AiSubtitleProjectionService.class);
            serviceIntent.putExtra(AiSubtitleProjectionService.EXTRA_RESULT_RECEIVER, receiver);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(activity, serviceIntent);
            } else {
                activity.startService(serviceIntent);
            }
        } catch (Throwable error) {
            stopProjectionService();
            resetAfterDenial("無法啟動螢幕擷取前景服務：" + readableMessage(error));
        }
    }

    private void startCaptureWithForeground(int resultCode, Intent data) {
        MediaProjection projection = null;
        try {
            MediaProjectionManager manager =
                    (MediaProjectionManager) activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("系統沒有提供 MediaProjectionManager");
            projection = manager.getMediaProjection(resultCode, data);
            if (projection == null) throw new IllegalStateException("系統未建立音訊擷取工作階段");

            if (!"source_only".equals(AiSubtitleSettingsActivity.getTranslationProvider(activity))) {
                translationEngine = new AiSubtitleTranslationEngine(activity, AiSubtitleSettingsActivity.getAsrLanguage(activity),
                        new AiSubtitleTranslationEngine.Listener() {
                            @Override
                            public void onStatus(String message) {
                                if (enabled) translationStatus.setText(message);
                            }

                            @Override
                            public void onTranslatedSubtitle(String source, String translation) {
                                if (!enabled || translation == null || translation.isEmpty()) return;
                                hasTranslatedCaption = true;
                                if (AiSubtitleSettingsActivity.getSubtitleDisplayMode(activity)
                                        == AiSubtitleSettingsActivity.DISPLAY_SOURCE_AND_TRANSLATION
                                        && !source.equals(translation)) {
                                    caption.setText(source + "\n" + translation);
                                } else {
                                    caption.setText(translation);
                                }
                            }
                        });
                translationEngine.start();
            } else {
                translationEngine = null;
                translationStatus.setText("已設定為僅顯示辨識原文；翻譯服務未啟用。");
            }

            AiSubtitleEngine.start(activity, modelManager.getModelDirectory(selectedModelId()),
                    AiSubtitleSettingsActivity.getAsrLanguage(activity), new AiSubtitleEngine.Listener() {
                @Override
                public void onStatus(String message) {
                    if (!enabled) return;
                    status.setText(message);
                    if (message.startsWith("正在檢查") || message.startsWith("正在載入")) {
                        inferenceInProgress = false;
                        hasInferenceStatus = false;
                        whisperReady = false;
                        mainHandler.removeCallbacks(modelLoadWarning);
                        mainHandler.postDelayed(modelLoadWarning, 20000L);
                        caption.setText(message);
                        caption.setVisibility(android.view.View.VISIBLE);
                    } else if (message.startsWith("本機語音辨識已就緒")) {
                        whisperReady = true;
                        mainHandler.removeCallbacks(modelLoadWarning);
                        if (!hasRecognizedCaption && !hasTranslatedCaption) {
                            caption.setText("模型已載入，等待播放語音…");
                            caption.setVisibility(android.view.View.VISIBLE);
                        }
                    } else if (message.startsWith("辨識單次運算超過")) {
                        inferenceInProgress = false;
                        caption.setText(message);
                        caption.setVisibility(android.view.View.VISIBLE);
                    } else if (message.startsWith("本機辨識 · ")) {
                        inferenceInProgress = false;
                        hasInferenceStatus = true;
                    } else if (message.startsWith("語音辨識啟動失敗")) {
                        mainHandler.removeCallbacks(modelLoadWarning);
                        stopCapture(message);
                        caption.setText(message);
                        caption.setVisibility(android.view.View.VISIBLE);
                    } else if (message.startsWith("目前沒有收到新的 PCM 音訊")) {
                        inferenceInProgress = false;
                        if (!hasRecognizedCaption && !hasTranslatedCaption) {
                            caption.setText("目前沒有收到播放音訊，請確認影片／直播聲音是否正常。");
                            caption.setVisibility(android.view.View.VISIBLE);
                        }
                    } else if (message.startsWith("音訊量測 ·") || message.startsWith("等待播放器 PCM 音訊")) {
                        if (!hasRecognizedCaption && !hasTranslatedCaption) {
                            caption.setText(message);
                            caption.setVisibility(android.view.View.VISIBLE);
                        }
                    }
                }

                @Override
                public void onSubtitle(String text) {
                    if (!enabled) return;
                    hasRecognizedCaption = true;
                    inferenceInProgress = false;
                    hasInferenceStatus = true;
                    boolean sourceOnly = "source_only".equals(
                            AiSubtitleSettingsActivity.getTranslationProvider(activity));
                    boolean showSource = sourceOnly
                            || AiSubtitleSettingsActivity.getSubtitleDisplayMode(activity)
                                    == AiSubtitleSettingsActivity.DISPLAY_SOURCE_AND_TRANSLATION;
                    if (showSource && !hasTranslatedCaption) {
                        caption.setText(text);
                        caption.setVisibility(android.view.View.VISIBLE);
                    } else if (!hasTranslatedCaption && !sourceOnly) {
                        caption.setText("正在辨識語音，等待臺灣繁體譯文…");
                        caption.setVisibility(android.view.View.VISIBLE);
                    }
                }

                @Override
                public void onFinalSubtitle(String text) {
                    AiSubtitleTranslationEngine translator = translationEngine;
                    if (enabled && translator != null) translator.translateFinalPhrase(text);
                }

                @Override
                public void onFinalSubtitle(String text, String language) {
                    AiSubtitleTranslationEngine translator = translationEngine;
                    if (enabled && translator != null) translator.translateFinalPhrase(text, language);
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
                            if (!enabled) return;
                            status.setText(String.format(
                                    java.util.Locale.ROOT,
                                    "MPV 音訊擷取中 · 音量 %.0f dBFS", dbfs));
                            if (whisperReady && !hasRecognizedCaption && !hasTranslatedCaption
                                    && !hasInferenceStatus && !inferenceInProgress) {
                                caption.setText(dbfs > -55.0f
                                        ? "已收到播放音訊，等待首次辨識…"
                                        : "模型已載入，但目前擷取音量很低；等待直播語音…");
                                caption.setVisibility(android.view.View.VISIBLE);
                            }
                        }
                    });
            caption.setVisibility(android.view.View.VISIBLE);
        } catch (Throwable error) {
            try {
                if (projection != null) projection.stop();
            } catch (Throwable ignored) {
            }
            AiSubtitleEngine.stop();
            resetAfterDenial("啟動失敗：" + readableMessage(error));
        }
    }

    private void stopProjectionService() {
        try {
            activity.stopService(new Intent(activity, AiSubtitleProjectionService.class));
        } catch (Throwable ignored) {
        }
    }

    private void resetAfterDenial(String message) {
        mainHandler.removeCallbacks(modelLoadWarning);
        stopProjectionService();
        enabled = false;
        whisperReady = false;
        hasRecognizedCaption = false;
        inferenceInProgress = false;
        hasInferenceStatus = false;
        closeTranslationEngine();
        AiSubtitleEngine.stop();
        toggle.setText("啟動即時字幕");
        caption.setText(message);
        caption.setVisibility(android.view.View.VISIBLE);
        status.setText(message);
        updateModelControls();
    }

    private void stopCapture(String message) {
        mainHandler.removeCallbacks(modelLoadWarning);
        stopProjectionService();
        enabled = false;
        whisperReady = false;
        hasRecognizedCaption = false;
        inferenceInProgress = false;
        hasInferenceStatus = false;
        hasTranslatedCaption = false;
        AiSubtitlePlaybackCapture current = capture;
        capture = null;
        if (current != null) current.stop();
        AiSubtitleEngine.stop();
        closeTranslationEngine();
        toggle.setText("啟動即時字幕");
        status.setText(message);
        boolean showFailure = message.contains("失敗") || message.contains("錯誤");
        caption.setText(showFailure ? message : "");
        caption.setVisibility(showFailure ? android.view.View.VISIBLE : android.view.View.GONE);
        updateModelControls();
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
        if (caption.getParent() instanceof FrameLayout) {
            ((FrameLayout) caption.getParent()).removeView(caption);
        }
        if (entryButton.getParent() instanceof FrameLayout) {
            ((FrameLayout) entryButton.getParent()).removeView(entryButton);
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
