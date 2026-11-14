package me.venkatesh.saveobsidian;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;

/** Reads metrics off the UI thread; the chat keeps working while this detail page is open. */
final class SessionUsageScreen extends Screen {
    private final String file;
    private final SessionUsageState state = new SessionUsageState();
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private boolean visible;
    private long revision;
    private final Runnable refresh = this::load;
    private final ScoutRuns.Watcher watcher = new ScoutRuns.Watcher() {
        @Override public void changed(String f) { if (f.isEmpty() || f.equals(file)) { main.removeCallbacks(refresh); main.postDelayed(refresh, 100); } }
        @Override public void live(String f, String text) { }
    };
    SessionUsageScreen(String file) { this.file = file; }

    @Override View build(Ui ui) {
        this.ui = ui;
        return SessionUsageView.create(ui.c, ui, state, () -> host.getOnBackPressedDispatcher().onBackPressed(), this::recover);
    }
    @Override void onShow() {
        visible = true; ScoutRuns.watch(watcher); load();
        ScoutUsage.model(ui.c, Ask.model(ui.c), () -> main.post(refresh));
        recover();
    }
    @Override void onHide() { visible = false; revision++; ScoutRuns.unwatch(watcher); main.removeCallbacks(refresh); }

    private void load() {
        if (!visible) return;
        long version = ++revision;
        new Thread(() -> {
            JSONObject data = new JSONObject();
            try {
                Session s = ScoutRuns.session(ui.c, file);
                JSONObject context = SessionUsage.context(s);
                int capacity = ScoutUsage.window(ui.c, context.optString("model", Ask.model(ui.c)));
                if (capacity > 0) context.put("window", capacity);
                ScoutRuns.Run active = ScoutRuns.run(file);
                data.put("title", ScoutRuns.title(ui.c, file)).put("totals", s.totals()).put("context", context)
                    .put("requests", new JSONArray(SessionUsage.requests(s))).put("history", new JSONArray(SessionUsage.history(s)))
                    .put("activeRun", active == null ? "" : active.id);
            } catch (Exception unavailable) { try { data.put("error", "This session could not be read."); } catch (Exception ignored) { } }
            final String json = data.toString();
            main.post(() -> { if (visible && version == revision) state.publish(json); });
        }, "scout-usage-detail").start();
    }
    private void recover() {
        if (state.getRefreshing()) return;
        state.refreshing(true);
        new Thread(() -> {
            try { ScoutUsage.reconcile(ui.c, ScoutRuns.session(ui.c, file), () -> { }); }
            catch (Exception ignored) { }
            main.post(() -> { state.refreshing(false); load(); });
        }, "scout-usage-recovery").start();
    }
}
