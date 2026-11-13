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
import org.json.JSONArray;
import org.json.JSONObject;

/** The Ask tab: a chat over your vault. Answers carry numbered markers and a list of the notes they came from. Drawn by AskView; the logic stays here. */
final class AskScreen extends Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray history = new JSONArray(); // user questions and final answers only
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
        @Override public void newChat() { if (!busy) { while (history.length() > 0) history.remove(0); save(); intro(); } }
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
        else if (Model.key(ui.c).isEmpty()) items.add(new Intro("Ask needs an OpenRouter key. Add one in Settings.", new ArrayList<>()));
        else {
            List<Vault.Note> hubs = new ArrayList<>();
            for (Vault.Note n : snap.graph.notes) if (!n.catalog && n.path.startsWith("Knowledge/Topics/")) hubs.add(n);
            hubs.sort((a, b) -> snap.graph.in(b).size() - snap.graph.in(a).size());
            List<String> tries = new ArrayList<>();
            for (Vault.Note n : hubs.subList(0, Math.min(3, hubs.size()))) tries.add("What do my notes say about " + n.title + "?");
            items.add(new Intro("Answers come only from your notes and cite each one. What you ask, and the notes it reads, are sent to OpenRouter.", tries));
        }
        publish();
    }

    // ---- asking ----

    private void ask(String question) { run(question, false); }

    private void run(String question, boolean again) {
        if (busy || question.isEmpty()) return;
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        String key = Model.key(ui.c);
        if (snap == null || key.isEmpty()) { intro(); return; }
        if (!online()) { view.header("", true); Toast.makeText(ui.c, "You are offline", Toast.LENGTH_SHORT).show(); return; }
        if (history.length() == 0 && !again) items.clear();
        if (!again) { draft = ""; view.input(""); }
        busy = true;
        lastQuestion = question;
        final Cancel cancel = asking = new Cancel();
        if (!again) items.add(new Question(question));
        publish();
        view.running(true);
        JSONArray sent;
        try {
            sent = new JSONArray(history.toString());
            sent.put(new JSONObject().put("role", "user").put("content", question));
        } catch (Exception impossible) { busy = false; view.running(false); return; }
        final JSONArray messages = sent;
        new Thread(() -> {
            String answer = null, error = null;
            Limits.interactive(true);
            try {
                answer = Ask.run(ui.c, snap, key, messages, line -> main.post(() -> view.status(line)), soFar -> main.post(() -> view.live(soFar)), cancel);
            } catch (Exception e) { String m = String.valueOf(e.getMessage()).replace("Smart tags paused: ", ""); String k = Failures.kind(m); error = k.equals(Failures.OTHER) || k.equals(Failures.CONFLICT) ? m : Failures.recovery(k, m).replace("It is tried again by itself when you are back online; Retry tries now.", "Try again when you are online."); }
            final String a = answer, e = error;
            main.post(() -> finish(question, a, e));
        }).start();
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
            try {
                history.put(new JSONObject().put("role", "user").put("content", question));
                history.put(new JSONObject().put("role", "assistant").put("content", answer));
            } catch (Exception ignored) { }
            save();
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

    private void save() {
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, history.length() - 20); i < history.length(); i++) keep.put(history.opt(i));
        ui.c.getSharedPreferences("capture", 0).edit().putString("askHistory", keep.toString()).apply();
    }

    private void restore() {
        try {
            JSONArray saved = new JSONArray(ui.c.getSharedPreferences("capture", 0).getString("askHistory", "[]"));
            if (saved.length() == 0 || VaultStore.cached(ui.c) == null) return;
            items.clear();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject m = saved.getJSONObject(i);
                history.put(m);
                items.add(m.getString("role").equals("user") ? new Question(m.getString("content")) : answerItem(m.getString("content")));
            }
            publish();
        } catch (Exception damaged) { while (history.length() > 0) history.remove(0); }
    }
}
