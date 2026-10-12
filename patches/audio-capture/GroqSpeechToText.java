package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Groq cloud speech-to-text client for the isolated R15 TV POC-84 branch.
 *
 * API keys are stored in app-private SharedPreferences only for personal testing.
 * This is not a secure production secret store: a key embedded in a client app can
 * ultimately be extracted. Never hard-code or commit a key.
 */
public final class GroqSpeechToText {
    private static final String PREFS = "r15_groq_poc84";
    private static final String KEY_API = "api_key";
    private static final String ENDPOINT =
            "https://api.groq.com/openai/v1/audio/transcriptions";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 45000;
    private static final int MAX_AUDIO_SECONDS = 20;

    private GroqSpeechToText() { }

    public static void saveApiKey(Context context, String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_API, value).apply();
    }

    public static boolean hasApiKey(Context context) {
        return !getApiKey(context).isEmpty();
    }

    public static void clearApiKey(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().remove(KEY_API).apply();
    }

    private static String getApiKey(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_API, "").trim();
    }

    /**
     * Transcribes mono 16 kHz float PCM. Call only from a worker thread.
     * The returned value is plain recognized text; HTTP failures include status
     * codes but never echo the API key.
     */
    public static String transcribe(Context context, float[] pcm16k, String language)
            throws Exception {
        String apiKey = getApiKey(context);
        if (apiKey.isEmpty()) throw new IllegalStateException("尚未設定 Groq API Key");
        if (pcm16k == null || pcm16k.length == 0) {
            throw new IllegalArgumentException("沒有可上傳的音訊");
        }
        if (pcm16k.length > 16000 * MAX_AUDIO_SECONDS) {
            throw new IllegalArgumentException("音訊片段不可超過 " + MAX_AUDIO_SECONDS + " 秒");
        }

        byte[] wav = toWav(pcm16k, 16000);
        String boundary = "R15Groq" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(ENDPOINT).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            connection.setRequestProperty("Accept", "application/json");

            try (OutputStream raw = connection.getOutputStream();
                 DataOutputStream out = new DataOutputStream(raw)) {
                writeField(out, boundary, "model", "whisper-large-v3-turbo");
                if (language != null && !language.trim().isEmpty()
                        && !"auto".equalsIgnoreCase(language.trim())) {
                    writeField(out, boundary, "language", language.trim());
                }
                writeField(out, boundary, "response_format", "json");
                out.writeBytes("--" + boundary + "\r\n");
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"r15-audio.wav\"\r\n");
                out.writeBytes("Content-Type: audio/wav\r\n\r\n");
                out.write(wav);
                out.writeBytes("\r\n--" + boundary + "--\r\n");
                out.flush();
            }

            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = readAll(stream);
            if (status < 200 || status >= 300) {
                String hint = "Groq API HTTP " + status;
                try {
                    JSONObject json = new JSONObject(body);
                    JSONObject error = json.optJSONObject("error");
                    if (error != null && !error.optString("message", "").isEmpty()) {
                        hint += "：" + error.optString("message");
                    }
                } catch (Exception ignored) { }
                if (status == 401) hint = "Groq API Key 無效或已撤銷（HTTP 401）";
                if (status == 429) hint = "Groq 免費額度或請求頻率限制（HTTP 429）";
                throw new IllegalStateException(hint);
            }
            JSONObject json = new JSONObject(body);
            return json.optString("text", "").trim();
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void writeField(DataOutputStream out, String boundary,
                                   String name, String value) throws Exception {
        out.writeBytes("--" + boundary + "\r\n");
        out.writeBytes("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.writeBytes("\r\n");
    }

    private static byte[] toWav(float[] samples, int sampleRate) throws Exception {
        int dataSize = samples.length * 2;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(dataSize + 44);
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeBytes("RIFF");
        writeLeInt(out, 36 + dataSize);
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeLeInt(out, 16);
        writeLeShort(out, 1);
        writeLeShort(out, 1);
        writeLeInt(out, sampleRate);
        writeLeInt(out, sampleRate * 2);
        writeLeShort(out, 2);
        writeLeShort(out, 16);
        out.writeBytes("data");
        writeLeInt(out, dataSize);
        ByteBuffer pcm = ByteBuffer.allocate(dataSize).order(ByteOrder.LITTLE_ENDIAN);
        for (float sample : samples) {
            float clipped = Math.max(-1.0f, Math.min(1.0f, Float.isFinite(sample) ? sample : 0.0f));
            int value = Math.round(clipped * (clipped < 0 ? 32768.0f : 32767.0f));
            pcm.putShort((short) value);
        }
        out.write(pcm.array());
        out.flush();
        return bytes.toByteArray();
    }

    private static void writeLeInt(DataOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
        out.write((value >>> 16) & 0xff);
        out.write((value >>> 24) & 0xff);
    }

    private static void writeLeShort(DataOutputStream out, int value) throws Exception {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static String readAll(InputStream input) throws Exception {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            return out.toString("UTF-8");
        }
    }
}
