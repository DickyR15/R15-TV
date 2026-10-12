package com.fongmi.android.tv.ui.activity;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.viewbinding.ViewBinding;

import com.fongmi.android.tv.ai.subtitle.AiSubtitleTranslationEngine;
import com.fongmi.android.tv.ai.subtitle.WhisperModelManager;
import com.fongmi.android.tv.databinding.ActivityAiSubtitleSettingsBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;
import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.zqc.opencc.android.lib.ChineseConverter;
import com.zqc.opencc.android.lib.ConversionType;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

public final class AiSubtitleSettingsActivity extends BaseActivity {
    private static final String PREFS_NAME = "r15_ai_subtitle_settings";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_TRANSLATION_SERVICE = "translation_service";
    private static final String KEY_SUBTITLE_DISPLAY = "subtitle_display";

    public static final int TRANSLATION_GOOGLE_MLKIT = 0;
    public static final int TRANSLATION_ORIGINAL_ONLY = 1;
    public static final int DISPLAY_TRANSLATED_ONLY = 0;
    public static final int DISPLAY_SOURCE_AND_TRANSLATION = 1;

    private ActivityAiSubtitleSettingsBinding mBinding;
    private WhisperModelManager modelManager;
    private Translator testTranslator;
    private boolean draftAutoStart;
    private int draftTranslationService;
    private int draftSubtitleDisplay;
    private boolean bindingSelections;
    private long translationTestStartMs;

    public static boolean isAutoStartEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false);
    }

    public static int getTranslationService(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_TRANSLATION_SERVICE, TRANSLATION_GOOGLE_MLKIT);
    }

    public static int getSubtitleDisplayMode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SUBTITLE_DISPLAY, DISPLAY_TRANSLATED_ONLY);
    }

    public static void start(Activity activity) {
        activity.startActivity(new Intent(activity, AiSubtitleSettingsActivity.class));
    }

    @Override
    protected ViewBinding getBinding() {
        return mBinding = ActivityAiSubtitleSettingsBinding.inflate(getLayoutInflater());
    }

    @Override
    protected void initView(Bundle savedInstanceState) {
        modelManager = WhisperModelManager.getInstance(this);
        draftAutoStart = isAutoStartEnabled(this);
        draftTranslationService = getTranslationService(this);
        draftSubtitleDisplay = getSubtitleDisplayMode(this);

        mBinding.autoStartSwitch.setChecked(draftAutoStart);
        ArrayAdapter<String> translationAdapter = makeAdapter(Arrays.asList(
                "Google 翻譯（ML Kit 本機模型）", "只顯示辨識原文（不翻譯）"));
        mBinding.translationServiceSpinner.setAdapter(translationAdapter);
        ArrayAdapter<String> displayAdapter = makeAdapter(Arrays.asList(
                "僅顯示繁體譯文", "原文＋繁體譯文"));
        mBinding.subtitleDisplaySpinner.setAdapter(displayAdapter);

        bindingSelections = true;
        mBinding.translationServiceSpinner.setSelection(clamp(
                draftTranslationService, TRANSLATION_GOOGLE_MLKIT, TRANSLATION_ORIGINAL_ONLY));
        mBinding.subtitleDisplaySpinner.setSelection(clamp(
                draftSubtitleDisplay, DISPLAY_TRANSLATED_ONLY, DISPLAY_SOURCE_AND_TRANSLATION));
        bindingSelections = false;

        mBinding.autoStartSwitch.setOnCheckedChangeListener((button, checked) -> draftAutoStart = checked);
        mBinding.translationServiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelections) return;
                draftTranslationService = position;
                updateTranslationOptions();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        mBinding.subtitleDisplaySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!bindingSelections) draftSubtitleDisplay = position;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        refreshModelState(null);
        updateTranslationOptions();
        if (!modelManager.isDownloaded()) mBinding.modelButton.requestFocus();
        else mBinding.autoStartSwitch.requestFocus();
    }

    private void jumpTo(View target) {
        if (target == null || mBinding == null) return;
        mBinding.subtitleSettingsScroll.post(() -> {
            int top = target.getTop() - dp(16);
            mBinding.subtitleSettingsScroll.smoothScrollTo(0, Math.max(0, top));
            target.requestFocus();
        });
    }

    private ArrayAdapter<String> makeAdapter(java.util.List<String> values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }
        };
    }

    private void styleSpinnerText(View view) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(getColor(android.R.color.white));
            text.setTextSize(18);
            text.setPadding(dp(12), dp(10), dp(12), dp(10));
        }
    }

    @Override
    protected void initEvent() {
        mBinding.jumpModelButton.setOnClickListener(v -> jumpTo(mBinding.modelSectionTitle));
        mBinding.jumpTranslationButton.setOnClickListener(v -> jumpTo(mBinding.translationSectionTitle));
        mBinding.jumpDisplayButton.setOnClickListener(v -> jumpTo(mBinding.displaySectionTitle));
        mBinding.modelButton.setOnClickListener(this::downloadModel);
        mBinding.deleteModelButton.setOnClickListener(this::confirmDeleteModel);
        mBinding.testTranslationButton.setOnClickListener(this::testTranslation);
        mBinding.saveApplyButton.setOnClickListener(this::saveAndApply);
    }

    private void updateTranslationOptions() {
        if (mBinding == null) return;
        boolean translationEnabled = draftTranslationService == TRANSLATION_GOOGLE_MLKIT;
        mBinding.subtitleDisplaySpinner.setEnabled(translationEnabled);
        mBinding.subtitleDisplayHint.setText(translationEnabled
                ? "目前支援英文語音翻成臺灣繁體；其他來源語言的翻譯尚未整合。"
                : "已關閉翻譯，只顯示 Whisper 辨識原文。");
        mBinding.translationSummary.setText(translationEnabled
                ? "Google ML Kit 英翻中＋OpenCC 臺灣繁體。首次使用需下載翻譯模型；下載完成後可離線翻譯。"
                : "不啟動翻譯模型，直接顯示 Whisper 辨識文字。");
        mBinding.testTranslationButton.setEnabled(translationEnabled);
    }

    private void downloadModel(View view) {
        if (modelManager.isDownloading()) return;
        mBinding.modelButton.setEnabled(false);
        mBinding.modelProgress.setVisibility(View.VISIBLE);
        mBinding.modelProgress.setProgress(0);
        mBinding.modelStatus.setText("正在連線下載 Whisper Base 多語模型…");
        modelManager.download(new WhisperModelManager.Listener() {
            @Override
            public void onProgress(long downloadedBytes, long totalBytes) {
                if (isFinishing()) return;
                if (totalBytes > 0) {
                    int percent = (int) Math.min(100L, downloadedBytes * 100L / totalBytes);
                    mBinding.modelProgress.setProgress(percent);
                    mBinding.modelStatus.setText(String.format(Locale.getDefault(),
                            "下載中：%.1f / %.1f MB（%d%%）",
                            downloadedBytes / 1048576.0, totalBytes / 1048576.0, percent));
                } else {
                    mBinding.modelStatus.setText(String.format(Locale.getDefault(),
                            "下載中：%.1f MB", downloadedBytes / 1048576.0));
                }
            }
            @Override public void onDownloaded(File file) {
                if (!isFinishing()) refreshModelState("Whisper Base 已下載完成。");
            }
            @Override public void onDeleted() {
                if (!isFinishing()) refreshModelState("Whisper Base 模型已刪除。");
            }
            @Override public void onError(String message) {
                if (!isFinishing()) refreshModelState("模型下載失敗：" + message);
            }
        });
    }

    private void confirmDeleteModel(View view) {
        if (modelManager.isDownloading()) return;
        new AlertDialog.Builder(this)
                .setTitle("刪除 Whisper Base 模型？")
                .setMessage("會刪除約 148 MB 的本機語音辨識模型；日後可重新下載。")
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("刪除", (dialog, which) ->
                        modelManager.delete(new WhisperModelManager.Listener() {
                            @Override public void onProgress(long downloadedBytes, long totalBytes) { }
                            @Override public void onDownloaded(File file) { }
                            @Override public void onDeleted() {
                                if (!isFinishing()) refreshModelState("Whisper Base 模型已刪除。");
                            }
                            @Override public void onError(String message) {
                                if (!isFinishing()) refreshModelState("刪除失敗：" + message);
                            }
                        }))
                .show();
    }

    private void refreshModelState(String notice) {
        if (mBinding == null || isFinishing()) return;
        boolean ready = modelManager != null && modelManager.isDownloaded();
        boolean downloading = modelManager != null && modelManager.isDownloading();
        mBinding.modelStatus.setText(notice != null ? notice : ready
                ? "模型狀態：Whisper Base 多語模型已下載"
                : downloading ? "模型下載中，請稍候…" : "模型狀態：尚未下載");
        mBinding.modelButton.setVisibility(ready ? View.GONE : View.VISIBLE);
        mBinding.modelButton.setEnabled(!downloading);
        mBinding.modelButton.setText(downloading
                ? "正在下載 Whisper Base…"
                : "下載 Whisper Base 多語模型（約 148 MB）");
        mBinding.deleteModelButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        mBinding.modelProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
    }

    private void testTranslation(View view) {
        if (draftTranslationService != TRANSLATION_GOOGLE_MLKIT) {
            mBinding.translationTestStatus.setText("已選擇只顯示辨識原文，不需要翻譯模型。");
            return;
        }
        if (testTranslator != null) {
            mBinding.translationTestStatus.setText("翻譯測試正在執行，請稍候…");
            return;
        }
        mBinding.testTranslationButton.setEnabled(false);
        mBinding.translationTestProgress.setVisibility(View.VISIBLE);
        mBinding.translationTestProgress.setProgress(0);
        mBinding.translationTestStatus.setText("正在檢查／下載 Google 翻譯模型…");
        translationTestStartMs = SystemClock.elapsedRealtime();
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        testTranslator = Translation.getClient(options);
        testTranslator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    if (isFinishing() || testTranslator == null) return;
                    mBinding.translationTestStatus.setText("模型已就緒，正在測試翻譯…");
                    testTranslator.translate("The match starts in ten minutes.")
                            .addOnSuccessListener(translated -> {
                                if (isFinishing()) return;
                                String traditional = translated;
                                try {
                                    String converted = ChineseConverter.convert(
                                            translated, ConversionType.S2TWP, this);
                                    if (converted != null && !converted.trim().isEmpty()) traditional = converted.trim();
                                } catch (Throwable ignored) { }
                                long elapsed = SystemClock.elapsedRealtime() - translationTestStartMs;
                                mBinding.translationTestStatus.setText("測試成功（" + elapsed + " ms）："
                                        + traditional + "。首次下載時間不列入純翻譯延遲。");
                                finishTranslationTest();
                            })
                            .addOnFailureListener(error -> {
                                if (!isFinishing()) {
                                    mBinding.translationTestStatus.setText("翻譯測試失敗：" + readableMessage(error));
                                    finishTranslationTest();
                                }
                            });
                })
                .addOnFailureListener(error -> {
                    if (!isFinishing()) {
                        mBinding.translationTestStatus.setText("翻譯模型下載失敗：" + readableMessage(error));
                        finishTranslationTest();
                    }
                });
    }

    private void finishTranslationTest() {
        Translator translator = testTranslator;
        testTranslator = null;
        if (translator != null) {
            try { translator.close(); } catch (Throwable ignored) { }
        }
        if (mBinding != null && !isFinishing()) {
            mBinding.testTranslationButton.setEnabled(
                    draftTranslationService == TRANSLATION_GOOGLE_MLKIT);
            mBinding.translationTestProgress.setVisibility(View.GONE);
        }
    }

    private void saveAndApply(View view) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, draftAutoStart)
                .putInt(KEY_TRANSLATION_SERVICE, draftTranslationService)
                .putInt(KEY_SUBTITLE_DISPLAY, draftSubtitleDisplay)
                .apply();
        Toast.makeText(this, "AI 字幕設定已儲存並套用", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onDestroy() {
        finishTranslationTest();
        super.onDestroy();
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
