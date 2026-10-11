package com.fongmi.android.tv.ai.subtitle;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.media3.ui.PlayerView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/** Small experimental subtitle control shown over the existing video player. */
public final class AiSubtitleOverlay {
    private static final Map<PlayerView, AiSubtitleOverlay> INSTANCES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private final Activity activity;
    private final PlayerView playerView;
    private final TextView caption;
    private final Button toggle;
    private final LinearLayout panel;
    private boolean enabled;

    private AiSubtitleOverlay(Activity activity, PlayerView playerView) {
        this.activity = activity;
        this.playerView = playerView;
        this.caption = new TextView(activity);
        this.toggle = new Button(activity);
        this.panel = new LinearLayout(activity);
        buildView();
    }

    public static void attach(Activity activity, PlayerView playerView) {
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

        caption.setText("AI 字幕實驗版：目前先支援 ExoPlayer");
        caption.setTextColor(Color.WHITE);
        caption.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        caption.setGravity(Gravity.CENTER);
        caption.setMaxLines(3);
        caption.setPadding(dp(12), dp(4), dp(12), dp(4));
        panel.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        toggle.setText("啟動 AI 字幕");
        toggle.setAllCaps(false);
        toggle.setFocusable(true);
        toggle.setOnClickListener(view -> toggleSubtitle());
        panel.addView(toggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private void attachView() {
        FrameLayout overlay = playerView.getOverlayFrameLayout();
        if (overlay == null) {
            caption.setText("AI 字幕：找不到播放器字幕顯示區");
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

    private void toggleSubtitle() {
        if (enabled) {
            AiSubtitleEngine.stop();
            enabled = false;
            toggle.setText("啟動 AI 字幕");
            caption.setText("AI 字幕已關閉");
            return;
        }
        enabled = true;
        toggle.setText("停止 AI 字幕");
        caption.setText("AI 字幕模型初始化中…");
        AiSubtitleEngine.start(activity, new AiSubtitleEngine.Listener() {
            @Override
            public void onStatus(String status) {
                if (enabled) caption.setText(status);
            }

            @Override
            public void onSubtitle(String text) {
                if (enabled) caption.setText(text);
            }
        });
    }

    private void detachView() {
        AiSubtitleEngine.stop();
        if (panel.getParent() instanceof FrameLayout) {
            ((FrameLayout) panel.getParent()).removeView(panel);
        }
    }

    private int dp(float value) {
        return (int) (value * activity.getResources().getDisplayMetrics().density + 0.5f);
    }
}
