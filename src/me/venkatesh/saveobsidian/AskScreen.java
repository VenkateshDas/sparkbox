package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * The Scout tab: one chat at a time, drawn from its stored session plus the live state of its run (ScoutRuns). Runs do not belong to this screen: leaving the
 * app, switching chats or changing tabs does not stop them, and a change waiting for your OK shows as a card in the chat, answered whenever you come back.
 */
final class AskScreen extends Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AskState view = new AskState();
    private final List<AskItem> items = new ArrayList<>();
    private Ui ui;
    private String file; // the chat on screen (a file in files/sessions), or null for a new one not started yet
    private String pendingQuestion, draft = "";

    private final ScoutRuns.Watcher watcher = new ScoutRuns.Watcher() {
        @Override public void changed(String f) { main.post(() -> { if (f.isEmpty() || f.equals(file)) refresh(); if (view.getDrawer()) loadChats(); }); }
        @Override public void live(String f, String text) { if (f.equals(file)) main.post(() -> view.live(text)); }
    };

    @Override View build(Ui ui) {
        this.ui = ui;
        view.input(draft); // a half-typed question survives tab switches and theme changes
        if (file == null) {
            String saved = ui.c.getSharedPreferences("capture", 0).getString("scoutSession", "");
            if (!saved.isEmpty() && new File(ScoutRuns.dir(ui.c), saved).exists()) file = saved;
        }
        refresh();
        return AskView.create(ui.c, ui, view, actions);
    }

    private final AskActions actions = new AskActions() {
        @Override public void ask(String q) { AskScreen.this.ask(q); }
        @Override public void cancel() { if (file != null) ScoutRuns.cancel(file); }
        @Override public void retry() { if (file != null && ScoutRuns.start(ui.c, file, null, true, false)) refresh(); }
        @Override public void newChat() { view.drawer(false); open(null); }
        @Override public void searchVault() { host.select(2); }
        @Override public void addNote() { ui.c.startActivity(new Intent(ui.c, QuickActivity.class)); }
        @Override public void draft(String text) { draft = text; }
        @Override public void approve(boolean yes) { if (file != null) ScoutRuns.approve(file, yes); }
        @Override public void drawer(boolean open) { view.drawer(open); if (open) loadChats(); }
        @Override public void openChat(String f) { view.drawer(false); open(f); }
        @Override public void searchChats(String q) { view.query(q); loadChats(); }
        @Override public void renameChat(String f) { rename(f); }
        @Override public void deleteChat(String f) { delete(f); }
    };

    /** Shows this chat (null: a new one). Whatever was running keeps running. */
    void open(String f) {
        file = f;
        ScoutRuns.watching = f;
        if (ui != null) {
            ui.c.getSharedPreferences("capture", 0).edit().putString("scoutSession", f == null ? "" : f).apply();
            ScoutRuns.clearNotification(ui.c, f);
            refresh();
        }
    }

    @Override boolean onBack() {
        if (view.getDrawer()) { view.drawer(false); return true; }
        return false;
    }

    /** The drawer's list: every chat with a name or messages, newest first, filtered by the search words (title or any message). Read off the main thread. */
    private void loadChats() {
        final String q = view.getQuery(), current = file;
        new Thread(() -> {
            List<ChatRow> rows = new ArrayList<>();
            for (JSONObject s : Session.list(ScoutRuns.dir(ui.c))) {
                if (s.optInt("messages") == 0) continue;
                String f = new File(s.optString("file")).getName();
                try { if (!ScoutRuns.session(ui.c, f).contains(q)) continue; } catch (Exception unreadable) { continue; }
                String name = s.optString("name"), title = name.isEmpty() ? s.optString("preview") : name;
                rows.add(new ChatRow(f, title.isEmpty() ? "Chat" : title, ScoutRuns.waitingForYou(f) ? "Needs your OK" : ScoutRuns.run(f) != null ? "Working…" : "", f.equals(current)));
            }
            main.post(() -> view.chats(rows));
        }).start();
    }

    private void rename(String f) {
        android.widget.EditText e = new android.widget.EditText(ui.c);
        e.setSingleLine(true);
        e.setText(ScoutRuns.title(ui.c, f));
        e.setSelectAllOnFocus(true);
        android.widget.LinearLayout box = new android.widget.LinearLayout(ui.c);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(e, new android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(host).setTitle("Rename chat").setView(box)
            .setPositiveButton("Save", (d, w) -> { try { ScoutRuns.rename(ui.c, f, e.getText().toString()); } catch (Exception failed) { Toast.makeText(ui.c, "Could not rename", Toast.LENGTH_SHORT).show(); } refresh(); loadChats(); })
            .setNegativeButton("Cancel", null).show();
    }

    private void delete(String f) {
        new android.app.AlertDialog.Builder(host).setTitle("Delete this chat?").setMessage(ScoutRuns.title(ui.c, f) + "\n\nIt cannot be brought back. Notes it changed stay as they are.")
            .setPositiveButton("Delete", (d, w) -> { ScoutRuns.delete(ui.c, f); if (f.equals(file)) open(null); loadChats(); })
            .setNegativeButton("Cancel", null).show();
    }

    @Override void onShow() {
        ScoutRuns.watch(watcher);
        ScoutRuns.watching = file;
        ScoutRuns.clearNotification(ui.c, file);
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        int n = 0;
        if (snap != null) for (Vault.Note note : snap.graph.notes) if (!note.catalog && !note.path.endsWith("/My cards.md")) n++;
        view.header(n == 0 ? "Your vault is not loaded yet" : String.format(java.util.Locale.US, "%,d notes in your vault", n), !online());
        refresh();
        if (pendingQuestion != null) { String q = pendingQuestion; pendingQuestion = null; ask(q); }
    }

    @Override void onHide() { ScoutRuns.unwatch(watcher); ScoutRuns.watching = null; ScoutRuns.leaving(ui.c, file); }

    /** A question from another screen (Ask about this): asked when this tab opens. */
    void prefill(String question) {
        if (ui == null) { pendingQuestion = question; return; }
        main.post(() -> ask(question));
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) ui.c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm == null || cm.getActiveNetwork() != null;
    }

    // ---- drawing: stored messages plus the live state of the run ----

    private void refresh() {
        items.clear();
        List<JSONObject> messages = new ArrayList<>();
        try { if (file != null) messages = ScoutRuns.session(ui.c, file).context(); } catch (Exception unreadable) { file = null; }
        view.title(file == null || messages.isEmpty() ? "" : ScoutRuns.title(ui.c, file));
        List<Chat.Row> rows = Chat.rows(messages);
        ScoutRuns.Run run = ScoutRuns.run(file);
        if (rows.isEmpty() && run == null) intro();
        for (Chat.Row r : rows) items.add(r.kind.equals("user") ? new Question(r.text) : r.kind.equals("step") ? new Step(r.text) : answerItem(r.text));
        if (run != null) {
            int users = 0;
            for (Chat.Row r : rows) if (r.kind.equals("user")) users++;
            if (run.sent != null && users <= run.usersAtStart) items.add(new Question(run.sent)); // the instant echo, only until the log has the message
            if (run.pending != null) items.add(new Approval(run.pending.title, run.pending.detail));
            view.running(true);
            view.status(run.pending != null ? "Waiting for your OK" : run.status);
            view.live(run.live);
        } else {
            view.running(false);
            if (Chat.interrupted(messages)) items.add(problem(messages));
        }
        view.publish(new ArrayList<>(items));
    }

    /** Why the last run has no answer: it failed, was stopped, or the app was closed under it. */
    private AskItem problem(List<JSONObject> messages) {
        JSONObject last = messages.get(messages.size() - 1);
        String stop = last.optString("stopReason");
        if (stop.equals("aborted")) return new Stopped("");
        if (stop.equals("error")) return new Failure("Could not finish the answer. " + last.optString("errorMessage"));
        if (stop.equals("max-steps")) return new Failure("Scout ran out of steps before it could answer.");
        return new Failure("This run did not finish: the app was closed while it was working.");
    }

    private void intro() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (!VaultStore.ready(ui.c) || snap == null) items.add(new Intro("Choose your vault folder first.", new ArrayList<>()));
        else if (Model.key(ui.c).isEmpty()) items.add(new Intro("Scout needs an OpenRouter key. Add one in Settings.", new ArrayList<>()));
        else {
            List<Vault.Note> hubs = new ArrayList<>();
            for (Vault.Note n : snap.graph.notes) if (!n.catalog && n.path.startsWith("Knowledge/Topics/")) hubs.add(n);
            hubs.sort((a, b) -> snap.graph.in(b).size() - snap.graph.in(a).size());
            List<String> tries = new ArrayList<>();
            for (Vault.Note n : hubs.subList(0, Math.min(3, hubs.size()))) tries.add("What do my notes say about " + n.title + "?");
            items.add(new Intro("Scout reads your notes, answers with citations, and can change notes when you ask. Every chat keeps working when you leave the app, and asks for your OK in the chat. Start a message with /research for a thorough, multi-source investigation. What you ask, and the notes it reads, are sent to OpenRouter.", tries));
        }
    }

    // ---- asking ----

    private void ask(String question) {
        question = question.trim();
        if (question.isEmpty()) return;
        if (ScoutRuns.run(file) != null) { ScoutRuns.steer(file, question); draft = ""; view.input(""); return; } // delivered after the current tools finish, before the next model call
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null || Model.key(ui.c).isEmpty()) { refresh(); return; }
        if (!online()) { view.header("", true); Toast.makeText(ui.c, "You are offline", Toast.LENGTH_SHORT).show(); return; }
        try {
            if (file == null) { file = ScoutRuns.create(ui.c); ui.c.getSharedPreferences("capture", 0).edit().putString("scoutSession", file).apply(); ScoutRuns.watching = file; }
        } catch (java.io.IOException e) { Toast.makeText(ui.c, "Could not start a chat", Toast.LENGTH_SHORT).show(); return; }
        draft = ""; view.input("");
        ScoutRuns.start(ui.c, file, question, false, false);
        refresh();
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
}
