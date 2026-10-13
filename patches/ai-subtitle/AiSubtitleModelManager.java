package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Pinned, checksum-verified speech model packs based on AutoBuild 170. */
public final class AiSubtitleModelManager {
    public interface Listener {
        void onProgress(long downloadedBytes, long totalBytes);
        void onDownloaded(File directory);
        void onDeleted();
        void onError(String message);
    }

    private static final String MANIFEST = "ai-subtitle-models.json";
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile AiSubtitleModelManager INSTANCE;

    private final Context context;
    private final File root;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "r15-ai-model-manager");
        t.setDaemon(true);
        return t;
    });
    private final JSONObject manifest;
    private volatile String downloadingLanguage = "";

    public static synchronized AiSubtitleModelManager getInstance(Context context) {
        if (INSTANCE == null) INSTANCE = new AiSubtitleModelManager(context.getApplicationContext());
        return INSTANCE;
    }

    private AiSubtitleModelManager(Context context) {
        this.context = context;
        root = new File(context.getFilesDir(), "ai-subtitle-models");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("無法建立語音模型資料夾");
        try (InputStream input = context.getAssets().open(MANIFEST)) {
            byte[] bytes = new byte[8192];
            StringBuilder json = new StringBuilder();
            int n;
            while ((n = input.read(bytes)) >= 0) json.append(new String(bytes, 0, n, java.nio.charset.StandardCharsets.UTF_8));
            manifest = new JSONObject(json.toString());
        } catch (Exception e) {
            throw new IllegalStateException("讀取語音模型清單失敗：" + e.getMessage(), e);
        }
    }

    public String[] getLanguageCodes() {
        JSONArray languages = manifest.optJSONArray("languages");
        if (languages == null) return new String[0];
        String[] codes = new String[languages.length()];
        for (int i = 0; i < languages.length(); i++) codes[i] = languages.optJSONObject(i).optString("id");
        return codes;
    }

    public String getLanguageLabel(String id) {
        JSONObject pack = findPack(id);
        return pack == null ? id : pack.optString("label", id);
    }

    public String getModelId(String id) {
        JSONObject pack = findPack(id);
        return pack == null ? "" : pack.optString("modelId", "");
    }

    public String getEngineType(String id) {
        JSONObject pack = findPack(id);
        return pack == null ? "" : pack.optString("engine", "");
    }

    public int getMinimumRamMb(String id) {
        JSONObject pack = findPack(id);
        return pack == null ? 0 : pack.optInt("minimumRamMb", 0);
    }

    public File getModelDirectory(String id) {
        JSONObject pack = findPack(id);
        String modelId = pack == null ? "unknown" : pack.optString("modelId", "unknown");
        return new File(root, modelId);
    }

    public File getModelFile(String language, String filename) {
        return new File(getModelDirectory(language), filename);
    }

    public int getEstimatedSizeMb(String id) {
        JSONObject pack = findPack(id);
        if (pack == null) return 0;
        JSONArray files = pack.optJSONArray("files");
        long size = 0;
        if (files != null) for (int i = 0; i < files.length(); i++) size += files.optJSONObject(i).optLong("sizeBytes", 0);
        return (int) Math.max(1, (size + 1048575L) / 1048576L);
    }

    public boolean isDownloading() {
        return !downloadingLanguage.isEmpty();
    }

    public boolean isDownloading(String id) {
        return normalizeLanguage(id).equals(downloadingLanguage);
    }

    public boolean isDownloaded(String id) {
        JSONObject pack = findPack(id);
        if (pack == null) return false;
        JSONArray files = pack.optJSONArray("files");
        if (files == null || files.length() == 0) return false;
        File directory = getModelDirectory(id);
        for (int i = 0; i < files.length(); i++) {
            JSONObject entry = files.optJSONObject(i);
            File file = new File(directory, entry.optString("name"));
            if (!file.isFile() || file.length() != entry.optLong("sizeBytes", -1)) return false;
        }
        return true;
    }

    public synchronized void download(String id, Listener listener) {
        final String language = normalizeLanguage(id);
        if (isDownloading()) {
            postError(listener, "另一個語音模型正在下載，請稍候");
            return;
        }
        if (findPack(language) == null) {
            postError(listener, "不支援的辨識語言：" + language);
            return;
        }
        downloadingLanguage = language;
        executor.execute(() -> downloadInternal(language, listener));
    }

    public synchronized void delete(String id, Listener listener) {
        final String language = normalizeLanguage(id);
        if (isDownloading()) {
            postError(listener, "模型下載中，請完成後再刪除");
            return;
        }
        executor.execute(() -> {
            try {
                File dir = getModelDirectory(language);
                deleteRecursively(dir);
                MAIN.post(() -> { if (listener != null) listener.onDeleted(); });
            } catch (Throwable error) {
                postError(listener, readableMessage(error));
            }
        });
    }

    private void downloadInternal(String language, Listener listener) {
        HttpURLConnection connection = null;
        File directory = getModelDirectory(language);
        JSONObject pack = findPack(language);
        JSONArray files = pack == null ? null : pack.optJSONArray("files");
        long total = 0;
        long downloaded = 0;
        if (files != null) for (int i = 0; i < files.length(); i++) total += files.optJSONObject(i).optLong("sizeBytes", 0);
        try {
            if (!directory.exists() && !directory.mkdirs()) throw new IOException("無法建立 " + getLanguageLabel(language) + " 模型資料夾");
            for (int i = 0; files != null && i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                if (!isCurrentDownload(language)) throw new IOException("模型下載已取消");
                String name = entry.getString("name");
                long expectedSize = entry.getLong("sizeBytes");
                String expectedSha = entry.getString("sha256").toLowerCase(Locale.ROOT);
                File target = new File(directory, name);
                if (target.isFile() && target.length() == expectedSize && expectedSha.equals(sha256(target))) {
                    downloaded += expectedSize;
                    postProgress(listener, downloaded, total);
                    continue;
                }
                if (target.exists() && !target.delete()) throw new IOException("無法移除舊檔案：" + name);
                File partial = new File(directory, name + ".part");
                if (partial.exists() && !partial.delete()) throw new IOException("無法清除未完成下載：" + name);

                URL url = new URL(entry.getString("url"));
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(30000);
                connection.setReadTimeout(60000);
                connection.setRequestProperty("User-Agent", "R15-TV-AutoBuild170-Port");
                connection.connect();
                int status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) throw new IOException(name + " 下載失敗，HTTP " + status);

                long fileWritten = 0;
                byte[] buffer = new byte[BUFFER_SIZE];
                try (InputStream in = new BufferedInputStream(connection.getInputStream());
                     BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(partial))) {
                    int n;
                    while ((n = in.read(buffer)) != -1) {
                        if (!isCurrentDownload(language)) throw new IOException("模型下載已取消");
                        out.write(buffer, 0, n);
                        fileWritten += n;
                        postProgress(listener, downloaded + fileWritten, total);
                    }
                    out.flush();
                } finally {
                    connection.disconnect();
                    connection = null;
                }
                if (fileWritten != expectedSize) throw new IOException(name + " 檔案大小不符，請重新下載");
                if (!expectedSha.equals(sha256(partial))) throw new IOException(name + " SHA-256 驗證失敗，已拒絕安裝");
                if (!partial.renameTo(target)) throw new IOException("無法安裝模型檔案：" + name);
                downloaded += fileWritten;
                postProgress(listener, downloaded, total);
            }
            if (!isDownloaded(language)) throw new IOException("語音模型檔案未完整安裝");
            MAIN.post(() -> { if (listener != null) listener.onDownloaded(directory); });
        } catch (Throwable error) {
            if (files != null) for (int i = 0; i < files.length(); i++) {
                File partial = new File(directory, files.optJSONObject(i).optString("name") + ".part");
                if (partial.exists()) partial.delete();
            }
            postError(listener, readableMessage(error));
        } finally {
            if (connection != null) connection.disconnect();
            downloadingLanguage = "";
        }
    }


    /** Full content verification, called only from the ASR worker (can read hundreds of MB). */
    public void verifyPackIntegrity(String id) throws IOException {
        JSONObject pack = findPack(id);
        if (pack == null) throw new IOException("不支援的辨識語言：" + id);
        JSONArray files = pack.optJSONArray("files");
        if (files == null || files.length() == 0) throw new IOException("模型清單沒有檔案：" + id);
        try {
            File directory = getModelDirectory(id);
            for (int i = 0; i < files.length(); i++) {
                JSONObject entry = files.getJSONObject(i);
                File file = new File(directory, entry.getString("name"));
                if (!file.isFile() || file.length() != entry.getLong("sizeBytes")) {
                    throw new IOException(getLanguageLabel(id) + " 模型檔案大小錯誤：" + entry.getString("name"));
                }
                String expected = entry.getString("sha256").toLowerCase(Locale.ROOT);
                if (!expected.equals(sha256(file))) {
                    throw new IOException(getLanguageLabel(id) + " 模型 SHA-256 驗證失敗：" + entry.getString("name") + "；請刪除後重新下載");
                }
            }
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("無法驗證語音模型：" + readableMessage(error), error);
        }
    }

    private JSONObject findPack(String id) {
        JSONArray languages = manifest.optJSONArray("languages");
        if (languages == null) return null;
        String key = normalizeLanguage(id);
        for (int i = 0; i < languages.length(); i++) {
            JSONObject pack = languages.optJSONObject(i);
            if (pack != null && key.equals(pack.optString("id"))) return pack;
        }
        return null;
    }

    private static String normalizeLanguage(String id) {
        if (id == null) return "en";
        String key = id.trim().toLowerCase(Locale.ROOT);
        switch (key) {
            case "yue": case "de": case "en": case "fr": case "es": case "ja": case "zh": case "ko":
                return key;
            default: return "en";
        }
    }

    private boolean isCurrentDownload(String language) {
        return language.equals(downloadingLanguage);
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }

    private void postProgress(Listener listener, long downloaded, long total) {
        MAIN.post(() -> { if (listener != null) listener.onProgress(downloaded, total); });
    }

    private void postError(Listener listener, String message) {
        downloadingLanguage = "";
        MAIN.post(() -> { if (listener != null) listener.onError(message); });
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("無法刪除 " + file.getName());
    }

    private static String readableMessage(Throwable e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty() ? e.getClass().getSimpleName() : message;
    }
}
