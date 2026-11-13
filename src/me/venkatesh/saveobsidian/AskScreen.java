package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** The Scout tab (Ask until v2): an agent chat over your vault. Answers carry numbered markers and a list of the notes they came from. Drawn by AskView; the logic stays here. */
final class AskScreen extends Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Agent agent;
    private Session session;
    private boolean started; // the stored conversation has at least one message
    private android.app.Dialog confirming;
    private final AskState view = new AskState();
    private final List<AskItem> items = new ArrayList<>();
    private Ui ui;
    private boolean busy;
    private Cancel asking;
    private String lastQuestion = "", pendingQuestion, draft = "";

    @Override View build(Ui ui) {
        this.ui = ui;
        view.input(draft); // a half-typed question survives tab switches and theme changes
        if (items.isEmpty()) { intro(); restore(); } else publish();
        return AskView.create(ui.c, ui, view, actions);
    }

    private final AskActions actions = new AskActions() {
        @Override public void ask(String q) { AskScreen.this.ask(q); }
        @Override public void cancel() { if (asking != null) { asking.stop(); view.status("Cancelling…"); } }
        @Override public void retry() { if (!lastQuestion.isEmpty()) { removeLastProblem(); run(lastQuestion, true); } }
        @Override public void newChat() { if (!busy) { Scout.forget(ui.c); session = null; started = false; intro(); } }
        @Override public void searchVault() { host.select(2); }
        @Override public void addNote() { ui.c.startActivity(new Intent(ui.c, QuickActivity.class)); }
        @Override public void draft(String text) { draft = text; }
    };

    @Override void onShow() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        int n = 0;
        if (snap != null) for (Vault.Note note : snap.graph.notes) if (!note.catalog && !note.path.endsWith("/My cards.md")) n++;
        view.header(n == 0 ? "Your vault is not loaded yet" : String.format(java.util.Locale.US, "%,d notes in your vault", n), !online());
        if (pendingQuestion != null) { String q = pendingQuestion; pendingQuestion = null; ask(q); }
    }

    /** A question from another screen (Ask about this): asked when this tab opens. */
    void prefill(String question) {
        if (ui == null) { pendingQuestion = question; return; }
        main.post(() -> ask(question));
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) ui.c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm == null || cm.getActiveNetwork() != null;
    }

    private void publish() { view.publish(new ArrayList<>(items)); }

    private void removeLastProblem() {
        if (!items.isEmpty() && (items.get(items.size() - 1) instanceof Failure || items.get(items.size() - 1) instanceof Stopped)) items.remove(items.size() - 1);
    }

    private void intro() {
        items.clear();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (!VaultStore.ready(ui.c) || snap == null) items.add(new Intro("Choose your vault folder first.", new ArrayList<>()));
        else if (Model.key(ui.c).isEmpty()) items.add(new Intro("Scout needs an OpenRouter key. Add one in Settings.", new ArrayList<>()));
        else {
            List<Vault.Note> hubs = new ArrayList<>();
            for (Vault.Note n : snap.graph.notes) if (!n.catalog && n.path.startsWith("Knowledge/Topics/")) hubs.add(n);
            hubs.sort((a, b) -> snap.graph.in(b).size() - snap.graph.in(a).size());
            List<String> tries = new ArrayList<>();
            for (Vault.Note n : hubs.subList(0, Math.min(3, hubs.size()))) tries.add("What do my notes say about " + n.title + "?");
            items.add(new Intro("Scout reads your notes, answers with citations, and can change notes when you ask. You confirm every change. What you ask, and the notes it reads, are sent to OpenRouter.", tries));
        }
        publish();
    }

    // ---- asking ----

    private void ask(String question) {
        if (busy && agent != null && !question.isEmpty()) { // steering: delivered after the current tools finish, before the next model call
            agent.steer(question);
            items.add(new Question(question));
            draft = ""; view.input("");
            publish();
            return;
        }
        run(question, false);
    }

    private void run(String question, boolean again) {
        if (busy || question.isEmpty()) return;
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        String key = Model.key(ui.c);
        if (snap == null || key.isEmpty()) { intro(); return; }
        if (!online()) { view.header("", true); Toast.makeText(ui.c, "You are offline", Toast.LENGTH_SHORT).show(); return; }
        if (!started && !again) items.clear();
        if (!again) { draft = ""; view.input(""); }
        busy = true;
        lastQuestion = question;
        final Cancel cancel = asking = new Cancel();
        if (!again) items.add(new Question(question));
        publish();
        view.running(true);
        new Thread(() -> {
            String answer = null, error = null;
            Limits.interactive(true);
            try {
                if (session == null) session = Scout.session(ui.c, false);
                Agent a = agent = Scout.agent(ui.c, snap, key, session, (title, detail) -> approve(title, detail, cancel), cancel);
                a.subscribe((type, data) -> events(type, data));
                Usage.hit(ui.c, "ask");
                JSONObject last;
                if (again) { try { last = a.resume(); } catch (IllegalStateException nothing) { last = a.prompt(Scout.expand(ui.c, snap, question)); } } else last = a.prompt(Scout.expand(ui.c, snap, question));
                started = true;
                if ("error".equals(last.optString("stopReason"))) throw new Exception(last.optString("errorMessage"));
                answer = last.optString("content", "");
            } catch (Exception e) { String m = String.valueOf(e.getMessage()).replace("Smart tags paused: ", ""); String k = Failures.kind(m); error = k.equals(Failures.OTHER) || k.equals(Failures.CONFLICT) ? m : Failures.recovery(k, m).replace("It is tried again by itself when you are back online; Retry tries now.", "Try again when you are online."); }
            final String ans = answer, err = error;
            main.post(() -> finish(question, ans, err));
        }).start();
    }

    /** Agent events arrive on the agent's thread: everything the UI shows goes through main. */
    private void events(String type, JSONObject data) {
        if (type.equals("message_update")) main.post(() -> view.live(data.optString("text")));
        else if (type.equals("message_end")) { JSONObject m = data.optJSONObject("message"); if (m != null && m.optJSONArray("tool_calls") != null) main.post(() -> view.live("")); } // preamble to tool calls, not the answer
        else if (type.equals("tool_execution_start")) { String line = status(data.optString("name"), data.optJSONObject("args")); main.post(() -> view.status(line)); }
        else if (type.equals("tool_execution_end") && !data.optBoolean("isError") && (data.optString("name").equals("edit") || data.optString("name").equals("write")))
            new Thread(() -> { try { VaultStore.scan(ui.c); } catch (Exception stale) { } }).start(); // the note list and links catch up with the change
        else if (type.equals("retry")) main.post(() -> view.status("Retrying…"));
        else if (type.equals("compaction_start")) main.post(() -> view.status("Summarising the conversation so far…"));
    }

    private static String status(String tool, JSONObject a) {
        if (a == null) a = new JSONObject();
        switch (tool) {
            case "search": return "Searching “" + a.optString("query") + "”";
            case "grep": return "Looking for “" + a.optString("pattern") + "”";
            case "find": return "Finding " + a.optString("pattern");
            case "ls": return "Looking in " + (a.optString("path").isEmpty() ? "the vault" : a.optString("path"));
            case "read": return "Reading " + a.optString("path");
            case "inbox": return "Looking at the inbox";
            case "edit": case "write": return "Changing " + a.optString("path");
            default: return "Using " + tool;
        }
    }

    /** Asks the user before a change; blocks the agent's thread until they answer (or the run is cancelled). */
    private boolean approve(String title, String detail, Cancel cancel) {
        final java.util.concurrent.ArrayBlockingQueue<Boolean> answer = new java.util.concurrent.ArrayBlockingQueue<>(1);
        main.post(() -> confirming = new android.app.AlertDialog.Builder(ui.c).setTitle(title).setMessage(detail).setCancelable(false)
            .setPositiveButton("Allow", (d, w) -> answer.offer(true)).setNegativeButton("Decline", (d, w) -> answer.offer(false)).show());
        try {
            while (true) {
                Boolean r = answer.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (r != null) return r;
                if (cancel.stopped()) { main.post(() -> { if (confirming != null) confirming.dismiss(); }); return false; }
            }
        } catch (InterruptedException interrupted) { return false; }
    }

    private void finish(String question, String answer, String error) {
        busy = false;
        boolean cancelled = asking != null && asking.stopped();
        asking = null;
        String partial = view.getLive();
        view.running(false);
        if (cancelled) items.add(new Stopped(answer != null && !answer.isEmpty() ? answer : partial)); // kept as it is; never asked again by itself
        else if (answer == null || answer.isEmpty()) items.add(new Failure(error != null && !error.isEmpty() ? "Could not finish the answer. " + error : "Could not finish the answer. The model returned nothing."));
        else {
            items.add(answerItem(answer));
        }
        publish();
    }

    private AskItem answerItem(String answer) {
        if (Cites.notInKnowledge(answer)) return new NotFound(refs(Cites.number(answer).sources));
        Cites.Result cited = Cites.number(answer);
        return new Answer(cited.text, cited.sources.size() == 1, refs(cited.sources));
    }

    private List<SourceRef> refs(List<String[]> sources) {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        List<SourceRef> out = new ArrayList<>();
        for (String[] s : sources) {
            Vault.Note note = snap == null ? null : snap.graph.resolve(s[1]);
            String meta = "";
            if (Layout.isInbox(s[1])) meta = "Inbox capture";
            else if (note != null && Layout.inSources(note.path)) { String h = Feed.host(note.meta("source")); String lane = Lane.of(note.path, note.meta); meta = (lane.isEmpty() ? "Source" : lane) + (h.isEmpty() ? "" : ", " + h); }
            else if (note != null && note.path.startsWith("Knowledge/Topics/")) meta = "Topic";
            else if (note != null && note.path.startsWith("Knowledge/People/")) meta = "Person";
            else if (note == null) meta = "Not in your vault";
            final String path = s[1];
            out.add(new SourceRef("[" + s[0] + "]", note != null ? note.title : s[2], meta, () -> openSource(path, note)));
        }
        return out;
    }

    private void openSource(String path, Vault.Note note) {
        if (Layout.isInbox(path)) { host.select(0); return; } // the inbox lives in the Feed
        if (note == null) { Toast.makeText(ui.c, "That note is not in your vault", Toast.LENGTH_SHORT).show(); return; }
        host.openNote(note.path);
    }

    // ---- history ----

    /** Shows the stored conversation: questions and final answers (tool traffic stays in the log). */
    private void restore() {
        try {
            if (VaultStore.cached(ui.c) == null || !new java.io.File(Scout.dir(ui.c), ui.c.getSharedPreferences("capture", 0).getString("scoutSession", "none")).exists()) return;
            session = Scout.session(ui.c, false);
            List<JSONObject> saved = session.context();
            if (saved.isEmpty()) return;
            started = true;
            items.clear();
            for (JSONObject m : saved) {
                if (m.optBoolean("summary")) continue;
                if (m.optString("role").equals("user")) items.add(new Question(m.optString("content")));
                else if (m.optString("role").equals("assistant") && m.optJSONArray("tool_calls") == null && !m.optString("content").isEmpty() && m.optString("stopReason", "stop").equals("stop")) items.add(answerItem(m.optString("content")));
            }
            publish();
        } catch (Exception damaged) { session = null; started = false; }
    }
}
