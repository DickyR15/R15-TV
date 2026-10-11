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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
    private boolean translating;
    private String lastQueuedPhrase = "";

    public AiSubtitleTranslationEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        translator = Translation.getClient(options);
    }

    /** Begins the one-time on-device model download. No API key or paid cloud service is used. */
    public void start() {
        postStatus("準備本機翻譯模型（首次使用需要網路）");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    synchronized (lock) {
                        if (closed) return;
                        modelReady = true;
                    }
                    postStatus("本機翻譯已就緒；繁體中文轉換已啟用");
                    drainQueue();
                })
                .addOnFailureListener(error -> {
                    synchronized (lock) {
                        if (closed) return;
                        modelReady = false;
                        pending.clear();
                    }
                    postStatus("翻譯模型下載失敗；仍可顯示英文辨識字幕：" + readableMessage(error));
                });
    }

    /** Enqueue a finalized ASR phrase; duplicate phrases are ignored. */
    public void translateFinalPhrase(String text) {
        if (closed || text == null) return;
        String phrase = text.trim();
        if (phrase.isEmpty()) return;

        synchronized (lock) {
            if (closed || phrase.equals(lastQueuedPhrase) || pending.contains(phrase)) return;
            lastQueuedPhrase = phrase;
            if (pending.size() >= MAX_PENDING_PHRASES) {
                pending.removeFirst();
                postStatus("翻譯略有積壓，已略過最舊句段以保持直播同步");
            }
            pending.addLast(phrase);
        }
        drainQueue();
    }

    private void drainQueue() {
        final String source;
        synchronized (lock) {
            if (closed || !modelReady || translating || pending.isEmpty()) return;
            translating = true;
            source = pending.removeFirst();
        }

        translator.translate(source)
                .addOnSuccessListener(translated -> conversionExecutor.execute(() -> {
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
                }))
                .addOnFailureListener(error -> {
                    postStatus("本機翻譯失敗，保留原文字幕：" + readableMessage(error));
                    finishOne();
                });
    }

    private void finishOne() {
        synchronized (lock) {
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
