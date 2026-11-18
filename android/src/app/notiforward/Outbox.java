package app.notiforward;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Local queue of notifications waiting to be sent. Every notification is stored
 * first and deleted only after the server answers 2xx, so nothing is lost while
 * the computer is off or unreachable.
 */
final class Outbox {
    private static final String TAG = "NotiForward";
    private static final int MAX_QUEUED = 20000;
    private static final int TIMEOUT_MS = 10000;

    static final String PREFS = "settings";
    static final String KEY_URL = "url";
    static final String KEY_TOKEN = "token";
    static final String KEY_LAST_RESULT = "last_result";
    static final String NO_URL = "No server URL set";

    private static Outbox instance;

    private final Context context;
    private final SQLiteOpenHelper db;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    static synchronized Outbox get(Context context) {
        if (instance == null) instance = new Outbox(context.getApplicationContext());
        return instance;
    }

    private Outbox(Context context) {
        this.context = context;
        this.db = new SQLiteOpenHelper(context, "outbox.db", null, 1) {
            @Override
            public void onCreate(SQLiteDatabase d) {
                d.execSQL("CREATE TABLE queue (id INTEGER PRIMARY KEY AUTOINCREMENT, body TEXT NOT NULL)");
            }

            @Override
            public void onUpgrade(SQLiteDatabase d, int oldV, int newV) { }
        };
    }

    SharedPreferences prefs() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Stores a JSON body and kicks off delivery. */
    void enqueue(final String jsonBody) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                SQLiteDatabase d = db.getWritableDatabase();
                ContentValues v = new ContentValues();
                v.put("body", jsonBody);
                d.insert("queue", null, v);
                // Cap the queue so a months-long outage can't fill the phone.
                d.execSQL("DELETE FROM queue WHERE id <= (SELECT MAX(id) FROM queue) - " + MAX_QUEUED);
                flushNow();
            }
        });
    }

    /** Tries to send everything queued, oldest first. */
    void flush() {
        worker.execute(new Runnable() {
            @Override
            public void run() { flushNow(); }
        });
    }

    long queuedCount() {
        return DatabaseUtils.queryNumEntries(db.getReadableDatabase(), "queue");
    }

    /** Runs on the worker thread only. Stops at the first failure and keeps the rest. */
    private void flushNow() {
        String url = prefs().getString(KEY_URL, "").trim();
        if (url.isEmpty()) {
            setLastResult(NO_URL);
            return;
        }
        String token = prefs().getString(KEY_TOKEN, "").trim();
        SQLiteDatabase d = db.getWritableDatabase();
        while (true) {
            Cursor c = d.rawQuery("SELECT id, body FROM queue ORDER BY id LIMIT 1", null);
            long id;
            String body;
            try {
                if (!c.moveToFirst()) return;
                id = c.getLong(0);
                body = c.getString(1);
            } finally {
                c.close();
            }
            String error = post(url, token, body);
            if (error != null) {
                setLastResult("Failed: " + error + " (will retry)");
                Log.w(TAG, "Delivery failed: " + error);
                return;
            }
            d.delete("queue", "id = ?", new String[] { Long.toString(id) });
            setLastResult("OK");
        }
    }

    /** Returns null on success, or a short error description. */
    static String post(String url, String token, String body) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!token.isEmpty()) conn.setRequestProperty("Authorization", "Bearer " + token);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            OutputStream out = conn.getOutputStream();
            try {
                out.write(bytes);
            } finally {
                out.close();
            }
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) return null;
            return "HTTP " + code;
        } catch (Exception e) {
            String msg = e.getMessage();
            return e.getClass().getSimpleName() + (msg != null ? ": " + msg : "");
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private void setLastResult(String result) {
        String stamp = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT)
                .format(new java.util.Date());
        prefs().edit().putString(KEY_LAST_RESULT, stamp + "  " + result).apply();
    }
}
