package com.dicky.r15tv.subtitle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;

import com.fongmi.android.tv.ai.subtitle.AiSubtitleEngine;
import com.fongmi.android.tv.ai.subtitle.AiSubtitlePlaybackCapture;
import com.fongmi.android.tv.ai.subtitle.AiSubtitleTranslationEngine;
import com.fongmi.android.tv.ai.subtitle.WhisperModelManager;

public final class SubtitleCaptureService extends Service {
    public static final String ACTION_START = "com.dicky.r15tv.subtitle.START";
    public static final String ACTION_STOP = "com.dicky.r15tv.subtitle.STOP";
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_PROJECTION_DATA = "projection_data";
    private static final String TARGET_PACKAGE = "com.dicky.r15tv";
    private static final int NOTIFICATION_ID = 7305;
    private static final String CHANNEL_ID = "r15_tv_ai_subtitle_companion";

    private MediaProjection projection;
    private AiSubtitlePlaybackCapture capture;
    private AiSubtitleTranslationEngine translation;
    private SubtitleOverlayWindow overlay;
    private boolean started;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (started) return START_NOT_STICKY;
        try {
            startForegroundCompat();
            int resultCode = intent == null ? 0 : intent.getIntExtra(EXTRA_RESULT_CODE, 0);
            Intent projectionData = getProjectionData(intent);
            if (resultCode == 0 || projectionData == null) {
                throw new IllegalStateException("缺少 Android 音訊擷取授權資料，請回測試 App 重新開始");
            }

            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            if (manager == null) throw new IllegalStateException("MediaProjection 服務不存在");
            projection = manager.getMediaProjection(resultCode, projectionData);
            if (projection == null) throw new IllegalStateException("Android 未建立 MediaProjection");

            if (!WhisperModelManager.getInstance(this).isDownloaded()) {
                throw new IllegalStateException("請先下載 Whisper Base 多語模型");
            }
            ApplicationInfo targetInfo = findTargetApp();
            if (targetInfo == null) {
                throw new IllegalStateException("找不到 R15 TV（com.dicky.r15tv）；請先安裝並啟動 FIX3");
            }

            overlay = new SubtitleOverlayWindow(this);
            overlay.show();
            overlay.setStatus("正在啟動本機語音辨識…");

            translation = new AiSubtitleTranslationEngine(this,
                    new AiSubtitleTranslationEngine.Listener() {
                @Override public void onStatus(String status) {
                    if (overlay != null) overlay.setStatus(status);
                }
                @Override public void onTranslatedSubtitle(String source, String translated) {
                    if (overlay != null) overlay.showSubtitle(source, translated);
                }
            });
            translation.start();

            AiSubtitleEngine.start(this, WhisperModelManager.getInstance(this).getModelFile(),
                    new AiSubtitleEngine.Listener() {
                @Override public void onStatus(String status) {
                    if (overlay != null) overlay.setStatus(status);
                }
                @Override public void onSubtitle(String text) {
                    if (overlay != null) overlay.showSubtitle(text, "");
                }
                @Override public void onFinalSubtitle(String text) { }
                @Override public void onFinalSubtitle(String text, String language) {
                    if (translation != null) translation.translateFinalPhrase(text, language);
                }
            });

            capture = AiSubtitlePlaybackCapture.start(this, projection, targetInfo.uid,
                    new AiSubtitlePlaybackCapture.Listener() {
                @Override public void onStatus(String status) {
                    if (overlay != null) overlay.setStatus(status);
                }
                @Override public void onLevel(float dbfs) {
                    if (overlay != null && dbfs < -55.0f) {
                        overlay.setStatus("未收到明顯音訊（" + Math.round(dbfs) + " dBFS）；請播放 R15 TV 直播");
                    }
                }
            });
            started = true;
        } catch (Throwable error) {
            if (overlay != null) overlay.setStatus("字幕啟動失敗：" + readable(error));
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private ApplicationInfo findTargetApp() throws Exception {
        PackageManager pm = getPackageManager();
        if (Build.VERSION.SDK_INT >= 33) {
            return pm.getApplicationInfo(TARGET_PACKAGE, PackageManager.ApplicationInfoFlags.of(0));
        }
        //noinspection deprecation
        return pm.getApplicationInfo(TARGET_PACKAGE, 0);
    }

    private Intent getProjectionData(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_PROJECTION_DATA, Intent.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_PROJECTION_DATA);
    }

    private void startForegroundCompat() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "R15 TV AI 字幕", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("使用本機辨識產生直播繁體中文字幕");
            manager.createNotificationChannel(channel);
        }
        Intent stop = new Intent(this, SubtitleCaptureService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPending = PendingIntent.getService(this, 7306, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("R15 TV AI 即時字幕")
                .setContentText("擷取 R15 TV 播放音訊；辨識與翻譯在本機執行")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "停止字幕", stopPending).build())
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private static String readable(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroy() {
        if (capture != null) {
            try { capture.stop(); } catch (Throwable ignored) { }
            capture = null;
        } else if (projection != null) {
            try { projection.stop(); } catch (Throwable ignored) { }
        }
        AiSubtitleEngine.stop();
        if (translation != null) {
            try { translation.close(); } catch (Throwable ignored) { }
            translation = null;
        }
        if (overlay != null) {
            overlay.remove();
            overlay = null;
        }
        stopForeground(true);
        started = false;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
