package com.example.nothing1glyphcontroller;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }

        boolean shouldRun =
                context.getSharedPreferences(
                        MainActivity.PREFS_NAME,
                        Context.MODE_PRIVATE
                ).getBoolean(
                        MainActivity.KEY_RUNNING,
                        false
                );

        if (!shouldRun) {
            return;
        }

        Intent serviceIntent =
                new Intent(
                        context,
                        LEDControlService.class
                );

        serviceIntent.setAction(
                MainActivity.ACTION_START
        );

        ContextCompat.startForegroundService(
                context,
                serviceIntent
        );
    }
}