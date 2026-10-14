package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Online/self-hosted translation adapters for R15 TV.
 *
 * All provider output is normalized through the official OpenCC S2TWP dictionary
 * before the callback. No provider is allowed to choose Simplified output for UI.
 */
public final class AiSubtitleTranslationEngine implements AutoCloseable {
    public interface Listener {
        void onStatus(String message);
        void onTranslatedSubtitle(String source, String translation);
    }

    private static final int MAX_PENDING_PHRASES = 8;
    private static final int CONNECT_TIMEOUT_MS = 12000;
    private static final int READ_TIMEOUT_MS = 18000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private final Context context;
    private final String sourceLanguage;
    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String region;
    private final String model;
    private final Listener listener;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "r15-tv-caption-translation");
        t.setDaemon(true);
        return t;
    });
    private final ArrayDeque<String> pending = new ArrayDeque<>();
    private final Object lock = new Object();
    private volatile boolean closed;
    private boolean translating;
    private String lastPhrase = "";

    public AiSubtitleTranslationEngine(
            Context context, String sourceLanguage, Listener listener) {
        this(context, sourceLanguage,
                com.fongmi.android.tv.ui.activity.AiSubtitleSettingsActivity.getTranslationProvider(context),
                com.fongmi.android.tv.ui.activity.AiSubtitleSettingsActivity.getTranslationEndpoint(context),
                com.fongmi.android.tv.ui.activity.AiSubtitleSettingsActivity.getTranslationApiKey(context),
                com.fongmi.android.tv.ui.activity.AiSubtitleSettingsActivity.getTranslationRegion(context),
                com.fongmi.android.tv.ui.activity.AiSubtitleSettingsActivity.getTranslationModel(context),
                listener);
    }

    public AiSubtitleTranslationEngine(
            Context context, String sourceLanguage, String provider,
            String baseUrl, String apiKey, String region, String model, Listener listener) {
        this.context = context.getApplicationContext();
        this.sourceLanguage = normalizeLanguage(sourceLanguage);
        this.provider = normalizeProvider(provider);
        this.baseUrl = safe(baseUrl);
        this.apiKey = safe(apiKey);
        this.region = safe(region);
        this.model = safe(model);
        this.listener = listener;
    }

    public void start() {
        if ("source_only".equals(provider)) {
            postStatus("已設定為僅顯示辨識原文；翻譯服務未啟用。");
        } else if ("zh".equals(sourceLanguage) || "yue".equals(sourceLanguage)) {
            postStatus("中文辨識將使用 OpenCC 臺灣繁體轉換；不需另行翻譯。");
        } else {
            postStatus("字幕翻譯目標：臺灣繁體中文（zh-TW）；譯文顯示前會套用 OpenCC S2TWP。");
        }
    }

    public void translateFinalPhrase(String text) {
        translateFinalPhrase(text, sourceLanguage);
    }

    public void translateFinalPhrase(String text, String sourceLanguageFromAsr) {
        if (closed || text == null) return;
        String phrase = text.trim();
        if (phrase.isEmpty()) return;

        String lang = normalizeLanguage(sourceLanguageFromAsr);
        if ("source_only".equals(provider)) return;
        if ("zh".equals(lang) || "yue".equals(lang)) {
            executor.execute(() -> deliverTraditional(phrase));
            return;
        }
        synchronized (lock) {
            if (closed || phrase.equals(lastPhrase) || pending.contains(phrase)) return;
            lastPhrase = phrase;
            if (pending.size() >= MAX_PENDING_PHRASES) {
                pending.removeFirst();
                postStatus("翻譯佇列已滿，已略過最舊句段以跟上直播。");
            }
            pending.addLast(phrase);
        }
        drainQueue();
    }

    private void drainQueue() {
        final String source;
        synchronized (lock) {
            if (closed || translating || pending.isEmpty()) return;
            translating = true;
            source = pending.removeFirst();
        }
        executor.execute(() -> {
            try {
                postStatus("正在翻譯字幕（目標：臺灣繁體中文）…");
                String translated = translate(source, sourceLanguage, provider, baseUrl, apiKey, region, model);
                if (translated == null || translated.trim().isEmpty()) {
                    throw new IOException("翻譯服務沒有回傳文字");
                }
                String traditional = toTaiwanTraditional(translated);
                if (traditional.isEmpty()) throw new IOException("OpenCC 沒有產生有效譯文");
                MAIN.post(() -> {
                    if (!closed && listener != null) listener.onTranslatedSubtitle(source, traditional);
                });
                postStatus("翻譯完成 · 已轉為臺灣繁體中文");
            } catch (Throwable error) {
                postStatus("字幕翻譯失敗：" + readableMessage(error) + "。不會以簡體中文作為替代譯文。");
            } finally {
                synchronized (lock) { translating = false; }
                drainQueue();
            }
        });
    }

    /** Use the same provider and OpenCC pipeline for the settings-page translation self-test. */
    public static void translateOnce(
            Context context, String text, String sourceLanguage, String provider,
            String baseUrl, String apiKey, String region, String model, Listener listener) {
        ExecutorService oneShot = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "r15-tv-translation-self-test");
            t.setDaemon(true);
            return t;
        });
        oneShot.execute(() -> {
            try {
                String translated;
                String lang = normalizeLanguage(sourceLanguage);
                if ("source_only".equals(normalizeProvider(provider))) {
                    translated = text;
                } else if ("zh".equals(lang) || "yue".equals(lang)) {
                    translated = text;
                } else {
                    translated = translate(text, lang, normalizeProvider(provider),
                            safe(baseUrl), safe(apiKey), safe(region), safe(model));
                }
                String traditional = toTaiwanTraditionalStatic(context, translated);
                MAIN.post(() -> {
                    if (listener != null) listener.onTranslatedSubtitle(text, traditional);
                });
            } catch (Throwable error) {
                MAIN.post(() -> {
                    if (listener != null) listener.onStatus("翻譯測試失敗：" + readableMessage(error));
                });
            } finally {
                oneShot.shutdown();
            }
        });
    }

    private void deliverTraditional(String source) {
        try {
            String result = toTaiwanTraditional(source);
            MAIN.post(() -> {
                if (!closed && listener != null) listener.onTranslatedSubtitle(source, result);
            });
            postStatus("中文辨識文字已轉為臺灣繁體用字。");
        } catch (Throwable error) {
            postStatus("臺灣繁體轉換失敗：" + readableMessage(error));
        }
    }

    private String toTaiwanTraditional(String text) throws IOException {
        return toTaiwanTraditionalStatic(context, text);
    }

    private static String toTaiwanTraditionalStatic(Context context, String text) throws IOException {
        if (text == null || text.trim().isEmpty()) return "";
        try {
            String result = TaiwanTraditionalConverter.getInstance(context).convert(text);
            return result == null ? "" : result.trim();
        } catch (Throwable error) {
            throw new IOException("OpenCC S2TWP 轉換失敗：" + readableMessage(error), error);
        }
    }

    private static String translate(
            String text, String sourceLanguage, String provider,
            String baseUrl, String apiKey, String region, String model) throws Exception {
        switch (normalizeProvider(provider)) {
            case "google":
                return translateGoogle(text, sourceLanguage);
            case "bing":
                return translateBing(text, sourceLanguage, baseUrl, apiKey, region);
            case "deepl":
                return translateDeepL(text, sourceLanguage, baseUrl, apiKey);
            case "mtran":
                return translateMTran(text, sourceLanguage, baseUrl, apiKey);
            case "openai":
                return translateOpenAiCompatible(text, sourceLanguage, baseUrl, apiKey, model);
            case "source_only":
                return text;
            default:
                throw new IOException("不支援的翻譯服務");
        }
    }

    private static String translateGoogle(String text, String sourceLanguage) throws Exception {
        String source = "yue".equals(sourceLanguage) ? "yue" : sourceLanguage;
        String url = "https://translate.googleapis.com/translate_a/single?client=gtx"
                + "&sl=" + encode(source) + "&tl=zh-TW&dt=t&q=" + encode(text);
        JSONArray root = new JSONArray(readResponse("GET", url, null, null));
        JSONArray chunks = root.optJSONArray(0);
        if (chunks == null) throw new IOException("Google 翻譯回應格式不正確");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunks.length(); i++) {
            JSONArray chunk = chunks.optJSONArray(i);
            if (chunk != null) out.append(chunk.optString(0, ""));
        }
        if (out.toString().trim().isEmpty()) throw new IOException("Google 翻譯沒有回傳譯文");
        return out.toString();
    }

    private static String translateBing(
            String text, String sourceLanguage, String baseUrl, String apiKey, String region) throws Exception {
        require(apiKey, "Bing 翻譯需要 API 金鑰");
        String host = normalizeBaseUrl(baseUrl, "https://api.cognitive.microsofttranslator.com");
        String target = "zh-Hant";
        String url = host + "/translate?api-version=3.0&from=" + encode(sourceLanguage) + "&to=" + target;
        JSONArray body = new JSONArray().put(new JSONObject().put("Text", text));
        JSONObject item = new JSONArray(readResponse("POST", url, body.toString(), headers(
                "Ocp-Apim-Subscription-Key", apiKey,
                "Ocp-Apim-Subscription-Region", region))).optJSONObject(0);
        JSONArray translations = item == null ? null : item.optJSONArray("translations");
        if (translations == null || translations.length() == 0) throw new IOException("Bing 翻譯回應格式不正確");
        return translations.optJSONObject(0).optString("text", "");
    }

    private static String translateDeepL(
            String text, String sourceLanguage, String baseUrl, String apiKey) throws Exception {
        require(apiKey, "DeepL 翻譯需要 API 金鑰");
        String host = normalizeBaseUrl(baseUrl, "https://api-free.deepl.com");
        String source = deepLSourceCode(sourceLanguage);
        String body = "text=" + encode(text) + "&target_lang=ZH";
        if (!source.isEmpty()) body += "&source_lang=" + encode(source);
        JSONObject root = new JSONObject(readResponse("POST", host + "/v2/translate", body,
                headers("Authorization", "DeepL-Auth-Key " + apiKey,
                        "Content-Type", "application/x-www-form-urlencoded")));
        JSONArray translations = root.optJSONArray("translations");
        if (translations == null || translations.length() == 0) throw new IOException("DeepL 翻譯回應格式不正確");
        return translations.optJSONObject(0).optString("text", "");
    }

    private static String translateMTran(
            String text, String sourceLanguage, String baseUrl, String apiKey) throws Exception {
        require(baseUrl, "MTranServer 需要伺服器網址");
        String host = normalizeBaseUrl(baseUrl, "");
        JSONObject body = new JSONObject()
                .put("from", sourceLanguage)
                .put("to", "zh-Hans")
                .put("text", text)
                .put("html", false);
        String[] auth = apiKey.isEmpty() ? new String[0] : new String[]{"Authorization", "Bearer " + apiKey};
        JSONObject root = new JSONObject(readResponse("POST", host + "/translate", body.toString(), headers(
                append(new String[]{"Content-Type", "application/json"}, auth))));
        String result = root.optString("result", "");
        if (result.isEmpty()) throw new IOException("MTranServer 回應格式不正確");
        return result;
    }

    private static String translateOpenAiCompatible(
            String text, String sourceLanguage, String baseUrl, String apiKey, String model) throws Exception {
        require(baseUrl, "大型語言模型需要 API 端點網址");
        require(apiKey, "大型語言模型需要 API 金鑰");
        require(model, "請填入模型名稱");
        String host = normalizeBaseUrl(baseUrl, "https://api.openai.com/v1");
        String url = host.endsWith("/v1") ? host + "/chat/completions" : host + "/v1/chat/completions";
        JSONObject body = new JSONObject()
                .put("model", model)
                .put("temperature", 0.1)
                .put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content",
                                "Translate the supplied subtitle into Taiwan Traditional Chinese (zh-TW), using Taiwan wording. "
                                + "Return only the translated subtitle. Do not output Simplified Chinese or explanations."))
                        .put(new JSONObject().put("role", "user").put("content",
                                "Source language: " + sourceLanguage + "\nSubtitle: " + text)));
        JSONObject root = new JSONObject(readResponse("POST", url, body.toString(),
                headers("Authorization", "Bearer " + apiKey, "Content-Type", "application/json")));
        JSONArray choices = root.optJSONArray("choices");
        JSONObject choice = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        String result = message == null ? "" : message.optString("content", "");
        if (result.trim().isEmpty()) throw new IOException("大型語言模型回應格式不正確");
        return result.trim();
    }

    private static String readResponse(String method, String address, String body, JSONObject headers) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", "R15-TV-AI-Subtitle");
            connection.setRequestProperty("Accept", "application/json");
            if (headers != null) {
                JSONArray names = headers.names();
                if (names != null) for (int i = 0; i < names.length(); i++) {
                    String name = names.getString(i);
                    connection.setRequestProperty(name, headers.getString(name));
                }
            }
            if (body != null) {
                connection.setDoOutput(true);
                if (headers == null || !headers.has("Content-Type")) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                }
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                    output.flush();
                }
            }
            int status = connection.getResponseCode();
            InputStream input = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = readUtf8(input);
            if (status < 200 || status >= 300) {
                String shortMessage = response.length() > 400 ? response.substring(0, 400) : response;
                throw new IOException("HTTP " + status + "：" + shortMessage);
            }
            return response;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readUtf8(InputStream in) throws IOException {
        if (in == null) return "";
        try (InputStream input = new BufferedInputStream(in);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static JSONObject headers(String... args) {
        JSONObject out = new JSONObject();
        for (int i = 0; i + 1 < args.length; i += 2) {
            try { out.put(args[i], args[i + 1]); } catch (Exception ignored) { }
        }
        return out;
    }

    private static String[] append(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        if (b.length > 0) System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static void require(String value, String message) throws IOException {
        if (value == null || value.trim().isEmpty()) throw new IOException(message);
    }

    private static String normalizeBaseUrl(String value, String fallback) {
        String result = safe(value).trim();
        if (result.isEmpty()) result = fallback;
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private static String deepLSourceCode(String lang) throws IOException {
        switch (lang) {
            case "en": return "EN";
            case "de": return "DE";
            case "fr": return "FR";
            case "es": return "ES";
            case "ja": return "JA";
            case "ko": return "KO";
            case "zh": return "ZH";
            default: throw new IOException("DeepL 不支援此來源語言，請改用 Google 翻譯或大型語言模型。");
        }
    }

    private static String normalizeProvider(String value) {
        if (value == null) return "google";
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "source_only": case "bing": case "deepl": case "google": case "mtran": case "openai":
                return value.trim().toLowerCase(Locale.ROOT);
            default: return "google";
        }
    }

    private static String normalizeLanguage(String value) {
        if (value == null) return "en";
        switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "yue": case "de": case "en": case "fr": case "es": case "ja": case "zh": case "ko":
                return value.trim().toLowerCase(Locale.ROOT);
            default: return "en";
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private void postStatus(String message) {
        MAIN.post(() -> { if (!closed && listener != null) listener.onStatus(message); });
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        closed = true;
        synchronized (lock) { pending.clear(); }
        executor.shutdownNow();
    }
}
