package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import androidx.media3.common.C;

import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Experimental on-device speech-to-text captions.
 *
 * Receives PCM copied by the in-app Android AudioPlaybackCapture reader.
 * This keeps speech recognition off the playback output path; MPV itself is unchanged.
 */
public final class AiSubtitleEngine {
    public interface Listener {
        void onStatus(String status);
        void onSubtitle(String text);
        default void onFinalSubtitle(String text) { }
    }

    private static final String ASSET_MODEL_DIR = "ai-subtitle-model";
    private static final int ASR_SAMPLE_RATE = 16000;
    private static final AtomicReference<AiSubtitleEngine> ACTIVE = new AtomicReference<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final float[] POISON = new float[0];

    private final Context context;
    private final Listener listener;
    private final ArrayBlockingQueue<float[]> audioQueue = new ArrayBlockingQueue<>(30);
    private final Object resampleLock = new Object();
    private volatile boolean running = true;
    private Thread worker;

    // State for a lightweight streaming linear resampler, accessed only under resampleLock.
    private int sourceSampleRate = -1;
    private long inputSampleIndex;
    private double nextOutputSamplePosition;
    private float previousInputSample;
    private boolean hasPreviousInputSample;

    private AiSubtitleEngine(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public static void start(Context context, Listener listener) {
        stop();
        AiSubtitleEngine engine = new AiSubtitleEngine(context, listener);
        ACTIVE.set(engine);
        engine.worker = new Thread(engine::runRecognizer, "r15-ai-subtitles");
        engine.worker.setDaemon(true);
        engine.worker.start();
    }

    public static void stop() {
        AiSubtitleEngine engine = ACTIVE.getAndSet(null);
        if (engine != null) {
            engine.running = false;
            if (!engine.audioQueue.offer(POISON)) engine.audioQueue.poll();
            engine.audioQueue.offer(POISON);
            Thread thread = engine.worker;
            if (thread != null) thread.interrupt();
        }
    }

    /** Called from the AudioRecord capture worker. Never runs inference here. */
    public static void offerPcm(ByteBuffer input, int sampleRate, int channelCount, int encoding) {
        AiSubtitleEngine engine = ACTIVE.get();
        if (engine == null || !engine.running || input == null || sampleRate <= 0 || channelCount <= 0) return;
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) return;
        engine.convertAndQueue(input, sampleRate, channelCount, encoding);
    }

    private void convertAndQueue(ByteBuffer source, int sampleRate, int channels, int encoding) {
        synchronized (resampleLock) {
            if (sampleRate != sourceSampleRate) {
                sourceSampleRate = sampleRate;
                inputSampleIndex = 0;
                nextOutputSamplePosition = 0;
                previousInputSample = 0;
                hasPreviousInputSample = false;
            }

            ByteBuffer input = source.asReadOnlyBuffer().order(ByteOrder.LITTLE_ENDIAN);
            int bytesPerSample = encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
            int bytesPerFrame = bytesPerSample * channels;
            int frameCount = input.remaining() / bytesPerFrame;
            if (frameCount <= 0) return;

            float[] output = new float[(int) Math.ceil(frameCount * ASR_SAMPLE_RATE / (double) sampleRate) + 4];
            int outputCount = 0;
            double step = sampleRate / (double) ASR_SAMPLE_RATE;

            for (int frame = 0; frame < frameCount; frame++) {
                float mixed = 0;
                for (int channel = 0; channel < channels; channel++) {
                    float sample = encoding == C.ENCODING_PCM_FLOAT
                            ? input.getFloat()
                            : input.getShort() / 32768.0f;
                    if (Float.isFinite(sample)) mixed += sample;
                }
                mixed /= channels;

                if (!hasPreviousInputSample) {
                    previousInputSample = mixed;
                    hasPreviousInputSample = true;
                    inputSampleIndex = 1;
                    nextOutputSamplePosition = step;
                    output[outputCount++] = mixed;
                    continue;
                }

                long currentIndex = inputSampleIndex;
                while (nextOutputSamplePosition <= currentIndex && outputCount < output.length) {
                    double fraction = nextOutputSamplePosition - (currentIndex - 1);
                    fraction = Math.max(0.0, Math.min(1.0, fraction));
                    output[outputCount++] = (float) (previousInputSample
                            + (mixed - previousInputSample) * fraction);
                    nextOutputSamplePosition += step;
                }
                previousInputSample = mixed;
                inputSampleIndex++;
            }

            if (outputCount == 0 || !running) return;
            float[] chunk = outputCount == output.length ? output : Arrays.copyOf(output, outputCount);
            if (!audioQueue.offer(chunk)) {
                audioQueue.poll();
                audioQueue.offer(chunk);
            }
        }
    }

    private void runRecognizer() {
        OnlineRecognizer recognizer = null;
        OnlineStream stream = null;
        try {
            postStatus("AI 字幕模型初始化中…");
            File modelDir = copyModelAssets();
            String encoder = new File(modelDir, "encoder-epoch-99-avg-1.int8.onnx").getAbsolutePath();
            String decoder = new File(modelDir, "decoder-epoch-99-avg-1.onnx").getAbsolutePath();
            String joiner = new File(modelDir, "joiner-epoch-99-avg-1.int8.onnx").getAbsolutePath();
            String tokens = new File(modelDir, "tokens.txt").getAbsolutePath();

            OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(encoder)
                    .setDecoder(decoder)
                    .setJoiner(joiner)
                    .build();
            OnlineModelConfig model = OnlineModelConfig.builder()
                    .setTransducer(transducer)
                    .setTokens(tokens)
                    .setNumThreads(Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors())))
                    .setDebug(false)
                    .setModelType("zipformer")
                    .build();
            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                    .setOnlineModelConfig(model)
                    .setDecodingMethod("greedy_search")
                    .build();

            recognizer = new OnlineRecognizer(config);
            stream = recognizer.createStream();
            postStatus("本機語音辨識已啟動；翻譯將在句段結束後執行");

            String lastText = "";
            while (running || !audioQueue.isEmpty()) {
                float[] samples;
                try {
                    samples = audioQueue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!running) break;
                    continue;
                }
                if (samples == null) continue;
                if (samples == POISON) break;
                if (samples.length == 0) continue;

                stream.acceptWaveform(samples, ASR_SAMPLE_RATE);
                while (recognizer.isReady(stream)) recognizer.decode(stream);
                OnlineRecognizerResult result = recognizer.getResult(stream);
                String text = result == null ? "" : result.getText();
                if (text != null && !text.trim().isEmpty() && !text.trim().equals(lastText)) {
                    lastText = text.trim();
                    postSubtitle(lastText);
                }
                if (recognizer.isEndpoint(stream)) {
                    if (!lastText.isEmpty()) postFinalSubtitle(lastText);
                    recognizer.reset(stream);
                    lastText = "";
                }
            }
        } catch (Throwable error) {
            postStatus("AI 字幕啟動失敗：" + readableMessage(error));
        } finally {
            try { if (stream != null) stream.release(); } catch (Throwable ignored) {}
            try { if (recognizer != null) recognizer.release(); } catch (Throwable ignored) {}
            if (!running) postStatus("AI 字幕已關閉");
        }
    }

    private File copyModelAssets() throws IOException {
        File modelDir = new File(context.getFilesDir(), ASSET_MODEL_DIR);
        if (!modelDir.exists() && !modelDir.mkdirs()) {
            throw new IOException("無法建立模型目錄");
        }
        AssetManager assets = context.getAssets();
        String[] names = assets.list(ASSET_MODEL_DIR);
        if (names == null || names.length == 0) throw new IOException("APK 未包含 AI 字幕模型");
        String[] required = {
                "encoder-epoch-99-avg-1.int8.onnx",
                "decoder-epoch-99-avg-1.onnx",
                "joiner-epoch-99-avg-1.int8.onnx",
                "tokens.txt"
        };
        for (String name : required) {
            File target = new File(modelDir, name);
            if (target.isFile() && target.length() > 0) continue;
            try (InputStream in = assets.open(ASSET_MODEL_DIR + "/" + name);
                 FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int count;
                while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
        }
        for (String name : required) {
            File file = new File(modelDir, name);
            if (!file.isFile() || file.length() == 0) throw new IOException("AI 模型檔案不完整：" + name);
        }
        return modelDir;
    }

    private void postStatus(String message) {
        MAIN.post(() -> {
            if (ACTIVE.get() == this && listener != null) listener.onStatus(message);
        });
    }

    private void postSubtitle(String message) {
        MAIN.post(() -> {
            if (ACTIVE.get() == this && listener != null) listener.onSubtitle(message);
        });
    }

    private void postFinalSubtitle(String message) {
        MAIN.post(() -> {
            if (ACTIVE.get() == this && listener != null) listener.onFinalSubtitle(message);
        });
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
