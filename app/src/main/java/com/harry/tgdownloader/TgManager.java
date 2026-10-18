package com.harry.tgdownloader;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.MediaStore;

import org.drinkless.tdlib.Client;
import org.drinkless.tdlib.TdApi;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Talks to Telegram through TDLib: login, reading chats, forwarding copies to Saved Messages, downloading. */
public class TgManager {

    public interface Listener {
        void onLog(String message);
        void onAuthState(String state);
    }

    private static TgManager instance;

    public static synchronized TgManager get() {
        if (instance == null) {
            instance = new TgManager();
        }
        return instance;
    }

    private static class Item {
        final int fileId;
        final String name;
        final String key;
        int lastPercent = -10;

        Item(int fileId, String name, String key) {
            this.fileId = fileId;
            this.name = name;
            this.key = key;
        }
    }

    private Context appContext;
    private SharedPreferences settings;
    private Client client;
    private Listener listener;
    private String apiId = "";
    private String apiHash = "";
    private String authState = "none";
    private boolean running = false;
    private boolean pendingStart = false;
    private long myUserId = 0;
    private final Map<Long, String> chats = new LinkedHashMap<>();
    private final ArrayDeque<Item> queue = new ArrayDeque<>();
    private Item active = null;
    private final Set<String> downloaded = new HashSet<>();
    private final Set<String> inFlight = new HashSet<>();

    private TgManager() {
    }

    public synchronized void init(Context context, String apiId, String apiHash) {
        this.appContext = context.getApplicationContext();
        this.settings = appContext.getSharedPreferences("tg_settings", Context.MODE_PRIVATE);
        this.apiId = apiId;
        this.apiHash = apiHash;
        SharedPreferences done = appContext.getSharedPreferences("tg_done", Context.MODE_PRIVATE);
        downloaded.addAll(done.getStringSet("ids", new HashSet<String>()));
        if (client == null) {
            client = Client.create(this::onUpdate, null, null);
        }
    }

    public void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onAuthState(authState);
        }
    }

    public String getAuthState() {
        return authState;
    }

    public boolean isRunning() {
        return running;
    }

    private void log(String message) {
        if (listener != null) {
            listener.onLog(message);
        }
    }

    private void setAuthState(String state) {
        authState = state;
        if (listener != null) {
            listener.onAuthState(state);
        }
    }

    // ---------- login steps, called from the screen ----------

    public void sendPhone(String phone) {
        TdApi.SetAuthenticationPhoneNumber request = new TdApi.SetAuthenticationPhoneNumber();
        request.phoneNumber = phone;
        client.send(request, result -> report("Phone sent", result));
    }

    public void sendCode(String code) {
        TdApi.CheckAuthenticationCode request = new TdApi.CheckAuthenticationCode();
        request.code = code;
        client.send(request, result -> report("Code checked", result));
    }

    public void sendPassword(String password) {
        TdApi.CheckAuthenticationPassword request = new TdApi.CheckAuthenticationPassword();
        request.password = password;
        client.send(request, result -> report("Password checked", result));
    }

    public synchronized void logOut() {
        stopDownloads();
        client.send(new TdApi.LogOut(), result -> report("Logged out", result));
    }

    private void report(String ok, TdApi.Object result) {
        if (result instanceof TdApi.Error) {
            log("Error: " + ((TdApi.Error) result).message);
        } else {
            log(ok);
        }
    }

    // ---------- start and stop ----------

    /** Starts downloading from the chats and with the filters saved on the screen. */
    public synchronized void startDownloads() {
        if (!"ready".equals(authState)) {
            pendingStart = true;
            log("Log in first. Downloads start after login.");
            return;
        }
        if (myUserId == 0) {
            fetchMe();
            pendingStart = true;
            return;
        }
        pendingStart = false;
        running = true;
        chats.clear();
        List<String> names = new ArrayList<>();
        for (String part : settings.getString("chats", "").split("[,\\n]")) {
            if (!part.trim().isEmpty()) {
                names.add(part.trim());
            }
        }
        if (names.isEmpty()) {
            log("Add at least one group or channel @username on the screen.");
            running = false;
            return;
        }
        log("Starting. Looking up " + names.size() + " chat(s)...");
        resolveChat(names, 0);
    }

    public synchronized void stopDownloads() {
        running = false;
        pendingStart = false;
        if (active != null) {
            TdApi.CancelDownloadFile cancel = new TdApi.CancelDownloadFile();
            cancel.fileId = active.fileId;
            cancel.onlyIfPending = false;
            client.send(cancel, result -> { });
        }
        queue.clear();
        inFlight.clear();
        active = null;
        log("Downloads stopped.");
    }

    private void fetchMe() {
        client.send(new TdApi.GetMe(), result -> {
            if (result instanceof TdApi.User) {
                synchronized (TgManager.this) {
                    myUserId = ((TdApi.User) result).id;
                }
                if (pendingStart) {
                    startDownloads();
                }
            } else {
                log("Could not read your account: " + ((TdApi.Error) result).message);
            }
        });
    }

    // ---------- chats ----------

    private void resolveChat(List<String> names, int index) {
        if (index >= names.size()) {
            List<Long> ids = new ArrayList<>(chats.keySet());
            if (settings.getBoolean("from_now", false)) {
                log("Watching " + ids.size() + " chat(s) for new files only.");
            } else {
                scanChat(ids, 0);
            }
            return;
        }
        String username = names.get(index).replace("https://t.me/", "").replace("t.me/", "").replace("@", "");
        TdApi.SearchPublicChat search = new TdApi.SearchPublicChat();
        search.username = username;
        client.send(search, result -> {
            synchronized (TgManager.this) {
                if (result instanceof TdApi.Error) {
                    log("Chat not found: @" + username + " (" + ((TdApi.Error) result).message + ")");
                } else {
                    TdApi.Chat chat = (TdApi.Chat) result;
                    chats.put(chat.id, chat.title);
                    log("Watching: " + chat.title);
                }
            }
            resolveChat(names, index + 1);
        });
    }

    private void scanChat(List<Long> ids, int index) {
        if (index >= ids.size()) {
            log("Finished checking old files.");
            return;
        }
        loadHistory(ids, index, ids.get(index), 0);
    }

    private void loadHistory(List<Long> ids, int index, long chatId, long fromMessageId) {
        TdApi.GetChatHistory request = new TdApi.GetChatHistory();
        request.chatId = chatId;
        request.fromMessageId = fromMessageId;
        request.offset = 0;
        request.limit = 50;
        request.onlyLocal = false;
        client.send(request, result -> {
            if (!(result instanceof TdApi.Messages)) {
                scanChat(ids, index + 1);
                return;
            }
            TdApi.Messages page = (TdApi.Messages) result;
            if (page.messages.length == 0) {
                scanChat(ids, index + 1);
                return;
            }
            long lower = lowerBound();
            boolean reachedOld = false;
            long lastId = 0;
            for (TdApi.Message message : page.messages) {
                lastId = message.id;
                if (lower > 0 && message.date < lower) {
                    reachedOld = true;
                    continue;
                }
                process(message, chatId);
            }
            if (reachedOld) {
                scanChat(ids, index + 1);
            } else {
                loadHistory(ids, index, chatId, lastId);
            }
        });
    }

    // ---------- TDLib updates ----------

    private void onUpdate(TdApi.Object object) {
        if (object instanceof TdApi.UpdateAuthorizationState) {
            handleAuth(((TdApi.UpdateAuthorizationState) object).authorizationState);
        } else if (object instanceof TdApi.UpdateNewMessage) {
            TdApi.Message message = ((TdApi.UpdateNewMessage) object).message;
            if (running && chats.containsKey(message.chatId)) {
                process(message, message.chatId);
            }
        } else if (object instanceof TdApi.UpdateFile) {
            handleFile(((TdApi.UpdateFile) object).file);
        }
    }

    private void handleAuth(TdApi.AuthorizationState state) {
        if (state instanceof TdApi.AuthorizationStateWaitTdlibParameters) {
            TdApi.SetTdlibParameters parameters = new TdApi.SetTdlibParameters();
            parameters.useTestDc = false;
            parameters.databaseDirectory = new File(appContext.getFilesDir(), "tdlib-db").getAbsolutePath();
            parameters.filesDirectory = new File(appContext.getFilesDir(), "tdlib-files").getAbsolutePath();
            parameters.useFileDatabase = true;
            parameters.useChatInfoDatabase = true;
            parameters.useMessageDatabase = true;
            parameters.useSecretChats = false;
            parameters.apiId = Integer.parseInt(apiId.trim());
            parameters.apiHash = apiHash.trim();
            parameters.systemLanguageCode = "en";
            parameters.deviceModel = "Android";
            parameters.systemVersion = "Android";
            parameters.applicationVersion = "1.1";
            client.send(parameters, result -> { });
        } else if (state instanceof TdApi.AuthorizationStateWaitPhoneNumber) {
            setAuthState("phone");
        } else if (state instanceof TdApi.AuthorizationStateWaitCode) {
            setAuthState("code");
        } else if (state instanceof TdApi.AuthorizationStateWaitPassword) {
            setAuthState("password");
        } else if (state instanceof TdApi.AuthorizationStateReady) {
            setAuthState("ready");
            log("Logged in.");
            fetchMe();
        } else if (state instanceof TdApi.AuthorizationStateClosed) {
            setAuthState("closed");
        }
    }

    // ---------- filters ----------

    private void process(TdApi.Message message, long chatId) {
        synchronized (this) {
            if (!running) {
                return;
            }
            TdApi.File file = fileOf(message.content);
            if (file == null) {
                return;
            }
            if (!passesDate(message.date) || !passesSize(file)) {
                return;
            }
            String name = nameOf(message.content, message.id);
            String key = keyOf(file);
            if (downloaded.contains(key) || inFlight.contains(key)) {
                return;
            }
            inFlight.add(key);
            forwardCopy(message, chatId, file, name, key);
        }
    }

    private long startOfToday() {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis() / 1000L;
    }

    private long lowerBound() {
        if (settings.getBoolean("today_only", true)) {
            return startOfToday();
        }
        return secondsOf(settings.getString("from_dt", ""), false);
    }

    private boolean passesDate(long date) {
        long dayStart = startOfToday();
        long dayEnd = dayStart + 86400L;
        if (settings.getBoolean("today_only", true) && (date < dayStart || date >= dayEnd)) {
            return false;
        }
        long from = secondsOf(settings.getString("from_dt", ""), false);
        long to = secondsOf(settings.getString("to_dt", ""), true);
        if (from > 0 && date < from) {
            return false;
        }
        return !(to > 0 && date >= to);
    }

    private boolean passesSize(TdApi.File file) {
        double megabytes = file.size / 1e6;
        double min = parseMb(settings.getString("min_mb", ""));
        double max = parseMb(settings.getString("max_mb", ""));
        if (min > 0 && megabytes < min) {
            return false;
        }
        return !(max > 0 && megabytes > max);
    }

    private static double parseMb(String text) {
        try {
            return Double.parseDouble(text.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Date text: "2026-10-16" or "2026-10-16 14:30". A date alone means the whole day. */
    private static long secondsOf(String text, boolean endOfDay) {
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            return 0;
        }
        try {
            if (t.length() > 10) {
                return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(t).getTime() / 1000L;
            }
            long start = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(t).getTime() / 1000L;
            return endOfDay ? start + 86400L : start;
        } catch (Exception e) {
            return 0;
        }
    }

    // ---------- copy to Saved Messages, then download ----------

    private void forwardCopy(TdApi.Message message, long chatId, TdApi.File file, String name, String key) {
        TdApi.ForwardMessages request = new TdApi.ForwardMessages();
        request.chatId = myUserId;
        request.fromChatId = chatId;
        request.messageIds = new long[]{message.id};
        client.send(request, result -> {
            synchronized (TgManager.this) {
                if (result instanceof TdApi.Error) {
                    log("Skipped (cannot copy to Saved Messages): " + name + " - "
                            + ((TdApi.Error) result).message);
                    inFlight.remove(key);
                    return;
                }
                log("Copied to Saved Messages: " + name);
                if (settings.getBoolean("forward_only", false)) {
                    downloaded.add(key);
                    persistDone();
                    inFlight.remove(key);
                    return;
                }
                TdApi.File copyFile = file;
                TdApi.Messages copies = (TdApi.Messages) result;
                if (copies.messages.length > 0 && copies.messages[0] != null) {
                    TdApi.File f = fileOf(copies.messages[0].content);
                    if (f != null) {
                        copyFile = f;
                    }
                }
                queue.add(new Item(copyFile.id, name, key));
                pump();
            }
        });
    }

    private synchronized void pump() {
        if (!running || active != null || queue.isEmpty()) {
            return;
        }
        final Item started = queue.poll();
        active = started;
        log("Downloading: " + started.name);
        TdApi.DownloadFile request = new TdApi.DownloadFile();
        request.fileId = started.fileId;
        request.priority = 32;
        request.offset = 0;
        request.limit = 0;
        request.synchronous = false;
        client.send(request, result -> {
            if (result instanceof TdApi.Error) {
                synchronized (TgManager.this) {
                    log("Download failed: " + started.name + " - " + ((TdApi.Error) result).message);
                    inFlight.remove(started.key);
                    if (active == started) {
                        active = null;
                    }
                    pump();
                }
            }
        });
    }

    private synchronized void handleFile(TdApi.File file) {
        if (active == null || file.id != active.fileId || file.local == null) {
            return;
        }
        if (file.size > 0) {
            int percent = (int) (file.local.downloadedSize * 100L / file.size);
            if (percent >= active.lastPercent + 10 && !file.local.isDownloadingCompleted) {
                active.lastPercent = percent;
                log("Downloading " + active.name + ": " + percent + "%");
            }
        }
        if (file.local.isDownloadingCompleted) {
            Item done = active;
            active = null;
            saveToDownloads(file.local.path, done.name);
            downloaded.add(done.key);
            inFlight.remove(done.key);
            persistDone();
            pump();
        }
    }

    private void persistDone() {
        appContext.getSharedPreferences("tg_done", Context.MODE_PRIVATE).edit()
                .putStringSet("ids", new HashSet<>(downloaded)).apply();
    }

    private void saveToDownloads(String path, String name) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, name);
            values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/TelegramDownloads");
            Uri uri = appContext.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            try (InputStream in = new FileInputStream(path);
                 OutputStream out = appContext.getContentResolver().openOutputStream(uri)) {
                byte[] buffer = new byte[1 << 16];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            log("Saved to Downloads/TelegramDownloads: " + name);
        } catch (Exception e) {
            log("Could not save " + name + ": " + e.getMessage());
        }
    }

    // ---------- helpers ----------

    private static String keyOf(TdApi.File file) {
        if (file.remote != null && file.remote.uniqueId != null && !file.remote.uniqueId.isEmpty()) {
            return file.remote.uniqueId;
        }
        return "id:" + file.id;
    }

    private static TdApi.File fileOf(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessageVideo) {
            return ((TdApi.MessageVideo) content).video.video;
        }
        if (content instanceof TdApi.MessageDocument) {
            return ((TdApi.MessageDocument) content).document.document;
        }
        if (content instanceof TdApi.MessageAudio) {
            return ((TdApi.MessageAudio) content).audio.audio;
        }
        if (content instanceof TdApi.MessageAnimation) {
            return ((TdApi.MessageAnimation) content).animation.animation;
        }
        if (content instanceof TdApi.MessagePhoto) {
            TdApi.PhotoSize[] sizes = ((TdApi.MessagePhoto) content).photo.sizes;
            return sizes[sizes.length - 1].photo;
        }
        return null;
    }

    private static String nameOf(TdApi.MessageContent content, long messageId) {
        String name = null;
        if (content instanceof TdApi.MessageVideo) {
            name = ((TdApi.MessageVideo) content).video.fileName;
        } else if (content instanceof TdApi.MessageDocument) {
            name = ((TdApi.MessageDocument) content).document.fileName;
        } else if (content instanceof TdApi.MessageAudio) {
            name = ((TdApi.MessageAudio) content).audio.fileName;
        } else if (content instanceof TdApi.MessageAnimation) {
            name = ((TdApi.MessageAnimation) content).animation.fileName;
        }
        if (name == null || name.isEmpty()) {
            name = "photo_" + messageId + ".jpg";
        }
        return name;
    }
}
