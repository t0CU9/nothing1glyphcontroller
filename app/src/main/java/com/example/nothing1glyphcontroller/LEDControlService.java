package com.example.nothing1glyphcontroller;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.DataOutputStream;
import java.io.IOException;

public class LEDControlService extends Service {

    private static final String LED_PATH =
            "/sys/class/leds/aw210xx_led/all_white_leds_br";

    private static final String CHANNEL_ID =
            "led_controller_channel";

    private static final int NOTIFICATION_ID = 1001;

    private static final String ACTION_START =
            "com.example.nothing1glyphcontroller.START";

    private static final String ACTION_STOP =
            "com.example.nothing1glyphcontroller.STOP";

    private static final int DEFAULT_LOOP_TIME = 5000;
    private static final int DEFAULT_MAX_BRIGHTNESS = 4095;
    private static final int DEFAULT_CHANGE_TIME = 2500;
    private static final int DEFAULT_DARK_RATIO = 50;

    private static final int MIN_LOOP_TIME = 500;
    private static final int MAX_LOOP_TIME = 10000;

    private static final String KEY_LOOP_TIME = "loop_time";
    private static final String KEY_MAX_BRIGHTNESS = "max_brightness";
    private static final String KEY_CHANGE_TIME = "change_time";
    private static final String KEY_DARK_RATIO = "dark_ratio";
    private static final String KEY_RUNNING = "running";

    private SharedPreferences prefs;
    private Thread controlThread;
    private Process rootProcess;
    private DataOutputStream rootStream;
    private PowerManager.WakeLock wakeLock;

    private final Object sleepLock = new Object();

    private volatile boolean isRunning = false;
    private volatile boolean userStopRequested = false;

    @Override
    public void onCreate() {
        super.onCreate();

        prefs = getSharedPreferences(
                MainActivity.PREFS_NAME,
                MODE_PRIVATE
        );

        createNotificationChannel();
        acquireWakeLock();
    }

    @Override
    public int onStartCommand(
            Intent intent,
            int flags,
            int startId
    ) {
        String action =
                intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            userStopRequested = true;

            prefs.edit()
                    .putBoolean(KEY_RUNNING, false)
                    .apply();

            stopLEDControl();
            stopForeground(true);
            stopSelf();

            return START_NOT_STICKY;
        }

        if (ACTION_START.equals(action)) {
            userStopRequested = false;

            prefs.edit()
                    .putBoolean(KEY_RUNNING, true)
                    .apply();

            startAsForeground();
            startLEDControl();

            return START_STICKY;
        }

        if (prefs.getBoolean(KEY_RUNNING, false)) {
            userStopRequested = false;
            startAsForeground();
            startLEDControl();

            return START_STICKY;
        }

        stopSelf();
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        Intent activityIntent =
                new Intent(this, MainActivity.class);

        PendingIntent contentIntent =
                PendingIntent.getActivity(
                        this,
                        0,
                        activityIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT |
                                PendingIntent.FLAG_IMMUTABLE
                );

        Notification notification =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_ID
                )
                        .setContentTitle(
                                "Nothing 1 Glyph Controller"
                        )
                        .setContentText(
                                "LED制御を実行中"
                        )
                        .setSmallIcon(
                                android.R.drawable.ic_menu_manage
                        )
                        .setContentIntent(contentIntent)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(
                                NotificationCompat.PRIORITY_LOW
                        )
                        .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    private void startLEDControl() {
        if (isRunning) {
            return;
        }

        isRunning = true;

        controlThread =
                new Thread(
                        this::controlLoop,
                        "LED-Control-Thread"
                );

        controlThread.start();
    }

    private void stopLEDControl() {
        isRunning = false;

        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }

        if (controlThread != null) {
            controlThread.interrupt();
        }
    }

    private void controlLoop() {
        try {
            openRootShell();

            while (isRunning) {
                Settings settings = loadSettings();
                CyclePlan plan = buildCyclePlan(settings);

                runOneCycle(settings, plan);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            showErrorNotification(
                    "LED制御エラー: " + e.getMessage()
            );
        } finally {
            try {
                if (rootStream != null) {
                    writeLED(0);
                }
            } catch (IOException ignored) {
            }

            closeRootShell();

            if (userStopRequested) {
                prefs.edit()
                        .putBoolean(KEY_RUNNING, false)
                        .apply();
            }

            isRunning = false;
        }
    }

    private Settings loadSettings() {
        int loopTimeMs =
                clamp(
                        prefs.getInt(
                                KEY_LOOP_TIME,
                                DEFAULT_LOOP_TIME
                        ),
                        MIN_LOOP_TIME,
                        MAX_LOOP_TIME
                );

        int maxBrightness =
                clamp(
                        prefs.getInt(
                                KEY_MAX_BRIGHTNESS,
                                DEFAULT_MAX_BRIGHTNESS
                        ),
                        0,
                        4095
                );

        int changeTimeMs =
                clamp(
                        prefs.getInt(
                                KEY_CHANGE_TIME,
                                DEFAULT_CHANGE_TIME
                        ),
                        0,
                        loopTimeMs / 2
                );

        int darkRatio =
                clamp(
                        prefs.getInt(
                                KEY_DARK_RATIO,
                                DEFAULT_DARK_RATIO
                        ),
                        0,
                        100
                );

        return new Settings(
                loopTimeMs,
                maxBrightness,
                changeTimeMs,
                darkRatio
        );
    }

    private CyclePlan buildCyclePlan(Settings settings) {
        long change =
                Math.min(
                        settings.changeTimeMs,
                        settings.loopTimeMs / 2L
                );

        long remaining =
                Math.max(
                        0,
                        settings.loopTimeMs -
                                change * 2
                );

        long darkHold =
                Math.round(
                        remaining *
                                settings.darkRatio /
                                100.0
                );

        long brightHold =
                remaining - darkHold;

        return new CyclePlan(
                change,
                darkHold,
                brightHold
        );
    }

    private void runOneCycle(
            Settings settings,
            CyclePlan plan
    ) throws IOException, InterruptedException {

        writeLED(0);
        sleepInterruptible(plan.darkHoldMs);

        fadeLED(
                0,
                settings.maxBrightness,
                settings.maxBrightness,
                plan.changeMs
        );

        writeLED(settings.maxBrightness);
        sleepInterruptible(plan.brightHoldMs);

        fadeLED(
                settings.maxBrightness,
                0,
                settings.maxBrightness,
                plan.changeMs
        );
    }

    private void fadeLED(
            int from,
            int to,
            int maxBrightness,
            long durationMs
    ) throws IOException, InterruptedException {

        if (!isRunning) {
            return;
        }

        if (durationMs <= 0) {
            writeLED(to);
            return;
        }

        if (maxBrightness <= 0) {
            writeLED(0);
            return;
        }

        final double gamma = 2.2;

        int steps =
                (int) Math.max(
                        30,
                        durationMs / 16
                );

        long interval =
                Math.max(
                        1,
                        durationMs / steps
                );

        double start =
                brightnessToPerceived(
                        from,
                        maxBrightness,
                        gamma
                );

        double end =
                brightnessToPerceived(
                        to,
                        maxBrightness,
                        gamma
                );

        for (int i = 0; i <= steps && isRunning; i++) {
            double t = i / (double) steps;
            double eased = easeInOutSine(t);

            double perceived =
                    start + (end - start) * eased;

            int value =
                    perceivedToBrightness(
                            perceived,
                            maxBrightness,
                            gamma
                    );

            writeLED(
                    clamp(
                            value,
                            0,
                            maxBrightness
                    )
            );

            if (i < steps) {
                sleepInterruptible(interval);
            }
        }
    }

    private double brightnessToPerceived(
            int brightness,
            int maxBrightness,
            double gamma
    ) {
        double normalized =
                clamp(
                        brightness,
                        0,
                        maxBrightness
                ) / (double) maxBrightness;

        return Math.pow(normalized, gamma);
    }

    private int perceivedToBrightness(
            double perceived,
            int maxBrightness,
            double gamma
    ) {
        double normalized =
                Math.max(
                        0,
                        Math.min(1, perceived)
                );

        return (int) Math.round(
                maxBrightness *
                        Math.pow(
                                normalized,
                                1.0 / gamma
                        )
        );
    }

    private double easeInOutSine(double t) {
        return 0.5 -
                0.5 * Math.cos(Math.PI * t);
    }

    private void sleepInterruptible(long ms)
            throws InterruptedException {

        if (ms <= 0) {
            if (!isRunning) {
                throw new InterruptedException("stopped");
            }
            return;
        }

        synchronized (sleepLock) {
            if (!isRunning) {
                throw new InterruptedException("stopped");
            }

            sleepLock.wait(ms);

            if (!isRunning) {
                throw new InterruptedException("stopped");
            }
        }
    }

    private synchronized void openRootShell()
            throws IOException {

        if (rootProcess != null ||
                rootStream != null) {
            return;
        }

        rootProcess =
                Runtime.getRuntime().exec("su");

        rootStream =
                new DataOutputStream(
                        rootProcess.getOutputStream()
                );
    }

    private synchronized void closeRootShell() {
        if (rootStream != null) {
            try {
                rootStream.writeBytes("exit\n");
                rootStream.flush();
                rootStream.close();
            } catch (IOException ignored) {
            }

            rootStream = null;
        }

        if (rootProcess != null) {
            rootProcess.destroy();
            rootProcess = null;
        }
    }

    private synchronized void writeLED(int brightness)
            throws IOException {

        if (rootStream == null) {
            throw new IOException(
                    "root shell not initialized"
            );
        }

        rootStream.writeBytes(
                "echo " +
                        brightness +
                        " > " +
                        LED_PATH +
                        "\n"
        );

        rootStream.flush();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        "LED Controller",
                        NotificationManager.IMPORTANCE_LOW
                );

        NotificationManager manager =
                getSystemService(NotificationManager.class);

        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private void showErrorNotification(String message) {
        Notification notification =
                new NotificationCompat.Builder(
                        this,
                        CHANNEL_ID
                )
                        .setContentTitle("LED制御エラー")
                        .setContentText(message)
                        .setSmallIcon(
                                android.R.drawable.ic_dialog_alert
                        )
                        .setOngoing(true)
                        .setPriority(
                                NotificationCompat.PRIORITY_LOW
                        )
                        .build();

        NotificationManager manager =
                (NotificationManager)
                        getSystemService(
                                NOTIFICATION_SERVICE
                        );

        if (manager != null) {
            manager.notify(
                    NOTIFICATION_ID,
                    notification
            );
        }
    }

    private void acquireWakeLock() {
        PowerManager powerManager =
                (PowerManager)
                        getSystemService(POWER_SERVICE);

        if (powerManager == null) {
            return;
        }

        wakeLock =
                powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "Nothing1GlyphController:LEDControl"
                );

        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
    }

    private void releaseWakeLock() {
        if (wakeLock != null &&
                wakeLock.isHeld()) {
            wakeLock.release();
        }

        wakeLock = null;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        stopLEDControl();
        closeRootShell();
        releaseWakeLock();
        super.onDestroy();
    }

    private static class Settings {
        final int loopTimeMs;
        final int maxBrightness;
        final int changeTimeMs;
        final int darkRatio;

        Settings(
                int loopTimeMs,
                int maxBrightness,
                int changeTimeMs,
                int darkRatio
        ) {
            this.loopTimeMs = loopTimeMs;
            this.maxBrightness = maxBrightness;
            this.changeTimeMs = changeTimeMs;
            this.darkRatio = darkRatio;
        }
    }

    private static class CyclePlan {
        final long changeMs;
        final long darkHoldMs;
        final long brightHoldMs;

        CyclePlan(
                long changeMs,
                long darkHoldMs,
                long brightHoldMs
        ) {
            this.changeMs = changeMs;
            this.darkHoldMs = darkHoldMs;
            this.brightHoldMs = brightHoldMs;
        }
    }
}