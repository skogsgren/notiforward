package app.notiforward;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Receives every notification posted on the phone and hands it to the Outbox.
 * Android keeps this service running by itself once Notification Access is granted.
 */
public class ForwardService extends NotificationListenerService {
    private static final long RETRY_MS = 60_000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            Outbox.get(ForwardService.this).flush();
            handler.postDelayed(this, RETRY_MS);
        }
    };

    /** Last content seen per notification key, so silent updates aren't sent twice. */
    private final Map<String, Integer> lastSeen = new LinkedHashMap<String, Integer>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
            return size() > 500;
        }
    };

    @Override
    public void onListenerConnected() {
        handler.removeCallbacks(retry);
        handler.post(retry);
    }

    // Added in API 24; no @Override because we compile against API 23, but Android still calls it.
    public void onListenerDisconnected() {
        handler.removeCallbacks(retry);
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn.getPackageName().equals(getPackageName())) return;
        Notification n = sbn.getNotification();
        if (n == null) return;
        // Skip persistent notifications (music players, downloads, VPN) and group summaries,
        // which repeat the individual notifications.
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;

        Bundle extras = n.extras;
        String title = str(extras, Notification.EXTRA_TITLE);
        String text = str(extras, Notification.EXTRA_TEXT);
        String bigText = str(extras, Notification.EXTRA_BIG_TEXT);
        String subText = str(extras, Notification.EXTRA_SUB_TEXT);
        String lines = joinLines(extras);
        if (title == null && text == null && bigText == null && lines == null) return;

        int contentHash = (title + "\u0000" + text + "\u0000" + bigText + "\u0000" + lines).hashCode();
        synchronized (lastSeen) {
            Integer previous = lastSeen.put(sbn.getKey(), contentHash);
            if (previous != null && previous == contentHash) return;
        }

        try {
            JSONObject json = new JSONObject();
            json.put("app", sbn.getPackageName());
            json.put("app_name", appLabel(sbn.getPackageName()));
            json.put("title", title);
            json.put("text", text);
            json.put("big_text", bigText);
            json.put("sub_text", subText);
            json.put("lines", lines);
            json.put("category", n.category);
            json.put("posted_at_ms", sbn.getPostTime());
            json.put("key", sbn.getKey());
            json.put("device", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            Outbox.get(this).enqueue(json.toString());
        } catch (Exception ignored) {
            // JSONObject.put only throws for non-finite numbers, which can't happen here.
        }
    }

    private static String str(Bundle extras, String key) {
        if (extras == null) return null;
        CharSequence cs = extras.getCharSequence(key);
        if (cs == null) return null;
        String s = cs.toString().trim();
        return s.isEmpty() ? null : s;
    }

    /** Inbox-style notifications (e.g. several emails) keep their content in text lines. */
    private static String joinLines(Bundle extras) {
        if (extras == null) return null;
        CharSequence[] arr = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (arr == null || arr.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : arr) {
            if (cs == null) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(cs);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(info).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }
}
