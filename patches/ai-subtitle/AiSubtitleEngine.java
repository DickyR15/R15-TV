package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.media3.common.C;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizer;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerResult;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineStream;
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerResult;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * sherpa-onnx 1.13.8 multilingual ASR. AudioRecord only enqueues PCM; all
 * model loading and decoding happen on a dedicated worker, never on playback.
 */
public final class AiSubtitleEngine {
    public interface Listener {
        void onStatus(String status);
        void onSubtitle(String text);
        default void onFinalSubtitle(String text) { }
        default void onFinalSubtitle(String text, String language) { onFinalSubtitle(text); }
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int QUEUE_CAPACITY = 48;
    private static final int OFFLINE_WINDOW_SAMPLES = SAMPLE_RATE * 4;
    private static final int OFFLINE_HOP_SAMPLES = SAMPLE_RATE * 2;
    private static final long STALL_TIMEOUT_MS = 20000L;
    private static final AtomicReference<AiSubtitleEngine> ACTIVE = new AtomicReference<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final float[] POISON = new float[0];

    private final Context context;
    private final File modelDirectory;
    private final String language;
    private final String engineType;
    private final Listener listener;
    private final AiSubtitleModelManager modelManager;
    private final ArrayBlockingQueue<float[]> audioQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Object resampleLock = new Object();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "r15-asr-watchdog");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean stallReported = new AtomicBoolean(false);

    private volatile boolean running = true;
    private volatile long inferenceStartedAtMs;
    private volatile long lastAudioAtMs;
    private volatile long lastStatusAtMs;
    private volatile float inputRms;
    private volatile long droppedChunks;
    private Thread worker;

    // Linear resampler state is accessed only while holding resampleLock.
    private int sourceSampleRate = -1;
    private long inputSampleIndex;
    private double nextOutputSamplePosition;
    private float previousInputSample;
    private boolean hasPreviousInputSample;

    private AiSubtitleEngine(Context context, File modelDirectory, String language, Listener listener) {
        this.context = context.getApplicationContext();
        this.modelManager = AiSubtitleModelManager.getInstance(this.context);
        this.modelDirectory = modelDirectory;
        this.language = normalizeLanguage(language);
        this.engineType = modelManager.getEngineType(this.language);
        this.listener = listener;
    }

    public static void start(Context context, File modelDirectory, String language, Listener listener) {
        stop();
        AiSubtitleEngine engine = new AiSubtitleEngine(context, modelDirectory, language, listener);
        ACTIVE.set(engine);
        engine.startWorkers();
    }

    private void startWorkers() {
        worker = new Thread(this::runRecognizer, "r15-sherpa-asr-" + language);
        worker.setDaemon(true);
        worker.start();
        watchdog.scheduleWithFixedDelay(this::checkHealth, 2, 2, TimeUnit.SECONDS);
    }

    public static void stop() {
        AiSubtitleEngine engine = ACTIVE.getAndSet(null);
        if (engine == null) return;
        engine.running = false;
        if (!engine.audioQueue.offer(POISON)) engine.audioQueue.poll();
        engine.audioQueue.offer(POISON);
        Thread thread = engine.worker;
        if (thread != null) thread.interrupt();
        engine.watchdog.shutdownNow();
    }

    /** Called on the AudioRecord worker. It must never call into sherpa/JNI. */
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

            float[] output = new float[(int) Math.ceil(frameCount * SAMPLE_RATE / (double) sampleRate) + 4];
            int outputCount = 0;
            double step = sampleRate / (double) SAMPLE_RATE;
            double sumSquares = 0;
            long inputValues = (long) frameCount * channels;

            for (int frame = 0; frame < frameCount; frame++) {
                float mixed = 0;
                for (int channel = 0; channel < channels; channel++) {
                    float sample = encoding == C.ENCODING_PCM_FLOAT
                            ? input.getFloat() : input.getShort() / 32768.0f;
                    if (Float.isFinite(sample)) mixed += sample;
                }
                mixed /= channels;
                sumSquares += mixed * mixed;

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

            inputRms = (float) Math.sqrt(sumSquares / Math.max(1L, frameCount));
            if (outputCount == 0 || !running) return;
            float[] chunk = outputCount == output.length ? output : Arrays.copyOf(output, outputCount);
            if (!audioQueue.offer(chunk)) {
                audioQueue.poll();
                audioQueue.offer(chunk);
                droppedChunks++;
            }
            lastAudioAtMs = SystemClock.elapsedRealtime();
        }
    }

    private void runRecognizer() {
        OnlineRecognizer online = null;
        OnlineStream onlineStream = null;
        OfflineRecognizer offline = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            if (modelDirectory == null || !modelDirectory.isDirectory()) {
                throw new IllegalStateException("請先下載所選語音模型");
            }
            postStatus("正在檢查「" + languageLabel(language) + "」模型檔案與 SHA-256…");
            modelManager.verifyPackIntegrity(language);
            postStatus("正在載入「" + languageLabel(language) + "」模型（sherpa-onnx 1.13.8）…");
            long loadStarted = SystemClock.elapsedRealtime();

            if ("online-transducer".equals(engineType)) {
                OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                        .setEncoder(path("encoder.onnx"))
                        .setDecoder(path("decoder.onnx"))
                        .setJoiner(path("joiner.onnx"))
                        .build();
                OnlineModelConfig model = OnlineModelConfig.builder()
                        .setTransducer(transducer)
                        .setTokens(path("tokens.txt"))
                        .setNumThreads(inferenceThreads())
                        .setProvider("cpu")
                        .setDebug(false)
                        .build();
                OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                        .setFeatureConfig(FeatureConfig.builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setFeatureDim(80)
                                .setDither(0.0f)
                                .build())
                        .setOnlineModelConfig(model)
                        .setEnableEndpoint(true)
                        .setDecodingMethod("greedy_search")
                        .setMaxActivePaths(4)
                        .build();
                online = new OnlineRecognizer(config);
                onlineStream = online.createStream();
            } else {
                OfflineModelConfig.Builder model = OfflineModelConfig.builder()
                        .setTokens(path("tokens.txt"))
                        .setNumThreads(inferenceThreads())
                        .setProvider("cpu")
                        .setDebug(false);
                switch (engineType) {
                    case "offline-wenet-ctc":
                        model.setWenetCtc(OfflineWenetCtcModelConfig.builder()
                                .setModel(path("model.int8.onnx")).build());
                        break;
                    case "offline-zipformer-ctc":
                        model.setZipformerCtc(OfflineZipformerCtcModelConfig.builder()
                                .setModel(path("model.int8.onnx")).build());
                        break;
                    case "offline-moonshine":
                        model.setMoonshine(OfflineMoonshineModelConfig.builder()
                                .setEncoder(path("encoder_model.ort"))
                                .setMergedDecoder(path("decoder_model_merged.ort"))
                                .build());
                        break;
                    case "offline-sensevoice":
                        model.setSenseVoice(OfflineSenseVoiceModelConfig.builder()
                                .setModel(path("model.int8.onnx"))
                                .setLanguage(language)
                                .setInverseTextNormalization(true)
                                .build());
                        break;
                    default:
                        throw new IllegalStateException("不支援的語音引擎：" + engineType);
                }
                OfflineRecognizerConfig config = OfflineRecognizerConfig.builder()
                        .setFeatureConfig(FeatureConfig.builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setFeatureDim(80)
                                .setDither(0.0f)
                                .build())
                        .setOfflineModelConfig(model.build())
                        .setDecodingMethod("greedy_search")
                        .setMaxActivePaths(4)
                        .build();
                offline = new OfflineRecognizer(config);
            }

            postStatus("本機語音辨識已就緒 · " + languageLabel(language)
                    + " · 載入 " + (SystemClock.elapsedRealtime() - loadStarted) + " ms");
            postStatus("音訊量測啟動中 · 等待播放器 PCM 音訊…");

            if (online != null) runOnline(online, onlineStream);
            else runOffline(offline);
        } catch (Throwable error) {
            if (running) postStatus("語音辨識啟動失敗：" + readableMessage(error));
        } finally {
            inferenceStartedAtMs = 0;
            running = false;
            watchdog.shutdownNow();
            if (onlineStream != null) try { onlineStream.release(); } catch (Throwable ignored) { }
            if (online != null) try { online.release(); } catch (Throwable ignored) { }
            if (offline != null) try { offline.release(); } catch (Throwable ignored) { }
            ACTIVE.compareAndSet(this, null);
        }
    }

    private void runOnline(OnlineRecognizer recognizer, OnlineStream stream) throws InterruptedException {
        String lastPartial = "";
        String lastFinal = "";
        long lastHeartbeat = 0;
        while (running) {
            float[] samples = audioQueue.poll(250, TimeUnit.MILLISECONDS);
            long now = SystemClock.elapsedRealtime();
            if (samples == null) {
                if (now - lastHeartbeat >= 5000) {
                    postAudioHealth();
                    lastHeartbeat = now;
                }
                continue;
            }
            if (samples == POISON) break;
            if (samples.length == 0) continue;

            stream.acceptWaveform(samples, SAMPLE_RATE);
            while (running && recognizer.isReady(stream)) {
                inferenceStartedAtMs = SystemClock.elapsedRealtime();
                try {
                    recognizer.decode(stream);
                } finally {
                    inferenceStartedAtMs = 0;
                }
                stallReported.set(false);
            }

            OnlineRecognizerResult result = recognizer.getResult(stream);
            String text = clean(result == null ? "" : result.getText());
            if (!text.isEmpty() && !text.equals(lastPartial)) {
                lastPartial = text;
                postSubtitle(text);
            }
            if (recognizer.isEndpoint(stream)) {
                String finalText = clean(result == null ? "" : result.getText());
                if (!finalText.isEmpty() && !finalText.equals(lastFinal)) {
                    lastFinal = finalText;
                    postFinalSubtitle(finalText, language);
                }
                recognizer.reset(stream);
                lastPartial = "";
            }
            if (now - lastHeartbeat >= 5000) {
                postAudioHealth();
                lastHeartbeat = now;
            }
        }
    }

    private void runOffline(OfflineRecognizer recognizer) throws InterruptedException {
        float[] ring = new float[OFFLINE_WINDOW_SAMPLES];
        int writeIndex = 0;
        int bufferedSamples = 0;
        int samplesSinceInference = 0;
        String previousTranscript = "";
        long lastHeartbeat = 0;

        while (running) {
            float[] samples = audioQueue.poll(250, TimeUnit.MILLISECONDS);
            long now = SystemClock.elapsedRealtime();
            if (samples == null) {
                if (now - lastHeartbeat >= 5000) {
                    postAudioHealth();
                    lastHeartbeat = now;
                }
                continue;
            }
            if (samples == POISON) break;
            if (samples.length == 0) continue;

            for (float sample : samples) {
                ring[writeIndex] = sample;
                writeIndex = (writeIndex + 1) % ring.length;
                if (bufferedSamples < ring.length) bufferedSamples++;
            }
            samplesSinceInference += samples.length;
            if (bufferedSamples < OFFLINE_WINDOW_SAMPLES || samplesSinceInference < OFFLINE_HOP_SAMPLES) continue;
            samplesSinceInference = 0;

            float[] window = snapshotTail(ring, writeIndex, OFFLINE_WINDOW_SAMPLES);
            OfflineStream stream = recognizer.createStream();
            long start = SystemClock.elapsedRealtime();
            OfflineRecognizerResult result;
            inferenceStartedAtMs = start;
            try {
                stream.acceptWaveform(window, SAMPLE_RATE);
                recognizer.decode(stream);
                result = recognizer.getResult(stream);
            } finally {
                inferenceStartedAtMs = 0;
                stream.release();
            }
            stallReported.set(false);
            if (!running) break;

            String fullText = clean(result == null ? "" : result.getText());
            if (!fullText.isEmpty()) {
                String delta = removeOverlap(previousTranscript, fullText);
                previousTranscript = fullText;
                if (!delta.isEmpty()) {
                    postSubtitle(delta);
                    postFinalSubtitle(delta, language);
                }
            }
            long elapsed = SystemClock.elapsedRealtime() - start;
            postStatus("本機辨識 · " + languageLabel(language)
                    + " · " + elapsed + " ms · 音訊佇列 " + audioQueue.size()
                    + (droppedChunks > 0 ? " · 丟棄區塊 " + droppedChunks : ""));
            lastHeartbeat = SystemClock.elapsedRealtime();
        }
    }

    private void checkHealth() {
        if (!running) return;
        long started = inferenceStartedAtMs;
        long now = SystemClock.elapsedRealtime();
        if (started > 0 && now - started > STALL_TIMEOUT_MS && stallReported.compareAndSet(false, true)) {
            // Do not release a recognizer concurrently with an active native decode call.
            // Detach it from the PCM ingress and report a finite error state to the UI.
            running = false;
            ACTIVE.compareAndSet(this, null);
            postStatus("辨識單次運算超過 20 秒，已停止接收新音訊；請重新啟動字幕。影片播放不受影響。");
        } else if (lastAudioAtMs > 0 && now - lastAudioAtMs > 10000 && now - lastStatusAtMs > 5000) {
            postStatus("目前沒有收到新的 PCM 音訊；請確認直播聲音正在播放。");
        }
    }

    private void postAudioHealth() {
        double rms = Math.max(0.000001, inputRms);
        double dbfs = 20.0 * Math.log10(rms);
        String message;
        if (lastAudioAtMs == 0) {
            message = "等待播放器 PCM 音訊…";
        } else {
            long age = Math.max(0, SystemClock.elapsedRealtime() - lastAudioAtMs);
            message = String.format(Locale.ROOT,
                    "音訊量測 · %.1f dBFS · 佇列 %d 段 · 延遲 %d ms",
                    Math.max(-120.0, dbfs), audioQueue.size(), age);
            if (droppedChunks > 0) message += " · 丟棄 " + droppedChunks + " 段";
        }
        postStatus(message);
    }

    private String path(String filename) {
        return new File(modelDirectory, filename).getAbsolutePath();
    }

    private int inferenceThreads() {
        return Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
    }

    private void postStatus(String status) {
        lastStatusAtMs = SystemClock.elapsedRealtime();
        MAIN.post(() -> {
            if (listener != null) listener.onStatus(status);
        });
    }

    private void postSubtitle(String text) {
        MAIN.post(() -> {
            if (running && listener != null) listener.onSubtitle(text);
        });
    }

    private void postFinalSubtitle(String text, String sourceLanguage) {
        MAIN.post(() -> {
            if (listener != null) listener.onFinalSubtitle(text, sourceLanguage);
        });
    }

    private static float[] snapshotTail(float[] ring, int writeIndex, int count) {
        float[] result = new float[count];
        int start = (writeIndex - count + ring.length) % ring.length;
        int first = Math.min(count, ring.length - start);
        System.arraycopy(ring, start, result, 0, first);
        if (first < count) System.arraycopy(ring, 0, result, first, count - first);
        return result;
    }

    private static String removeOverlap(String previous, String current) {
        if (current.isEmpty()) return "";
        if (previous.isEmpty()) return current;
        if (current.equals(previous)) return "";
        if (current.startsWith(previous)) return current.substring(previous.length()).trim();
        if (previous.contains(current)) return "";
        int max = Math.min(previous.length(), current.length());
        for (int n = max; n > 0; n--) {
            if (previous.regionMatches(previous.length() - n, current, 0, n)) {
                return current.substring(n).trim();
            }
        }
        return current;
    }

    private static String clean(String text) {
        if (text == null) return "";
        return text.replace('\n', ' ').replace('\r', ' ').replaceAll("\\s+", " ").trim();
    }

    private static String normalizeLanguage(String language) {
        if (language == null) return "en";
        switch (language.trim().toLowerCase(Locale.ROOT)) {
            case "yue": case "de": case "en": case "fr": case "es": case "ja": case "zh": case "ko":
                return language.trim().toLowerCase(Locale.ROOT);
            default: return "en";
        }
    }

    private static String languageLabel(String language) {
        switch (language) {
            case "yue": return "粵語";
            case "de": return "德語";
            case "en": return "英語";
            case "fr": return "法語";
            case "es": return "西班牙語";
            case "ja": return "日語";
            case "zh": return "中文（普通話）";
            case "ko": return "韓語";
            default: return language;
        }
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
