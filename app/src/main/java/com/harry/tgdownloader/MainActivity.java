package com.harry.tgdownloader;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.Locale;

public class MainActivity extends Activity implements TgManager.Listener {

    private ScrollView outer;
    private TextView statusText;
    private TextView logText;
    private EditText apiIdBox;
    private EditText apiHashBox;
    private EditText inputBox;
    private EditText chatsBox;
    private CheckBox todayOnly;
    private CheckBox fromNow;
    private CheckBox forwardOnly;
    private CheckBox autoStart;
    private EditText fromBox;
    private EditText toBox;
    private EditText minBox;
    private EditText maxBox;
    private SharedPreferences prefs;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("tg_settings", MODE_PRIVATE);

        outer = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 160, 32, 32);

        TextView version = new TextView(this);
        version.setText("Version " + BuildConfig.VERSION_NAME + " (build " + BuildConfig.VERSION_CODE + ")");
        version.setTextSize(14);
        root.addView(version);

        Button top = new Button(this);
        top.setText("Scroll to top");
        top.setOnClickListener(v -> outer.smoothScrollTo(0, 0));
        root.addView(top);

        Button update = new Button(this);
        update.setText("Check for update");
        update.setOnClickListener(v -> {
            tell("Checking for update...");
            UpdateChecker.check(this, true);
        });
        root.addView(update);

        statusText = new TextView(this);
        statusText.setTextSize(16);
        root.addView(statusText);

        root.addView(section("Account"));
        apiIdBox = field("API ID (from my.telegram.org)");
        apiIdBox.setText(prefs.getString("api_id", ""));
        root.addView(apiIdBox);
        apiHashBox = field("API hash");
        apiHashBox.setText(prefs.getString("api_hash", ""));
        root.addView(apiHashBox);
        Button connect = new Button(this);
        connect.setText("Connect");
        connect.setOnClickListener(v -> connect());
        root.addView(connect);
        inputBox = field("Phone (with country code)");
        root.addView(inputBox);
        Button next = new Button(this);
        next.setText("Next");
        next.setOnClickListener(v -> next());
        root.addView(next);

        root.addView(section("Groups and channels"));
        chatsBox = field("@group1, @channel2 (public usernames)");
        chatsBox.setSingleLine(false);
        chatsBox.setText(prefs.getString("chats", ""));
        root.addView(chatsBox);

        root.addView(section("Options"));
        todayOnly = check("Only today's files", prefs.getBoolean("today_only", true));
        root.addView(todayOnly);
        fromNow = check("Only files posted from now on (skip old files)", prefs.getBoolean("from_now", false));
        root.addView(fromNow);
        forwardOnly = check("Only copy to Saved Messages (do not download to phone)",
                prefs.getBoolean("forward_only", false));
        root.addView(forwardOnly);
        autoStart = check("Start automatically when the phone turns on", prefs.getBoolean("auto_start", false));
        root.addView(autoStart);

        root.addView(section("Date and time"));
        fromBox = dateField("From (YYYY-MM-DD HH:mm)", prefs.getString("from_dt", ""), root);
        toBox = dateField("To (YYYY-MM-DD HH:mm)", prefs.getString("to_dt", ""), root);

        root.addView(section("Size (MB), leave empty for no limit"));
        minBox = field("Minimum MB");
        minBox.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        minBox.setText(prefs.getString("min_mb", ""));
        root.addView(minBox);
        maxBox = field("Maximum MB");
        maxBox.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxBox.setText(prefs.getString("max_mb", ""));
        root.addView(maxBox);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(v -> start());
        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(v -> {
            tell("Stopping downloads...");
            TgManager.get().stopDownloads();
            updateButtons();
        });
        Button logout = new Button(this);
        logout.setText("Logout");
        logout.setOnClickListener(v -> {
            tell("Logging out...");
            TgManager.get().logOut();
            updateButtons();
        });
        startButton = start;
        stopButton = stop;
        buttons.addView(start);
        buttons.addView(stop);
        buttons.addView(logout);
        root.addView(buttons);

        Button quit = new Button(this);
        quit.setText("Quit");
        quit.setOnClickListener(v -> {
            tell("Quitting...");
            TgManager.get().stopDownloads();
            stopService(new Intent(this, DownloadService.class));
            finishAffinity();
        });
        root.addView(quit);

        logText = new TextView(this);
        logText.setGravity(Gravity.TOP);
        root.addView(logText);

        outer.addView(root);
        setContentView(outer);
        plainButtons(root);
        outer.post(() -> outer.scrollTo(0, 0));
        setTitle("Telegram Downloader " + BuildConfig.VERSION_NAME);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        String savedId = prefs.getString("api_id", "");
        String savedHash = prefs.getString("api_hash", "");
        if (!savedId.isEmpty() && !savedHash.isEmpty()) {
            TgManager.get().init(getApplicationContext(), savedId, savedHash);
        }
        UpdateChecker.check(this, false);
        onAuthState(TgManager.get().getAuthState());
    }

    private TextView section(String title) {
        TextView view = new TextView(this);
        view.setText(title);
        view.setTextSize(18);
        view.setPadding(0, 32, 0, 8);
        return view;
    }

    private EditText field(String hint) {
        EditText box = new EditText(this);
        box.setHint(hint);
        box.setSingleLine(true);
        return box;
    }

    private CheckBox check(String text, boolean checked) {
        CheckBox box = new CheckBox(this);
        box.setText(text);
        box.setChecked(checked);
        return box;
    }

    /** A text box for a date and time, with a Pick button that opens the calendar and clock. */
    private EditText dateField(String hint, String value, LinearLayout root) {
        EditText box = field(hint);
        box.setText(value);
        root.addView(box);
        Button pick = new Button(this);
        pick.setText("Pick date and time");
        pick.setOnClickListener(v -> pickDateTime(box));
        root.addView(pick);
        return box;
    }

    private void pickDateTime(EditText target) {
        Calendar now = Calendar.getInstance();
        new DatePickerDialog(this, (view, year, month, day) -> new TimePickerDialog(this,
                (tv, hour, minute) -> target.setText(String.format(Locale.US,
                        "%04d-%02d-%02d %02d:%02d", year, month + 1, day, hour, minute)),
                now.get(Calendar.HOUR_OF_DAY), now.get(Calendar.MINUTE), true).show(),
                now.get(Calendar.YEAR), now.get(Calendar.MONTH), now.get(Calendar.DAY_OF_MONTH)).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TgManager.get().setListener(this);
        updateButtons();
    }

    @Override
    protected void onPause() {
        TgManager.get().setListener(null);
        super.onPause();
    }

    private void saveSettings() {
        prefs.edit()
                .putString("api_id", apiIdBox.getText().toString().trim())
                .putString("api_hash", apiHashBox.getText().toString().trim())
                .putString("chats", chatsBox.getText().toString().trim())
                .putBoolean("today_only", todayOnly.isChecked())
                .putBoolean("from_now", fromNow.isChecked())
                .putBoolean("forward_only", forwardOnly.isChecked())
                .putBoolean("auto_start", autoStart.isChecked())
                .putString("from_dt", fromBox.getText().toString().trim())
                .putString("to_dt", toBox.getText().toString().trim())
                .putString("min_mb", minBox.getText().toString().trim())
                .putString("max_mb", maxBox.getText().toString().trim())
                .apply();
    }

    /** Removes the highlight and press animation that stay on buttons after a tap. */
    private void plainButtons(android.view.View view) {
        if (view instanceof Button) {
            view.setFocusable(false);
            view.setFocusableInTouchMode(false);
            view.setStateListAnimator(null);
        } else if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                plainButtons(group.getChildAt(i));
            }
        }
    }

    /** Start is locked while downloads run; Stop unlocks it. */
    private void updateButtons() {
        boolean running = TgManager.get().isRunning();
        if (startButton != null) {
            startButton.setEnabled(!running);
        }
        if (stopButton != null) {
            stopButton.setEnabled(running);
        }
    }

    private void tell(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void connect() {
        tell("Connecting...");
        saveSettings();
        String apiId = apiIdBox.getText().toString().trim();
        String apiHash = apiHashBox.getText().toString().trim();
        if (apiId.isEmpty() || apiHash.isEmpty()) {
            appendLog("Enter the API ID and API hash first.");
            return;
        }
        startService(false);
        TgManager.get().init(getApplicationContext(), apiId, apiHash);
        TgManager.get().setListener(this);
        appendLog("Connecting...");
    }

    private void next() {
        tell("Sending...");
        String text = inputBox.getText().toString().trim();
        String state = TgManager.get().getAuthState();
        if ("phone".equals(state)) {
            TgManager.get().sendPhone(text);
        } else if ("code".equals(state)) {
            TgManager.get().sendCode(text);
        } else if ("password".equals(state)) {
            TgManager.get().sendPassword(text);
        } else {
            appendLog("Press Connect first.");
        }
        inputBox.setText("");
    }

    private void start() {
        tell("Starting downloads...");
        saveSettings();
        String apiId = apiIdBox.getText().toString().trim();
        String apiHash = apiHashBox.getText().toString().trim();
        if (apiId.isEmpty() || apiHash.isEmpty()) {
            appendLog("Enter the API ID and API hash first.");
            return;
        }
        startService(true);
        TgManager.get().init(getApplicationContext(), apiId, apiHash);
        TgManager.get().startDownloads();
        updateButtons();
    }

    /** Keeps the app running in the background, so downloads continue with the screen off. */
    private void startService(boolean keepDownloading) {
        Intent service = new Intent(this, DownloadService.class);
        service.putExtra("start_downloads", keepDownloading);
        startForegroundService(service);
    }

    @Override
    public void onLog(String message) {
        runOnUiThread(() -> {
            appendLog(message);
            updateButtons();
        });
    }

    @Override
    public void onAuthState(String state) {
        runOnUiThread(() -> {
            switch (state) {
                case "phone":
                    statusText.setText("Enter your phone number, then press Next");
                    inputBox.setHint("Phone (with country code)");
                    inputBox.setInputType(InputType.TYPE_CLASS_PHONE);
                    break;
                case "code":
                    statusText.setText("Enter the code Telegram sent you, then press Next");
                    inputBox.setHint("Login code");
                    inputBox.setInputType(InputType.TYPE_CLASS_NUMBER);
                    break;
                case "password":
                    statusText.setText("Enter your 2-step password, then press Next");
                    inputBox.setHint("2-step password");
                    inputBox.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                    break;
                case "ready":
                    statusText.setText("Logged in. Choose groups and options, then press Start.");
                    break;
                default:
                    statusText.setText("Not connected. Enter API details and press Connect.");
            }
        });
    }

    private void appendLog(String message) {
        logText.append(message + "\n");
    }
}
