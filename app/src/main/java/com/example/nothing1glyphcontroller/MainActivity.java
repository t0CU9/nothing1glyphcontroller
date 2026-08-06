package com.example.nothing1glyphcontroller;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.io.DataOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final String LED_PATH = "/sys/class/leds/aw210xx_led/all_white_leds_br"; // 実機のパスに変更

    // パラメータ
    private int loopTimeMs = 5000;      // 1ループ時間
    private int maxBrightness = 4095;   // 最大光量
    private int changeTimeMs = 2000;    // 明るく/暗く変化する時間
    private int darkRatio = 50;         // 残り時間のうち暗い時間の割合(%)

    // UI
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

    // 制御
    private volatile boolean isRunning = false;
    private Thread controlThread;

    // root shell
    private Process rootProcess;
    private DataOutputStream rootStream;

    // 同期待機用
    private final Object sleepLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupSeekBars();
        setupButtons();

        refreshAllTexts();
        updateChangeTimeLimit();
        clampChangeTimeToLoop();
        refreshTexts();
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
        // ループ時間: 0.5秒刻み、0.5～60.0秒
        // progress 0 => 500ms, progress 119 => 60000ms
        loopTimeSeekBar.setMax(18);
        loopTimeSeekBar.setProgress(9); // 5.0秒

        loopTimeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                loopTimeMs = (progress + 2) * 500;
                updateChangeTimeLimit();
                clampChangeTimeToLoop();
                refreshTexts();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 最大光量: 0～4095
        maxBrightnessSeekBar.setMax(4095);
        maxBrightnessSeekBar.setProgress(4095);

        maxBrightnessSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                maxBrightness = progress;
                refreshTexts();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 変化時間: 50ms刻み
        changeTimeSeekBar.setMax(Math.max(1, loopTimeMs / 50));
        changeTimeSeekBar.setProgress(40); // 2000ms

        changeTimeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                changeTimeMs = progress * 50;
                clampChangeTimeToLoop();
                refreshTexts();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 暗い時間の比率: 0～100%
        darkRatioSeekBar.setMax(100);
        darkRatioSeekBar.setProgress(50);

        darkRatioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                darkRatio = progress;
                refreshTexts();
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
    }

    private void setupButtons() {
        startButton.setOnClickListener(v -> startLEDControl());
        stopButton.setOnClickListener(v -> stopLEDControl());
        stopButton.setEnabled(false);
    }

    private void refreshAllTexts() {
        refreshTexts();
    }

    @SuppressLint("SetTextI18n")
    private void refreshTexts() {
        long effectiveChange = Math.min(changeTimeMs, loopTimeMs / 2L);
        long remain = Math.max(0L, loopTimeMs - (effectiveChange * 2L));
        long darkHold = Math.round(remain * (darkRatio / 100.0));
        long brightHold = remain - darkHold;

        loopTimeText.setText("ループ時間: " + loopTimeMs + " ms");
        maxBrightnessText.setText("最大光量: " + maxBrightness + " / 4095");
        changeTimeText.setText(
                "変化時間: " + changeTimeMs + " ms"
                        + "（上限 " + (loopTimeMs / 2) + " ms）"
        );
        darkRatioText.setText(
                "暗い時間の比率: " + darkRatio + "%"
                        + " / 暗: " + darkHold + " ms"
                        + " / 明: " + brightHold + " ms"
        );
    }

    private void updateChangeTimeLimit() {
        int maxChangeProgress = Math.max(1, loopTimeMs / 50); // 50ms刻み
        changeTimeSeekBar.setMax(maxChangeProgress);

        if (changeTimeSeekBar.getProgress() > maxChangeProgress) {
            changeTimeSeekBar.setProgress(maxChangeProgress);
        }
    }

    private void clampChangeTimeToLoop() {
        int maxAllowed = loopTimeMs / 2;
        if (changeTimeMs > maxAllowed) {
            changeTimeMs = maxAllowed;
            changeTimeSeekBar.setProgress(changeTimeMs / 50);
        }
    }

    private void startLEDControl() {
        if (isRunning) return;

        isRunning = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        controlThread = new Thread(() -> {
            try {
                openRootShell();

                while (isRunning) {
                    CyclePlan plan = buildCyclePlan();
                    runOneCycle(plan);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                runOnUiThread(() ->
                        Toast.makeText(
                                MainActivity.this,
                                "LED制御エラー: " + e.getMessage(),
                                Toast.LENGTH_SHORT
                        ).show()
                );
            } finally {
                try {
                    if (rootStream != null) {
                        writeLED(0);
                    }
                } catch (IOException ignored) {
                }

                closeRootShell();

                isRunning = false;
                runOnUiThread(() -> {
                    startButton.setEnabled(true);
                    stopButton.setEnabled(false);
                });
            }
        });

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

    private CyclePlan buildCyclePlan() {
        long effectiveChange = Math.min(changeTimeMs, loopTimeMs / 2L);
        long remaining = Math.max(0L, loopTimeMs - (effectiveChange * 2L));

        long darkHold = Math.round(remaining * (darkRatio / 100.0));
        long brightHold = remaining - darkHold;

        return new CyclePlan(effectiveChange, darkHold, brightHold);
    }

    private void runOneCycle(CyclePlan plan) throws IOException, InterruptedException {
        // 暗い状態から開始
        writeLED(0);
        sleepInterruptible(plan.darkHoldMs);

        // 暗 -> 明
        fadeLED(0, maxBrightness, plan.changeMs);

        // 明るい状態
        sleepInterruptible(plan.brightHoldMs);

        // 明 -> 暗
        fadeLED(maxBrightness, 0, plan.changeMs);
    }

    private void fadeLED(int from, int to, long durationMs) throws IOException, InterruptedException {
        if (!isRunning) return;

        if (durationMs <= 0) {
            writeLED(to);
            return;
        }

        final double gamma = 2.2;

        // 16ms前後で更新。短い変化でも最低30分割。
        int steps = (int) Math.max(30, durationMs / 16);
        long interval = Math.max(1L, durationMs / steps);

        double startPerceived = brightnessToPerceived(from, gamma);
        double endPerceived = brightnessToPerceived(to, gamma);

        for (int i = 0; i <= steps && isRunning; i++) {
            double t = i / (double) steps;

            // 滑らかな加減速
            double eased = easeInOutSine(t);

            // 人間の目に自然に見えるよう、知覚空間で補間
            double perceived = startPerceived + (endPerceived - startPerceived) * eased;

            int value = perceivedToBrightness(perceived, gamma);
            value = clamp(value, 0, maxBrightness);

            writeLED(value);

            if (i < steps) {
                sleepInterruptible(interval);
            }
        }
        writeLED(0);
    }

    private double brightnessToPerceived(int brightness, double gamma) {
        if (maxBrightness <= 0) return 0.0;
        double normalized = Math.max(0.0, Math.min(1.0, brightness / (double) maxBrightness));
        return Math.pow(normalized, gamma);
    }

    private int perceivedToBrightness(double perceived, double gamma) {
        if (maxBrightness <= 0) return 0;
        double normalized = Math.max(0.0, Math.min(1.0, perceived));
        return (int) Math.round(maxBrightness * Math.pow(normalized, 1.0 / gamma));
    }

    // 0→1 を滑らかにする
    private double easeInOutSine(double t) {
        return 0.5 - 0.5 * Math.cos(Math.PI * t);
    }

    private void sleepInterruptible(long ms) throws InterruptedException {
        if (ms <= 0) {
            if (!isRunning) throw new InterruptedException("stopped");
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

    private void openRootShell() throws IOException {
        rootProcess = Runtime.getRuntime().exec("su");
        rootStream = new DataOutputStream(rootProcess.getOutputStream());
    }

    private void closeRootShell() {
        try {
            if (rootStream != null) {
                try {
                    rootStream.writeBytes("exit\n");
                    rootStream.flush();
                } catch (IOException ignored) {
                }
                rootStream.close();
                rootStream = null;
            }
        } catch (IOException ignored) {
        }

        if (rootProcess != null) {
            rootProcess.destroy();
            rootProcess = null;
        }
    }

    private void writeLED(int brightness) throws IOException {
        if (rootStream == null) {
            throw new IOException("root shell not initialized");
        }

        rootStream.writeBytes("echo " + brightness + " > " + LED_PATH + "\n");
        rootStream.flush();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopLEDControl();
        closeRootShell();
    }

    private static class CyclePlan {
        final long changeMs;
        final long darkHoldMs;
        final long brightHoldMs;

        CyclePlan(long changeMs, long darkHoldMs, long brightHoldMs) {
            this.changeMs = changeMs;
            this.darkHoldMs = darkHoldMs;
            this.brightHoldMs = brightHoldMs;
        }
    }
}