package app.notiforward;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** The app's only screen: server URL, optional token, access button, test button, status. */
public class MainActivity extends Activity {
    private EditText urlField;
    private EditText tokenField;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        root.addView(label("Server URL"));
        urlField = new EditText(this);
        urlField.setHint("http://100.x.y.z:8000/notify");
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(urlField);

        root.addView(label("Token"));
        tokenField = new EditText(this);
        tokenField.setSingleLine(true);
        tokenField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(tokenField);

        SharedPreferences prefs = Outbox.get(this).prefs();
        urlField.setText(prefs.getString(Outbox.KEY_URL, ""));
        tokenField.setText(prefs.getString(Outbox.KEY_TOKEN, ""));

        root.addView(button("Save", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save();
                Outbox.get(MainActivity.this).flush();
                refreshStatus("Saved.");
            }
        }));
        root.addView(button("Open notification access settings", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            }
        }));
        root.addView(button("Send test", new View.OnClickListener() {
            @Override
            public void onClick(View v) { sendTest(); }
        }));
        root.addView(button("Refresh status", new View.OnClickListener() {
            @Override
            public void onClick(View v) { refreshStatus(null); }
        }));

        status = new TextView(this);
        status.setPadding(0, pad, 0, 0);
        status.setTextIsSelectable(true);
        root.addView(status);

        TextView help = new TextView(this);
        help.setPadding(0, pad, 0, 0);
        help.setAlpha(0.7f);
        help.setText("If notification access is greyed out: open Settings > Apps > NotiForward, "
                + "tap the ⋮ menu, choose \"Allow restricted settings\", then try again.\n\n"
                + "Ongoing notifications (music, downloads) and group summaries are skipped. "
                + "Notifications are queued on the phone and resent when the server is reachable.");
        root.addView(help);

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus(null);
    }

    private void save() {
        SharedPreferences prefs = Outbox.get(this).prefs();
        String url = urlField.getText().toString().trim();
        if (!url.isEmpty() && !url.contains("://")) {
            url = "http://" + url;
            urlField.setText(url);
        }
        SharedPreferences.Editor edit = prefs.edit()
                .putString(Outbox.KEY_URL, url)
                .putString(Outbox.KEY_TOKEN, tokenField.getText().toString().trim());
        // A "no URL" status from before the URL was entered is stale once one is saved.
        if (!url.isEmpty() && prefs.getString(Outbox.KEY_LAST_RESULT, "").endsWith(Outbox.NO_URL)) {
            edit.remove(Outbox.KEY_LAST_RESULT);
        }
        edit.commit();
    }

    private void sendTest() {
        save();
        final String url = urlField.getText().toString().trim();
        final String token = tokenField.getText().toString().trim();
        if (url.isEmpty()) {
            status.setText("Enter a server URL first.");
            return;
        }
        status.setText("Sending test…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String body;
                try {
                    JSONObject json = new JSONObject();
                    json.put("app", getPackageName());
                    json.put("app_name", "NotiForward");
                    json.put("title", "Test");
                    json.put("text", "Test notification from NotiForward");
                    json.put("posted_at_ms", System.currentTimeMillis());
                    json.put("test", true);
                    body = json.toString();
                } catch (Exception e) {
                    body = "{\"title\":\"Test\"}";
                }
                final String error = Outbox.post(url, token, body);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus("Test: " + (error == null ? "OK, server received it" : "FAILED, " + error));
                    }
                });
            }
        }).start();
    }

    /** Redraws the status; {@code note}, if given, is shown above it so it can't be overwritten. */
    private void refreshStatus(final String note) {
        final boolean access = hasAccess();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final long queued = Outbox.get(MainActivity.this).queuedCount();
                final String last = Outbox.get(MainActivity.this).prefs()
                        .getString(Outbox.KEY_LAST_RESULT, "nothing sent yet");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        status.setText((note != null ? note + "\n\n" : "")
                                + "Notification access: " + (access ? "granted" : "NOT granted")
                                + "\nWaiting in queue: " + queued
                                + "\nLast delivery: " + last);
                    }
                });
            }
        }).start();
    }

    private boolean hasAccess() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        String me = new ComponentName(this, ForwardService.class).flattenToString();
        for (String s : enabled.split(":")) {
            if (s.equals(me)) return true;
        }
        return false;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, 0);
        return t;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(listener);
        return b;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
