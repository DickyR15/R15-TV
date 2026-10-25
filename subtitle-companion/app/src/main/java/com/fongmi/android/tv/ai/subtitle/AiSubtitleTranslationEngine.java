package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.mlkit.common.model.DownloadConditions;
import com.google.mlkit.nl.translate.TranslateLanguage;
import com.google.mlkit.nl.translate.Translation;
import com.google.mlkit.nl.translate.Translator;
import com.google.mlkit.nl.translate.TranslatorOptions;
import com.github.qichuan.androidopencc.lib.ChineseConverter;
import com.github.qichuan.androidopencc.lib.ConversionType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local ML Kit translation for supported languages; all Chinese output is normalized with OpenCC S2TWP. */
public final class AiSubtitleTranslationEngine implements AutoCloseable {
    public interface Listener {
        void onStatus(String status);
        void onTranslatedSubtitle(String source, String translation);
    }

    private static final int MAX_PENDING = 8;
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService converter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "r15-subtitle-opencc");
        t.setDaemon(true);
        return t;
    });
    private final Object lock = new Object();
    private final ArrayDeque<Phrase> pending = new ArrayDeque<>();
    private final Map<String, Translator> translators = new HashMap<>();
    private final Set<String> modelReady = new HashSet<>();
    private final Set<String> modelLoading = new HashSet<>();
    private boolean translating;
    private boolean closed;
    private String lastQueued = "";
    private String processing = "";

    public AiSubtitleTranslationEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        postStatus("本機翻譯已就緒；支援語言會自動下載對應翻譯模型");
    }

    public void translateFinalPhrase(String text) {
        translateFinalPhrase(text, "en");
    }

    public void translateFinalPhrase(String text, String detectedLanguage) {
        if (text == null || text.trim().isEmpty() || closed) return;
        String phrase = text.trim();
        String language = normalizeLanguage(detectedLanguage, phrase);

        if ("zh".equals(language)) {
            deliverOriginal(phrase, true, "已辨識中文，正在轉為臺灣繁體");
            return;
        }

        List<String> supported = TranslateLanguage.getAllLanguages();
        if (!supported.contains(language) || TranslateLanguage.CHINESE.equals(language)) {
            deliverOriginal(phrase, false, "此語言目前無法本機翻譯，先顯示辨識原文（" + language + "）");
            return;
        }

        synchronized (lock) {
            if (closed || phrase.equals(lastQueued)) return;
            for (Phrase item : pending) {
                if (item.text.equals(phrase) && item.language.equals(language)) return;
            }
            lastQueued = phrase;
            if (pending.size() >= MAX_PENDING) {
                Phrase dropped = pending.removeFirst();
                postStatus("翻譯佇列繁忙，略過較早句段以避免字幕延遲累積");
                deliverOriginal(dropped.text, false, "");
            }
            pending.addLast(new Phrase(phrase, language));
        }
        ensureModelLoaded(language);
        drainQueue();
    }

    private String normalizeLanguage(String detected, String text) {
        String lang = detected == null ? "und" : detected.trim().toLowerCase(Locale.ROOT);
        if (lang.contains("-")) lang = lang.substring(0, lang.indexOf('-'));
        if (lang.equals("english")) lang = "en";
        if (lang.equals("japanese")) lang = "ja";
        if (lang.equals("chinese")) lang = "zh";
        if (lang.equals("und") || lang.isEmpty()) {
            if (containsJapaneseKana(text)) return "ja";
            if (containsHan(text)) return "zh";
            if (looksLatin(text)) return "en";
        }
        return lang;
    }

    private void ensureModelLoaded(String language) {
        final Translator translator;
        synchronized (lock) {
            if (closed || modelReady.contains(language) || modelLoading.contains(language)) return;
            translator = translators.computeIfAbsent(language, this::newTranslator);
            modelLoading.add(language);
        }
        postStatus("首次使用 " + language.toUpperCase(Locale.ROOT) + " → 繁中：下載本機翻譯模型中…");
        translator.downloadModelIfNeeded(new DownloadConditions.Builder().build())
                .addOnSuccessListener(unused -> {
                    synchronized (lock) {
                        modelLoading.remove(language);
                        if (closed) return;
                        modelReady.add(language);
                    }
                    postStatus(language.toUpperCase(Locale.ROOT) + " → 繁中本機翻譯已就緒");
                    drainQueue();
                })
                .addOnFailureListener(error -> {
                    ArrayList<Phrase> fallback = new ArrayList<>();
                    synchronized (lock) {
                        modelLoading.remove(language);
                        modelReady.remove(language);
                        pending.removeIf(item -> {
                            if (item.language.equals(language)) {
                                fallback.add(item);
                                return true;
                            }
                            return false;
                        });
                    }
                    postStatus("翻譯模型下載失敗，保留辨識原文：" + readable(error));
                    for (Phrase item : fallback) deliverOriginal(item.text, false, "");
                    drainQueue();
                });
    }

    private Translator newTranslator(String language) {
        TranslatorOptions options = new TranslatorOptions.Builder()
                .setSourceLanguage(language)
                .setTargetLanguage(TranslateLanguage.CHINESE)
                .build();
        return Translation.getClient(options);
    }

    private void drainQueue() {
        final Phrase phrase;
        final Translator translator;
        synchronized (lock) {
            if (closed || translating || pending.isEmpty()) return;
            phrase = pending.peekFirst();
            if (!modelReady.contains(phrase.language)) return;
            pending.removeFirst();
            translating = true;
            processing = phrase.text;
            translator = translators.get(phrase.language);
        }
        if (translator == null) {
            deliverOriginal(phrase.text, false, "");
            finishOne();
            return;
        }
        translator.translate(phrase.text)
                .addOnSuccessListener(result -> {
                    if (closed) {
                        finishOne();
                        return;
                    }
                    try {
                        converter.execute(() -> {
                            String output = result;
                            try {
                                String converted = ChineseConverter.convert(
                                        result, ConversionType.S2TWP, context);
                                if (converted != null && !converted.trim().isEmpty()) output = converted.trim();
                            } catch (Throwable error) {
                                postStatus("臺灣繁體轉換失敗，顯示翻譯原文：" + readable(error));
                            }
                            String translated = output;
                            main.post(() -> {
                                if (!closed && listener != null) {
                                    listener.onTranslatedSubtitle(phrase.text, translated);
                                }
                            });
                            finishOne();
                        });
                    } catch (Throwable error) {
                        postStatus("字幕轉換工作已停止：" + readable(error));
                        finishOne();
                    }
                })
                .addOnFailureListener(error -> {
                    postStatus("本機翻譯失敗，保留原文：" + readable(error));
                    deliverOriginal(phrase.text, false, "");
                    finishOne();
                });
    }

    private void finishOne() {
        synchronized (lock) {
            if (lastQueued.equals(processing)) lastQueued = "";
            processing = "";
            translating = false;
        }
        drainQueue();
    }

    private void deliverOriginal(String text, boolean convert, String status) {
        if (status != null && !status.isEmpty()) postStatus(status);
        try {
            converter.execute(() -> {
                String output = text;
                if (convert) {
                    try {
                        String converted = ChineseConverter.convert(text, ConversionType.S2TWP, context);
                        if (converted != null && !converted.trim().isEmpty()) output = converted.trim();
                    } catch (Throwable error) {
                        postStatus("臺灣繁體轉換失敗，保留原文：" + readable(error));
                    }
                }
                String translated = output;
                main.post(() -> {
                    if (!closed && listener != null) listener.onTranslatedSubtitle(text, translated);
                });
            });
        } catch (Throwable ignored) {
            main.post(() -> {
                if (!closed && listener != null) listener.onTranslatedSubtitle(text, text);
            });
        }
    }

    private void postStatus(String status) {
        main.post(() -> {
            if (!closed && listener != null) listener.onStatus(status);
        });
    }

    private static boolean containsJapaneseKana(String text) {
        return text.codePoints().anyMatch(c -> (c >= 0x3040 && c <= 0x30ff));
    }

    private static boolean containsHan(String text) {
        return text.codePoints().anyMatch(c -> (c >= 0x3400 && c <= 0x9fff));
    }

    private static boolean looksLatin(String text) {
        long letters = text.codePoints().filter(Character::isLetter).count();
        long latin = text.codePoints().filter(c -> (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')).count();
        return letters > 0 && latin >= letters * 0.6;
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            pending.clear();
        }
        for (Translator translator : translators.values()) {
            try { translator.close(); } catch (Throwable ignored) { }
        }
        translators.clear();
        converter.shutdownNow();
    }

    private static final class Phrase {
        final String text;
        final String language;
        Phrase(String text, String language) {
            this.text = text;
            this.language = language;
        }
    }
}
