package com.harry.tgdownloader;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

/** Keeps the app running in the background so downloads continue with the screen off. */
public class DownloadService extends Service {

    private static final String CHANNEL_ID = "tg_downloads";

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW);
        manager.createNotificationChannel(channel);
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Telegram Downloader")
                .setContentText("Keeping downloads running")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
        startForeground(1, notification);

        SharedPreferences settings = getSharedPreferences("tg_settings", MODE_PRIVATE);
        String apiId = settings.getString("api_id", "");
        String apiHash = settings.getString("api_hash", "");
        if (!apiId.isEmpty() && !apiHash.isEmpty()) {
            TgManager.get().init(getApplicationContext(), apiId, apiHash);
            boolean startDownloads = intent == null
                    ? settings.getBoolean("auto_start", false)
                    : intent.getBooleanExtra("start_downloads", false);
            if (startDownloads) {
                TgManager.get().startDownloads();
            }
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
