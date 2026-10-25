package com.fongmi.android.tv.ai.subtitle;

import android.content.Context;
import android.media.AudioFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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

    private static final String TAG = "R15ASR";
    private static final AtomicLong NO_ACTIVE_OFFERS = new AtomicLong();
    private static final int ASR_SAMPLE_RATE = 16000;
    // Shorter windows and hop intervals cap CPU time for live captioning on TV boxes.
    private static final int WINDOW_SAMPLES = ASR_SAMPLE_RATE * 3;
    private static final int INITIAL_WINDOW_SAMPLES = ASR_SAMPLE_RATE * 2;
    private static final int UPDATE_SAMPLES = ASR_SAMPLE_RATE * 2;
    private static final int MAX_WINDOW_SAMPLES = ASR_SAMPLE_RATE * 4;
    // Cloud requests use a longer window with a slower hop to limit API calls and
    // preserve enough context for punctuation and sentence boundaries.
    private static final int CLOUD_WINDOW_SAMPLES = ASR_SAMPLE_RATE * 8;
    private static final int CLOUD_UPDATE_SAMPLES = ASR_SAMPLE_RATE * 5;
    private static final int CLOUD_RING_SAMPLES = ASR_SAMPLE_RATE * 10;
    private static final String SETTINGS_PREFS = "r15_ai_subtitle_settings";
    private static final String KEY_ASR_SERVICE = "asr_service";
    private static final int ASR_SERVICE_GROQ_CLOUD = 1;
    private static final AtomicReference<AiSubtitleEngine> ACTIVE = new AtomicReference<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final float[] POISON = new float[0];

    private final Context appContext;
    private final File modelFile;
    private final Listener listener;
    private final boolean cloudMode;
    private final ArrayBlockingQueue<float[]> audioQueue = new ArrayBlockingQueue<>(30);
    private final Object resampleLock = new Object();
    private volatile boolean running = true;
    private Thread worker;
    private volatile long queuedChunks;
    private volatile long queuedSamples;
    private volatile long droppedChunks;
    private volatile long consumedChunks;
    private volatile long consumedSamples;
    private long lastQueueStatsMs;

    // State for a lightweight streaming linear resampler, accessed only under resampleLock.
    private int sourceSampleRate = -1;
    private long inputSampleIndex;
    private double nextOutputSamplePosition;
    private float previousInputSample;
    private boolean hasPreviousInputSample;

    private AiSubtitleEngine(Context context, File modelFile, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.modelFile = modelFile;
        this.listener = listener;
        this.cloudMode = appContext.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_ASR_SERVICE, 0) == ASR_SERVICE_GROQ_CLOUD;
    }

    public static void start(Context context, File modelFile, Listener listener) {
        stop();
        AiSubtitleEngine engine = new AiSubtitleEngine(context, modelFile, listener);
        ACTIVE.set(engine);
        Log.i(TAG, "engine_start modelExists=" + (modelFile != null && modelFile.isFile())
                + " modelBytes=" + (modelFile != null && modelFile.isFile() ? modelFile.length() : -1)
                + " cloudMode=" + engine.cloudMode
                + " cpuCount=" + Runtime.getRuntime().availableProcessors());
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
        if (engine == null) {
            long count = NO_ACTIVE_OFFERS.incrementAndGet();
            if (count == 1 || count % 50 == 0) {
                Log.w(TAG, "pcm_received_without_active_engine count=" + count);
            }
            return;
        }
        if (!engine.running || input == null || sampleRate <= 0 || channelCount <= 0) {
            long count = NO_ACTIVE_OFFERS.incrementAndGet();
            if (count == 1 || count % 50 == 0) {
                Log.w(TAG, "pcm_offer_ignored count=" + count + " running=" + engine.running
                        + " inputNull=" + (input == null) + " sampleRate=" + sampleRate
                        + " channels=" + channelCount);
            }
            return;
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT && encoding != AudioFormat.ENCODING_PCM_FLOAT) {
            Log.e(TAG, "unsupported_pcm_encoding=" + encoding);
            return;
        }
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
            int bytesPerSample = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
            int bytesPerFrame = bytesPerSample * channels;
            int frameCount = input.remaining() / bytesPerFrame;
            if (frameCount <= 0) return;

            float[] output = new float[(int) Math.ceil(frameCount * ASR_SAMPLE_RATE / (double) sampleRate) + 4];
            int outputCount = 0;
            double step = sampleRate / (double) ASR_SAMPLE_RATE;

            for (int frame = 0; frame < frameCount; frame++) {
                float mixed = 0;
                for (int channel = 0; channel < channels; channel++) {
                    float sample = encoding == AudioFormat.ENCODING_PCM_FLOAT
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
                droppedChunks++;
                audioQueue.poll();
                if (!audioQueue.offer(chunk)) {
                    Log.e(TAG, "pcm_queue_full_drop_new_chunk samples=" + chunk.length);
                    return;
                }
            }
            queuedChunks++;
            queuedSamples += chunk.length;
            long now = SystemClock.elapsedRealtime();
            if (now - lastQueueStatsMs >= 5000L) {
                lastQueueStatsMs = now;
                Log.i(TAG, "pcm_queued chunks=" + queuedChunks
                        + " samples=" + queuedSamples
                        + " lastChunkSamples=" + chunk.length
                        + " lastChunkDbfs=" + rmsDbfs(chunk)
                        + " queueSize=" + audioQueue.size()
                        + " droppedChunks=" + droppedChunks
                        + " sourceRate=" + sampleRate
                        + " channels=" + channels);
            }
        }
    }

    private void runRecognizer() {
        long whisperContext = 0L;
        long lastFlowLogMs = SystemClock.elapsedRealtime();
        long queueTimeouts = 0L;
        try {
            Log.i(TAG, "worker_enter cloudMode=" + cloudMode
                    + " modelExists=" + (modelFile != null && modelFile.isFile())
                    + " modelBytes=" + (modelFile != null && modelFile.isFile() ? modelFile.length() : -1));
            // Keep video playback responsive, but do not demote CPU-only Whisper to
            // background priority: that made real-time inference appear stalled on TV boxes.
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT);
            if (cloudMode) {
                if (!GroqSpeechToText.hasApiKey(appContext)) {
                    throw new IllegalStateException("尚未設定 Groq API Key；請先到 AI 字幕設定儲存金鑰");
                }
                postStatus("Groq 雲端辨識已啟動；每段音訊會上傳至 Groq 進行辨識");
            } else {
                if (modelFile == null || !modelFile.isFile()) {
                    throw new IllegalStateException("請先下載 Whisper Base 多語模型");
                }
                postStatus("正在載入 Whisper Base 多語模型…");
                long modelLoadStarted = SystemClock.elapsedRealtime();
                Log.i(TAG, "whisper_context_create_start");
                whisperContext = WhisperCpp.createContext(modelFile);
                Log.i(TAG, "whisper_context_create_done elapsedMs="
                        + (SystemClock.elapsedRealtime() - modelLoadStarted)
                        + " contextNonZero=" + (whisperContext != 0L));
                postStatus("Whisper Base 已啟動；多語自動辨識中");
            }

            float[] ring = new float[cloudMode ? CLOUD_RING_SAMPLES : MAX_WINDOW_SAMPLES];
            int writeIndex = 0;
            int bufferedSamples = 0;
            int samplesSinceInference = 0;
            String previousTranscript = "";
            String previousLanguage = "";

            while (running || !audioQueue.isEmpty()) {
                float[] samples;
                try {
                    samples = audioQueue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    if (!running) break;
                    continue;
                }
                if (samples == null) {
                    queueTimeouts++;
                    long now = SystemClock.elapsedRealtime();
                    if (now - lastFlowLogMs >= 5000L) {
                        lastFlowLogMs = now;
                        Log.w(TAG, "waiting_for_pcm queueTimeouts=" + queueTimeouts
                                + " queuedChunks=" + queuedChunks
                                + " queuedSamples=" + queuedSamples
                                + " consumedChunks=" + consumedChunks
                                + " consumedSamples=" + consumedSamples
                                + " queueSize=" + audioQueue.size());
                    }
                    continue;
                }
                if (samples == POISON) break;
                if (samples.length == 0) continue;

                consumedChunks++;
                consumedSamples += samples.length;
                long flowNow = SystemClock.elapsedRealtime();
                if (flowNow - lastFlowLogMs >= 5000L) {
                    lastFlowLogMs = flowNow;
                    Log.i(TAG, "pcm_consumed queuedChunks=" + queuedChunks
                            + " queuedSamples=" + queuedSamples
                            + " consumedChunks=" + consumedChunks
                            + " consumedSamples=" + consumedSamples
                            + " queueSize=" + audioQueue.size());
                }

                for (float sample : samples) {
                    ring[writeIndex] = sample;
                    writeIndex = (writeIndex + 1) % ring.length;
                    if (bufferedSamples < ring.length) bufferedSamples++;
                }
                samplesSinceInference += samples.length;

                int windowSize = cloudMode ? CLOUD_WINDOW_SAMPLES : WINDOW_SAMPLES;
                int updateSize = cloudMode ? CLOUD_UPDATE_SAMPLES : UPDATE_SAMPLES;
                int minimumWindow = previousTranscript.isEmpty()
                        ? (cloudMode ? CLOUD_WINDOW_SAMPLES : INITIAL_WINDOW_SAMPLES) : windowSize;
                if (bufferedSamples < minimumWindow || samplesSinceInference < updateSize) {
                    continue;
                }
                samplesSinceInference = 0;

                float[] window = snapshotTail(ring, writeIndex, Math.min(bufferedSamples, windowSize));
                long inferenceStarted = SystemClock.elapsedRealtime();
                // Two CPU threads balance recognition latency with ongoing video playback.
                int inferenceThreads = Math.max(1,
                        Math.min(2, Runtime.getRuntime().availableProcessors()));
                final long inputDbfs = rmsDbfs(window);
                Log.i(TAG, "asr_inference_start samples=" + window.length
                        + " seconds=" + (window.length / ASR_SAMPLE_RATE)
                        + " rmsDbfs=" + inputDbfs + " threads=" + inferenceThreads
                        + " languageHint=" + (cloudMode ? "auto" : languageHint));
                postStatus((cloudMode ? "正在送出直播語音辨識" : "Whisper 本機辨識中")
                        + " · " + (window.length / ASR_SAMPLE_RATE)
                        + " 秒音訊 · ASR " + inputDbfs + " dBFS");
                AtomicBoolean inferenceActive = new AtomicBoolean(true);
                Runnable[] heartbeat = new Runnable[1];
                heartbeat[0] = () -> {
                    if (!running || !inferenceActive.get()) return;
                    long seconds = (SystemClock.elapsedRealtime() - inferenceStarted) / 1000L;
                    postStatus((cloudMode ? "Groq 雲端辨識仍在處理" : "Whisper 本機辨識仍在運算")
                            + " · 已等待 " + seconds + " 秒 · ASR " + inputDbfs + " dBFS");
                    MAIN.postDelayed(heartbeat[0], 5000L);
                };
                MAIN.postDelayed(heartbeat[0], 5000L);
                String result;
                String detectedLanguage = "und";
                String priorLanguage = previousLanguage;
                String languageHint = priorLanguage.matches("[a-z]{2,3}")
                        && !"und".equals(priorLanguage) ? priorLanguage : "auto";
                try {
                    if (cloudMode) {
                        GroqSpeechToText.TranscriptionResult cloudResult =
                                GroqSpeechToText.transcribeDetailed(appContext, window, "auto");
                        result = cloudResult.text;
                        detectedLanguage = cloudResult.language;
                    } else {
                        // Auto-detect the source language once, then reuse it for later
                        // windows to avoid paying the language-detection cost every time.
                        result = WhisperCpp.transcribe(whisperContext, window, languageHint, inferenceThreads);
                    }
                } finally {
                    inferenceActive.set(false);
                    MAIN.removeCallbacks(heartbeat[0]);
                }
                long inferenceMs = SystemClock.elapsedRealtime() - inferenceStarted;
                Log.i(TAG, "asr_inference_done elapsedMs=" + inferenceMs
                        + " resultChars=" + (result == null ? -1 : result.length())
                        + " resultNull=" + (result == null));

                String transcript = result == null ? "" : result.trim();
                if (!cloudMode) {
                    int separator = transcript.indexOf('\n');
                    if (separator > 0 && separator <= 8) {
                        String language = transcript.substring(0, separator).trim();
                        if (language.matches("[a-z]{2,3}|und")) {
                            detectedLanguage = language;
                            transcript = transcript.substring(separator + 1).trim();
                        }
                    }
                }

                boolean validDetectedLanguage = detectedLanguage.matches("[a-z]{2,3}")
                        && !"und".equals(detectedLanguage);
                if (validDetectedLanguage) previousLanguage = detectedLanguage;

                if (!transcript.isEmpty()) {
                    postSubtitle(transcript);
                    String newText = detectedLanguage.equals(priorLanguage)
                            ? extractNewText(previousTranscript, transcript) : transcript;
                    if (!newText.isEmpty()) postFinalSubtitle(newText, detectedLanguage);
                    previousTranscript = transcript;
                    previousLanguage = detectedLanguage;
                    postStatus((cloudMode ? "Groq 雲端辨識" : "Whisper Base") + " · 偵測 "
                            + detectedLanguage + " · " + (window.length / ASR_SAMPLE_RATE)
                            + " 秒音訊 · 推論 " + inferenceMs + " ms");
                } else {
                    postStatus((cloudMode ? "Groq 辨識已完成" : "Whisper 推論已完成")
                            + " · " + inferenceMs + " ms · 沒有回傳文字 · ASR "
                            + inputDbfs + " dBFS；持續聆聽");
                }
            }
        } catch (Throwable error) {
            running = false;
            Log.e(TAG, "recognizer_worker_failed", error);
            postStatus((cloudMode ? "Groq 雲端辨識失敗：" : "Whisper Base 啟動失敗：")
                    + readableMessage(error));
        } finally {
            try {
                if (whisperContext != 0L) WhisperCpp.freeContext(whisperContext);
            } catch (Throwable ignored) {
            }
            if (!running) postStatus("AI 字幕已關閉");
        }
    }

    private static long rmsDbfs(float[] samples) {
        if (samples == null || samples.length == 0) return -120L;
        double sumSquares = 0.0;
        int validSamples = 0;
        for (float sample : samples) {
            if (!Float.isFinite(sample)) continue;
            sumSquares += sample * sample;
            validSamples++;
        }
        if (validSamples == 0) return -120L;
        double rms = Math.sqrt(sumSquares / validSamples);
        return Math.round(20.0 * Math.log10(Math.max(1.0e-6, rms)));
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

        // CJK transcripts usually contain no spaces. Compare Unicode letters/digits
        // and trim the overlap by code point so rolling cloud windows do not replay
        // the same Chinese/Japanese/Korean caption every update.
        if (containsCjk(previous) || containsCjk(current)) {
            String prevUnicode = normalizeUnicodeText(previous);
            String currentUnicode = normalizeUnicodeText(current);
            if (currentUnicode.isEmpty()) return "";
            if (prevUnicode.isEmpty()) return current.trim();
            if (prevUnicode.equals(currentUnicode) || prevUnicode.contains(currentUnicode)) return "";
            if (currentUnicode.startsWith(prevUnicode)) {
                return suffixAfterNormalizedCharacters(current, prevUnicode.length());
            }
            int maxOverlap = Math.min(80, Math.min(prevUnicode.length(), currentUnicode.length()));
            for (int overlap = maxOverlap; overlap >= 6; overlap--) {
                if (prevUnicode.regionMatches(prevUnicode.length() - overlap,
                        currentUnicode, 0, overlap)) {
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
