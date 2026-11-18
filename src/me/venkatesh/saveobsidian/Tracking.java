package me.venkatesh.saveobsidian;

import android.content.Context;
import org.json.JSONObject;

/** Correlates private capture UUIDs with Markdown entries without storing their contents. */
final class Tracking {
    private Tracking() { }
    private static String entryKey(InboxFile.Entry e) { return Metrics.id(e.time + "|" + e.url + "|" + (e.text ? e.body : "")); }
    static String capture(Context c, InboxFile.Entry e) {
        return c.getSharedPreferences("metrics_capture_ids", 0).getString(entryKey(e), "legacy-" + entryKey(e));
    }
    static void linked(Context c, JSONObject job, String entry) {
        if (!Metrics.enabled()) return;
        for (InboxFile.Entry e : InboxFile.parse(entry)) c.getSharedPreferences("metrics_capture_ids", 0).edit().putString(entryKey(e), job.optString("captureId")).putLong(entryKey(e) + ".at", System.currentTimeMillis()).apply();
    }
    static void prune(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("metrics_capture_ids", 0);
        android.content.SharedPreferences.Editor edit = p.edit();
        for (java.util.Map.Entry<String, ?> e : p.getAll().entrySet()) if (e.getKey().endsWith(".at") && e.getValue() instanceof Long && (Long) e.getValue() < System.currentTimeMillis() - 90L * 86400000)
            edit.remove(e.getKey()).remove(e.getKey().substring(0, e.getKey().length() - 3));
        edit.apply();
    }
    static Metrics.Operation capture(Context c, InboxFile.Entry e, String stage) { return Metrics.operation("Capture processing", scope(c, capture(c, e)), stage); }
    static String scope(Context c, String key) { return Metrics.id(VaultStore.tree(c)) + ":" + key; }
    static void accepted(Context c, JSONObject job) {
        String id = job.optString("captureId"); Metrics.event("capture_accepted", id);
        try (Metrics.Operation op = Metrics.operation(DirectCapture.direct(job) ? "Direct capture" : "Capture processing", scope(c, id), "local capture")) { op.outcome("queued"); }
    }
}
