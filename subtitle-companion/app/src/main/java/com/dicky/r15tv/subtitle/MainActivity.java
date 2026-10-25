package com.dicky.r15tv.subtitle;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.fongmi.android.tv.ai.subtitle.WhisperModelManager;

public final class MainActivity extends Activity {
    private static final int REQUEST_CAPTURE = 7301;
    private static final int REQUEST_AUDIO = 7302;
    private TextView statusView;
    private TextView modelView;
    private ProgressBar progress;
    private WhisperModelManager modelManager;
    private SubtitleOverlayWindow overlayTestWindow;
    private Button overlayTestButton;
    private TextView crashView;
    private boolean overlayTestVisible;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        modelManager = WhisperModelManager.getInstance(this);
        buildUi();
        refreshModelState();
        loadCrashReport();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (statusView == null) return;
        String lastStatus = getSharedPreferences(SubtitleCaptureService.PREFS, MODE_PRIVATE)
                .getString(SubtitleCaptureService.PREF_STATUS, "");
        if (!lastStatus.isEmpty() && !lastStatus.equals("字幕服務已停止。")) {
            setStatus("字幕服務回報：" + lastStatus);
        }
        loadCrashReport();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER_HORIZONTAL);
        int padding = dp(28);
        layout.setPadding(padding, dp(22), padding, dp(22));
        layout.setBackgroundColor(0xFF081326);

        TextView title = new TextView(this);
        title.setText("R15 TV｜AI 即時字幕測試");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(28);
        title.setGravity(Gravity.CENTER);
        layout.addView(title, matchWrap());

        TextView desc = new TextView(this);
        desc.setText("獨立測試工具：先確認浮窗可顯示，再啟動音訊擷取並切回 R15 TV 播放直播。\n使用 Whisper Base 多語辨識、ML Kit 本機翻譯與 OpenCC 臺灣繁體轉換。");
        desc.setTextColor(0xFFD3E1F5);
        desc.setTextSize(17);
        desc.setGravity(Gravity.CENTER);
        desc.setPadding(0, dp(12), 0, dp(16));
        layout.addView(desc, matchWrap());

        modelView = new TextView(this);
        modelView.setTextColor(0xFF8AC8FF);
        modelView.setTextSize(16);
        modelView.setGravity(Gravity.CENTER);
        layout.addView(modelView, matchWrap());

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.setMargins(0, dp(8), 0, dp(8));
        layout.addView(progress, progressParams);

        addButton(layout, "1. 允許顯示在其他應用程式上層", v -> requestOverlayPermission());
        overlayTestButton = addButton(layout, "2. 測試字幕浮窗（不啟動音訊）", v -> toggleOverlayTest());
        addButton(layout, "3. 下載／檢查 Whisper Base 多語模型", v -> downloadModel());
        addButton(layout, "4. 開始字幕擷取（接著切回 R15 TV 播放）", v -> requestStartCapture());
        addButton(layout, "停止字幕擷取", v -> {
            closeOverlayTest();
            Intent stop = new Intent(this, SubtitleCaptureService.class);
            stop.setAction(SubtitleCaptureService.ACTION_STOP);
            startService(stop);
            setStatus("已送出停止要求。");
        });

        statusView = new TextView(this);
        statusView.setTextColor(0xFFFFFFFF);
        statusView.setTextSize(16);
        statusView.setGravity(Gravity.CENTER);
        statusView.setPadding(0, dp(14), 0, dp(4));
        statusView.setText("準備中…");
        layout.addView(statusView, matchWrap());

        TextView warning = new TextView(this);
        warning.setText("注意：本測試版會要求 Android 音訊擷取及浮層授權；音訊只在裝置上辨識，翻譯模型首次使用時需下載。若啟動失敗，錯誤會保留在本頁與系統通知中。");
        warning.setTextColor(0xFF9EACC2);
        warning.setTextSize(14);
        warning.setGravity(Gravity.CENTER);
        warning.setPadding(0, dp(12), 0, dp(8));
        layout.addView(warning, matchWrap());

        crashView = new TextView(this);
        crashView.setTextColor(0xFFFFB8B8);
        crashView.setTextSize(13);
        crashView.setTextIsSelectable(true);
        crashView.setPadding(0, dp(12), 0, dp(12));
        crashView.setVisibility(View.GONE);
        layout.addView(crashView, matchWrap());

        Button clearCrash = new Button(this);
        clearCrash.setText("清除已保存的閃退紀錄");
        clearCrash.setAllCaps(false);
        clearCrash.setOnClickListener(v -> {
            SubtitlePocApplication.clearLastCrash(this);
            loadCrashReport();
            setStatus("已清除本機閃退紀錄。");
        });
        layout.addView(clearCrash, matchWrap());

        scroll.addView(layout);
        setContentView(scroll);
    }

    private Button addButton(LinearLayout root, String label, View.OnClickListener action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setOnClickListener(action);
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(5), 0, dp(5));
        root.addView(button, params);
        return button;
    }

    private void toggleOverlayTest() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setStatus("尚未允許浮窗權限。請在設定頁開啟後返回，再按一次「測試字幕浮窗」。");
            requestOverlayPermission();
            return;
        }
        if (overlayTestVisible) {
            closeOverlayTest();
            setStatus("已關閉測試浮窗。");
            return;
        }
        try {
            overlayTestWindow = new SubtitleOverlayWindow(this);
            overlayTestWindow.show();
            overlayTestWindow.setStatus("浮窗測試成功｜尚未開始語音辨識");
            overlayTestWindow.showSubtitle("這是浮窗預覽", "繁體中文字幕測試");
            overlayTestVisible = true;
            if (overlayTestButton != null) overlayTestButton.setText("2. 關閉字幕浮窗測試");
            setStatus("已送出浮窗顯示要求。若沒有看到「繁體中文字幕測試」，請確認系統浮窗權限或回報此狀態。");
        } catch (Throwable error) {
            closeOverlayTest();
            setStatus("浮窗測試失敗：" + readable(error));
        }
    }

    private void closeOverlayTest() {
        if (overlayTestWindow != null) {
            try { overlayTestWindow.remove(); } catch (Throwable ignored) { }
            overlayTestWindow = null;
        }
        overlayTestVisible = false;
        if (overlayTestButton != null) overlayTestButton.setText("2. 測試字幕浮窗（不啟動音訊）");
    }

    private void requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } else {
            setStatus("浮層權限已允許。");
        }
    }

    private void downloadModel() {
        if (modelManager.isDownloaded()) {
            setStatus("Whisper Base 已下載完成，可開始擷取。");
            refreshModelState();
            return;
        }
        setStatus("正在下載 Whisper Base 多語模型，請保持本頁開啟…");
        progress.setVisibility(View.VISIBLE);
        modelManager.download(new WhisperModelManager.Listener() {
            @Override public void onProgress(long done, long total) {
                if (total > 0) {
                    progress.setIndeterminate(false);
                    progress.setProgress((int) Math.min(1000, done * 1000 / total));
                    modelView.setText("模型下載：" + (done / 1048576) + " / " + (total / 1048576) + " MiB");
                } else {
                    progress.setIndeterminate(true);
                    modelView.setText("模型下載中：" + (done / 1048576) + " MiB");
                }
            }
            @Override public void onDownloaded(java.io.File file) {
                progress.setVisibility(View.GONE);
                setStatus("Whisper Base 模型下載完成。");
                refreshModelState();
            }
            @Override public void onDeleted() { refreshModelState(); }
            @Override public void onError(String message) {
                progress.setVisibility(View.GONE);
                setStatus("模型下載失敗：" + message);
                refreshModelState();
            }
        });
    }

    private void refreshModelState() {
        if (modelManager == null || modelView == null) return;
        modelView.setText(modelManager.isDownloaded()
                ? "模型狀態：Whisper Base 已就緒"
                : "模型狀態：尚未下載（首次下載約 150 MiB）");
    }

    private void requestStartCapture() {
        // Android playback capture APIs were introduced in Android 10 (API 29).
        // The app can still test overlays on Android 9, but must not try to start capture there.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            setStatus("此 Android 版本（API " + Build.VERSION.SDK_INT
                    + "）不支援內部播放音訊擷取；需要 Android 10（API 29）以上。浮窗預覽仍可使用。");
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            setStatus("請先允許「顯示在其他應用程式上層」，完成後再按開始。");
            requestOverlayPermission();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        if (!modelManager.isDownloaded()) {
            setStatus("請先下載 Whisper Base 多語模型。");
            return;
        }
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            setStatus("此裝置不支援 MediaProjection，無法擷取播放音訊。");
            return;
        }
        setStatus("請在 Android 系統授權視窗中允許音訊擷取。");
        startActivityForResult(manager.createScreenCaptureIntent(), REQUEST_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAPTURE) return;
        if (resultCode != RESULT_OK || data == null) {
            setStatus("你取消了音訊擷取授權，尚未開始字幕。");
            return;
        }
        Intent start = new Intent(this, SubtitleCaptureService.class);
        start.setAction(SubtitleCaptureService.ACTION_START);
        start.putExtra(SubtitleCaptureService.EXTRA_RESULT_CODE, resultCode);
        start.putExtra(SubtitleCaptureService.EXTRA_PROJECTION_DATA, data);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(start);
            else startService(start);
            setStatus("字幕服務正在啟動；若服務異常退出，重新開啟本 App 可查看錯誤紀錄。");
        } catch (Throwable error) {
            setStatus("無法啟動字幕服務：" + readable(error));
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQUEST_AUDIO) {
            boolean allowed = results.length > 0
                    && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            setStatus(allowed
                    ? "麥克風／播放音訊擷取權限已允許，請再按一次開始。"
                    : "未允許音訊擷取權限，無法產生字幕。");
        }
    }

    @Override
    protected void onDestroy() {
        closeOverlayTest();
        super.onDestroy();
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private void loadCrashReport() {
        if (crashView == null) return;
        String report = SubtitlePocApplication.readLastCrash(this);
        if (report == null || report.isEmpty()) {
            crashView.setVisibility(View.GONE);
            return;
        }
        crashView.setVisibility(View.VISIBLE);
        // Show the latest part of the stack trace so it fits on TV; text remains selectable.
        if (report.length() > 10000) report = report.substring(report.length() - 10000);
        crashView.setText("偵測到上次閃退紀錄（可選取複製或截圖回傳）：\n" + report);
        setStatus("偵測到上次閃退紀錄，請查看頁面下方紅色文字。");
    }

    private String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private void setStatus(String value) {
        if (statusView != null) statusView.setText(value);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
