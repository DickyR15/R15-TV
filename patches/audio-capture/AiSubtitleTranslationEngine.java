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

/**
 * Free on-device English-to-Chinese translation followed by OpenCC Taiwan conversion.
 * The ML Kit language model is downloaded once on first use; translation itself is local.
 */
public final class AiSubtitleTranslationEngine {
    public interface Listener {
        void onStatus(String status);
        void onTranslatedSubtitle(String source, String translation);
    }

    private static final int MAX_PENDING_PHRASES = 8;

    private final Context context;
    private final Listener listener;
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

    public AiSubtitleTranslationEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        translator = Translation.getClient(options);
    }

    /**
     * Defer ML Kit initialization until an English phrase actually needs translation.
     * This avoids loading an English-to-Chinese model for Mandarin/Japanese channels.
     */
    public void start() {
        postStatus("已啟用語言偵測；只有辨識到英文時才準備英翻中模型");
    }

    public void translateFinalPhrase(String text) {
        translateFinalPhrase(text, "en");
    }

    public void translateFinalPhrase(String text, String sourceLanguage) {
        if (closed || text == null) return;
        String phrase = text.trim();
        if (phrase.isEmpty()) return;
        String language = sourceLanguage == null ? "und" : sourceLanguage.trim().toLowerCase(Locale.ROOT);

        if ("zh".equals(language) || ("und".equals(language) && containsCjk(phrase))) {
            deliverOriginal(phrase, true, "已辨識中文；直接轉為臺灣繁體，不啟動英翻中模型");
            return;
        }
        if (!"en".equals(language)) {
            deliverOriginal(phrase, false, "已辨識 " + language
                    + "；目前僅支援英文→繁體中文翻譯，先顯示辨識原文");
            return;
        }

        synchronized (lock) {
            if (closed || phrase.equals(lastQueuedPhrase) || pending.contains(phrase)) return;
            lastQueuedPhrase = phrase;
            if (pending.size() >= MAX_PENDING_PHRASES) {
                pending.removeFirst();
                postStatus("翻譯略有積壓，已略過最舊句段以保持直播同步");
            }
            pending.addLast(phrase);
        }
        ensureEnglishModelLoaded();
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

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF));
    }

    private void ensureEnglishModelLoaded() {
        synchronized (lock) {
            if (closed || modelReady || modelLoading) return;
            modelLoading = true;
        }
        postStatus("偵測到英文，正在準備本機英翻中模型…");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    synchronized (lock) {
                        if (closed) return;
                        modelReady = true;
                        modelLoading = false;
                    }
                    postStatus("英文→繁中本機翻譯已就緒");
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
                    postStatus("英文翻譯模型下載失敗，已保留英文原文：" + readableMessage(error));
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
            if (closed || !modelReady || translating || pending.isEmpty()) return;
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
                                    ? translated
                                    : taiwanTraditional.trim();
                            mainHandler.post(() -> {
                                if (!closed && listener != null) {
                                    listener.onTranslatedSubtitle(source, result);
                                }
                            });
                            finishOne();
                        });
                    } catch (RejectedExecutionException ignored) {
                        finishOne();
                    }
                })
                .addOnFailureListener(error -> {
                    postStatus("本機翻譯失敗，保留原文字幕：" + readableMessage(error));
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
        try {
            translator.close();
        } catch (Throwable ignored) {
        }
        conversionExecutor.shutdownNow();
    }

    private void postStatus(String message) {
        mainHandler.post(() -> {
            if (!closed && listener != null) listener.onStatus(message);
        });
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
