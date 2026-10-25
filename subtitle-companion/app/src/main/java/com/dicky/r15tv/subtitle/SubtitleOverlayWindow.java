package com.dicky.r15tv.subtitle;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

public final class SubtitleOverlayWindow {
    private final Context context;
    private final WindowManager windowManager;
    private LinearLayout root;
    private TextView statusView;
    private TextView sourceView;
    private TextView translatedView;
    private boolean attached;

    public SubtitleOverlayWindow(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    public void show() {
        if (attached || windowManager == null) return;
        int pad = dp(18);
        root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(pad, dp(8), pad, dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xD9081020);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0xAA8AC8FF);
        root.setBackground(bg);

        statusView = new TextView(context);
        statusView.setTextColor(0xFFBFD7F2);
        statusView.setTextSize(13);
        statusView.setGravity(Gravity.CENTER);
        statusView.setText("字幕測試已啟動，等待播放音訊…");
        root.addView(statusView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        sourceView = new TextView(context);
        sourceView.setTextColor(0xFFD9E6F7);
        sourceView.setTextSize(18);
        sourceView.setGravity(Gravity.CENTER);
        sourceView.setMaxLines(2);
        sourceView.setShadowLayer(2, 1, 2, Color.BLACK);
        LinearLayout.LayoutParams sourceParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sourceParams.topMargin = dp(3);
        root.addView(sourceView, sourceParams);

        translatedView = new TextView(context);
        translatedView.setTextColor(Color.WHITE);
        translatedView.setTextSize(25);
        translatedView.setGravity(Gravity.CENTER);
        translatedView.setMaxLines(2);
        translatedView.setShadowLayer(2, 1, 2, Color.BLACK);
        LinearLayout.LayoutParams translatedParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        translatedParams.topMargin = dp(2);
        root.addView(translatedView, translatedParams);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                Math.max(dp(320), Math.min(screenWidth - dp(64), dp(1400))),
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = dp(95);
        try {
            windowManager.addView(root, params);
            attached = true;
        } catch (Throwable error) {
            attached = false;
            throw error;
        }
    }

    public void setStatus(String status) {
        if (statusView != null) statusView.setText(status == null ? "" : status);
    }

    public void showSubtitle(String source, String translated) {
        if (sourceView == null || translatedView == null) return;
        source = source == null ? "" : source.trim();
        translated = translated == null ? "" : translated.trim();
        sourceView.setText(source);
        translatedView.setText(translated);
        sourceView.setVisibility(source.isEmpty() || source.equals(translated)
                ? TextView.GONE : TextView.VISIBLE);
        translatedView.setVisibility(translated.isEmpty() ? TextView.GONE : TextView.VISIBLE);
    }

    public void remove() {
        if (!attached || root == null || windowManager == null) return;
        try { windowManager.removeView(root); } catch (Throwable ignored) { }
        attached = false;
        root = null;
        sourceView = null;
        translatedView = null;
        statusView = null;
    }

    private int dp(int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
