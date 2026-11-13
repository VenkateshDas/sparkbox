package me.venkatesh.saveobsidian;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONObject;

/**
 * Every Scout chat is a stored session that runs on its own thread inside this process, kept alive by ScoutService while any run is active, so leaving the
 * app does not stop it. The screen only watches: it shows the stored messages plus the live state here (status line, streamed text, a change waiting for the
 * user's OK). An approval is part of the run: the run waits on it, a notification says so, and the card in the chat answers it whenever the user comes back.
 * If Android kills the process the run is gone, the log stays, and the chat offers to continue (Agent.resume).
 */
final class ScoutRuns {
    static final String SESSION = "session"; // intent extra: which chat to open
    private static final String CHANNEL = "scout_chat";
    private static final long APPROVAL_WAIT_MS = 6L * 60 * 60 * 1000; // dataSync services may run about 6 hours

    private ScoutRuns() { }

    interface Watcher {
        /** Messages, status or an approval changed: draw the chat again. */
        void changed(String file);
        /** Text of the answer so far (empty when a step turned out to be a tool call). */
        void live(String file, String text);
    }

    static final class Approval {
        final String title, detail;
        final java.util.concurrent.ArrayBlockingQueue<Boolean> answer = new java.util.concurrent.ArrayBlockingQueue<>(1);
        Approval(String title, String detail) { this.title = title; this.detail = detail; }
    }

    static final class Run {
        final String file;
        final long began = System.currentTimeMillis();
        final Cancel cancel = new Cancel();
        volatile Agent agent;
        volatile String status = "Starting…", live = "";
        /** The message just sent, shown at once; cleared when the agent has stored it (building the agent can take seconds: MCP servers connect first). */
        volatile String sent;
        /** How many user messages the log held when the run began: the echo shows only while the log still has no newer one. */
        int usersAtStart;
        volatile Approval pending;
        volatile boolean done;
        Run(String file) { this.file = file; }
    }

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Run> runs = new ConcurrentHashMap<>();
    private static final List<Watcher> watchers = new CopyOnWriteArrayList<>();
    /** The chat the user is looking at right now (no notification needed for it). */
    static volatile String watching;

    static void watch(Watcher w) { watchers.add(w); }
    static void unwatch(Watcher w) { watchers.remove(w); }
    private static void changed(String file) { for (Watcher w : watchers) w.changed(file); }

    static File dir(Context c) { return Scout.dir(c); }

    /** One Session object per file, shared by the run and the screen. */
    static Session session(Context c, String file) throws java.io.IOException {
        Session s = sessions.get(file);
        if (s == null) { s = Session.open(new File(dir(c), file)); sessions.put(file, s); }
        return s;
    }

    /** A new empty chat; its file name. */
    static String create(Context c) throws java.io.IOException {
        Session s = Session.create(dir(c));
        String file = new File(s.exportPath()).getName();
        sessions.put(file, s);
        return file;
    }

    static Run run(String file) { Run r = file == null ? null : runs.get(file); return r != null && !r.done ? r : null; }
    static boolean waitingForYou(String file) { Run r = run(file); return r != null && r.pending != null; }
    static int active() { int n = 0; for (Run r : runs.values()) if (!r.done) n++; return n; }

    // ---- control ----

    /** Starts a run on this chat: the text as a new message, or (again) a continuation of what is stored. Returns false when it is already running. */
    static synchronized boolean start(Context context, String file, String text, boolean again, boolean research) {
        if (run(file) != null) return false;
        final Context c = context.getApplicationContext();
        final Run run = new Run(file);
        run.sent = text == null ? null : text.startsWith("/research ") ? text.substring(10).trim() : text;
        try { for (Chat.Row r : Chat.rows(session(c, file).context())) if (r.kind.equals("user")) run.usersAtStart++; } catch (java.io.IOException unreadable) { }
        runs.put(file, run);
        ScoutService.sync(c);
        new Thread(() -> execute(c, run, text, again, research)).start();
        return true;
    }

    private static void execute(Context c, Run run, String text, boolean again, boolean research) {
        String failure = null;
        boolean loggedByAgent = false;
        Limits.interactive(true);
        try {
            VaultStore.Snap snap = VaultStore.cached(c);
            String key = Model.key(c);
            if (snap == null || key.isEmpty()) throw new Exception("The vault or the OpenRouter key is not set up.");
            if (text != null && text.startsWith("/research ")) { research = true; text = text.substring(10).trim(); }
            Session session = session(c, run.file);
            if (session.name() == null && text != null) nameChat(c, session, text, key);
            Agent a = run.agent = Scout.agent(c, snap, key, session, (title, detail) -> ask(c, run, title, detail), run.cancel, w -> { run.status = w; changed(run.file); }, research);
            a.subscribe((type, data) -> events(c, run, type, data));
            Usage.hit(c, "ask");
            JSONObject last;
            if (again) last = a.resume();
            else last = a.prompt(Scout.expand(c, snap, text));
            if ("error".equals(last.optString("stopReason"))) { failure = last.optString("errorMessage"); loggedByAgent = true; } // the agent already stored its failed turn
        } catch (Exception e) { failure = String.valueOf(e.getMessage()); }
        if (failure != null && !loggedByAgent) { // kept in the log as a failed turn, so the chat shows it and offers to continue
            try { Session s = session(c, run.file); s.appendMessage(new JSONObject().put("role", "assistant").put("content", "").put("stopReason", "error").put("errorMessage", friendly(failure))); } catch (Exception ignored) { }
        }
        run.done = true;
        run.live = ""; run.status = ""; run.pending = null;
        changed(run.file);
        if (!run.file.equals(watching) && !run.cancel.stopped()) notify(c, run.file, failure == null ? "Scout finished" : "Scout could not finish", title(c, run.file));
        ScoutService.sync(c);
    }

    /** The chat's name: the start of the first message at once, then a short title from the small model (unless the user renamed it meanwhile). */
    private static void nameChat(Context c, Session session, String text, String key) throws java.io.IOException {
        final String first = text.length() > 40 ? text.substring(0, 40) : text;
        session.setName(first);
        new Thread(() -> {
            try {
                String model = c.getSharedPreferences("capture", 0).getString("aiModel", Model.DEFAULT);
                String t = Model.completeLean(key, model, "Write a title of 2 to 5 words for a chat that starts with the user's message. Reply with the title only: no quotes, no final punctuation.", text.length() > 500 ? text.substring(0, 500) : text, 30);
                t = t == null ? "" : t.trim().split("\n")[0].replaceAll("^[\"'#*\\s]+|[\"'*\\s.]+$", "");
                if (t.length() > 48) t = t.substring(0, 48);
                if (!t.isEmpty() && first.equals(session.name())) { session.setName(t); for (Watcher w : watchers) w.changed(""); }
            } catch (Exception keepFirst) { } // the start of the message stays as the name
        }).start();
    }

    static void rename(Context c, String file, String name) throws java.io.IOException {
        if (!name.trim().isEmpty()) session(c, file).setName(name.trim().length() > 60 ? name.trim().substring(0, 60) : name.trim());
    }

    /** Stops the chat's run if any, then removes its file. */
    static void delete(Context c, String file) {
        Run r = run(file);
        if (r != null) { r.cancel.stop(); r.done = true; }
        runs.remove(file);
        Session s = sessions.remove(file);
        if (s != null) s.close();
        new File(dir(c), file).delete();
        clearNotification(c, file);
    }

    private static String friendly(String m) {
        String msg = m.replace("Smart tags paused: ", ""), k = Failures.kind(msg);
        return k.equals(Failures.OTHER) || k.equals(Failures.CONFLICT) ? msg : Failures.recovery(k, msg).replace("It is tried again by itself when you are back online; Retry tries now.", "Try again when you are online.");
    }

    private static void events(Context c, Run run, String type, JSONObject data) {
        if (type.equals("message_update")) { run.live = data.optString("text"); for (Watcher w : watchers) w.live(run.file, run.live); return; }
        if (type.equals("message_end") && data.optJSONObject("message") != null && data.optJSONObject("message").optString("role").equals("user")) run.sent = null;
        if (type.equals("message_end")) { run.live = ""; for (Watcher w : watchers) w.live(run.file, ""); }
        else if (type.equals("tool_execution_start")) run.status = Chat.step(data.optString("name"), data.optJSONObject("args"));
        else if (type.equals("retry")) run.status = "Retrying…";
        else if (type.equals("compaction_start")) run.status = "Summarising the conversation so far…";
        else if (type.equals("turn_start")) run.status = "Thinking…";
        if (type.equals("tool_execution_end") && !data.optBoolean("isError") && (data.optString("name").equals("edit") || data.optString("name").equals("write")))
            new Thread(() -> { try { VaultStore.scan(c); } catch (Exception stale) { } }).start(); // the note list and links catch up with the change
        changed(run.file);
    }

    /** Blocks the run until the user answers the card in the chat (or stops the run). */
    private static boolean ask(Context c, Run run, String title, String detail) {
        synchronized (run) { return askOne(c, run, title, detail); } // parallel tool calls ask one after another: one card at a time, none lost
    }

    private static boolean askOne(Context c, Run run, String title, String detail) {
        Approval a = new Approval(title, detail);
        run.pending = a;
        changed(run.file);
        if (!run.file.equals(watching)) notify(c, run.file, "Scout needs your OK", title);
        try {
            long until = System.currentTimeMillis() + APPROVAL_WAIT_MS;
            while (System.currentTimeMillis() < until) {
                Boolean r = a.answer.poll(500, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (r != null) return r;
                if (run.cancel.stopped()) return false;
            }
            return false;
        } catch (InterruptedException interrupted) { return false; }
        finally { run.pending = null; changed(run.file); }
    }

    /** The user left the chat while a change waits for their OK: say so in a notification. */
    static void leaving(Context c, String file) { if (file != null && waitingForYou(file)) notify(c, file, "Scout needs your OK", run(file).pending.title); }

    static void approve(String file, boolean yes) { Run r = run(file); Approval a = r == null ? null : r.pending; if (a != null) a.answer.offer(yes); }

    static void cancel(String file) { Run r = run(file); if (r != null) { r.status = "Cancelling…"; r.cancel.stop(); changed(file); } }

    static void steer(String file, String text) { Run r = run(file); if (r != null && r.agent != null) r.agent.steer(text); }

    // ---- notifications ----

    static String title(Context c, String file) {
        try { Session s = session(c, file); String n = s.name(); return n == null ? "Chat" : n; } catch (Exception e) { return "Chat"; }
    }

    static void notify(Context c, String file, String title, String text) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Scout chats", NotificationManager.IMPORTANCE_DEFAULT));
            PendingIntent open = PendingIntent.getActivity(c, file.hashCode(), new Intent(c, MainActivity.class).putExtra(MainActivity.TAB, 3).putExtra(SESSION, file)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(file.hashCode(), new android.app.Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.notification_icon).setContentTitle(title)
                .setContentText(text.length() > 80 ? text.substring(0, 80) : text).setContentIntent(open).setAutoCancel(true).build());
        } catch (RuntimeException ignored) { }
    }

    static void clearNotification(Context c, String file) { if (file != null) c.getSystemService(NotificationManager.class).cancel(file.hashCode()); }
}
