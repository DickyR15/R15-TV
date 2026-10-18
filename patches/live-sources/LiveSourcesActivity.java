package com.fongmi.android.tv.ui.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LiveSourcesActivity extends BaseActivity {

    private static final String PREFS = "r15_live_sources_v1";
    private static final String KEY_SOURCES = "sources";
    private static final String DEFAULT_EPG = "https://epg.zsdc.eu.org/t.xml";
    private static final int LIVE_TYPE = 1;
    private static final int WHITE = Color.WHITE;
    private static final int PURPLE = 0xFF452080;
    private static final int CARD = 0x55303F24;

    private LinearLayout root;
    private final List<Source> sources = new ArrayList<>();
    private final Map<String, EditText> fields = new HashMap<>();
    private Source editingSource;
    private int editingIndex = -1;
    private int refreshHours = 8;
    private boolean editing;
    private Switch autoSortSwitch;
    private LinearLayout advancedFields;

    public static void start(Context context) {
        context.startActivity(new android.content.Intent(context, LiveSourcesActivity.class));
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
        loadSources();
        showList();
    }

    @Override
    protected void onBackInvoked() {
        if (editing) showList();
        else super.onBackInvoked();
    }

    private void loadSources() {
        sources.clear();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (prefs.contains(KEY_SOURCES)) {
            try {
                JSONArray array = new JSONArray(prefs.getString(KEY_SOURCES, "[]"));
                for (int i = 0; i < array.length(); i++) {
                    JSONObject json = array.optJSONObject(i);
                    if (json != null) {
                        Source source = Source.fromJson(json);
                        if (!TextUtils.isEmpty(source.url)) sources.add(source);
                    }
                }
            } catch (JSONException ignored) {
                // A malformed user preference should not prevent the settings page from opening.
            }
        } else {
            Set<String> seen = new HashSet<>();
            try {
                for (Config config : Config.getAll(LIVE_TYPE)) {
                    if (config == null || TextUtils.isEmpty(config.getUrl()) || !seen.add(config.getUrl())) continue;
                    Source source = new Source();
                    source.name = safe(config.getName());
                    source.url = safe(config.getUrl());
                    source.epg = DEFAULT_EPG;
                    sources.add(source);
                }
            } catch (Exception ignored) {
                // The currently active config below is a safe fallback on a fresh install.
            }
            if (sources.isEmpty() && !TextUtils.isEmpty(LiveConfig.getUrl())) {
                Source source = new Source();
                source.name = safe(LiveConfig.getDesc());
                source.url = safe(LiveConfig.getUrl());
                source.epg = DEFAULT_EPG;
                sources.add(source);
            }
            saveSources();
        }
    }

    private void saveSources() {
        JSONArray array = new JSONArray();
        for (Source source : sources) array.put(source.toJson());
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_SOURCES, array.toString()).apply();
    }

    private void showList() {
        editing = false;
        root.removeAllViews();
        root.addView(makeHeader("直播配置", "新增直播源", this::newSource), new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(20), 0, dp(16));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (sources.isEmpty()) {
            TextView empty = text("尚未新增直播源，點擊「新增直播源」開始配置", 20, WHITE);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(20), dp(60), dp(20), dp(60));
            body.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            return;
        }

        String activeUrl = safe(LiveConfig.getUrl());
        for (int i = 0; i < sources.size(); i++) {
            final int index = i;
            Source source = sources.get(i);
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(18), dp(14), dp(18), dp(14));
            card.setBackground(cardDrawable(false));
            card.setFocusable(true);
            card.setClickable(true);
            card.setOnFocusChangeListener((v, focused) -> v.setBackground(cardDrawable(focused)));
            card.setOnClickListener(v -> activate(source));
            TextView title = text(TextUtils.isEmpty(source.name) ? "未命名直播源" : source.name, 21, WHITE);
            title.setFocusable(false);
            card.addView(title, new LinearLayout.LayoutParams(-1, -2));
            TextView url = text(source.url, 15, 0xFFEAEAEA);
            url.setFocusable(false);
            url.setPadding(0, dp(6), 0, 0);
            card.addView(url, new LinearLayout.LayoutParams(-1, -2));
            if (!TextUtils.isEmpty(source.epg)) {
                TextView epg = text("節目表：" + source.epg, 14, 0xFFD7D7D7);
                epg.setFocusable(false);
                epg.setPadding(0, dp(4), 0, 0);
                card.addView(epg, new LinearLayout.LayoutParams(-1, -2));
            }
            if (source.url.equals(activeUrl)) {
                TextView current = text("目前使用中", 14, 0xFFFFE0A6);
                current.setPadding(0, dp(4), 0, 0);
                current.setFocusable(false);
                card.addView(current, new LinearLayout.LayoutParams(-1, -2));
            }
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.HORIZONTAL);
            actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            actions.setPadding(0, dp(10), 0, 0);
            Button use = button("啟用", true);
            use.setOnClickListener(v -> activate(source));
            Button edit = button("編輯", false);
            edit.setOnClickListener(v -> editSource(index));
            Button delete = button("刪除", false);
            delete.setOnClickListener(v -> confirmDelete(index));
            actions.addView(use, weightParams(0, 48, 1f, dp(8)));
            actions.addView(edit, weightParams(0, 48, 1f, dp(8)));
            actions.addView(delete, weightParams(0, 48, 1f, 0));
            card.addView(actions, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
            cp.bottomMargin = dp(14);
            body.addView(card, cp);
        }
    }

    private void newSource() {
        editingIndex = -1;
        editingSource = new Source();
        editingSource.epg = DEFAULT_EPG;
        refreshHours = 8;
        showEditor();
    }

    private void editSource(int index) {
        editingIndex = index;
        editingSource = new Source(sources.get(index));
        refreshHours = editingSource.refreshHours <= 0 ? 8 : editingSource.refreshHours;
        showEditor();
    }

    private void showEditor() {
        editing = true;
        fields.clear();
        root.removeAllViews();
        root.addView(makeHeader(editingIndex < 0 ? "新增直播源配置" : "編輯直播源配置", "儲存", this::saveEditedSource, "取消", this::showList),
                new LinearLayout.LayoutParams(-1, dp(60)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(0, dp(12), 0, dp(30));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addField(body, "直播源名稱（可選）", "請輸入直播源名稱（可選）", "name", editingSource.name, false);
        addField(body, "直播源訂閱", "請輸入直播源訂閱網址", "url", editingSource.url, false);
        addField(body, "節目表位址", "請輸入 XMLTV 節目表網址", "epg", editingSource.epg, false);

        autoSortSwitch = new Switch(this);
        autoSortSwitch.setText("自動重新整理配置");
        autoSortSwitch.setTextColor(WHITE);
        autoSortSwitch.setTextSize(18);
        autoSortSwitch.setChecked(editingSource.autoSort);
        autoSortSwitch.setPadding(dp(2), dp(16), dp(2), dp(16));
        body.addView(autoSortSwitch, new LinearLayout.LayoutParams(-1, -2));

        Button interval = button("重新整理間隔：" + refreshHours + " 小時", false);
        interval.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        interval.setOnClickListener(v -> chooseInterval(interval));
        LinearLayout.LayoutParams intervalParams = new LinearLayout.LayoutParams(-1, dp(54));
        intervalParams.topMargin = dp(4);
        intervalParams.bottomMargin = dp(18);
        body.addView(interval, intervalParams);

        Button advanced = button("進階  ▾", false);
        advanced.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        advanced.setOnClickListener(v -> {
            boolean show = advancedFields.getVisibility() != View.VISIBLE;
            advancedFields.setVisibility(show ? View.VISIBLE : View.GONE);
            advanced.setText(show ? "進階  ▴" : "進階  ▾");
        });
        LinearLayout.LayoutParams advParams = new LinearLayout.LayoutParams(-1, dp(54));
        advParams.bottomMargin = dp(10);
        body.addView(advanced, advParams);

        advancedFields = new LinearLayout(this);
        advancedFields.setOrientation(LinearLayout.VERTICAL);
        advancedFields.setVisibility(View.GONE);
        body.addView(advancedFields, new LinearLayout.LayoutParams(-1, -2));
        addField(advancedFields, "User-Agent", "User-Agent", "userAgent", editingSource.userAgent, false);
        addField(advancedFields, "Referer", "Referer", "referer", editingSource.referer, false);
        addField(advancedFields, "自訂 Header", "每行一組標頭，例如 Authorization: Bearer …", "headers", editingSource.headers, true);
    }

    private void addField(LinearLayout container, String label, String hint, String key, String initial, boolean multiline) {
        TextView caption = text(label, 17, WHITE);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.topMargin = dp(12);
        cp.bottomMargin = dp(6);
        container.addView(caption, cp);

        EditText input = new EditText(this);
        input.setSingleLine(!multiline);
        input.setMinLines(multiline ? 2 : 1);
        input.setMaxLines(multiline ? 4 : 1);
        input.setTextSize(18);
        input.setTextColor(WHITE);
        input.setHintTextColor(0xCCFFFFFF);
        input.setHint(hint);
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setBackground(fieldDrawable());
        input.setText(safe(initial));
        input.setSelectAllOnFocus(false);
        fields.put(key, input);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, multiline ? dp(96) : dp(58));
        ip.bottomMargin = dp(8);
        container.addView(input, ip);
    }

    private void chooseInterval(Button interval) {
        int[] values = new int[]{1, 2, 4, 8, 12, 24};
        String[] labels = new String[]{"1 小時", "2 小時", "4 小時", "8 小時", "12 小時", "24 小時"};
        int selected = 3;
        for (int i = 0; i < values.length; i++) if (values[i] == refreshHours) selected = i;
        new AlertDialog.Builder(this)
                .setTitle("重新整理間隔")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    refreshHours = values[which];
                    interval.setText("重新整理間隔：" + refreshHours + " 小時");
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void saveEditedSource() {
        String url = value("url").trim();
        if (url.isEmpty()) {
            Toast.makeText(this, "請先輸入直播源訂閱網址", Toast.LENGTH_SHORT).show();
            return;
        }
        for (int i = 0; i < sources.size(); i++) {
            if (i != editingIndex && url.equals(sources.get(i).url)) {
                Toast.makeText(this, "這個直播源已經存在", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Source source = new Source();
        source.name = value("name").trim();
        source.url = url;
        source.epg = value("epg").trim();
        if (source.epg.isEmpty()) source.epg = DEFAULT_EPG;
        source.autoSort = autoSortSwitch != null && autoSortSwitch.isChecked();
        source.refreshHours = refreshHours;
        source.userAgent = value("userAgent").trim();
        source.referer = value("referer").trim();
        source.headers = value("headers").trim();

        Config config = Config.find(source.url, source.name, LIVE_TYPE);
        config.url(source.url).type(LIVE_TYPE).name(source.name).save();

        if (editingIndex < 0) sources.add(source);
        else sources.set(editingIndex, source);
        saveSources();
        Toast.makeText(this, "直播源設定已儲存", Toast.LENGTH_SHORT).show();
        showList();
    }

    private String value(String key) {
        EditText input = fields.get(key);
        return input == null ? "" : safe(input.getText().toString());
    }

    private void confirmDelete(int index) {
        Source source = sources.get(index);
        new AlertDialog.Builder(this)
                .setTitle("刪除直播源")
                .setMessage("確定要刪除「" + (TextUtils.isEmpty(source.name) ? source.url : source.name) + "」嗎？")
                .setNegativeButton("取消", null)
                .setPositiveButton("刪除", (dialog, which) -> {
                    sources.remove(index);
                    saveSources();
                    showList();
                })
                .show();
    }

    private void activate(Source source) {
        if (source == null || TextUtils.isEmpty(source.url)) return;
        Config config = Config.find(source.url, source.name, LIVE_TYPE);
        config.url(source.url).type(LIVE_TYPE).name(source.name).save();
        Notify.progress(this);
        LiveConfig.load(config, new Callback() {
            @Override
            public void success() {
                Notify.dismiss();
                Toast.makeText(LiveSourcesActivity.this, "直播源載入成功", Toast.LENGTH_SHORT).show();
                LiveActivity.start(LiveSourcesActivity.this);
                finish();
            }

            @Override
            public void error(String msg) {
                Notify.dismiss();
                Notify.show(msg);
            }
        });
    }

    private LinearLayout makeHeader(String titleText, String rightText, Runnable rightAction) {
        return makeHeader(titleText, rightText, rightAction, null, null);
    }

    private LinearLayout makeHeader(String titleText, String rightText, Runnable rightAction, String leftText, Runnable leftAction) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        if (leftText != null) {
            Button left = button(leftText, false);
            left.setOnClickListener(v -> leftAction.run());
            header.addView(left, new LinearLayout.LayoutParams(dp(112), dp(48)));
        } else {
            View spacer = new View(this);
            header.addView(spacer, new LinearLayout.LayoutParams(dp(36), 1));
        }

        TextView title = text(titleText, 25, WHITE);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));

        Button right = button(rightText, true);
        right.setOnClickListener(v -> rightAction.run());
        header.addView(right, new LinearLayout.LayoutParams(dp(150), dp(48)));
        return header;
    }

    private Button button(String label, boolean primary) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setTextColor(primary ? PURPLE : WHITE);
        button.setPadding(dp(14), dp(4), dp(14), dp(4));
        button.setMinHeight(dp(46));
        button.setBackground(buttonDrawable(primary, false));
        button.setFocusable(true);
        button.setOnFocusChangeListener((v, focused) -> v.setBackground(buttonDrawable(primary, focused)));
        return button;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
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

    private GradientDrawable cardDrawable(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(9));
        drawable.setColor(focused ? 0x66395F2C : CARD);
        drawable.setStroke(dp(focused ? 2 : 1), focused ? WHITE : 0x55FFFFFF);
        return drawable;
    }

    private GradientDrawable fieldDrawable() {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(dp(7));
        drawable.setColor(0x19000000);
        drawable.setStroke(dp(1), 0x99FFFFFF);
        return drawable;
    }

    private LinearLayout.LayoutParams weightParams(int width, int height, float weight, int marginEnd) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(width, dp(height), weight);
        lp.setMarginEnd(marginEnd);
        return lp;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final class Source {
        String name = "";
        String url = "";
        String epg = DEFAULT_EPG;
        boolean autoSort = true;
        int refreshHours = 8;
        String userAgent = "";
        String referer = "";
        String headers = "";

        Source() {}

        Source(Source other) {
            name = other.name;
            url = other.url;
            epg = other.epg;
            autoSort = other.autoSort;
            refreshHours = other.refreshHours;
            userAgent = other.userAgent;
            referer = other.referer;
            headers = other.headers;
        }

        JSONObject toJson() {
            JSONObject json = new JSONObject();
            try {
                json.put("name", name);
                json.put("url", url);
                json.put("epg", epg);
                json.put("autoSort", autoSort);
                json.put("refreshHours", refreshHours);
                json.put("userAgent", userAgent);
                json.put("referer", referer);
                json.put("headers", headers);
            } catch (JSONException ignored) {}
            return json;
        }

        static Source fromJson(JSONObject json) {
            Source source = new Source();
            source.name = json.optString("name", "");
            source.url = json.optString("url", "");
            source.epg = json.optString("epg", DEFAULT_EPG);
            source.autoSort = json.optBoolean("autoSort", true);
            source.refreshHours = json.optInt("refreshHours", 8);
            source.userAgent = json.optString("userAgent", "");
            source.referer = json.optString("referer", "");
            source.headers = json.optString("headers", "");
            return source;
        }
    }
}
