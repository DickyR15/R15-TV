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
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * On-device multilingual ML Kit translation to Chinese, then OpenCC Taiwan conversion.
 * Uses one translator/model at a time to keep memory use suitable for Android TV devices.
 */
public final class AiSubtitleTranslationEngine {
    public interface Listener {
        void onStatus(String status);
        void onTranslatedSubtitle(String source, String translation);
    }

    private static final int MAX_PENDING_PHRASES = 8;

    private static final class Phrase {
        final String text;
        final String language;
        final String key;

        Phrase(String text, String language) {
            this.text = text;
            this.language = language;
            this.key = language + "||" + text;
        }
    }

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService conversionExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "r15-subtitle-translation");
                thread.setDaemon(true);
                return thread;
            });
    private final ArrayDeque<Phrase> pending = new ArrayDeque<>();
    private final Object lock = new Object();

    private Translator translator;
    private String translatorSourceLanguage = "";
    private volatile boolean closed;
    private boolean modelReady;
    private boolean modelLoading;
    private boolean translating;
    private String lastQueuedKey = "";
    private String processingKey = "";

    public AiSubtitleTranslationEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        postStatus("已啟用多語本機翻譯；辨識到英文、日文、韓文等支援語言時會翻成繁體中文");
    }

    public void translateFinalPhrase(String text) {
        translateFinalPhrase(text, "en");
    }

    public void translateFinalPhrase(String text, String sourceLanguage) {
        if (closed || text == null) return;
        String phrase = text.trim();
        if (phrase.isEmpty()) return;
        String language = normalizeLanguage(sourceLanguage);

        // Chinese speech is already the target language: only normalize to Taiwan Traditional.
        if ("zh".equals(language) || ("und".equals(language) && containsCjk(phrase))) {
            deliverOriginal(phrase, true,
                    "已辨識中文；直接轉為臺灣繁體，不啟動翻譯模型");
            return;
        }

        String supported = TranslateLanguage.fromLanguageTag(language);
        if (supported == null || TranslateLanguage.CHINESE.equals(supported)) {
            deliverOriginal(phrase, false,
                    "來源語言 " + language + " 暫無可用的本機翻譯模型，先顯示辨識原文");
            return;
        }

        Phrase item = new Phrase(phrase, supported);
        synchronized (lock) {
            if (closed || item.key.equals(lastQueuedKey) || item.key.equals(processingKey)) return;
            for (Phrase queued : pending) {
                if (item.key.equals(queued.key)) return;
            }
            if (pending.size() >= MAX_PENDING_PHRASES) {
                pending.removeFirst();
                postStatus("翻譯略有積壓，已略過最舊句段以保持直播同步");
            }
            pending.addLast(item);
            lastQueuedKey = item.key;
        }
        drainQueue();
    }

    private void drainQueue() {
        final Phrase item;
        synchronized (lock) {
            if (closed || translating || pending.isEmpty()) return;
            translating = true;
            item = pending.removeFirst();
            processingKey = item.key;
        }

        Translator activeTranslator = getTranslator(item.language);
        if (activeTranslator == null) {
            deliverOriginalAndFinish(item, false,
                    "無法建立 " + item.language + " 翻譯器，先顯示辨識原文");
            return;
        }

        boolean ready;
        synchronized (lock) {
            ready = modelReady && translator == activeTranslator
                    && item.language.equals(translatorSourceLanguage);
            if (!ready && !modelLoading) modelLoading = true;
            else if (!ready && modelLoading) {
                // Only one phrase is processed at a time; this is defensive against teardown races.
                modelLoading = true;
            }
        }

        if (ready) {
            translate(item, activeTranslator);
            return;
        }

        postStatus("正在準備 " + item.language + " → 繁體中文本機翻譯模型…");
        activeTranslator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    synchronized (lock) {
                        if (closed || translator != activeTranslator
                                || !item.language.equals(translatorSourceLanguage)) return;
                        modelReady = true;
                        modelLoading = false;
                    }
                    postStatus(item.language + " → 繁中本機翻譯已就緒");
                    translate(item, activeTranslator);
                })
                .addOnFailureListener(error -> {
                    synchronized (lock) {
                        if (translator == activeTranslator) {
                            modelReady = false;
                            modelLoading = false;
                        }
                    }
                    deliverOriginalAndFinish(item, false,
                            item.language + " 翻譯模型下載失敗，保留原文：" + readableMessage(error));
                });
    }

    private Translator getTranslator(String sourceLanguage) {
        Translator old = null;
        Translator result;
        synchronized (lock) {
            if (closed) return null;
            if (translator != null && sourceLanguage.equals(translatorSourceLanguage)) {
                return translator;
            }
            old = translator;
            TranslatorOptions options = new TranslatorOptions.Builder()
                    .setSourceLanguage(sourceLanguage)
                    .setTargetLanguage(TranslateLanguage.CHINESE)
                    .build();
            result = Translation.getClient(options);
            translator = result;
            translatorSourceLanguage = sourceLanguage;
            modelReady = false;
            modelLoading = false;
        }
        if (old != null) {
            try { old.close(); } catch (Throwable ignored) { }
        }
        return result;
    }

    private void translate(Phrase item, Translator activeTranslator) {
        if (closed) {
            finishOne(item.key);
            return;
        }
        activeTranslator.translate(item.text)
                .addOnSuccessListener(translated -> {
                    try {
                        conversionExecutor.execute(() -> {
                            if (closed) {
                                finishOne(item.key);
                                return;
                            }
                            String traditional = translated;
                            try {
                                String converted = ChineseConverter.convert(
                                        translated, ConversionType.S2TWP, context);
                                if (converted != null && !converted.trim().isEmpty()) {
                                    traditional = converted.trim();
                                }
                            } catch (Throwable error) {
                                postStatus("繁體轉換失敗，保留翻譯結果：" + readableMessage(error));
                            }
                            final String output = traditional;
                            mainHandler.post(() -> {
                                if (!closed && listener != null) {
                                    listener.onTranslatedSubtitle(item.text, output);
                                }
                                finishOne(item.key);
                            });
                        });
                    } catch (RejectedExecutionException ignored) {
                        deliverOriginalAndFinish(item, false, "翻譯工作已關閉，保留原文");
                    }
                })
                .addOnFailureListener(error ->
                        deliverOriginalAndFinish(item, false,
                                "本機翻譯失敗，保留原文：" + readableMessage(error)));
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

    private void deliverOriginalAndFinish(Phrase item, boolean convertTraditional, String statusMessage) {
        postStatus(statusMessage);
        try {
            conversionExecutor.execute(() -> {
                String result = item.text;
                if (convertTraditional) {
                    try {
                        String converted = ChineseConverter.convert(item.text, ConversionType.S2TWP, context);
                        if (converted != null && !converted.trim().isEmpty()) result = converted.trim();
                    } catch (Throwable error) {
                        postStatus("繁體轉換失敗，保留原文：" + readableMessage(error));
                    }
                }
                final String finalResult = result;
                mainHandler.post(() -> {
                    if (!closed && listener != null) listener.onTranslatedSubtitle(item.text, finalResult);
                    finishOne(item.key);
                });
            });
        } catch (RejectedExecutionException ignored) {
            mainHandler.post(() -> {
                if (!closed && listener != null) listener.onTranslatedSubtitle(item.text, item.text);
                finishOne(item.key);
            });
        }
    }

    private void finishOne(String key) {
        synchronized (lock) {
            if (processingKey.equals(key)) processingKey = "";
            if (lastQueuedKey.equals(key) && !containsPendingKey(key)) lastQueuedKey = "";
            translating = false;
        }
        drainQueue();
    }

    private boolean containsPendingKey(String key) {
        for (Phrase phrase : pending) if (key.equals(phrase.key)) return true;
        return false;
    }

    private static String normalizeLanguage(String language) {
        if (language == null || language.trim().isEmpty()) return "und";
        String value = language.trim().toLowerCase(Locale.ROOT);
        int separator = value.indexOf('-');
        if (separator > 0) value = value.substring(0, separator);
        return value;
    }

    private static boolean containsCjk(String text) {
        return text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF));
    }

    public void close() {
        Translator current;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pending.clear();
            modelReady = false;
            modelLoading = false;
            translating = false;
            processingKey = "";
            current = translator;
            translator = null;
        }
        if (current != null) {
            try { current.close(); } catch (Throwable ignored) { }
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
