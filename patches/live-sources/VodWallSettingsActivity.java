package com.fongmi.android.tv.ui.activity;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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
import com.fongmi.android.tv.ui.dialog.ConfigDialog;
import com.fongmi.android.tv.ui.dialog.HistoryDialog;
import com.fongmi.android.tv.ui.dialog.SiteDialog;
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
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        Button back = button("返回", false);
        back.setOnClickListener(v -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(110), dp(48)));
        TextView title = text("點播和桌布", 25);
        title.setGravity(Gravity.CENTER);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        View spacer = new View(this);
        header.addView(spacer, new LinearLayout.LayoutParams(dp(110), dp(1)));
        root.addView(header, new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(16), 0, dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addCard(body, "點播來源", VodConfig.getDesc(),
                new String[]{"設定點播來源", "首頁／站源", "播放紀錄"},
                new Runnable[]{
                        () -> ConfigDialog.create().vod().show(this),
                        () -> SiteDialog.create().action().show(this),
                        () -> HistoryDialog.create().vod().show(this)
                });

        addCard(body, "桌布來源", WallConfig.getDesc(),
                new String[]{"設定桌布來源", "切換桌布", "重新整理桌布"},
                new Runnable[]{
                        () -> ConfigDialog.create().wall().show(this),
                        this::setWallDefault,
                        this::setWallRefresh
                });
    }

    private void addCard(LinearLayout body, String titleText, String description, String[] labels, Runnable[] actions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(cardDrawable());
        TextView title = text(titleText, 22);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView desc = text(description == null || description.isEmpty() ? "尚未設定" : description, 15);
        desc.setPadding(0, dp(6), 0, dp(10));
        card.addView(desc, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < labels.length; i++) {
            final Runnable action = actions[i];
            Button item = button(labels[i], i == 0);
            item.setOnClickListener(v -> action.run());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(50), 1f);
            if (i < labels.length - 1) lp.setMarginEnd(dp(8));
            row.addView(item, lp);
        }
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(16);
        body.addView(card, cp);
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
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTextColor(primary ? PURPLE : WHITE);
        button.setPadding(dp(10), dp(4), dp(10), dp(4));
        button.setBackground(buttonDrawable(primary, false));
        button.setFocusable(true);
        button.setOnFocusChangeListener((v, focused) -> v.setBackground(buttonDrawable(primary, focused)));
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
