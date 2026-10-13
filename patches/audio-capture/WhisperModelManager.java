package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Downloads on-device Whisper weights into app-private storage. */
public final class WhisperModelManager {
    public static final String MODEL_BASE = "base";
    public static final String MODEL_TINY_Q5_1 = "tiny_q5_1";
    public static final String MODEL_NAME = "Whisper Base 多語模型";
    public static final String MODEL_FILENAME = "ggml-base.bin";
    public static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";

    private static volatile WhisperModelManager INSTANCE;
    private static final long BASE_MIN_MODEL_BYTES = 100L * 1024L * 1024L;
    private static final long TINY_MIN_MODEL_BYTES = 20L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onProgress(long downloadedBytes, long totalBytes);
        void onDownloaded(File modelFile);
        void onDeleted();
        void onError(String message);
    }

    private final File directory;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "r15-whisper-model-download");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean downloading;
    private volatile String downloadingModelId = "";

    public static synchronized WhisperModelManager getInstance(Context context) {
        if (INSTANCE == null) INSTANCE = new WhisperModelManager(context.getApplicationContext());
        return INSTANCE;
    }

    public WhisperModelManager(Context context) {
        directory = new File(context.getApplicationContext().getFilesDir(), "whisper-models");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("無法建立 Whisper 模型目錄");
        }
    }

    public static String normalizeModelId(String modelId) {
        return MODEL_TINY_Q5_1.equals(modelId) ? MODEL_TINY_Q5_1 : MODEL_BASE;
    }

    public static String getModelName(String modelId) {
        return MODEL_TINY_Q5_1.equals(normalizeModelId(modelId))
                ? "Whisper Tiny Q5_1 模型" : "Whisper Base 多語模型";
    }

    public static String getModelFilename(String modelId) {
        return MODEL_TINY_Q5_1.equals(normalizeModelId(modelId))
                ? "ggml-tiny-q5_1.bin" : MODEL_FILENAME;
    }

    public static String getModelUrl(String modelId) {
        return MODEL_TINY_Q5_1.equals(normalizeModelId(modelId))
                ? "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny-q5_1.bin"
                : MODEL_URL;
    }

    public static int getEstimatedSizeMb(String modelId) {
        return MODEL_TINY_Q5_1.equals(normalizeModelId(modelId)) ? 31 : 148;
    }

    private static long minimumBytes(String modelId) {
        return MODEL_TINY_Q5_1.equals(normalizeModelId(modelId))
                ? TINY_MIN_MODEL_BYTES : BASE_MIN_MODEL_BYTES;
    }

    public File getModelFile() {
        return getModelFile(MODEL_BASE);
    }

    public File getModelFile(String modelId) {
        return new File(directory, getModelFilename(modelId));
    }

    private File getPartialFile(String modelId) {
        return new File(directory, getModelFilename(modelId) + ".part");
    }

    public boolean isDownloaded() {
        return isDownloaded(MODEL_BASE);
    }

    public boolean isDownloaded(String modelId) {
        File file = getModelFile(modelId);
        return file.isFile() && file.length() >= minimumBytes(modelId);
    }

    public boolean isDownloading() {
        return downloading;
    }

    public boolean isDownloading(String modelId) {
        return downloading && normalizeModelId(modelId).equals(downloadingModelId);
    }

    public synchronized void download(Listener listener) {
        download(MODEL_BASE, listener);
    }

    public synchronized void download(String modelId, Listener listener) {
        if (downloading) return;
        final String selected = normalizeModelId(modelId);
        downloading = true;
        downloadingModelId = selected;
        executor.execute(() -> downloadInternal(selected, listener));
    }

    public synchronized void delete(Listener listener) {
        delete(MODEL_BASE, listener);
    }

    public synchronized void delete(String modelId, Listener listener) {
        if (downloading) {
            postError(listener, "模型下載中，請先等候下載完成");
            return;
        }
        final String selected = normalizeModelId(modelId);
        executor.execute(() -> {
            File modelFile = getModelFile(selected);
            File partialFile = getPartialFile(selected);
            try {
                if (partialFile.exists() && !partialFile.delete()) {
                    throw new IOException("無法清除未完成的模型檔");
                }
                if (modelFile.exists() && !modelFile.delete()) {
                    throw new IOException("無法刪除 " + getModelName(selected));
                }
                MAIN.post(() -> {
                    if (listener != null) listener.onDeleted();
                });
            } catch (Throwable error) {
                postError(listener, readableMessage(error));
            }
        });
    }

    private void downloadInternal(String modelId, Listener listener) {
        HttpURLConnection connection = null;
        File modelFile = getModelFile(modelId);
        File partialFile = getPartialFile(modelId);
        try {
            if (partialFile.exists() && !partialFile.delete()) {
                throw new IOException("無法清除上次未完成的下載檔");
            }

            connection = (HttpURLConnection) new URL(getModelUrl(modelId)).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(60000);
            connection.setRequestProperty("User-Agent", "R15-TV-Whisper-Model-Downloader");
            connection.connect();

            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) {
                throw new IOException("模型伺服器回應 HTTP " + response);
            }

            long totalBytes = connection.getContentLengthLong();
            long downloadedBytes = 0;
            byte[] buffer = new byte[BUFFER_SIZE];
            long lastProgress = 0;

            try (InputStream input = connection.getInputStream();
                 FileOutputStream output = new FileOutputStream(partialFile)) {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    downloadedBytes += count;
                    if (downloadedBytes - lastProgress >= 512 * 1024L) {
                        postProgress(listener, downloadedBytes, totalBytes);
                        lastProgress = downloadedBytes;
                    }
                }
                output.flush();
                output.getFD().sync();
            }

            if (downloadedBytes < minimumBytes(modelId)) {
                throw new IOException("下載的 " + getModelName(modelId) + " 檔案太小，可能不完整");
            }
            if (totalBytes > 0 && downloadedBytes != totalBytes) {
                throw new IOException("模型下載大小不符，請重新下載");
            }

            try {
                Files.move(partialFile.toPath(), modelFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicMoveError) {
                if (modelFile.exists() && !modelFile.delete()) {
                    throw new IOException("無法替換舊的 Whisper 模型", atomicMoveError);
                }
                if (!partialFile.renameTo(modelFile)) {
                    throw new IOException("無法完成 Whisper 模型檔案安裝", atomicMoveError);
                }
            }

            if (!isDownloaded(modelId)) throw new IOException("模型檔案驗證失敗");
            postProgress(listener, downloadedBytes, downloadedBytes);
            MAIN.post(() -> {
                if (listener != null) listener.onDownloaded(modelFile);
            });
        } catch (Throwable error) {
            if (partialFile.exists()) partialFile.delete();
            postError(listener, readableMessage(error));
        } finally {
            if (connection != null) connection.disconnect();
            downloading = false;
            downloadingModelId = "";
        }
    }

    private static void postProgress(Listener listener, long downloaded, long total) {
        MAIN.post(() -> {
            if (listener != null) listener.onProgress(downloaded, total);
        });
    }

    private static void postError(Listener listener, String message) {
        MAIN.post(() -> {
            if (listener != null) listener.onError(message);
        });
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
