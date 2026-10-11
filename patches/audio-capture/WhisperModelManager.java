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

/** Downloads the official multilingual Whisper Base weights into app-private storage. */
public final class WhisperModelManager {
    public static final String MODEL_NAME = "Whisper Base 多語模型";
    public static final String MODEL_FILENAME = "ggml-base.bin";
    public static final String MODEL_URL =
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin";
    private static final long MIN_MODEL_BYTES = 100L * 1024L * 1024L;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Listener {
        void onProgress(long downloadedBytes, long totalBytes);
        void onDownloaded(File modelFile);
        void onDeleted();
        void onError(String message);
    }

    private final File modelFile;
    private final File partialFile;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "r15-whisper-model-download");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean downloading;

    public WhisperModelManager(Context context) {
        File directory = new File(context.getApplicationContext().getFilesDir(), "whisper-models");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("無法建立 Whisper 模型目錄");
        }
        modelFile = new File(directory, MODEL_FILENAME);
        partialFile = new File(directory, MODEL_FILENAME + ".part");
    }

    public File getModelFile() {
        return modelFile;
    }

    public boolean isDownloaded() {
        return modelFile.isFile() && modelFile.length() >= MIN_MODEL_BYTES;
    }

    public boolean isDownloading() {
        return downloading;
    }

    public synchronized void download(Listener listener) {
        if (downloading) return;
        downloading = true;
        executor.execute(() -> downloadInternal(listener));
    }

    public synchronized void delete(Listener listener) {
        if (downloading) {
            postError(listener, "模型下載中，請先等候下載完成");
            return;
        }
        executor.execute(() -> {
            try {
                if (partialFile.exists() && !partialFile.delete()) {
                    throw new IOException("無法清除未完成的模型檔");
                }
                if (modelFile.exists() && !modelFile.delete()) {
                    throw new IOException("無法刪除 Whisper Base 模型");
                }
                MAIN.post(() -> {
                    if (listener != null) listener.onDeleted();
                });
            } catch (Throwable error) {
                postError(listener, readableMessage(error));
            }
        });
    }

    private void downloadInternal(Listener listener) {
        HttpURLConnection connection = null;
        try {
            if (partialFile.exists() && !partialFile.delete()) {
                throw new IOException("無法清除上次未完成的下載檔");
            }

            connection = (HttpURLConnection) new URL(MODEL_URL).openConnection();
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

            if (downloadedBytes < MIN_MODEL_BYTES) {
                throw new IOException("下載的檔案太小，可能不是完整的 Whisper Base 模型");
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

            if (!isDownloaded()) throw new IOException("模型檔案驗證失敗");
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
                ? error.getClass().getSimpleName()
                : message;
    }
}
