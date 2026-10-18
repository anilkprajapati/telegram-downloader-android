package com.harry.tgdownloader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Asks GitHub for the newest release and, if it is newer than this app, offers to download and install it. */
public class UpdateChecker {

    private static final String LATEST_RELEASE =
            "https://api.github.com/repos/anilkprajapati/telegram-downloader-android/releases/latest";

    /** Checks in the background. Shows a dialog only when a newer version exists. */
    public static void check(Activity activity, boolean showIfUpToDate) {
        new Thread(() -> {
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(LATEST_RELEASE).openConnection();
                connection.setRequestProperty("Accept", "application/vnd.github+json");
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                if (connection.getResponseCode() != 200) {
                    report(activity, "Update check failed (" + connection.getResponseCode() + ").", showIfUpToDate);
                    return;
                }
                JSONObject release = new JSONObject(readAll(connection.getInputStream()));
                String tag = release.getString("tag_name");
                int latestBuild = buildNumberOf(tag);
                if (latestBuild <= BuildConfig.VERSION_CODE) {
                    report(activity, "You have the latest version.", showIfUpToDate);
                    return;
                }
                JSONArray assets = release.getJSONArray("assets");
                if (assets.length() == 0) {
                    report(activity, "A new version exists but has no APK yet.", showIfUpToDate);
                    return;
                }
                JSONObject apk = assets.getJSONObject(0);
                String url = apk.getString("browser_download_url");
                String name = apk.getString("name");
                activity.runOnUiThread(() -> askToInstall(activity, tag, url, name));
            } catch (Exception e) {
                report(activity, "Update check failed: " + e.getMessage(), showIfUpToDate);
            }
        }).start();
    }

    private static void report(Activity activity, String message, boolean show) {
        if (show) {
            activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_LONG).show());
        }
    }

    /** Tags look like "v1.1-build7". Returns 7. */
    private static int buildNumberOf(String tag) {
        int at = tag.lastIndexOf("build");
        if (at < 0) {
            return 0;
        }
        return Integer.parseInt(tag.substring(at + 5).replaceAll("[^0-9]", ""));
    }

    private static void askToInstall(Activity activity, String tag, String url, String name) {
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage("A new version (" + tag + ") is available. Download and install it now?")
                .setPositiveButton("Install", (dialog, which) -> download(activity, url, name))
                .setNegativeButton("Later", null)
                .show();
    }

    private static void download(Activity activity, String url, String name) {
        DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setTitle("Telegram Downloader update");
        request.setDestinationInExternalFilesDir(activity, Environment.DIRECTORY_DOWNLOADS, name);
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        final long downloadId = manager.enqueue(request);
        Toast.makeText(activity, "Downloading the update...", Toast.LENGTH_LONG).show();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long finished = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (finished != downloadId) {
                    return;
                }
                context.unregisterReceiver(this);
                Uri apkUri = manager.getUriForDownloadedFile(downloadId);
                if (apkUri == null) {
                    return;
                }
                Intent install = new Intent(Intent.ACTION_VIEW);
                install.setDataAndType(apkUri, "application/vnd.android.package-archive");
                install.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(install);
            }
        };
        activity.registerReceiver(receiver, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        in.close();
        return out.toString("UTF-8");
    }
}
