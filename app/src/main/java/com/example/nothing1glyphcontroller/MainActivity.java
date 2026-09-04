package com.example.nothing1glyphcontroller;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS_NAME = "led_settings";

    public static final String KEY_LOOP_TIME = "loop_time";
    public static final String KEY_MAX_BRIGHTNESS = "max_brightness";
    public static final String KEY_CHANGE_TIME = "change_time";
    public static final String KEY_DARK_RATIO = "dark_ratio";
    public static final String KEY_RUNNING = "running";

    public static final String ACTION_START =
            "com.example.nothing1glyphcontroller.START";
    public static final String ACTION_STOP =
            "com.example.nothing1glyphcontroller.STOP";

    private static final String KEY_SETTINGS_VERSION = "settings_version";
    private static final int SETTINGS_VERSION = 3;

    private static final int DEFAULT_LOOP_TIME = 5000;
    private static final int DEFAULT_MAX_BRIGHTNESS = 4095;
    private static final int DEFAULT_CHANGE_TIME = 2500;
    private static final int DEFAULT_DARK_RATIO = 50;

    private static final int MIN_LOOP_TIME = 500;
    private static final int MAX_LOOP_TIME = 10000;
    private static final int LOOP_STEP = 500;
    private static final int CHANGE_STEP = 50;

    private int loopTimeMs;
    private int maxBrightness;
    private int changeTimeMs;
    private int darkRatio;

    private TextView loopTimeText;
    private TextView maxBrightnessText;
    private TextView changeTimeText;
    private TextView darkRatioText;

    private SeekBar loopTimeSeekBar;
    private SeekBar maxBrightnessSeekBar;
    private SeekBar changeTimeSeekBar;
    private SeekBar darkRatioSeekBar;

    private Button startButton;
    private Button stopButton;

    private SharedPreferences prefs;
    private boolean initializing = true;

    private final ActivityResultLauncher<String> notificationPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startLEDControl();
                        } else {
                            Toast.makeText(
                                    this,
                                    "通知権限が必要です",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
            );

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        initializeSettings();
        bindViews();
        setupSeekBars();
        setupButtons();

        applySettingsToSeekBars();

        initializing = false;

        refreshTexts();
        updateButtons();
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (!initializing) {
            loadSettings();
            applySettingsToSeekBars();
            refreshTexts();
            updateButtons();
        }
    }

    private void initializeSettings() {
        int version = prefs.getInt(KEY_SETTINGS_VERSION, 0);

        if (version == 0) {
            prefs.edit()
                    .putInt(KEY_LOOP_TIME, DEFAULT_LOOP_TIME)
                    .putInt(
                            KEY_MAX_BRIGHTNESS,
                            DEFAULT_MAX_BRIGHTNESS
                    )
                    .putInt(
                            KEY_CHANGE_TIME,
                            DEFAULT_CHANGE_TIME
                    )
                    .putInt(
                            KEY_DARK_RATIO,
                            DEFAULT_DARK_RATIO
                    )
                    .putBoolean(KEY_RUNNING, false)
                    .putInt(
                            KEY_SETTINGS_VERSION,
                            SETTINGS_VERSION
                    )
                    .apply();
        } else if (version < SETTINGS_VERSION) {
            prefs.edit()
                    .putInt(
                            KEY_CHANGE_TIME,
                            DEFAULT_CHANGE_TIME
                    )
                    .putInt(
                            KEY_SETTINGS_VERSION,
                            SETTINGS_VERSION
                    )
                    .apply();
        }

        loadSettings();
    }

    private void loadSettings() {
        loopTimeMs = clamp(
                prefs.getInt(KEY_LOOP_TIME, DEFAULT_LOOP_TIME),
                MIN_LOOP_TIME,
                MAX_LOOP_TIME
        );

        maxBrightness = clamp(
                prefs.getInt(
                        KEY_MAX_BRIGHTNESS,
                        DEFAULT_MAX_BRIGHTNESS
                ),
                0,
                4095
        );

        changeTimeMs = clamp(
                prefs.getInt(
                        KEY_CHANGE_TIME,
                        DEFAULT_CHANGE_TIME
                ),
                0,
                loopTimeMs / 2
        );

        darkRatio = clamp(
                prefs.getInt(
                        KEY_DARK_RATIO,
                        DEFAULT_DARK_RATIO
                ),
                0,
                100
        );
    }

    private void saveSettings() {
        prefs.edit()
                .putInt(KEY_LOOP_TIME, loopTimeMs)
                .putInt(KEY_MAX_BRIGHTNESS, maxBrightness)
                .putInt(KEY_CHANGE_TIME, changeTimeMs)
                .putInt(KEY_DARK_RATIO, darkRatio)
                .apply();
    }

    private void bindViews() {
        loopTimeText = findViewById(R.id.loopTimeText);
        maxBrightnessText = findViewById(R.id.maxBrightnessText);
        changeTimeText = findViewById(R.id.changeTimeText);
        darkRatioText = findViewById(R.id.darkRatioText);

        loopTimeSeekBar = findViewById(R.id.loopTimeSeekBar);
        maxBrightnessSeekBar = findViewById(R.id.maxBrightnessSeekBar);
        changeTimeSeekBar = findViewById(R.id.changeTimeSeekBar);
        darkRatioSeekBar = findViewById(R.id.darkRatioSeekBar);

        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
    }

    private void setupSeekBars() {
        loopTimeSeekBar.setMax(
                (MAX_LOOP_TIME - MIN_LOOP_TIME) / LOOP_STEP
        );

        loopTimeSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        loopTimeMs =
                                MIN_LOOP_TIME +
                                        progress * LOOP_STEP;

                        updateChangeTimeLimit();
                        clampChangeTime();

                        if (!initializing && fromUser) {
                            saveSettings();
                        }

                        refreshTexts();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );

        maxBrightnessSeekBar.setMax(4095);
        maxBrightnessSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        maxBrightness = progress;

                        if (!initializing && fromUser) {
                            saveSettings();
                        }

                        refreshTexts();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );

        changeTimeSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        changeTimeMs =
                                progress * CHANGE_STEP;

                        if (changeTimeMs > loopTimeMs / 2) {
                            changeTimeMs =
                                    (loopTimeMs / 2 / CHANGE_STEP)
                                            * CHANGE_STEP;

                            seekBar.setProgress(
                                    changeTimeMs / CHANGE_STEP
                            );
                        }

                        if (!initializing && fromUser) {
                            saveSettings();
                        }

                        refreshTexts();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );

        darkRatioSeekBar.setMax(100);
        darkRatioSeekBar.setOnSeekBarChangeListener(
                new SeekBar.OnSeekBarChangeListener() {
                    @Override
                    public void onProgressChanged(
                            SeekBar seekBar,
                            int progress,
                            boolean fromUser
                    ) {
                        darkRatio = progress;

                        if (!initializing && fromUser) {
                            saveSettings();
                        }

                        refreshTexts();
                    }

                    @Override
                    public void onStartTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }

                    @Override
                    public void onStopTrackingTouch(
                            SeekBar seekBar
                    ) {
                    }
                }
        );
    }

    private void applySettingsToSeekBars() {
        loopTimeSeekBar.setProgress(
                (loopTimeMs - MIN_LOOP_TIME) / LOOP_STEP
        );

        maxBrightnessSeekBar.setProgress(
                maxBrightness
        );

        updateChangeTimeLimit();

        changeTimeSeekBar.setProgress(
                changeTimeMs / CHANGE_STEP
        );

        darkRatioSeekBar.setProgress(
                darkRatio
        );
    }

    private void updateChangeTimeLimit() {
        int maxProgress =
                (loopTimeMs / 2) / CHANGE_STEP;

        changeTimeSeekBar.setMax(
                Math.max(1, maxProgress)
        );
    }

    private void clampChangeTime() {
        int maxAllowed =
                (loopTimeMs / 2 / CHANGE_STEP)
                        * CHANGE_STEP;

        if (changeTimeMs > maxAllowed) {
            changeTimeMs = maxAllowed;

            changeTimeSeekBar.setProgress(
                    changeTimeMs / CHANGE_STEP
            );
        }
    }

    private void setupButtons() {
        startButton.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.POST_NOTIFICATIONS
                    ) != PackageManager.PERMISSION_GRANTED) {

                notificationPermissionLauncher.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                );
                return;
            }

            startLEDControl();
        });

        stopButton.setOnClickListener(
                v -> stopLEDControl()
        );
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                        this,
                        Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED) {

            notificationPermissionLauncher.launch(
                    Manifest.permission.POST_NOTIFICATIONS
            );
        }
    }

    private void startLEDControl() {
        saveSettings();

        Intent intent =
                new Intent(
                        this,
                        LEDControlService.class
                );

        intent.setAction(ACTION_START);

        ContextCompat.startForegroundService(
                this,
                intent
        );

        prefs.edit()
                .putBoolean(KEY_RUNNING, true)
                .apply();

        updateButtons();

        Toast.makeText(
                this,
                "LED制御を開始しました",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void stopLEDControl() {
        Intent intent =
                new Intent(
                        this,
                        LEDControlService.class
                );

        intent.setAction(ACTION_STOP);

        startService(intent);

        prefs.edit()
                .putBoolean(KEY_RUNNING, false)
                .apply();

        updateButtons();

        Toast.makeText(
                this,
                "LED制御を停止しました",
                Toast.LENGTH_SHORT
        ).show();
    }

    private void updateButtons() {
        boolean running =
                prefs.getBoolean(
                        KEY_RUNNING,
                        false
                );

        startButton.setEnabled(!running);
        stopButton.setEnabled(running);
    }

    @SuppressLint("SetTextI18n")
    private void refreshTexts() {
        long effectiveChange =
                Math.min(
                        changeTimeMs,
                        loopTimeMs / 2L
                );

        long remaining =
                Math.max(
                        0L,
                        loopTimeMs -
                                effectiveChange * 2L
                );

        long darkHold =
                Math.round(
                        remaining *
                                darkRatio /
                                100.0
                );

        long brightHold =
                remaining -
                        darkHold;

        loopTimeText.setText(
                "ループ時間: " +
                        loopTimeMs +
                        " ms"
        );

        maxBrightnessText.setText(
                "最大光量: " +
                        maxBrightness +
                        " / 4095"
        );

        changeTimeText.setText(
                "変化時間: " +
                        changeTimeMs +
                        " ms（上限 " +
                        (loopTimeMs / 2) +
                        " ms）"
        );

        darkRatioText.setText(
                "暗い時間の比率: " +
                        darkRatio +
                        "% / 暗: " +
                        darkHold +
                        " ms / 明: " +
                        brightHold +
                        " ms"
        );
    }

    private int clamp(
            int value,
            int min,
            int max
    ) {
        return Math.max(
                min,
                Math.min(max, value)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}