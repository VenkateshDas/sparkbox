package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Offline dashboard. Loading and export never block the UI or make model calls. */
final class DashboardScreen extends Screen {
    private final DashboardState state = new DashboardState();
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor();
    private Ui ui;
    private boolean visible;
    private long revision;
    private final Runnable refresh = () -> load(false);
    private final ScoutRuns.Watcher watcher = new ScoutRuns.Watcher() {
        @Override public void changed(String f) { main.removeCallbacks(refresh); main.postDelayed(refresh, 200); }
        @Override public void live(String f, String text) { }
    };
    @Override View build(Ui ui) {
        this.ui = ui;
        return DashboardView.create(ui.c, ui, state, new DashboardActions() {
            @Override public void back() { host.getOnBackPressedDispatcher().onBackPressed(); }
            @Override public void refresh() { load(true); }
            @Override public void export(String json) { exportFile(json); }
            @Override public void openNote(String path) { host.openNote(path); }
            @Override public void openSession(String file) { host.push(new SessionUsageScreen(file)); }
            @Override public void settings() { host.push(new SettingsScreen()); }
            @Override public void tracking(boolean enabled) {
                ui.c.getSharedPreferences("metrics", 0).edit().putBoolean("enabled", enabled).apply();
                Metrics.configure(new File(ui.c.getFilesDir(), "metrics.jsonl"), enabled); load(false);
            }
            @Override public void clear() { worker.execute(() -> { Metrics.clear(); ui.c.getSharedPreferences("metrics_capture_ids", 0).edit().clear().apply(); main.post(refresh); }); }
        });
    }
    @Override void onShow() { visible = true; ScoutRuns.watch(watcher); load(false); }
    @Override void onHide() { visible = false; revision++; ScoutRuns.unwatch(watcher); main.removeCallbacks(refresh); }
    private void load(boolean scan) {
        if (!visible || ui == null) return;
        long ticket = ++revision; state.loading(true);
        worker.execute(() -> {
            JSONObject data = new JSONObject();
            try {
                Metrics.prune(); data = Metrics.read();
                JSONArray warnings = new JSONArray();
                if (!data.optString("problem").isEmpty()) warnings.put(data.optString("problem"));
                if (data.optInt("malformed") > 0) warnings.put(data.optInt("malformed") + " unreadable metric records; history is partial.");
                VaultStore.Snap snap = VaultStore.cached(ui.c);
                boolean scanned = false;
                if (scan) try { snap = VaultStore.scan(ui.c); scanned = true; } catch (Exception failure) { warnings.put("Vault refresh failed. Showing the last readable snapshot."); }
                List<Cards.Card> cards = CardStore.all(ui.c);
                if (snap != null) data.put("vault", Dashboard.inventory(snap.graph, cards));
                else warnings.put("Choose a readable vault in Settings. Vault inventory is unavailable.");
                data.put("recall", Dashboard.recall(cards, System.currentTimeMillis(), ZoneId.systemDefault()));
                // Merge legacy session history by the SAME request IDs, never by adding its totals.
                Map<String, JSONObject> requests = new LinkedHashMap<>();
                JSONArray saved = data.optJSONArray("requests");
                for (int i = 0; saved != null && i < saved.length(); i++) { JSONObject r = saved.getJSONObject(i); requests.put(r.optString("requestId"), r); }
                JSONArray sessions = new JSONArray();
                File[] sessionFiles = Scout.dir(ui.c).listFiles((d, n) -> n.endsWith(".jsonl"));
                if (sessionFiles != null) for (File f : sessionFiles) {
                    Session session;
                    try { session = ScoutRuns.session(ui.c, f.getName()); } catch (Exception unreadable) { warnings.put("A Scout session could not be read."); continue; }
                    sessions.put(new JSONObject().put("file", f.getName()).put("id", session.id()).put("name", session.name() == null ? "Scout chat" : session.name()).put("partial", session.totals().optBoolean("partial")));
                    for (JSONObject raw : SessionUsage.requests(session)) {
                        JSONObject r = requests.get(raw.optString("requestId")); if (r == null) r = new JSONObject();
                        for (String key : new String[]{"requestId", "timestamp", "model", "actualModel", "purpose", "run", "status", "attempt", "continuation", "generationId", "provider", "durationMs", "operationId"}) if (raw.has(key)) r.put(key, raw.get(key));
                        if (raw.optJSONObject("usage") != null) r.put("usage", Metrics.usage(raw.optJSONObject("usage")));
                        r.put("workflow", "Scout interaction").put("stage", raw.optString("purpose")).put("sessionId", session.id()).put("sessionFile", f.getName());
                        if (r.optString("status").equals("pending") && !ScoutRuns.sessionRunning(session)) r.put("status", "interrupted");
                        requests.put(r.optString("requestId"), r);
                    }
                }
                data.put("requests", new JSONArray(requests.values())).put("sessions", sessions);
                JSONObject health = new JSONObject().put("pendingCaptures", CaptureService.pending(ui.c).length).put("recoveries", CaptureService.recoveries(ui.c).length)
                    .put("scanFresh", scanned).put("problem", ui.c.getSharedPreferences("capture", 0).getString("aiProblem", ""));
                File cardQueue = new File(ui.c.getFilesDir(), "pending-cards.jsonl");
                int pendingCards = 0;
                if (cardQueue.exists()) try (java.io.BufferedReader reader = Files.newBufferedReader(cardQueue.toPath(), StandardCharsets.UTF_8)) { for (String line; (line = reader.readLine()) != null;) if (!line.trim().isEmpty()) pendingCards++; }
                health.put("pendingCards", pendingCards);
                warnings.put("AI spend is app/device-wide. Vault inventory covers Knowledge/ and legacy Sources/ only.");
                warnings.put("Requests before detailed tracking may be absent or unattributed. Independent tool charges and credit fees are excluded.");
                warnings.put("Recall history retains at most 50 reviews per surviving card. Schedules remain disabled.");
                warnings.put("Queued Scout messages handled in one turn form a shared interaction batch; superseded/batched inputs are excluded from completed-interaction averages.");
                data.put("asOf", System.currentTimeMillis()).put("tracking", Metrics.enabled()).put("warnings", warnings).put("health", health);
            } catch (Exception failure) { try { data.put("error", "Dashboard could not finish loading. Your notes and captures are unchanged."); } catch (Exception ignored) { } }
            String json = data.toString();
            main.post(() -> { if (visible && ticket == revision) { state.publish(json); state.loading(false); } });
        });
    }
    private void exportFile(String json) {
        worker.execute(() -> {
            try {
                // Same private FileProvider location as existing chat exports; no new permissions.
                File dir = new File(ui.c.getCacheDir(), "scout-exports"); if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException();
                File out = new File(dir, "sparkbox-dashboard.json"); Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
                main.post(() -> {
                    if (!visible) return;
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(ui.c, ui.c.getPackageName() + ".scout.exports", out);
                    host.startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Export dashboard"));
                });
            } catch (Exception failure) { main.post(() -> Toast.makeText(ui.c, "Could not export statistics", Toast.LENGTH_LONG).show()); }
        });
    }
}
