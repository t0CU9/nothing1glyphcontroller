package com.example.nothing1glyphcontroller:
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.DataOutputStream;
import java.io.IOException;

public class MainActivity extends AppCompatActivity {

    private static final int PERMISSION_REQUEST_CODE = 100;
    private static final String LED_PATH = "/sys/class/leds/aw210xx_led/all_white_leds_br";

    // パラメータ変数
    private int loopTime = 5000; // ミリ秒
    private int maxBrightness = 4095;
    private int changeTime = 2000; // ミリ秒
    private int darkRatio = 50; // 暗い時間の割合（0-100）

    // UI要素
    private TextView loopTimeText;
    private TextView maxBrightnessText;
    private TextView changeTimeText;
    private SeekBar darkRatioSeekBar;
    private TextView darkRatioText;
    private Button startButton;
    private Button stopButton;

    private Handler handler;
    private Runnable ledRunnable;
    private boolean isRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // パーミッションのチェック
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    PERMISSION_REQUEST_CODE);
        }

        // UI要素の初期化
        initUI();

        // ハンドラの初期化
        handler = new Handler();

        // イベントリスナーの設定
        setupEventListeners();
    }

    private void initUI() {
        loopTimeText = findViewById(R.id.loopTimeText);
        maxBrightnessText = findViewById(R.id.maxBrightnessText);
        changeTimeText = findViewById(R.id.changeTimeText);
        darkRatioSeekBar = findViewById(R.id.darkRatioSeekBar);
        darkRatioText = findViewById(R.id.darkRatioText);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        // 初期値の設定
        loopTimeText.setText("ループ時間: " + loopTime/1000.0 + "秒");
        maxBrightnessText.setText("最大光量: " + maxBrightness);
        changeTimeText.setText("変化時間: " + changeTime/1000.0 + "秒");
        darkRatioSeekBar.setProgress(darkRatio);
        darkRatioText.setText("暗い時間割合: " + darkRatio + "%");
    }

    private void setupEventListeners() {
        // ループ時間スライダー
        findViewById(R.id.loopTimeSeekBar).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                loopTime = progress * 1000; // 秒からミリ秒に変換
                loopTimeText.setText("ループ時間: " + loopTime/1000.0 + "秒");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 最大光量スライダー
        findViewById(R.id.maxBrightnessSeekBar).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                maxBrightness = progress;
                maxBrightnessText.setText("最大光量: " + maxBrightness);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 変化時間スライダー
        findViewById(R.id.changeTimeSeekBar).setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                changeTime = progress * 100; // 0.1秒単位
                changeTimeText.setText("変化時間: " + changeTime/1000.0 + "秒");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 暗い時間割合スライダー
        darkRatioSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                darkRatio = progress;
                darkRatioText.setText("暗い時間割合: " + darkRatio + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        // 開始ボタン
        startButton.setOnClickListener(v -> startLEDControl());

        // 停止ボタン
        stopButton.setOnClickListener(v -> stopLEDControl());
    }

    private void startLEDControl() {
        if (isRunning) return;

        isRunning = true;
        startButton.setEnabled(false);
        stopButton.setEnabled(true);

        // LED制御のRunnableを開始
        ledRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;

                try {
                    // パラメータを計算
                    int brightChangeTime = Math.min(changeTime, loopTime / 2);
                    int darkChangeTime = Math.min(changeTime, loopTime / 2);

                    // 暗い時間と明るい時間の割合を計算
                    int darkTime = (int) (loopTime * darkRatio / 100.0);
                    int brightTime = loopTime - darkTime;

                    // 明るくする
                    setLEDIntensity(0, maxBrightness, brightChangeTime, 10);
                    // 暗くする
                    setLEDIntensity(maxBrightness, 0, darkChangeTime, 10);

                    // 次のループをスケジュール
                    handler.postDelayed(this, loopTime);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "LED制御エラー: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    stopLEDControl();
                }
            }
        };

        handler.post(ledRunnable);
    }

    private void stopLEDControl() {
        isRunning = false;
        if (ledRunnable != null) {
            handler.removeCallbacks(ledRunnable);
        }
        startButton.setEnabled(true);
        stopButton.setEnabled(false);

        // LEDをオフ
        setLEDIntensity(0, 0, 100, 10);
    }

    private void setLEDIntensity(int start, int end, int duration, int steps) {
        if (duration <= 0 || steps <= 0) return;

        int step = (end - start) / steps;
        int interval = duration / steps;

        for (int i = 0; i < steps; i++) {
            final int brightness = start + (step * i);
            try {
                writeLED(brightness);
                try {
                    Thread.sleep(interval);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (IOException e) {
                Toast.makeText(this, "LED書き込みエラー: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                return;
            }
        }
    }

    private void writeLED(int brightness) throws IOException {
        Process process = Runtime.getRuntime().exec("su");
        DataOutputStream os = new DataOutputStream(process.getOutputStream());
        os.writeBytes("echo " + brightness + " > " + LED_PATH + "\n");
        os.writeBytes("exit\n");
        os.flush();
        process.waitFor();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isRunning) {
            stopLEDControl();
        }
    }
}