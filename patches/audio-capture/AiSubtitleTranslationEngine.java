package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.zqc.opencc.android.lib.ChineseConverter;
import com.zqc.opencc.android.lib.ConversionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

public final class AiSubtitleTranslationEngine {
    public interface Listener {
        void onStatus(String status);
        void onTranslatedSubtitle(String source, String translation);
    }

    private static final int MAX_PENDING_PHRASES = 8;

    private final Context context;
    private final Listener listener;
    private final String sourceLanguage;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService conversionExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "r15-subtitle-translation");
                thread.setDaemon(true);
                return thread;
            });
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Translator translator;

    private volatile boolean closed;
    private boolean modelReady;
    private boolean modelLoading;
    private boolean translating;
    private String lastQueuedPhrase = "";
    private String processingPhrase = "";

    public AiSubtitleTranslationEngine(Context context, String sourceLanguage, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.sourceLanguage = normalizeSourceLanguage(sourceLanguage);
        if ("zh".equals(this.sourceLanguage)) {
            translator = null;
            modelReady = true;
        } else {
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(toMlKitLanguage(this.sourceLanguage))
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build();
            translator = Translation.getClient(options);
        }
    }

    public void start() {
        if ("zh".equals(sourceLanguage)) {
            postStatus("中文辨識已設定；將使用 OpenCC 轉為臺灣繁體，不需下載翻譯模型");
        } else {
            postStatus("免費 Google ML Kit 本機翻譯：" + languageLabel(sourceLanguage)
                    + "→繁體中文；首次使用下載模型");
        }
    }

    public void translateFinalPhrase(String text) {
        translateFinalPhrase(text, sourceLanguage);
    }

    public void translateFinalPhrase(String text, String sourceLanguageFromAsr) {
        if (closed || text == null) return;
        String phrase = text.trim();
        if (phrase.isEmpty()) return;

        String language = normalizeSourceLanguageOrUnd(sourceLanguageFromAsr);
        if ("und".equals(language)) language = sourceLanguage;

        if ("zh".equals(sourceLanguage)) {
            deliverOriginal(phrase, true, "已辨識中文；轉為臺灣繁體");
            return;
        }
        if (!sourceLanguage.equals(language)) {
            deliverOriginal(phrase, false, "辨識語言與固定選項不一致；先保留原文");
            return;
        }

        synchronized (lock) {
            if (closed || phrase.equals(lastQueuedPhrase) || pending.contains(phrase)) return;
            lastQueuedPhrase = phrase;
            if (pending.size() >= MAX_PENDING_PHRASES) {
                pending.removeFirst();
                postStatus("翻譯略有積壓，略過最舊句段以保持直播同步");
            }
            pending.addLast(phrase);
        }
        ensureTranslationModelLoaded();
        drainQueue();
    }

    private void deliverOriginal(String source, boolean convertTraditional, String statusMessage) {
        postStatus(statusMessage);
        try {
            conversionExecutor.execute(() -> {
                String result = source;
                if (convertTraditional) {
                    try {
                        String converted = ChineseConverter.convert(source, ConversionType.S2TWP, context);
                        if (converted != null && !converted.trim().isEmpty()) result = converted.trim();
                    } catch (Throwable error) {
                        postStatus("繁體轉換失敗，保留原文：" + readableMessage(error));
                    }
                }
                final String finalResult = result;
                mainHandler.post(() -> {
                    if (!closed && listener != null) listener.onTranslatedSubtitle(source, finalResult);
                });
            });
        } catch (RejectedExecutionException ignored) {
            if (!closed && listener != null) listener.onTranslatedSubtitle(source, source);
        }
    }

    private void ensureTranslationModelLoaded() {
        synchronized (lock) {
            if (closed || modelReady || modelLoading || translator == null) return;
            modelLoading = true;
        }
        postStatus("正在準備 " + languageLabel(sourceLanguage) + "→繁中本機翻譯模型…");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    synchronized (lock) {
                        if (closed) return;
                        modelReady = true;
                        modelLoading = false;
                    }
                    postStatus(languageLabel(sourceLanguage) + "→繁中本機翻譯已就緒");
                    drainQueue();
                })
                .addOnFailureListener(error -> {
                    final ArrayList<String> fallback;
                    synchronized (lock) {
                        if (closed) return;
                        modelReady = false;
                        modelLoading = false;
                        fallback = new ArrayList<>(pending);
                        pending.clear();
                    }
                    postStatus("翻譯模型下載失敗，已保留來源字幕：" + readableMessage(error));
                    for (String phrase : fallback) {
                        mainHandler.post(() -> {
                            if (!closed && listener != null) listener.onTranslatedSubtitle(phrase, phrase);
                        });
                    }
                });
    }

    private void drainQueue() {
        final String source;
        synchronized (lock) {
            if (closed || !modelReady || translating || pending.isEmpty() || translator == null) return;
            translating = true;
            source = pending.removeFirst();
            processingPhrase = source;
        }

        translator.translate(source)
                .addOnSuccessListener(translated -> {
                    if (closed) {
                        finishOne();
                        return;
                    }
                    try {
                        conversionExecutor.execute(() -> {
                            if (closed) {
                                finishOne();
                                return;
                            }
                            String taiwanTraditional;
                            try {
                                taiwanTraditional = ChineseConverter.convert(
                                        translated, ConversionType.S2TWP, context);
                            } catch (Throwable error) {
                                postStatus("繁體轉換失敗，顯示原始翻譯：" + readableMessage(error));
                                taiwanTraditional = translated;
                            }
                            final String result = taiwanTraditional == null || taiwanTraditional.trim().isEmpty()
                                    ? translated : taiwanTraditional.trim();
                            mainHandler.post(() -> {
                                if (!closed && listener != null) listener.onTranslatedSubtitle(source, result);
                            });
                            finishOne();
                        });
                    } catch (RejectedExecutionException ignored) {
                        finishOne();
                    }
                })
                .addOnFailureListener(error -> {
                    postStatus("本機翻譯失敗，保留來源字幕：" + readableMessage(error));
                    finishOne();
                });
    }

    private void finishOne() {
        synchronized (lock) {
            if (lastQueuedPhrase.equals(processingPhrase)) lastQueuedPhrase = "";
            processingPhrase = "";
            translating = false;
        }
        drainQueue();
    }

    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pending.clear();
            modelReady = false;
            modelLoading = false;
        }
        if (translator != null) {
            try {
                translator.close();
            } catch (Throwable ignored) { }
        }
        conversionExecutor.shutdownNow();
    }

    private void postStatus(String message) {
        mainHandler.post(() -> {
            if (!closed && listener != null) listener.onStatus(message);
        });
    }

    private static String normalizeSourceLanguage(String value) {
        if ("zh".equals(value) || "ja".equals(value) || "ko".equals(value)) return value;
        return "en";
    }

    private static String normalizeSourceLanguageOrUnd(String value) {
        if (value == null || value.trim().isEmpty() || "und".equalsIgnoreCase(value.trim())) return "und";
        String code = value.trim().toLowerCase(Locale.ROOT);
        if ("zh".equals(code) || "ja".equals(code) || "ko".equals(code) || "en".equals(code)) return code;
        return "und";
    }

    private static String toMlKitLanguage(String language) {
        switch (language) {
            case "ja": return TranslateLanguage.JAPANESE;
            case "ko": return TranslateLanguage.KOREAN;
            default: return TranslateLanguage.ENGLISH;
        }
    }

    private static String languageLabel(String language) {
        switch (language) {
            case "zh": return "中文";
            case "ja": return "日文";
            case "ko": return "韓文";
            default: return "英文";
        }
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
