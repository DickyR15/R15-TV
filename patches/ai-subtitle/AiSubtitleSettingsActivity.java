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

import com.fongmi.android.tv.ai.subtitle.AiSubtitleModelManager;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleTranslationEngine;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleTranslationProvider;
import com.fongmi.android.tv.databinding.ActivityAiSubtitleSettingsBinding;
import com.fongmi.android.tv.ui.base.BaseActivity;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Taiwan Traditional Chinese settings for AutoBuild-170-style live subtitles. */
public final class AiSubtitleSettingsActivity extends BaseActivity {
    private static final String PREFS_NAME = "r15_ai_subtitle_settings";
    private static final String KEY_AUTO_START = "auto_start_v3";
    private static final String KEY_TRANSLATION_PROVIDER = "translation_provider";
    private static final String KEY_SUBTITLE_DISPLAY = "subtitle_display";
    private static final String KEY_ASR_LANGUAGE = "asr_language";
    private static final String KEY_ENDPOINT = "translation_endpoint";
    private static final String KEY_API_KEY = "translation_api_key";
    private static final String KEY_REGION = "translation_region";
    private static final String KEY_MODEL = "translation_model";

    private static final String[] ASR_LANGUAGE_CODES = {"en", "zh", "yue", "ja", "ko", "de", "fr", "es"};
    private static final String[] ASR_LANGUAGE_LABELS = {
            "英語", "中文", "粵語", "日語", "韓語", "德語", "法語", "西班牙語"
    };

    public static final int DISPLAY_TRANSLATED_ONLY = 0;
    public static final int DISPLAY_SOURCE_AND_TRANSLATION = 1;

    private ActivityAiSubtitleSettingsBinding mBinding;
    private AiSubtitleModelManager modelManager;
    private boolean bindingSelections;
    private boolean testingTranslation;
    private long translationTestStartMs;

    private boolean draftAutoStart;
    private String draftLanguage = "en";
    private String draftProvider = "google";
    private int draftSubtitleDisplay = DISPLAY_TRANSLATED_ONLY;
    private String draftEndpoint = "";
    private String draftApiKey = "";
    private String draftRegion = "";
    private String draftModel = "";

    public static boolean isAutoStartEnabled(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_START, false);
    }

    public static String getAsrLanguage(Context context) {
        String value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_ASR_LANGUAGE, "en");
        for (String code : ASR_LANGUAGE_CODES) if (code.equals(value)) return code;
        return "en";
    }

    public static void setAsrLanguage(Context context, String language) {
        if (context == null) return;
        String selected = "en";
        for (String code : ASR_LANGUAGE_CODES) {
            if (code.equals(language)) {
                selected = code;
                break;
            }
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_ASR_LANGUAGE, selected).apply();
    }

    public static String getAsrLanguageLabel(String code) {
        for (int i = 0; i < ASR_LANGUAGE_CODES.length; i++) {
            if (ASR_LANGUAGE_CODES[i].equals(code)) return ASR_LANGUAGE_LABELS[i];
        }
        return ASR_LANGUAGE_LABELS[0];
    }

    public static String getTranslationProvider(Context context) {
        String value = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_TRANSLATION_PROVIDER, "google");
        return AiSubtitleTranslationProvider.fromStoredValue(value).getId();
    }

    public static int getSubtitleDisplayMode(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_SUBTITLE_DISPLAY, DISPLAY_TRANSLATED_ONLY) == DISPLAY_SOURCE_AND_TRANSLATION
                ? DISPLAY_SOURCE_AND_TRANSLATION : DISPLAY_TRANSLATED_ONLY;
    }

    public static String getTranslationEndpoint(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ENDPOINT, "");
    }

    public static String getTranslationApiKey(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_API_KEY, "");
    }

    public static String getTranslationRegion(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_REGION, "");
    }

    public static String getTranslationModel(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_MODEL, "");
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
        modelManager = AiSubtitleModelManager.getInstance(this);
        draftAutoStart = isAutoStartEnabled(this);
        draftLanguage = getAsrLanguage(this);
        draftProvider = getTranslationProvider(this);
        draftSubtitleDisplay = getSubtitleDisplayMode(this);
        draftEndpoint = safe(getTranslationEndpoint(this));
        draftApiKey = safe(getTranslationApiKey(this));
        draftRegion = safe(getTranslationRegion(this));
        draftModel = safe(getTranslationModel(this));

        mBinding.autoStartSwitch.setChecked(draftAutoStart);
        mBinding.asrLanguageSpinner.setAdapter(makeAdapter(Arrays.asList(ASR_LANGUAGE_LABELS)));
        mBinding.asrModelSpinner.setAdapter(makeAdapter(Arrays.asList(modelDescription(draftLanguage))));
        mBinding.asrModelSpinner.setEnabled(false);

        List<AiSubtitleTranslationProvider> providers = AiSubtitleTranslationProvider.selectorOptions();
        List<String> labels = new ArrayList<>();
        for (AiSubtitleTranslationProvider provider : providers) labels.add(provider.getLabel());
        mBinding.translationServiceSpinner.setAdapter(makeAdapter(labels));
        mBinding.subtitleDisplaySpinner.setAdapter(makeAdapter(Arrays.asList(
                "僅顯示臺灣繁體中文字幕", "來源字幕＋臺灣繁體中文字幕")));

        mBinding.translationEndpoint.setText(draftEndpoint);
        mBinding.translationApiKey.setText(draftApiKey);
        mBinding.translationRegion.setText(draftRegion);
        mBinding.translationModel.setText(draftModel);

        bindingSelections = true;
        mBinding.asrLanguageSpinner.setSelection(languageIndex(draftLanguage));
        mBinding.translationServiceSpinner.setSelection(providerIndex(draftProvider, providers));
        mBinding.subtitleDisplaySpinner.setSelection(draftSubtitleDisplay);
        bindingSelections = false;

        mBinding.autoStartSwitch.setOnCheckedChangeListener((button, checked) -> draftAutoStart = checked);
        mBinding.asrLanguageSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelections) return;
                draftLanguage = ASR_LANGUAGE_CODES[Math.max(0, Math.min(ASR_LANGUAGE_CODES.length - 1, position))];
                mBinding.asrModelSpinner.setAdapter(makeAdapter(Arrays.asList(modelDescription(draftLanguage))));
                updateLanguageOptions();
                refreshModelState(null);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        mBinding.translationServiceSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (bindingSelections) return;
                draftProvider = providers.get(Math.max(0, Math.min(providers.size() - 1, position))).getId();
                updateTranslationOptions();
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        mBinding.subtitleDisplaySpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!bindingSelections) draftSubtitleDisplay = position == DISPLAY_SOURCE_AND_TRANSLATION
                        ? DISPLAY_SOURCE_AND_TRANSLATION : DISPLAY_TRANSLATED_ONLY;
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });

        updateLanguageOptions();
        updateTranslationOptions();
        refreshModelState(null);
        if (!modelManager.isDownloaded(draftLanguage)) mBinding.modelButton.requestFocus();
        else mBinding.autoStartSwitch.requestFocus();
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

    private void jumpTo(View target) {
        if (target == null || mBinding == null) return;
        mBinding.subtitleSettingsScroll.post(() -> {
            mBinding.subtitleSettingsScroll.smoothScrollTo(0, Math.max(0, target.getTop() - dp(16)));
            target.requestFocus();
        });
    }

    private ArrayAdapter<String> makeAdapter(List<String> values) {
        return new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                styleSpinnerText(view);
                return view;
            }
            @Override public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
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

    private void updateLanguageOptions() {
        if (mBinding == null) return;
        String modelName = modelManager.getModelId(draftLanguage);
        String engineType = modelManager.getEngineType(draftLanguage);
        mBinding.asrMode.setText("固定辨識語言：" + getAsrLanguageLabel(draftLanguage)
                + "｜對應模型：" + modelName + "｜辨識模式：" + engineType);
        mBinding.asrModelSpinner.setAdapter(makeAdapter(Arrays.asList(modelDescription(draftLanguage))));
    }

    private String modelDescription(String language) {
        return modelManager.getModelId(language) + "（約 " + modelManager.getEstimatedSizeMb(language) + " MB）";
    }

    private void updateTranslationOptions() {
        if (mBinding == null) return;
        boolean sourceOnly = "source_only".equals(draftProvider);
        boolean providerNeedsEndpoint = "bing".equals(draftProvider) || "deepl".equals(draftProvider)
                || "mtran".equals(draftProvider) || "openai".equals(draftProvider);
        boolean keyNeeded = providerNeedsEndpoint;
        boolean regionNeeded = "bing".equals(draftProvider);
        boolean modelNeeded = "openai".equals(draftProvider);

        mBinding.translationEndpointLabel.setVisibility(providerNeedsEndpoint ? View.VISIBLE : View.GONE);
        mBinding.translationEndpoint.setVisibility(providerNeedsEndpoint ? View.VISIBLE : View.GONE);
        mBinding.translationApiKeyLabel.setVisibility(keyNeeded ? View.VISIBLE : View.GONE);
        mBinding.translationApiKey.setVisibility(keyNeeded ? View.VISIBLE : View.GONE);
        mBinding.translationRegionLabel.setVisibility(regionNeeded ? View.VISIBLE : View.GONE);
        mBinding.translationRegion.setVisibility(regionNeeded ? View.VISIBLE : View.GONE);
        mBinding.translationModelLabel.setVisibility(modelNeeded ? View.VISIBLE : View.GONE);
        mBinding.translationModel.setVisibility(modelNeeded ? View.VISIBLE : View.GONE);

        mBinding.subtitleDisplaySpinner.setEnabled(!sourceOnly);
        mBinding.subtitleDisplayHint.setText(sourceOnly
                ? "已停用翻譯，只顯示原始辨識字幕。"
                : "所有譯文顯示前都會轉成臺灣繁體中文；不提供簡體中文字幕。");
        if (sourceOnly) {
            mBinding.translationSummary.setText("只顯示辨識原文，不連線翻譯服務。");
        } else if ("zh".equals(draftLanguage) || "yue".equals(draftLanguage)) {
            mBinding.translationSummary.setText("中文辨識直接使用 OpenCC S2TWP 轉為臺灣繁體，不需翻譯 API。");
        } else {
            mBinding.translationSummary.setText("目標語言固定為 zh-TW。可選 Google、Bing、DeepL、MTranServer 或 OpenAI 相容服務。");
        }
        mBinding.testTranslationButton.setEnabled(!testingTranslation);
        updateInputHints();
    }

    private void updateInputHints() {
        if ("bing".equals(draftProvider)) {
            mBinding.translationEndpoint.setHint("https://api.cognitive.microsofttranslator.com");
            mBinding.translationApiKey.setHint("Microsoft Translator 訂閱金鑰");
            mBinding.translationRegion.setHint("例如：eastus（需與金鑰區域相符）");
        } else if ("deepl".equals(draftProvider)) {
            mBinding.translationEndpoint.setHint("https://api-free.deepl.com");
            mBinding.translationApiKey.setHint("DeepL API 金鑰");
            mBinding.translationRegion.setHint("DeepL 不需要區域設定");
        } else if ("mtran".equals(draftProvider)) {
            mBinding.translationEndpoint.setHint("https://你的 MTranServer 網址");
            mBinding.translationApiKey.setHint("選填：Bearer Token");
        } else if ("openai".equals(draftProvider)) {
            mBinding.translationEndpoint.setHint("https://api.openai.com/v1");
            mBinding.translationApiKey.setHint("OpenAI 相容 API 金鑰");
            mBinding.translationModel.setHint("例如：gpt-4o-mini");
        }
    }

    private void downloadModel(View view) {
        if (modelManager.isDownloading()) return;
        final String language = draftLanguage;
        final String label = getAsrLanguageLabel(language);
        mBinding.modelButton.setEnabled(false);
        mBinding.modelProgress.setVisibility(View.VISIBLE);
        mBinding.modelProgress.setProgress(0);
        mBinding.modelStatus.setText("正在下載「" + label + "」語音模型…");
        modelManager.download(language, new AiSubtitleModelManager.Listener() {
            @Override public void onProgress(long downloadedBytes, long totalBytes) {
                if (isFinishing()) return;
                int percent = totalBytes > 0 ? (int) Math.min(100L, downloadedBytes * 100L / totalBytes) : 0;
                mBinding.modelProgress.setProgress(percent);
                mBinding.modelStatus.setText(totalBytes > 0
                        ? String.format(Locale.getDefault(), "下載中：%.1f / %.1f MB（%d%%）",
                        downloadedBytes / 1048576.0, totalBytes / 1048576.0, percent)
                        : String.format(Locale.getDefault(), "下載中：%.1f MB", downloadedBytes / 1048576.0));
            }
            @Override public void onDownloaded(File directory) {
                if (!isFinishing()) refreshModelState("已下載「" + label + "」模型。");
            }
            @Override public void onDeleted() {
                if (!isFinishing()) refreshModelState("已刪除「" + label + "」模型。");
            }
            @Override public void onError(String message) {
                if (!isFinishing()) refreshModelState("模型下載失敗：" + message);
            }
        });
    }

    private void confirmDeleteModel(View view) {
        if (modelManager.isDownloading()) return;
        final String language = draftLanguage;
        new AlertDialog.Builder(this)
                .setTitle("刪除「" + getAsrLanguageLabel(language) + "」語音模型？")
                .setMessage("會移除約 " + modelManager.getEstimatedSizeMb(language)
                        + " MB 的本機模型；日後可以重新下載。")
                .setNegativeButton("取消", (dialog, which) -> dialog.dismiss())
                .setPositiveButton("刪除", (dialog, which) ->
                        modelManager.delete(language, new AiSubtitleModelManager.Listener() {
                            @Override public void onProgress(long downloadedBytes, long totalBytes) { }
                            @Override public void onDownloaded(File directory) { }
                            @Override public void onDeleted() {
                                if (!isFinishing()) refreshModelState("模型已刪除。");
                            }
                            @Override public void onError(String message) {
                                if (!isFinishing()) refreshModelState("刪除失敗：" + message);
                            }
                        })).show();
    }

    private void refreshModelState(String notice) {
        if (mBinding == null || isFinishing()) return;
        boolean ready = modelManager != null && modelManager.isDownloaded(draftLanguage);
        boolean downloading = modelManager != null && modelManager.isDownloading(draftLanguage);
        String label = getAsrLanguageLabel(draftLanguage);
        mBinding.modelStatus.setText(notice != null ? notice : ready
                ? "模型狀態：「" + label + "」已下載並通過大小檢查。"
                : downloading ? "模型下載中，請稍候…" : "尚未下載「" + label + "」模型。");
        mBinding.modelButton.setVisibility(ready ? View.GONE : View.VISIBLE);
        mBinding.modelButton.setEnabled(!modelManager.isDownloading());
        mBinding.modelButton.setText(downloading ? "正在下載…" : "下載「" + label + "」模型（約 "
                + modelManager.getEstimatedSizeMb(draftLanguage) + " MB）");
        mBinding.deleteModelButton.setVisibility(ready ? View.VISIBLE : View.GONE);
        mBinding.deleteModelButton.setEnabled(!modelManager.isDownloading());
        mBinding.modelProgress.setVisibility(modelManager.isDownloading(draftLanguage) ? View.VISIBLE : View.GONE);
    }

    private void testTranslation(View view) {
        if (testingTranslation) return;
        draftEndpoint = safe(mBinding.translationEndpoint.getText().toString());
        draftApiKey = safe(mBinding.translationApiKey.getText().toString());
        draftRegion = safe(mBinding.translationRegion.getText().toString());
        draftModel = safe(mBinding.translationModel.getText().toString());
        String sample = samplePhrase(draftLanguage);
        testingTranslation = true;
        translationTestStartMs = SystemClock.elapsedRealtime();
        mBinding.translationTestProgress.setVisibility(View.VISIBLE);
        mBinding.translationTestProgress.setIndeterminate(true);
        mBinding.testTranslationButton.setEnabled(false);
        mBinding.translationTestStatus.setText("正在測試翻譯服務與臺灣繁體輸出…");
        AiSubtitleTranslationEngine.translateOnce(this, sample, draftLanguage, draftProvider,
                draftEndpoint, draftApiKey, draftRegion, draftModel,
                new AiSubtitleTranslationEngine.Listener() {
                    @Override public void onStatus(String message) {
                        if (!isFinishing()) mBinding.translationTestStatus.setText(message);
                    }
                    @Override public void onTranslatedSubtitle(String source, String translated) {
                        if (isFinishing()) return;
                        long elapsed = SystemClock.elapsedRealtime() - translationTestStartMs;
                        mBinding.translationTestStatus.setText("測試成功（" + elapsed + " ms）：\n" + translated);
                        finishTranslationTest();
                    }
                });
    }

    private String samplePhrase(String language) {
        switch (language) {
            case "yue": return "賽事十分钟後開始。";
            case "de": return "Das Rennen beginnt in zehn Minuten.";
            case "en": return "The race starts in ten minutes.";
            case "fr": return "La course commence dans dix minutes.";
            case "es": return "La carrera comienza en diez minutos.";
            case "ja": return "レースは10分後にスタートします。";
            case "zh": return "這場比賽將在十分鐘後開始。";
            case "ko": return "경주는 10분 후에 시작합니다.";
            default: return "The race starts in ten minutes.";
        }
    }

    private void finishTranslationTest() {
        testingTranslation = false;
        if (mBinding != null && !isFinishing()) {
            mBinding.testTranslationButton.setEnabled(true);
            mBinding.translationTestProgress.setVisibility(View.GONE);
        }
    }

    private void saveAndApply(View view) {
        draftEndpoint = safe(mBinding.translationEndpoint.getText().toString());
        draftApiKey = safe(mBinding.translationApiKey.getText().toString());
        draftRegion = safe(mBinding.translationRegion.getText().toString());
        draftModel = safe(mBinding.translationModel.getText().toString());
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_AUTO_START, draftAutoStart)
                .putString(KEY_ASR_LANGUAGE, draftLanguage)
                .putString(KEY_TRANSLATION_PROVIDER, draftProvider)
                .putInt(KEY_SUBTITLE_DISPLAY, draftSubtitleDisplay)
                .putString(KEY_ENDPOINT, draftEndpoint)
                .putString(KEY_API_KEY, draftApiKey)
                .putString(KEY_REGION, draftRegion)
                .putString(KEY_MODEL, draftModel)
                .apply();
        Toast.makeText(this, "R15 TV 即時字幕設定已儲存並套用", Toast.LENGTH_SHORT).show();
        finish();
    }

    private static int providerIndex(String id, List<AiSubtitleTranslationProvider> providers) {
        for (int i = 0; i < providers.size(); i++) if (providers.get(i).getId().equals(id)) return i;
        for (int i = 0; i < providers.size(); i++) if ("google".equals(providers.get(i).getId())) return i;
        return 0;
    }

    private static int languageIndex(String value) {
        for (int i = 0; i < ASR_LANGUAGE_CODES.length; i++) if (ASR_LANGUAGE_CODES[i].equals(value)) return i;
        return 0;
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
