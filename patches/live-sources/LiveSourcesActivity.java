package com.fongmi.android.tv.ui.activity;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.api.config.LiveConfig;
import com.fongmi.android.tv.bean.Config;
import com.fongmi.android.tv.event.ServerEvent;
import com.fongmi.android.tv.impl.Callback;
import com.fongmi.android.tv.server.Server;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.fongmi.android.tv.utils.Notify;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
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
    private com.google.android.material.materialswitch.MaterialSwitch autoSortSwitch;
    private EditText lastFocusedField;
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
        fields.clear();
        root.removeAllViews();
        root.setPadding(0, 0, 0, dp(16));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(16), dp(20), 0);
        TextView title = text("直播配置", 24, WHITE);
        title.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button add = button("新增直播源", true);
        add.setOnClickListener(v -> newSource());
        header.addView(add, new LinearLayout.LayoutParams(-2, dp(48)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), dp(24));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        if (sources.isEmpty()) {
            TextView empty = text("尚未新增直播源，點擊「新增直播源」開始配置", 16,
                    0xFFBDBDBD);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(24), dp(40), dp(24), dp(40));
            body.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1f));
            return;
        }

        String activeUrl = safe(LiveConfig.getUrl());
        for (int i = 0; i < sources.size(); i++) {
            final int index = i;
            Source source = sources.get(i);
            boolean current = source.url.equals(activeUrl);
            com.google.android.material.card.MaterialCardView card =
                    new com.google.android.material.card.MaterialCardView(this);
            card.setCardBackgroundColor(0xFF242424);
            card.setStrokeColor(0xFF555555);
            card.setStrokeWidth(dp(1));
            card.setRadius(dp(18));
            card.setCardElevation(0);
            card.setUseCompatPadding(false);

            LinearLayout content = new LinearLayout(this);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setPadding(dp(16), dp(14), dp(16), dp(14));
            card.addView(content, new com.google.android.material.card.MaterialCardView.LayoutParams(-1, -2));

            LinearLayout titleRow = new LinearLayout(this);
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setOrientation(LinearLayout.HORIZONTAL);
            TextView name = text(TextUtils.isEmpty(source.name) ? "未命名直播源" : source.name, 18,
                    WHITE);
            name.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            titleRow.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
            if (current) {
                TextView badge = text("目前使用", 12,
                        WHITE);
                badge.setPadding(dp(10), dp(4), dp(10), dp(4));
                badge.setBackground(roundDrawable(
                        PURPLE, dp(16)));
                titleRow.addView(badge, new LinearLayout.LayoutParams(-2, -2));
            }
            content.addView(titleRow, new LinearLayout.LayoutParams(-1, -2));

            TextView url = text(source.url, 14,
                    0xFFBDBDBD);
            url.setMaxLines(1);
            url.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            LinearLayout.LayoutParams urlParams = new LinearLayout.LayoutParams(-1, -2);
            urlParams.topMargin = dp(6);
            content.addView(url, urlParams);

            StringBuilder summaryText = new StringBuilder();
            if (!TextUtils.isEmpty(source.epg)) summaryText.append("EPG");
            if (source.autoSort) {
                if (summaryText.length() > 0) summaryText.append(" · ");
                summaryText.append("自動重新整理 ").append(source.refreshHours).append(" 小時");
            } else {
                if (summaryText.length() > 0) summaryText.append(" · ");
                summaryText.append("手動重新整理");
            }
            if (!TextUtils.isEmpty(source.userAgent)) summaryText.append(" · 自訂 UA");
            if (!TextUtils.isEmpty(source.headers) || !TextUtils.isEmpty(source.referer)) summaryText.append(" · 自訂 Header");
            if (summaryText.length() > 0) {
                TextView summary = text(summaryText.toString(), 13,
                        0xFFBDBDBD);
                summary.setMaxLines(2);
                LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
                summaryParams.topMargin = dp(4);
                content.addView(summary, summaryParams);
            }

            LinearLayout firstActions = new LinearLayout(this);
            firstActions.setOrientation(LinearLayout.HORIZONTAL);
            firstActions.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(-1, -2);
            firstParams.topMargin = dp(12);
            content.addView(firstActions, firstParams);

            Button pass = button("自動重新整理", false);
            pass.setTextSize(13);
            pass.setMinHeight(dp(42));
            pass.setOnClickListener(v -> {
                source.autoSort = !source.autoSort;
                saveSources();
                showList();
            });
            firstActions.addView(pass, new LinearLayout.LayoutParams(-2, dp(42)));
            firstActions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
            Button use = button(current ? "目前使用" : "啟用", false);
            use.setOnClickListener(v -> activate(source));
            firstActions.addView(use, new LinearLayout.LayoutParams(-2, dp(42)));

            LinearLayout secondActions = new LinearLayout(this);
            secondActions.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(-1, -2);
            secondParams.topMargin = dp(8);
            content.addView(secondActions, secondParams);
            Button edit = button("編輯", false);
            edit.setOnClickListener(v -> editSource(index));
            secondActions.addView(edit, new LinearLayout.LayoutParams(0, dp(44), 1f));
            Button delete = button("刪除", false);
            delete.setOnClickListener(v -> confirmDelete(index));
            LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(0, dp(44), 1f);
            deleteParams.setMarginStart(dp(8));
            secondActions.addView(delete, deleteParams);

            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.bottomMargin = dp(12);
            body.addView(card, cardParams);
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
        lastFocusedField = null;
        root.removeAllViews();
        root.setPadding(0, 0, 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(16), dp(16), dp(16), 0);
        Button cancel = button("取消", false);
        cancel.setOnClickListener(v -> showList());
        header.addView(cancel, new LinearLayout.LayoutParams(dp(92), dp(44)));
        TextView title = text(editingIndex < 0 ? "新增直播源配置" : "編輯直播源配置", 24,
                WHITE);
        title.setGravity(Gravity.CENTER);
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        Button save = button("儲存", true);
        save.setOnClickListener(v -> saveEditedSource());
        header.addView(save, new LinearLayout.LayoutParams(dp(92), dp(44)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(16), dp(12), dp(16), dp(36));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        addField(body, "直播源名稱", "請輸入直播源名稱（可選）", "name", editingSource.name, false);
        addField(body, "直播源訂閱", "支援 M3U、TXT 與 JSON 配置", "url", editingSource.url, false);
        addField(body, "節目表位址", "XMLTV / EPG 網址", "epg", editingSource.epg, false);

        Button qrButton = button("手機掃描 QR Code 輸入直播源", false);
        qrButton.setGravity(Gravity.CENTER);
        qrButton.setOnClickListener(v -> showPhoneQrDialog());
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(-1, dp(48));
        qrParams.topMargin = dp(4);
        qrParams.bottomMargin = dp(8);
        body.addView(qrButton, qrParams);

        autoSortSwitch = new com.google.android.material.materialswitch.MaterialSwitch(this);
        autoSortSwitch.setText("自動重新整理配置");
        autoSortSwitch.setTextColor(WHITE);
        autoSortSwitch.setTextSize(16);
        autoSortSwitch.setChecked(editingSource.autoSort);
        autoSortSwitch.setPadding(0, dp(8), 0, dp(8));
        autoSortSwitch.setMinHeight(dp(52));
        body.addView(autoSortSwitch, new LinearLayout.LayoutParams(-1, -2));

        Button interval = button("重新整理間隔：" + refreshHours + " 小時", false);
        interval.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        interval.setOnClickListener(v -> chooseInterval(interval));
        LinearLayout.LayoutParams intervalParams = new LinearLayout.LayoutParams(-1, dp(48));
        intervalParams.topMargin = dp(4);
        intervalParams.bottomMargin = dp(18);
        body.addView(interval, intervalParams);

        View divider = new View(this);
        divider.setBackgroundColor(0x66555555);
        body.addView(divider, new LinearLayout.LayoutParams(-1, dp(1)));

        Button advanced = button("進階  ﹀", false);
        advanced.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        advanced.setOnClickListener(v -> {
            boolean show = advancedFields.getVisibility() != View.VISIBLE;
            advancedFields.setVisibility(show ? View.VISIBLE : View.GONE);
            advanced.setText(show ? "進階  ︿" : "進階  ﹀");
        });
        LinearLayout.LayoutParams advParams = new LinearLayout.LayoutParams(-1, dp(48));
        advParams.topMargin = dp(10);
        advParams.bottomMargin = dp(6);
        body.addView(advanced, advParams);

        advancedFields = new LinearLayout(this);
        advancedFields.setOrientation(LinearLayout.VERTICAL);
        advancedFields.setVisibility(View.GONE);
        body.addView(advancedFields, new LinearLayout.LayoutParams(-1, -2));
        addField(advancedFields, "User-Agent", "自訂 User-Agent（選填）", "userAgent", editingSource.userAgent, false);
        addField(advancedFields, "Referer", "自訂 Referer（選填）", "referer", editingSource.referer, false);
        addField(advancedFields, "自訂 Header", "Header-Name=HeaderValue，多個請換行輸入", "headers", editingSource.headers, true);
    }

    private void showPhoneQrDialog() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(16));

        ImageView qr = new ImageView(this);
        qr.setContentDescription("手機掃描以開啟直播配置頁");
        qr.setScaleType(ImageView.ScaleType.FIT_CENTER);
        qr.setBackgroundColor(Color.WHITE);
        try {
            qr.setImageBitmap(com.fongmi.android.tv.utils.QRCode.getBitmap(Server.get().getAddress(4), dp(220), 0));
        } catch (Exception ignored) {
        }
        content.addView(qr, new LinearLayout.LayoutParams(dp(220), dp(220)));

        TextView address = text(Server.get().getAddress(), 14,
                0xFFBDBDBD);
        address.setGravity(Gravity.CENTER);
        address.setPadding(0, dp(10), 0, dp(8));
        content.addView(address, new LinearLayout.LayoutParams(-1, -2));

        TextView message = text("手機掃描後，在「配置」頁面輸入直播源名稱與網址並推送；回到電視後會自動填入，再按儲存。",
                14, WHITE);
        message.setGravity(Gravity.CENTER);
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("手機掃描 QR Code")
                .setView(content)
                .setPositiveButton("完成", null)
                .show();
    }

    private void addField(LinearLayout container, String label, String hint, String key, String initial, boolean multiline) {
        com.google.android.material.textfield.TextInputLayout field =
                new com.google.android.material.textfield.TextInputLayout(this);
        field.setHint(label);
        field.setBoxBackgroundMode(com.google.android.material.textfield.TextInputLayout.BOX_BACKGROUND_OUTLINE);
        field.setBoxStrokeColor(0xFF888888);
        field.setBoxStrokeWidth(dp(1));
        field.setBoxStrokeWidthFocused(dp(2));
        field.setBoxCornerRadii(dp(7), dp(7), dp(7), dp(7));
        field.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(
                0xFFBDBDBD));
        field.setPlaceholderText(hint);
        field.setHelperText("按確認鍵可直接輸入，也可以在 " + Server.get().getAddress()
                + " 的「配置」頁面推送");
        field.setHelperTextTextColor(android.content.res.ColorStateList.valueOf(
                0xFFBDBDBD));

        com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(this);
        input.setSingleLine(!multiline);
        input.setMinLines(multiline ? 3 : 1);
        input.setMaxLines(multiline ? 5 : 1);
        input.setTextSize(18);
        input.setTextColor(WHITE);
        input.setHintTextColor(0xFFBDBDBD);
        input.setText(safe(initial));
        input.setSelectAllOnFocus(false);
        input.setInputType(multiline
                ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                : (key.equals("url") || key.equals("epg") || key.equals("referer")
                    ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI
                    : android.text.InputType.TYPE_CLASS_TEXT));
        input.setImeOptions(multiline ? android.view.inputmethod.EditorInfo.IME_ACTION_NONE
                : android.view.inputmethod.EditorInfo.IME_ACTION_NEXT);
        input.setOnFocusChangeListener((view, focused) -> {
            if (focused) lastFocusedField = input;
        });
        input.setOnClickListener(view -> lastFocusedField = input);
        fields.put(key, input);
        field.addView(input, new LinearLayout.LayoutParams(-1, multiline ? dp(86) : dp(56)));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(key.equals("name") ? 20 : 12);
        params.bottomMargin = dp(2);
        container.addView(field, params);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onServerEvent(ServerEvent event) {
        if (!editing || event.type() != ServerEvent.Type.SETTING) return;

        String incomingName = safe(event.name()).trim();
        String incomingText = safe(event.text()).trim();
        EditText nameInput = fields.get("name");
        EditText urlInput = fields.get("url");
        if (!incomingName.isEmpty() && nameInput != null) {
            nameInput.setText(incomingName);
            nameInput.setSelection(nameInput.getText().length());
        }
        if (!incomingText.isEmpty()) {
            EditText target = urlInput;
            if (incomingName.isEmpty() && lastFocusedField != null) target = lastFocusedField;
            if (target == null) target = urlInput;
            if (target != null) {
                target.setText(incomingText);
                target.setSelection(target.getText().length());
            }
        }
        Toast.makeText(this, "已接收手機推送資料，確認欄位後請按儲存", Toast.LENGTH_LONG).show();
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
        config.url(source.url).type(LIVE_TYPE).name(source.name).json(optionsJson(source).toString()).save();

        if (editingIndex < 0) sources.add(source);
        else sources.set(editingIndex, source);
        saveSources();
        Toast.makeText(this, "直播源設定已儲存", Toast.LENGTH_SHORT).show();
        showList();
    }

    private JSONObject optionsJson(Source source) {
        JSONObject json = new JSONObject();
        try {
            json.put("r15SourceOptions", true);
            json.put("epg", safe(source.epg));
            json.put("autoRefresh", source.autoSort);
            json.put("refreshMinutes", Math.max(1, source.refreshHours) * 60);
            json.put("userAgent", safe(source.userAgent));
            json.put("referer", safe(source.referer));
            json.put("headersText", safe(source.headers));
        } catch (JSONException ignored) {
        }
        return json;
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
        config.url(source.url).type(LIVE_TYPE).name(source.name).json(optionsJson(source).toString()).save();
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
            header.addView(spacer, new LinearLayout.LayoutParams(dp(12), 1));
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
        com.google.android.material.button.MaterialButton button =
                new com.google.android.material.button.MaterialButton(this);
        button.setText(label);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setMinHeight(dp(44));
        button.setInsetTop(0);
        button.setInsetBottom(0);
        button.setCornerRadius(dp(22));
        int surface = 0xFF292929;
        int primaryColor = PURPLE;
        int onPrimary = Color.WHITE;
        int onSurface = WHITE;
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? primaryColor : surface));
        button.setTextColor(primary ? onPrimary : onSurface);
        button.setStrokeWidth(dp(1));
        button.setStrokeColor(android.content.res.ColorStateList.valueOf(
                0xFF666666));
        button.setFocusable(true);
        button.setOnFocusChangeListener((view, focused) -> button.setStrokeWidth(dp(focused ? 2 : 1)));
        return button;
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
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

    private GradientDrawable roundDrawable(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
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
