package com.fongmi.android.tv.ai.subtitle;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.ResultReceiver;

/** Starts the required mediaProjection foreground service before getMediaProjection(). */
public final class AiSubtitleProjectionService extends Service {
    public static final String EXTRA_RESULT_RECEIVER = "r15.subtitle.EXTRA_RESULT_RECEIVER";
    public static final int RESULT_READY = 1;
    private static final int NOTIFICATION_ID = 7305;
    private static final String CHANNEL_ID = "r15_ai_subtitle_capture";

    @Override
    public void onCreate() {
        super.onCreate();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    "R15 TV 即時字幕", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("即時字幕擷取播放音訊時顯示");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ResultReceiver receiver = getReceiver(intent);
        try {
            Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
            Notification notification = builder
                    .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                    .setContentTitle("R15 TV 即時字幕")
                    .setContentText("正在擷取播放音訊以產生字幕")
                    .setOngoing(true).build();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            if (receiver != null) receiver.send(RESULT_READY, null);
        } catch (Throwable error) {
            if (receiver != null) {
                android.os.Bundle data = new android.os.Bundle();
                data.putString("error", error.getMessage() == null
                        ? error.getClass().getSimpleName() : error.getMessage());
                receiver.send(0, data);
            }
            stopSelf(startId);
        }
        return START_NOT_STICKY;
    }

    private ResultReceiver getReceiver(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER, ResultReceiver.class);
        }
        //noinspection deprecation
        return intent.getParcelableExtra(EXTRA_RESULT_RECEIVER);
    }

    @Override
    public void onDestroy() {
        stopForeground(true);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
