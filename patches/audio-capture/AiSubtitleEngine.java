package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import androidx.media3.common.C;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
        default void onFinalSubtitle(String text, String language) {
            onFinalSubtitle(text);
        }
    }

    private static final int ASR_SAMPLE_RATE = 16000;
    // Shorter windows and hop intervals cap CPU time for live captioning on TV boxes.
    private static final int WINDOW_SAMPLES = ASR_SAMPLE_RATE * 2;
    private static final int INITIAL_WINDOW_SAMPLES = ASR_SAMPLE_RATE * 3 / 2;
    // Whisper Tiny on low-power TV SoCs needs a little breathing room between passes.
    private static final int UPDATE_SAMPLES = ASR_SAMPLE_RATE * 5 / 4;
    private static final int MAX_WINDOW_SAMPLES = ASR_SAMPLE_RATE * 4;
    private static final AtomicReference<AiSubtitleEngine> ACTIVE = new AtomicReference<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final float[] POISON = new float[0];

    private final File modelFile;
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

    private AiSubtitleEngine(File modelFile, Listener listener) {
        this.modelFile = modelFile;
        this.listener = listener;
    }

    public static void start(Context context, File modelFile, Listener listener) {
        stop();
        AiSubtitleEngine engine = new AiSubtitleEngine(modelFile, listener);
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
        long whisperContext = 0L;
        try {
            // Let video rendering and audio playback take priority on low-core TV boxes.
            // Keep inference at normal priority; Android's BACKGROUND priority made CPU-only
            // Whisper inference appear stalled on low-power TV boxes.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            if (modelFile == null || !modelFile.isFile()) {
                throw new IllegalStateException("請先下載 Whisper Tiny Q5_1 多語模型");
            }
            postStatus("正在載入 Whisper Tiny Q5_1 多語模型…");
            whisperContext = WhisperCpp.createContext(modelFile);
            postStatus("Whisper Tiny Q5_1 已啟動；多語自動辨識中");

            float[] ring = new float[MAX_WINDOW_SAMPLES];
            int writeIndex = 0;
            int bufferedSamples = 0;
            int samplesSinceInference = 0;
            String previousTranscript = "";
            String previousLanguage = "";
            int inferencesSinceAutoDetect = 0;

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

                for (float sample : samples) {
                    ring[writeIndex] = sample;
                    writeIndex = (writeIndex + 1) % ring.length;
                    if (bufferedSamples < ring.length) bufferedSamples++;
                }
                samplesSinceInference += samples.length;

                int minimumWindow = previousTranscript.isEmpty()
                        ? INITIAL_WINDOW_SAMPLES : WINDOW_SAMPLES;
                if (bufferedSamples < minimumWindow || samplesSinceInference < UPDATE_SAMPLES) {
                    continue;
                }
                samplesSinceInference = 0;

                float[] window = snapshotTail(ring, writeIndex, Math.min(bufferedSamples, WINDOW_SAMPLES));
                long inferenceStarted = SystemClock.elapsedRealtime();
                // Reserve CPU capacity for decoding and rendering on lower-power TV devices.
                int inferenceThreads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors()));
                postStatus("正在辨識直播語音…");
                AtomicBoolean inferenceActive = new AtomicBoolean(true);
                Runnable[] heartbeat = new Runnable[1];
                heartbeat[0] = () -> {
                    if (!running || !inferenceActive.get()) return;
                    long seconds = (SystemClock.elapsedRealtime() - inferenceStarted) / 1000L;
                    postStatus("Whisper 本機辨識仍在運算 · 已等待 " + seconds + " 秒");
                    MAIN.postDelayed(heartbeat[0], 5000L);
                };
                MAIN.postDelayed(heartbeat[0], 5000L);
                // Detect the language on the first pass, then reuse it for most passes to avoid
                // repeating Whisper language-detection work on every overlapping audio window.
                // Re-check every ninth inference so a changing broadcast can switch languages.
                boolean detectLanguage = previousLanguage.isEmpty() || "und".equals(previousLanguage)
                        || inferencesSinceAutoDetect >= 8;
                String inferenceLanguage = detectLanguage ? "auto" : previousLanguage;
                if (detectLanguage) inferencesSinceAutoDetect = 0;
                else inferencesSinceAutoDetect++;
                String result;
                try {
                    result = WhisperCpp.transcribe(whisperContext, window, inferenceLanguage, inferenceThreads);
                } finally {
                    inferenceActive.set(false);
                    MAIN.removeCallbacks(heartbeat[0]);
                }
                long inferenceMs = SystemClock.elapsedRealtime() - inferenceStarted;

                String detectedLanguage = "und";
                String transcript = result == null ? "" : result.trim();
                int separator = transcript.indexOf('\n');
                if (separator > 0 && separator <= 8) {
                    String language = transcript.substring(0, separator).trim();
                    if (language.matches("[a-z]{2,3}|und")) {
                        detectedLanguage = language;
                        transcript = transcript.substring(separator + 1).trim();
                    }
                }

                if (!transcript.isEmpty()) {
                    postSubtitle(transcript);
                    String newText = detectedLanguage.equals(previousLanguage)
                            ? extractNewText(previousTranscript, transcript) : transcript;
                    if (!newText.isEmpty()) postFinalSubtitle(newText, detectedLanguage);
                    previousTranscript = transcript;
                    previousLanguage = detectedLanguage;
                    postStatus("Whisper Tiny Q5_1 · 偵測 " + detectedLanguage + " · "
                            + (window.length / ASR_SAMPLE_RATE) + " 秒音訊 · 推論 "
                            + inferenceMs + " ms");
                } else {
                    // Even a quiet/unclear window can yield a usable language ID. Keep it so
                    // every subsequent window does not repeat expensive auto-detection.
                    if (!"und".equals(detectedLanguage)) previousLanguage = detectedLanguage;
                    postStatus("已收到音訊，但尚未辨識到清楚語音；持續聆聽…");
                }
            }
        } catch (Throwable error) {
            running = false;
            postStatus("Whisper Tiny Q5_1 啟動失敗：" + readableMessage(error));
        } finally {
            try {
                WhisperCpp.freeContext(whisperContext);
            } catch (Throwable ignored) {
            }
            if (!running) postStatus("AI 字幕已關閉");
        }
    }

    private static float[] snapshotTail(float[] ring, int writeIndex, int count) {
        int length = Math.min(count, ring.length);
        int start = writeIndex - length;
        if (start < 0) start += ring.length;
        float[] result = new float[length];
        int firstLength = Math.min(length, ring.length - start);
        System.arraycopy(ring, start, result, 0, firstLength);
        if (firstLength < length) {
            System.arraycopy(ring, 0, result, firstLength, length - firstLength);
        }
        return result;
    }

    /**
     * Each Whisper pass sees an overlapping rolling window. Remove words already
     * shown in the prior window before handing the stable tail to the translator.
     */
    private static String extractNewText(String previous, String current) {
        if (current == null || current.trim().isEmpty()) return "";
        if (previous == null || previous.trim().isEmpty()) return current.trim();

        // For Chinese/Japanese/Korean captions, compare Unicode letters/digits rather
        // than whitespace-delimited words, because CJK text usually has no spaces.
        if (containsCjk(previous) || containsCjk(current)) {
            String prevUnicode = normalizeUnicodeText(previous);
            String currentUnicode = normalizeUnicodeText(current);
            if (currentUnicode.isEmpty()) return "";
            if (prevUnicode.isEmpty()) return current.trim();
            if (prevUnicode.equals(currentUnicode) || prevUnicode.contains(currentUnicode)) return "";
            if (currentUnicode.startsWith(prevUnicode)) {
                return suffixAfterNormalizedCharacters(current, prevUnicode.codePointCount(0, prevUnicode.length()));
            }
            int maxOverlap = Math.min(80, Math.min(
                    prevUnicode.codePointCount(0, prevUnicode.length()),
                    currentUnicode.codePointCount(0, currentUnicode.length())));
            for (int overlap = maxOverlap; overlap >= 5; overlap--) {
                int prevCount = prevUnicode.codePointCount(0, prevUnicode.length());
                int start = prevUnicode.offsetByCodePoints(0, prevCount - overlap);
                String left = prevUnicode.substring(start);
                int currentEnd = currentUnicode.offsetByCodePoints(0, overlap);
                if (left.equals(currentUnicode.substring(0, currentEnd))) {
                    return suffixAfterNormalizedCharacters(current, overlap);
                }
            }
            return current.trim();
        }

        String prevNorm = normalizeText(previous);
        String currentNorm = normalizeText(current);
        if (prevNorm.isEmpty()) return current.trim();
        if (prevNorm.equals(currentNorm) || prevNorm.contains(currentNorm)) return "";

        String[] prevWords = previous.trim().split("\\s+");
        String[] currentWords = current.trim().split("\\s+");
        int maxOverlap = Math.min(12, Math.min(prevWords.length, currentWords.length));
        for (int overlap = maxOverlap; overlap >= 2; overlap--) {
            boolean matches = true;
            for (int i = 0; i < overlap; i++) {
                String left = normalizeToken(prevWords[prevWords.length - overlap + i]);
                String right = normalizeToken(currentWords[i]);
                if (left.isEmpty() || !left.equals(right)) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                StringBuilder tail = new StringBuilder();
                for (int i = overlap; i < currentWords.length; i++) {
                    if (tail.length() > 0) tail.append(' ');
                    tail.append(currentWords[i]);
                }
                return tail.toString().trim();
            }
        }

        if (currentNorm.contains(prevNorm)) {
            int rawPos = current.toLowerCase(Locale.ROOT).indexOf(previous.toLowerCase(Locale.ROOT));
            if (rawPos >= 0) {
                int end = rawPos + previous.length();
                if (end < current.length()) return current.substring(end).trim();
                return "";
            }
        }
        return current.trim();
    }

    private static boolean containsCjk(String text) {
        return text != null && text.codePoints().anyMatch(codePoint ->
                (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7AF));
    }

    private static String normalizeUnicodeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String suffixAfterNormalizedCharacters(String current, int count) {
        if (current == null || count <= 0) return current == null ? "" : current.trim();
        int index = 0;
        int seen = 0;
        while (index < current.length() && seen < count) {
            int codePoint = current.codePointAt(index);
            index += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) seen++;
        }
        while (index < current.length()) {
            int codePoint = current.codePointAt(index);
            if (Character.isLetterOrDigit(codePoint)) break;
            index += Character.charCount(codePoint);
        }
        return index < current.length() ? current.substring(index).trim() : "";
    }

    private static String normalizeText(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9']+", " ").trim();
    }

    private static String normalizeToken(String token) {
        return token == null ? "" : token.toLowerCase(Locale.ROOT)
                .replaceAll("^[^a-z0-9']+|[^a-z0-9']+$", "");
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

    private void postFinalSubtitle(String message, String language) {
        MAIN.post(() -> {
            if (ACTIVE.get() == this && listener != null) {
                listener.onFinalSubtitle(message, language);
            }
        });
    }

    private static String readableMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }
}
