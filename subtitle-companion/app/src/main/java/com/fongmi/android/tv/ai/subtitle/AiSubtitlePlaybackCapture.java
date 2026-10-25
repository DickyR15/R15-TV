package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Experimental in-app playback capture for the MPV/Media3 player.
 *
 * Captures only playback produced by this app's UID. The output is copied to the
 * ASR queue on a background thread; model inference is never executed here.
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public final class AiSubtitlePlaybackCapture {
    public interface Listener {
        void onStatus(String status);
        void onLevel(float dbfs);
    }

    private static final String TAG = "R15AudioCapture";
    private static final int SAMPLE_RATE = 48000;
    private static final int CHANNELS = 2;
    private static final int CHANNEL_MASK = AudioFormat.CHANNEL_IN_STEREO;

    private final AudioRecord audioRecord;
    private final MediaProjection projection;
    private final MediaProjection.Callback projectionCallback;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread worker;
    private volatile long lastLevelUpdateMs;

    public static AiSubtitlePlaybackCapture start(
            Context context, MediaProjection projection, int targetUid, Listener listener) throws Exception {
        return new AiSubtitlePlaybackCapture(context, projection, targetUid, listener);
    }

    private AiSubtitlePlaybackCapture(
            Context context, MediaProjection projection, int targetUid, Listener listener) throws Exception {
        if (projection == null) throw new IllegalArgumentException("MediaProjection 為空");
        this.projection = projection;
        this.listener = listener;

        int minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, CHANNEL_MASK, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IllegalStateException("裝置不支援 48 kHz 立體聲 PCM 擷取，錯誤碼：" + minBuffer);
        }

        AudioPlaybackCaptureConfiguration captureConfig =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUid(targetUid)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .build();

        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(CHANNEL_MASK)
                .build();

        int bufferBytes = Math.max(minBuffer * 2, SAMPLE_RATE * CHANNELS * 2 / 5);
        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferBytes)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build();

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            throw new IllegalStateException("Android 音訊擷取器初始化失敗");
        }

        projectionCallback = new MediaProjection.Callback() {
            @Override
            public void onStop() {
                stopInternal(false);
                postStatus("系統已停止音訊擷取");
            }
        };
        projection.registerCallback(projectionCallback, mainHandler);

        try {
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("音訊擷取未進入錄音狀態");
            }
            Log.i(TAG, "capture_started sampleRate=" + SAMPLE_RATE
                    + " channels=" + CHANNELS
                    + " bufferBytes=" + bufferBytes
                    + " targetUid=" + targetUid
                    + " audioSession=" + audioRecord.getAudioSessionId());
        } catch (Exception error) {
            Log.e(TAG, "capture_start_failed", error);
            projection.unregisterCallback(projectionCallback);
            audioRecord.release();
            throw error;
        } catch (Error error) {
            Log.e(TAG, "capture_start_error", error);
            projection.unregisterCallback(projectionCallback);
            audioRecord.release();
            throw error;
        }

        running.set(true);
        worker = new Thread(this::readLoop, "r15-playback-capture");
        worker.setDaemon(true);
        worker.start();
        postStatus("音訊擷取已啟動；正在檢查 MPV 音訊");
    }

    public void stop() {
        stopInternal(true);
    }

    private void stopInternal(boolean stopProjection) {
        if (!running.compareAndSet(true, false)) {
            if (stopProjection) safeStopProjection();
            return;
        }
        try {
            if (audioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                audioRecord.stop();
            }
        } catch (Throwable ignored) {
        }
        try {
            audioRecord.release();
        } catch (Throwable ignored) {
        }
        try {
            projection.unregisterCallback(projectionCallback);
        } catch (Throwable ignored) {
        }
        if (stopProjection) safeStopProjection();
        postStatus("音訊擷取已停止");
    }

    private void safeStopProjection() {
        try {
            projection.stop();
        } catch (Throwable ignored) {
        }
    }

    private void readLoop() {
        // Read roughly 100 ms per iteration. A bounded buffer prevents stale audio piling up.
        short[] samples = new short[SAMPLE_RATE / 10 * CHANNELS];
        byte[] pcmBytes = new byte[samples.length * 2];
        long totalReadSamples = 0L;
        long totalReadBlocks = 0L;
        long lastReadLogMs = SystemClock.uptimeMillis();
        Log.i(TAG, "read_loop_started bufferSamples=" + samples.length);

        while (running.get()) {
            int count;
            try {
                count = audioRecord.read(samples, 0, samples.length, AudioRecord.READ_BLOCKING);
            } catch (Throwable error) {
                Log.e(TAG, "audio_record_read_exception", error);
                postStatus("音訊讀取失敗：" + readableMessage(error));
                break;
            }
            if (count <= 0) {
                if (count == AudioRecord.ERROR_DEAD_OBJECT
                        || count == AudioRecord.ERROR_INVALID_OPERATION
                        || count == AudioRecord.ERROR_BAD_VALUE) {
                    Log.e(TAG, "audio_record_read_error code=" + count);
                    postStatus("音訊擷取讀取錯誤（" + count + "）");
                    break;
                }
                continue;
            }

            double sumSquares = 0.0;
            for (int i = 0; i < count; i++) {
                short sample = samples[i];
                sumSquares += (sample / 32768.0) * (sample / 32768.0);
                int value = sample;
                pcmBytes[i * 2] = (byte) (value & 0xff);
                pcmBytes[i * 2 + 1] = (byte) ((value >>> 8) & 0xff);
            }

            totalReadSamples += count;
            totalReadBlocks++;
            long now = SystemClock.uptimeMillis();
            double rms = Math.sqrt(sumSquares / Math.max(1, count));
            float dbfs = (float) (20.0 * Math.log10(Math.max(1e-6, rms)));
            if (now - lastLevelUpdateMs >= 2500) {
                lastLevelUpdateMs = now;
                if (listener != null) mainHandler.post(() -> listener.onLevel(dbfs));
            }
            if (now - lastReadLogMs >= 5000) {
                lastReadLogMs = now;
                Log.i(TAG, "pcm_read blocks=" + totalReadBlocks
                        + " samples=" + totalReadSamples
                        + " lastCount=" + count
                        + " dbfs=" + Math.round(dbfs));
            }

            try {
                ByteBuffer chunk = ByteBuffer.wrap(pcmBytes, 0, count * 2)
                        .order(ByteOrder.LITTLE_ENDIAN);
                AiSubtitleEngine.offerPcm(chunk, SAMPLE_RATE, CHANNELS, AudioFormat.ENCODING_PCM_16BIT);
            } catch (Throwable error) {
                Log.e(TAG, "offer_pcm_failed", error);
                postStatus("無法送入語音辨識：" + readableMessage(error));
            }
        }

        Log.w(TAG, "read_loop_stopped blocks=" + totalReadBlocks
                + " samples=" + totalReadSamples);
        stopInternal(true);
    }

    private void postStatus(String message) {
        if (listener != null) mainHandler.post(() -> listener.onStatus(message));
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
