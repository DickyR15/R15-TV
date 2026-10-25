package com.dicky.r15tv.subtitle;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

public final class SubtitlePocApplication extends Application {
    private static final String CRASH_FILE = "r15-ai-subtitle-last-crash.txt";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(new File(getFilesDir(), CRASH_FILE), false),
                    StandardCharsets.UTF_8))) {
                writer.println("R15 TV AI Subtitle crash report");
                writer.println("Thread: " + thread.getName());
                writer.println("Android API: " + Build.VERSION.SDK_INT);
                writer.println("Android release: " + Build.VERSION.RELEASE);
                writer.println("Device: " + Build.MANUFACTURER + " " + Build.MODEL);
                writer.println("Package: " + getPackageName());
                writer.println();
                error.printStackTrace(writer);
                writer.flush();
            } catch (Throwable ignored) {
                // Preserve the original process crash even if writing the report fails.
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            } else {
                Process.killProcess(Process.myPid());
                System.exit(10);
            }
        });
    }

    public static String readLastCrash(Context context) {
        File file = new File(context.getFilesDir(), CRASH_FILE);
        if (!file.isFile()) return "";
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(file.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (Throwable error) {
            return "無法讀取上次閃退紀錄：" + error;
        }
    }

    public static void clearLastCrash(Context context) {
        try {
            new File(context.getFilesDir(), CRASH_FILE).delete();
        } catch (Throwable ignored) { }
    }
}
