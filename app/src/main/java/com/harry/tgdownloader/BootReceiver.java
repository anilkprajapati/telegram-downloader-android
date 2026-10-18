package com.harry.tgdownloader;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/** Starts the downloader when the phone turns on, if "Start automatically" is checked. */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        SharedPreferences settings = context.getSharedPreferences("tg_settings", Context.MODE_PRIVATE);
        if (settings.getBoolean("auto_start", false) && !settings.getString("api_id", "").isEmpty()) {
            Intent service = new Intent(context, DownloadService.class);
            service.putExtra("start_downloads", true);
            context.startForegroundService(service);
        }
    }
}
