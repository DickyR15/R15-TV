package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.VodConfig;
import com.fongmi.android.tv.api.config.WallConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.bean.Site;
import com.fongmi.android.tv.event.ConfigEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.impl.ConfigListener;
import com.fongmi.android.tv.impl.SiteListener;
import com.fongmi.android.tv.setting.Setting;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.utils.Notify;
import com.fongmi.android.tv.utils.PermissionUtil;

public final class VodWallSettingsActivity extends BaseActivity implements ConfigListener, SiteListener {

    private static final int WHITE = Color.WHITE;
    private static final int PURPLE = 0xFF452080;
    private LinearLayout root;

    public static void start(Context context) {
        context.startActivity(new Intent(context, VodWallSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(20), dp(24), dp(24));
        root.setBackgroundColor(Color.TRANSPARENT);
        return () -> root;
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        render();
    }

    private void render() {
        root.removeAllViews();
        root.setPadding(0, 0, 0, dp(20));

        TextView title = text("點播和桌布", 24);
        title.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface, WHITE));
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(-1, -2);
        titleParams.setMargins(dp(20), dp(16), dp(20), 0);
        root.addView(title, titleParams);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addMediaCard(body, "點播", VodConfig.getDesc(), "設定來源", "首頁／站源", "播放紀錄",
                () -> editConfig(0),
                () -> com.fongmi.android.tv.ui.dialog.SiteDialog.create().action().show(this),
                () -> HistoryDialog.create().vod().show(this), true);

        addMediaCard(body, "桌布", WallConfig.getDesc(), "設定來源", "切換桌布", "重新整理",
                () -> editConfig(2), this::setWallDefault, this::setWallRefresh, false);
    }

    private void addMediaCard(LinearLayout body, String titleText, String description,
                              String primaryLabel, String secondaryLabel, String thirdLabel,
                              Runnable primaryAction, Runnable secondaryAction, Runnable thirdAction,
                              boolean vod) {
        com.google.android.material.card.MaterialCardView card =
                new com.google.android.material.card.MaterialCardView(this);
        card.setCardBackgroundColor(colorAttr(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF242424));
        card.setStrokeColor(colorAttr(com.google.android.material.R.attr.colorOutlineVariant, 0xFF555555));
        card.setStrokeWidth(dp(1));
        card.setRadius(dp(18));
        card.setCardElevation(0);
        card.setUseCompatPadding(false);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.addView(content, new com.google.android.material.card.MaterialCardView.LayoutParams(-1, -2));

        TextView name = text(titleText, 18);
        name.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurface, WHITE));
        name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        content.addView(name, new LinearLayout.LayoutParams(-1, -2));

        TextView url = text(TextUtils.isEmpty(description) ? "尚未設定來源" : description, 14);
        url.setTextColor(colorAttr(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFFBDBDBD));
        url.setMaxLines(1);
        url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
        urlParams.topMargin = dp(6);
        content.addView(url, urlParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actionsParams = new LinearLayout.LayoutParams(-1, -2);
        actionsParams.topMargin = dp(16);
        content.addView(actions, actionsParams);

        Button primary = button(primaryLabel, true);
        primary.setOnClickListener(v -> primaryAction.run());
        actions.addView(primary, new LinearLayout.LayoutParams(0, dp(48), 1f));

        Button secondary = button(secondaryLabel, false);
        secondary.setOnClickListener(v -> secondaryAction.run());
        LinearLayout.LayoutParams secondaryParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        secondaryParams.setMarginStart(dp(8));
        actions.addView(secondary, secondaryParams);

        Button third = button(thirdLabel, false);
        third.setOnClickListener(v -> thirdAction.run());
        LinearLayout.LayoutParams thirdParams = new LinearLayout.LayoutParams(0, dp(48), 1f);
        thirdParams.setMarginStart(dp(8));
        actions.addView(third, thirdParams);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(vod ? 16 : 16);
        body.addView(card, cardParams);
    }

    private void editConfig(int type) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(type == 0 ? "設定點播來源" : "設定桌布來源")
                .setMessage("請輸入來源網址。若要使用檔案或進階來源，請返回主設定頁進行設定。")
                .setView(createSourceInput())
                .setNegativeButton("取消", null)
                .setPositiveButton("儲存", (dialog, which) -> {
                    android.widget.EditText input = (android.widget.EditText) ((android.app.AlertDialog) dialog).findViewById(android.R.id.edit);
                    if (input == null) {
                        Toast.makeText(this, "請從主設定頁設定來源", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String url = input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    Config config = Config.find(url, type);
                    config.url(url).type(type).save();
                    load(config);
                }).show();
    }

    private android.widget.EditText createSourceInput() {
        android.widget.EditText input = new android.widget.EditText(this);
        input.setId(android.R.id.edit);
        input.setHint("https://");
        input.setSingleLine(true);
        input.setTextColor(WHITE);
        input.setHintTextColor(0xCCFFFFFF);
        input.setPadding(dp(18), dp(12), dp(18), dp(12));
        return input;
    }

    @Override
    public void setConfig(Config config) {
        if (config.getUrl() != null && config.getUrl().startsWith("file")) {
            PermissionUtil.requestFile(this, allGranted -> load(config));
        } else {
            load(config);
        }
    }

    private void load(Config config) {
        switch (config.getType()) {
            case 0 -> VodConfig.load(config, getCallback());
            case 2 -> {
                Setting.putWall(0);
                WallConfig.load(config, getCallback());
            }
            default -> Toast.makeText(this, "不支援的設定類型", Toast.LENGTH_SHORT).show();
        }
    }

    private Callback getCallback() {
        return new Callback() {
            @Override
            public void start() {
                Notify.progress(VodWallSettingsActivity.this);
            }

            @Override
            public void success() {
                Notify.dismiss();
                render();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        };
    }

    @Override
    public void setSite(Site item) {
        VodConfig.get().setHome(item);
    }

    private void setWallDefault() {
        Setting.putWall(Setting.getWall() == 4 ? 1 : Setting.getWall() + 1);
        Setting.putWallType(0);
        ConfigEvent.wall();
    }

    private void setWallRefresh() {
        Setting.putWall(0);
        WallConfig.get().load(getCallback());
    }

    private Button button(String label, boolean primary) {
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(this);
        button.setText(label);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(22));
        int surface = colorAttr(com.google.android.material.R.attr.colorSurfaceContainer, 0xFF292929);
        int primaryColor = colorAttr(com.google.android.material.R.attr.colorPrimary, PURPLE);
        int onPrimary = colorAttr(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE);
        int onSurface = colorAttr(com.google.android.material.R.attr.colorOnSurface, WHITE);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? primaryColor : surface));
        button.setTextColor(primary ? onPrimary : onSurface);
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(
                colorAttr(com.google.android.material.R.attr.colorOutline, 0xFF666666)));
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, focused) -> button.setStrokeWidth(dp(focused ? 2 : 1)));
        return button;
    }

    private TextView text(String value, int sp) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(WHITE);
        return view;
    }

    private GradientDrawable buttonDrawable(boolean primary, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(28));
        drawable.setColor(primary ? Color.WHITE : 0x22333333);
        drawable.setStroke(dp(focused ? 2 : 1), focused ? WHITE : 0x88FFFFFF);
        return drawable;
    }

    private GradientDrawable cardDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(9));
        drawable.setColor(0x55303F24);
        drawable.setStroke(dp(1), 0x55FFFFFF);
        return drawable;
    }

    private int colorAttr(int attribute, int fallback) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (!getTheme().resolveAttribute(attribute, value, true)) return fallback;
        if (value.resourceId != 0) {
            try {
                return androidx.core.content.ContextCompat.getColor(this, value.resourceId);
            } catch (Exception ignored) {
            }
        }
        return value.data != 0 ? value.data : fallback;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
