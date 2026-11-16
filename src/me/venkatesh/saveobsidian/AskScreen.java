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
    private String pendingQuestion, draft = "", ctxPath = "", ctxTitle = "", ctxTree = "";
    private final Runnable saveDraft = this::flushDraft;

    private android.util.AtomicFile draftFile() {
        File dir = new File(ui.c.getFilesDir(), "scout-drafts"); dir.mkdirs();
        return new android.util.AtomicFile(new File(dir, (file == null ? "new" : file) + ".json"));
    }

    private void flushDraft() {
        if (ui == null) return;
        main.removeCallbacks(saveDraft);
        java.io.FileOutputStream out = null;
        android.util.AtomicFile store = draftFile();
        try {
            JSONObject data = new JSONObject().put("text", draft).put("path", ctxPath).put("title", ctxTitle).put("tree", ctxTree)
                .put("afterAnswer", view.getAfterAnswer()).put("caret", view.getCaret()).put("expanded", view.getExpanded()).put("research", view.getResearch()).put("web", view.getWeb());
            out = store.startWrite(); out.write(data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); store.finishWrite(out);
        } catch (Exception failed) { if (out != null) store.failWrite(out); }
    }

    private void restoreDraft() {
        draft = ""; ctxPath = ""; ctxTitle = ""; ctxTree = "";
        view.research(false); view.web(false); view.expanded(false);
        try {
            JSONObject data = new JSONObject(new String(draftFile().readFully(), java.nio.charset.StandardCharsets.UTF_8));
            draft = data.optString("text"); ctxPath = data.optString("path"); ctxTitle = data.optString("title"); ctxTree = data.optString("tree");
            view.afterAnswer(data.optBoolean("afterAnswer"));
            view.research(data.optBoolean("research")); view.web(data.optBoolean("web")); view.expanded(data.optBoolean("expanded"));
            view.input(draft); view.caret(data.optInt("caret", draft.length()));
        } catch (Exception missing) { view.input(""); }
        view.context(ctxTitle);
    }

    private final ScoutRuns.Watcher watcher = new ScoutRuns.Watcher() {
        @Override public void changed(String f) { main.post(() -> { if (f.isEmpty() || f.equals(file)) refresh(); if (view.getDrawer()) loadChats(); }); }
        @Override public void live(String f, String text) { if (f.equals(file)) main.post(() -> { if (f.equals(file)) view.live(text); }); }
    };

    @Override View build(Ui ui) {
        this.ui = ui;
        if (file == null) {
            String saved = ui.c.getSharedPreferences("capture", 0).getString("scoutSession", "");
            if (!saved.isEmpty() && new File(ScoutRuns.dir(ui.c), saved).exists()) file = saved;
        }
        restoreDraft();
        refresh();
        return AskView.create(ui.c, ui, view, actions);
    }

    private final AskActions actions = new AskActions() {
        @Override public void ask(String q) { AskScreen.this.ask(q); }
        @Override public void clearContext() { attach("", ""); }
        @Override public void cancel() { if (file != null) ScoutRuns.cancel(file); }
        @Override public void retry() { if (file != null && ScoutRuns.start(ui.c, file, null, true, view.getResearch(), view.getWeb())) refresh(); }
        @Override public void newChat() { view.drawer(false); open(null); }
        @Override public void searchVault() { host.select(2); }
        @Override public void addNote() { ui.c.startActivity(new Intent(ui.c, QuickActivity.class)); }
        @Override public void draft(String text) { draft = text; main.removeCallbacks(saveDraft); main.postDelayed(saveDraft, 250); }
        @Override public void optionsChanged() { flushDraft(); }
        @Override public void suggest(String text) { AskScreen.this.draft(text); flushDraft(); }
        @Override public void attachNote() { chooseNote(); }
        @Override public void attachQuote() { quote(); }
        @Override public void discover() { discovery(); }
        @Override public void approve(String id, boolean yes) { if (file != null) ScoutRuns.approve(ui.c, file, id, yes); }
        @Override public void drawer(boolean open) { view.drawer(open); if (open) loadChats(); }
        @Override public void openChat(String f) { view.drawer(false); open(f); }
        @Override public void searchChats(String q) { view.query(q); loadChats(); }
        @Override public void renameChat(String f) { rename(f); }
        @Override public void deleteChat(String f) { delete(f); }
        @Override public void exportChat(String f) { exportChatFile(f); }
        @Override public void usage(String f) { view.drawer(false); host.push(new SessionUsageScreen(f)); }
    };

    /** Shows this chat (null: a new one). Whatever was running keeps running. */
    void open(String f) {
        flushDraft();
        file = f;
        if (ui != null) restoreDraft();
        ScoutRuns.watching = f;
        if (ui != null) {
            ui.c.getSharedPreferences("capture", 0).edit().putString("scoutSession", f == null ? "" : f).apply();
            ScoutRuns.clearNotification(ui.c, f);
            refresh();
        }
    }

    @Override boolean onBack() {
        if (view.getExpanded()) { view.expanded(false); flushDraft(); return true; }
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
            main.post(() -> { if (q.equals(view.getQuery()) && java.util.Objects.equals(current, file)) view.chats(rows); });
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

    @Override void onHide() { flushDraft(); ScoutRuns.unwatch(watcher); ScoutRuns.watching = null; ScoutRuns.leaving(ui.c, file); }

    /** A question from another screen (Ask about this): asked when this tab opens. */
    void prefill(String question) {
        if (ui == null) { pendingQuestion = question; return; }
        main.post(() -> ask(question));
    }

    /** Ask about this: puts the note in context and leaves the message for you to write. Nothing is sent. */
    void attach(String path, String title) {
        ctxPath = path; ctxTitle = title; ctxTree = ui == null ? "" : VaultStore.tree(ui.c);
        if (ui != null) flushDraft();
        if (ui != null) view.context(title);
    }

    /** Text for the message box, not sent. */
    void draft(String text) {
        draft = text;
        if (ui != null) { view.input(text); view.caret(text.length()); flushDraft(); }
    }

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) ui.c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm == null || cm.getActiveNetwork() != null;
    }

    // ---- drawing: stored messages plus the live state of the run ----

    private void refresh() {
        items.clear();
        List<JSONObject> messages = new ArrayList<>();
        try { if (file != null) messages = ScoutRuns.session(ui.c, file).retained(); } catch (Exception unreadable) { file = null; }
        view.session(file == null ? "" : file);
        view.title(file == null || messages.isEmpty() ? "" : ScoutRuns.title(ui.c, file));
        List<Chat.Row> rows = Chat.rows(messages);
        ScoutRuns.Run run = ScoutRuns.run(file);
        if (rows.isEmpty() && run == null) intro();
        java.util.Map<AskItem, String> stableIds = new java.util.IdentityHashMap<>();
        String userId = "", userText = ""; int workOrdinal = 0;
        for (Chat.Row row : rows) {
            AskItem item;
            String id;
            if (row.kind.equals("user")) { userId = row.id; userText = row.text; workOrdinal = 0; item = new Question(row.text); id = row.id; }
            else if (row.kind.equals("work")) { item = new Work(row.secs, java.util.Arrays.asList(row.text.split("\n"))); id = userId + ":work:" + workOrdinal++; }
            else { item = answerItem(row.text, userId, userText, row.id); id = row.id; }
            items.add(item); stableIds.put(item, id);
        }
        if (run != null) {
            int users = 0;
            for (Chat.Row r : rows) if (r.kind.equals("user")) users++;
            if (run.sent != null && users <= run.usersAtStart) items.add(new Question(run.sent)); // the instant echo, only until the log has the message

            view.running(true);
            view.began(run.began);
            view.status(run.status);
            view.live(run.live);
        } else {
            view.running(false);
            if (Chat.interrupted(messages)) items.add(problem(messages));
        }
        if (file != null) try {
            Session session = ScoutRuns.session(ui.c, file);
            String current = file;
            for (JSONObject queued : ScoutState.pending(session)) {
                String id = queued.optString("queueId"), text = queued.optString("text");
                if (run != null && id.equals(run.initialId)) continue;
                items.add(new QueuedItem(text, queued.optBoolean("followUp"),
                    () -> textDialog("Edit queued direction", text, edited -> { ScoutRuns.editQueue(ui.c, current, id, edited, false); refresh(); }),
                    () -> { ScoutRuns.editQueue(ui.c, current, id, text, true); refresh(); }));
            }
            for (JSONObject proposal : ScoutChanges.pending(session)) {
                boolean note = proposal.optString("kind").equals("note");
                String detail = note ? "Before:\n" + (proposal.isNull("before") ? "(new note)" : proposal.optString("before")) + "\n\nAfter:\n" + proposal.optString("after") : proposal.optString("endpoint") + "\n" + proposal.optString("arguments");
                if (!note && proposal.optJSONArray("previousUnknown") != null && proposal.optJSONArray("previousUnknown").length() > 0) detail = "Previous dispatch outcome unknown. Approving starts a separate attempt and may repeat the external action.\n\n" + detail;
                for (JSONObject event : session.log()) if (event.optString("kind").equals("proposal_error") && event.optJSONObject("data") != null && proposal.optString("proposalId").equals(event.getJSONObject("data").optString("proposalId"))) detail += "\n\nLast attempt: " + event.getJSONObject("data").optString("message");
                items.add(new Approval(proposal.optString("proposalId"), note ? proposal.optString("path") : proposal.optString("server") + "." + proposal.optString("tool"), detail, run != null));
            }
            if (session.storageError() != null) items.add(new Failure(session.storageError()));
        } catch (Exception ignored) { }
        discoveryFindings();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) ids.add(stableIds.getOrDefault(items.get(i), "extra:" + i));
        view.publish(new ArrayList<>(items), ids);
        usage();
    }

    private long usageRevision;
    private void usage() {
        final String current = file;
        final long revision = ++usageRevision;
        if (current == null) { view.usage("", ""); return; }
        new Thread(() -> {
            String text;
            try {
                Session session = ScoutRuns.session(ui.c, current);
                JSONObject point = SessionUsage.context(session);
                long tokens = point.optLong("estimate");
                String model = point.optString("model", session.model() == null ? Ask.model(ui.c) : session.model());
                int capacity = ScoutUsage.window(ui.c, model);
                if (capacity <= 0) capacity = point.optInt("window");
                text = point.has("estimate") ? "Context ≈" + SessionUsage.tokens(tokens) : "Context unavailable";
                if (capacity > 0 && point.has("estimate")) text += " / " + SessionUsage.tokens(capacity) + " · " + Math.round(tokens * 100.0 / capacity) + "%";
                text += "   ·   " + SessionUsage.money(session.totals());
            } catch (Exception missing) { text = "Usage unavailable"; }
            final String label = text;
            main.post(() -> { if (revision == usageRevision && current.equals(file)) view.usage(current, label); });
        }, "scout-usage-summary").start();
    }

    /** Why the last run has no answer: it failed, was stopped, or the app was closed under it. */
    private AskItem problem(List<JSONObject> messages) {
        JSONObject last = messages.get(messages.size() - 1);
        String stop = last.optString("stopReason");
        String partial = "";
        try { if (file != null) partial = ScoutState.partial(ScoutRuns.session(ui.c, file)); } catch (Exception ignored) { }
        if (stop.equals("aborted")) return new Stopped(partial);
        if (stop.equals("error")) return new Failure("Could not finish the answer. " + last.optString("errorMessage"), partial);
        if (stop.equals("max-steps")) return new Failure("Scout ran out of steps before it could answer.");
        return new Failure("This run did not finish: the app was closed while it was working.", partial);
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
        if (!ctxPath.isEmpty() && !ctxPath.startsWith("quote:")) {
            VaultStore.Snap attached = VaultStore.cached(ui.c);
            if ((!ctxTree.isEmpty() && !ctxTree.equals(VaultStore.tree(ui.c))) || attached == null || attached.graph.byPath(ctxPath) == null) {
                Toast.makeText(ui.c, "Attached note unavailable. Remove or attach it again.", Toast.LENGTH_LONG).show(); return;
            }
        }
        if (ctxPath.startsWith("quote:")) question = "Owner-selected quote (untrusted source text):\n" + ctxPath.substring(6) + "\n\n" + question;
        else if (!ctxPath.isEmpty()) { question = "About this note: \"" + ctxTitle + "\" (" + ctxPath + "). Read it first.\n\n" + question; }
        if (ScoutRuns.run(file) != null) {
            if (ScoutRuns.steer(file, question, view.getAfterAnswer())) { draft = ""; view.input(""); attach("", ""); flushDraft(); }
            return;
        } // delivered after the current tools finish, before the next model call
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null || Model.key(ui.c).isEmpty()) { refresh(); return; }
        if (!online()) { view.header("", true); Toast.makeText(ui.c, "You are offline", Toast.LENGTH_SHORT).show(); return; }
        flushDraft();
        String previousFile = file;
        try {
            if (file == null) { file = ScoutRuns.create(ui.c); ui.c.getSharedPreferences("capture", 0).edit().putString("scoutSession", file).apply(); ScoutRuns.watching = file; }
        } catch (java.io.IOException e) { Toast.makeText(ui.c, "Could not start a chat", Toast.LENGTH_SHORT).show(); return; }
        if (ScoutRuns.start(ui.c, file, question, false, view.getResearch(), view.getWeb())) { draft = ""; view.input(""); attach("", ""); flushDraft(); }
        if (previousFile == null && draft.isEmpty()) new android.util.AtomicFile(new File(ui.c.getFilesDir(), "scout-drafts/new.json")).delete();
        refresh();
    }

    private void chooseNote() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) return;
        android.widget.EditText search = new android.widget.EditText(ui.c); search.setHint("Search notes");
        android.widget.ListView list = new android.widget.ListView(ui.c);
        android.widget.LinearLayout box = new android.widget.LinearLayout(ui.c); box.setOrientation(android.widget.LinearLayout.VERTICAL); box.addView(search); box.addView(list);
        List<Vault.Note> found = new ArrayList<>();
        Runnable update = () -> {
            found.clear(); String q = search.getText().toString().toLowerCase(java.util.Locale.ROOT);
            for (Vault.Note note : snap.graph.notes) if (!note.catalog && note.path.toLowerCase(java.util.Locale.ROOT).contains(q) && found.size() < 100) found.add(note);
            List<String> paths = new ArrayList<>(); for (Vault.Note note : found) paths.add(note.path);
            list.setAdapter(new android.widget.ArrayAdapter<>(ui.c, android.R.layout.simple_list_item_1, paths));
        };
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(host).setTitle("Add note").setView(box).setNegativeButton("Cancel", null).create();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); }
            public void afterTextChanged(android.text.Editable e) { }
        });
        list.setOnItemClickListener((parent, row, position, id) -> { Vault.Note note = found.get(position); attach(note.path, note.title); dialog.dismiss(); });
        update.run(); dialog.show();
    }

    private void quote() {
        android.widget.EditText edit = new android.widget.EditText(ui.c); edit.setHint("Paste quote or capture text"); edit.setMaxLines(8);
        new android.app.AlertDialog.Builder(host).setTitle("Add quote / capture").setView(edit).setNegativeButton("Cancel", null)
            .setPositiveButton("Attach", (d, w) -> { String text = edit.getText().toString(); if (!text.isBlank() && text.length() <= 16000) attach("quote:" + text, "Quote / capture"); }).show();
    }

    private AskItem answerItem(String answer, String userId, String userText, String answerId) {
        try { if (file != null) answer = ScoutEvidence.export(ScoutRuns.session(ui.c, file), answer); } catch (Exception ignored) { }
        if (Cites.notInKnowledge(answer)) return new NotFound(refs(Cites.number(answer).sources));
        Cites.Result cited = Cites.number(answer);
        String raw = answer, current = file;
        try { if (current != null) raw = ScoutEvidence.share(ScoutRuns.session(ui.c, current), answer); } catch (Exception ignored) { }
        String copy = raw;
        return new Answer(cited.text, cited.sources.size() == 1, refs(cited.sources), copy,
            () -> keep(current, copy), () -> addToTopic(current, copy), () -> makeCards(current), () -> retryAnswer(current, userId, userText, answerId));
    }

    private List<SourceRef> refs(List<String[]> sources) {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        List<SourceRef> out = new ArrayList<>();
        for (String[] s : sources) {
            Vault.Note note = snap == null ? null : snap.graph.resolve(s[1]);
            String meta = "";
            if (ScoutEvidence.validUrl(s[1])) {
                meta = "Web · coverage unknown";
                try {
                    for (JSONObject source : ScoutEvidence.sources(ScoutRuns.session(ui.c, file)).values()) if (s[1].equals(source.optString("target"))) meta = "Web · " + source.optString("coverage", "unknown");
                } catch (Exception ignored) { }
            }
            else if (Layout.isInbox(s[1])) meta = "Inbox capture";
            else if (note != null && Layout.inSources(note.path)) { String h = Feed.host(note.meta("source")); String lane = Lane.of(note.path, note.meta); meta = (lane.isEmpty() ? "Source" : lane) + (h.isEmpty() ? "" : ", " + h); }
            else if (note != null && note.path.startsWith("Knowledge/Topics/")) meta = "Topic";
            else if (note != null && note.path.startsWith("Knowledge/People/")) meta = "Person";
            else if (note == null) meta = "Not in your vault";
            final String path = s[1];
            out.add(new SourceRef("[" + s[0] + "]", note != null ? note.title : s[2], meta, () -> previewSource(path, note)));
        }
        return out;
    }

    private void discovery() {
        try {
            JSONObject state = ScoutDiscovery.state(ui.c);
            org.json.JSONArray interests = state.getJSONArray("interests");
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < interests.length(); i++) labels.add(interests.getJSONObject(i).optString("name"));
            labels.add("Add selected interest"); labels.add("Schedule settings");
            new android.app.AlertDialog.Builder(host).setTitle("Discover · selected interests only").setItems(labels.toArray(new String[0]), (d, which) -> {
                if (which == interests.length()) {
                    String seed = ctxPath.startsWith("Knowledge/") ? ctxPath : "";
                    textDialog(seed.isEmpty() ? "Interest to explore" : "Interest · seed: " + ctxTitle, "", name -> {
                        try { ScoutDiscovery.interest(ui.c, name, seed); discovery(); }
                        catch (Exception failed) { Toast.makeText(ui.c, failed.getMessage(), Toast.LENGTH_LONG).show(); }
                    });
                } else if (which == interests.length() + 1) scheduleSettings();
                else {
                    try {
                        JSONObject interest = interests.getJSONObject(which);
                        if (!interest.optString("tree").equals(VaultStore.tree(ui.c))) throw new Exception("Interest belongs to another vault. Select it again for this vault.");
                        if (!ScoutDiscovery.capable(ui.c)) throw new Exception("No research capability. Settings > Scout tools > Read-only research tools: select verified search/fetch tool names.");
                        if (Model.key(ui.c).isEmpty()) throw new Exception("Add an OpenRouter key in Settings");
                        String current = ScoutRuns.create(ui.c);
                        if (!ScoutRuns.start(ui.c, current, ScoutDiscovery.prompt(interest, 5), false, true, true, interest, false, null)) throw new Exception("Scout is busy. Try again after an active chat finishes.");
                        open(current);
                    } catch (Exception failed) { Toast.makeText(ui.c, failed.getMessage(), Toast.LENGTH_LONG).show(); }
                }
            }).show();
        } catch (Exception failed) { Toast.makeText(ui.c, "Discovery state unavailable: " + failed.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    private void discoveryFindings() {
        if (file == null) return;
        String current = file;
        try {
            Session session = ScoutRuns.session(ui.c, current);
            JSONObject findings = ScoutState.latest(session, "discovery_findings");
            if (findings == null) return;
            java.util.Set<String> dismissed = new java.util.HashSet<>();
            for (JSONObject event : session.log()) if (event.optString("kind").equals("discovery_dismiss") && event.optJSONObject("data") != null) dismissed.add(event.getJSONObject("data").optString("sourceId"));
            org.json.JSONArray candidates = findings.getJSONArray("candidates");
            if (candidates.length() == 0) items.add(new Intro("No new sources met this discovery request.", List.of()));
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.getJSONObject(i); String id = candidate.getString("sourceId");
                if (dismissed.contains(id)) continue;
                JSONObject source = ScoutEvidence.sources(session).get(id); if (source == null) continue;
                String detail = "Selected interest: " + candidate.optString("interest") + "\n" + ScoutEvidence.share(session, candidate.optString("relevance")) + "\nAdds: " + candidate.optString("novelty") + "\nCoverage: " + candidate.optString("coverage");
                items.add(new DiscoveryItem(candidate.optString("title"), detail,
                    () -> previewSource(candidate.optString("url"), null),
                    () -> { open(null); view.research(true); view.web(true); ask("Investigate this source for " + candidate.optString("interest") + ": " + candidate.optString("url")); },
                    () -> saveSource(current, source),
                    () -> { try { ScoutDiscovery.remember(ui.c, candidate.getString("url")); session.appendCustom("discovery_dismiss", new JSONObject().put("sourceId", id)); refresh(); } catch (Exception ignored) { } }));
            }
        } catch (Exception ignored) { }
    }

    private void scheduleSettings() {
        try {
            JSONObject schedule = ScoutJob.schedule(ui.c);
            org.json.JSONArray interests = ScoutDiscovery.state(ui.c).getJSONArray("interests");
            android.widget.LinearLayout box = new android.widget.LinearLayout(ui.c); box.setOrientation(android.widget.LinearLayout.VERTICAL); box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
            android.widget.TextView status = new android.widget.TextView(ui.c); status.setText(ScoutJob.description(ui.c)); box.addView(status);
            android.widget.Switch enabled = new android.widget.Switch(ui.c); enabled.setText("Enable optional discovery"); enabled.setChecked(schedule.optBoolean("enabled")); enabled.setEnabled(ScoutJob.QUALIFIED); box.addView(enabled);
            android.widget.EditText hour = new android.widget.EditText(ui.c); hour.setHint("Preferred local hour (0–23)"); hour.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); hour.setText(String.valueOf(schedule.optInt("hour", 9))); box.addView(hour);
            android.widget.Switch weekly = new android.widget.Switch(ui.c); weekly.setText("Weekly (Monday); otherwise daily"); weekly.setChecked(schedule.optString("frequency").equals("weekly")); box.addView(weekly);
            android.widget.Switch follow = new android.widget.Switch(ui.c); follow.setText("Follow device timezone"); follow.setChecked(schedule.optBoolean("followDevice", true)); box.addView(follow);
            android.widget.EditText zone = new android.widget.EditText(ui.c); zone.setHint("Timezone, e.g. Europe/Berlin"); zone.setText(schedule.optString("zone", java.time.ZoneId.systemDefault().getId())); box.addView(zone);
            android.widget.Switch network = new android.widget.Switch(ui.c); network.setText("Unmetered network only"); network.setChecked(schedule.optBoolean("unmetered", true)); box.addView(network);
            android.widget.Switch battery = new android.widget.Switch(ui.c); battery.setText("Battery not low"); battery.setChecked(schedule.optBoolean("batteryNotLow", true)); box.addView(battery);
            android.widget.Spinner interest = new android.widget.Spinner(ui.c); List<String> names = new ArrayList<>(); int selected = 0;
            for (int i = 0; i < interests.length(); i++) { JSONObject item = interests.getJSONObject(i); names.add(item.optString("name")); if (item.optString("id").equals(schedule.optString("interestId"))) selected = i; }
            interest.setAdapter(new android.widget.ArrayAdapter<>(ui.c, android.R.layout.simple_spinner_dropdown_item, names)); interest.setSelection(selected); box.addView(interest);
            android.widget.ScrollView scroll = new android.widget.ScrollView(ui.c); scroll.addView(box);
            new android.app.AlertDialog.Builder(host).setTitle("Discovery schedule").setView(scroll).setNegativeButton("Cancel", null)
                .setPositiveButton("Save preferences", (d, w) -> {
                    try {
                        schedule.put("enabled", enabled.isChecked() && ScoutJob.QUALIFIED).put("hour", Integer.parseInt(hour.getText().toString()))
                            .put("frequency", weekly.isChecked() ? "weekly" : "daily").put("weekday", 1).put("followDevice", follow.isChecked())
                            .put("zone", zone.getText().toString().trim()).put("unmetered", network.isChecked()).put("batteryNotLow", battery.isChecked()).remove("next");
                        if (interests.length() > 0) schedule.put("interestId", interests.getJSONObject(interest.getSelectedItemPosition()).getString("id"));
                        ScoutJob.configure(ui.c, schedule);
                    } catch (Exception failed) { Toast.makeText(ui.c, failed.getMessage(), Toast.LENGTH_LONG).show(); }
                }).show();
        } catch (Exception failed) { Toast.makeText(ui.c, "Schedule state unavailable", Toast.LENGTH_LONG).show(); }
    }

    private void previewSource(String path, Vault.Note note) {
        JSONObject evidence = null;
        try {
            for (JSONObject source : ScoutEvidence.sources(ScoutRuns.session(ui.c, file)).values()) {
                String target = source.optString("target");
                if (target.equals(path) || target.equals(path + ".md")) evidence = source;
            }
        } catch (Exception ignored) { }
        String detail = evidence == null ? "Legacy citation · coverage unknown" : "Coverage: " + evidence.optString("coverage") + " · " + evidence.optString("range") + "\n" + evidence.optString("retrieved") + "\n\n" + evidence.optString("excerpt");
        android.app.AlertDialog.Builder dialog = new android.app.AlertDialog.Builder(host).setTitle(note == null ? path : note.title).setMessage(detail)
            .setPositiveButton("Open", (d, w) -> openSource(path, note)).setNegativeButton("Close", null);
        if (note != null) dialog.setNeutralButton("Attach", (d, w) -> attach(note.path, note.title));
        else if (ScoutEvidence.validUrl(path) && evidence != null) {
            JSONObject source = evidence; String current = file;
            dialog.setNeutralButton("Save source", (d, w) -> saveSource(current, source));
        }
        dialog.show();
    }

    private interface TextAction { void accept(String text); }
    private void textDialog(String title, String initial, TextAction action) {
        android.widget.EditText edit = new android.widget.EditText(ui.c); edit.setText(initial); edit.setMaxLines(8);
        new android.app.AlertDialog.Builder(host).setTitle(title).setView(edit).setNegativeButton("Cancel", null)
            .setPositiveButton("Prepare preview", (d, w) -> { String text = edit.getText().toString().trim(); if (!text.isEmpty()) action.accept(text); }).show();
    }

    private void propose(String current, String path, String after, org.json.JSONArray dependencies) {
        String tree = VaultStore.tree(ui.c);
        Session session;
        try { session = ScoutRuns.session(ui.c, current); } catch (Exception unavailable) { return; }
        new Thread(() -> {
            try {
                String before = VaultWrite.readPinned(ui.c, tree, path);
                if (before != null) throw new Exception("A note already uses this title. Choose another title.");
                ScoutChanges.CALL.set(new JSONObject().put("id", "manual_" + java.util.UUID.randomUUID()).put("name", "save"));
                ScoutChanges.prepareNote(session, tree, path, before, after, dependencies, false);
                main.post(() -> { if (current.equals(file)) refresh(); });
            } catch (Exception failed) { main.post(() -> Toast.makeText(ui.c, "Could not prepare: " + failed.getMessage(), Toast.LENGTH_LONG).show()); }
            finally { ScoutChanges.CALL.remove(); }
        }, "scout-proposal").start();
    }

    private void keep(String current, String answer) {
        textDialog("Keep answer · title", "Scout " + java.time.LocalDate.now(), title -> {
            String clean = title.replaceAll("[\\/:*?\"<>|\r\n]", " ").trim();
            if (clean.length() > 100) clean = clean.substring(0, 100);
            propose(current, "Knowledge/Learnings/Scout/" + clean + ".md", "# " + title + "\n\n" + answer + "\n", new org.json.JSONArray());
        });
    }

    private void addToTopic(String current, String answer) {
        textDialog("Existing topic path", "Knowledge/Topics/", path -> {
            String tree = VaultStore.tree(ui.c);
            new Thread(() -> {
                try {
                    if (!path.startsWith("Knowledge/Topics/") || !path.endsWith(".md")) throw new Exception("Choose a topic .md path");
                    String before = VaultWrite.readPinned(ui.c, tree, path);
                    if (before == null) throw new Exception("Topic does not exist");
                    Session session = ScoutRuns.session(ui.c, current);
                    ScoutChanges.CALL.set(new JSONObject().put("id", "manual_" + java.util.UUID.randomUUID()).put("name", "add_to_topic"));
                    ScoutChanges.prepareNote(session, tree, path, before, before + "\n\n## Scout findings\n" + answer + "\n", new org.json.JSONArray(), false);
                    main.post(() -> { if (current.equals(file)) refresh(); });
                } catch (Exception failed) { main.post(() -> Toast.makeText(ui.c, failed.getMessage(), Toast.LENGTH_LONG).show()); }
                finally { ScoutChanges.CALL.remove(); }
            }).start();
        });
    }

    private void saveSource(String current, JSONObject source) {
        String[] categories = {"AI Systems", "Models & Training", "Data & Knowledge", "Developer Tools", "Health & Biology", "People & Society", "Research Workflow"};
        new android.app.AlertDialog.Builder(host).setTitle("Source category").setItems(categories, (d, at) -> {
            textDialog("Source title", source.optString("title"), title -> {
                String tree = VaultStore.tree(ui.c);
                new Thread(() -> {
                    try {
                        Session session = ScoutRuns.session(ui.c, current);
                        String date = java.time.LocalDate.now().toString();
                        String name = title.replaceAll("[\\/:*?\"<>|\r\n]", " ").trim(); if (name.length() > 100) name = name.substring(0, 100);
                        String path = "Knowledge/Sources/" + categories[at] + "/" + date.substring(0, 4) + "/" + date + " - " + name + ".md";
                        if (VaultWrite.readPinned(ui.c, tree, path) != null) throw new Exception("Source path already exists; choose another title");
                        ScoutChanges.CALL.set(new JSONObject().put("id", "manual_" + java.util.UUID.randomUUID()).put("name", "save_source"));
                        String raw = "Scout evidence excerpt. Coverage: " + source.optString("coverage") + ".\n\n" + source.optString("excerpt");
                        String body = IngestText.sourceNote(title, source.optString("target"), "", date, List.of(), List.of(), "", "", raw, List.of(), List.of("Saved excerpt; full page not asserted."), "");
                        JSONObject saved = ScoutChanges.prepareNote(session, tree, path, null, body, new org.json.JSONArray(), false);
                        org.json.JSONArray dependencies = new org.json.JSONArray().put(saved.getString("proposalId"));
                        String link = "[[" + path.substring(0, path.length() - 3) + "|" + title + "]]";
                        for (String target : new String[]{"Knowledge/Sources/index.md", "Knowledge/log.md"}) {
                            String before = VaultWrite.readPinned(ui.c, tree, target);
                            String after = (before == null ? "" : before) + "\n- [SCOUT] " + date + " " + link + "\n";
                            ScoutChanges.prepareNote(session, tree, target, before, after, dependencies, false);
                        }
                        main.post(() -> { if (current.equals(file)) refresh(); });
                    } catch (Exception failed) { main.post(() -> Toast.makeText(ui.c, failed.getMessage(), Toast.LENGTH_LONG).show()); }
                    finally { ScoutChanges.CALL.remove(); }
                }).start();
            });
        }).show();
    }

    private void exportChatFile(String current) {
        new Thread(() -> {
            try {
                Session session = ScoutRuns.session(ui.c, current);
                StringBuilder markdown = new StringBuilder("# " + ScoutRuns.title(ui.c, current) + "\n");
                for (JSONObject message : session.retained()) {
                    String role = message.optString("role");
                    if (!(role.equals("user") || role.equals("assistant")) || message.optString("content").isEmpty()) continue;
                    markdown.append("\n## ").append(role.equals("user") ? "You" : "Scout").append("\n\n").append(ScoutEvidence.share(session, message.optString("content"))).append('\n');
                }
                File dir = new File(ui.c.getCacheDir(), "scout-exports"); dir.mkdirs();
                File output = new File(dir, current.replace(".jsonl", ".md"));
                java.nio.file.Files.write(output.toPath(), markdown.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                main.post(() -> new android.app.AlertDialog.Builder(host).setTitle("Export chat").setMessage("This file contains chat text and may include selected note content. Choose where to share it.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Share file", (d, w) -> {
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(ui.c, ui.c.getPackageName() + ".scout.exports", output);
                        Intent send = new Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        host.startActivity(Intent.createChooser(send, "Export Scout chat"));
                    }).show());
            } catch (Exception failed) { main.post(() -> Toast.makeText(ui.c, "Could not export chat", Toast.LENGTH_LONG).show()); }
        }, "scout-export").start();
    }

    private void makeCards(String current) {
        textDialog("Make cards · source note path", "Knowledge/Sources/", path -> {
            if (!ScoutRuns.cards(ui.c, current, path)) Toast.makeText(ui.c, "Scout is busy; no card request sent", Toast.LENGTH_LONG).show();
            refresh();
        });
    }

    private void retryAnswer(String current, String userId, String userText, String answerId) {
        if (!java.util.Objects.equals(current, file) || ScoutRuns.run(current) != null) return;
        textDialog("Edit and retry · previous answers remain", userText, text -> {
            try { ScoutRuns.session(ui.c, current).appendCustom("answer_attempt", new JSONObject().put("userEntryId", userId).put("answerEntryId", answerId).put("attemptId", java.util.UUID.randomUUID().toString())); }
            catch (Exception failed) { return; }
            ask(text);
        });
    }

    private void openSource(String path, Vault.Note note) {
        if (Layout.isInbox(path)) { host.select(0); return; } // the inbox lives in the Feed
        if (ScoutEvidence.validUrl(path)) { ui.c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(path))); return; }
        if (note == null) { Toast.makeText(ui.c, "That note is not in your vault", Toast.LENGTH_SHORT).show(); return; }
        host.openNote(note.path);
    }
}
