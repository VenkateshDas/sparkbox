package me.venkatesh.saveobsidian;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Scout with nobody at the screen ("/later task" in the Scout tab). Android runs this job when there is a network, one queued task at a time, each in its
 * own session file. Read-only by design: nobody can approve anything, so changes to notes are declined and listed in the result instead, and a web or MCP
 * tool runs only when its server is marked trusted in Settings. The result waits in preferences until the Scout tab opens (and a notification says it is ready).
 */
public class ScoutJob extends JobService {
    private static final int ID = 20;
    private static final String CHANNEL = "scout_done", BG = "(Background run: nobody can answer questions or approve changes. Do not ask questions. Use read and search tools; if a change to the notes would help, describe it instead of attempting it.)\n\n";
    private volatile Cancel running;

    // ---- the queue and the finished results, in preferences ----

    private static android.content.SharedPreferences prefs(Context c) { return c.getSharedPreferences("capture", 0); }

    private static JSONArray list(Context c, String key) { try { return new JSONArray(prefs(c).getString(key, "[]")); } catch (org.json.JSONException damaged) { return new JSONArray(); } }

    static synchronized void enqueue(Context c, String task) {
        try {
            JSONArray q = list(c, "scoutLater");
            q.put(new JSONObject().put("task", task).put("time", System.currentTimeMillis()));
            prefs(c).edit().putString("scoutLater", q.toString()).apply();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) == null) // also true while running: rescheduling would stop it, and the loop picks up new tasks anyway
            scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(c, ScoutJob.class)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    private static synchronized String next(Context c) { JSONArray q = list(c, "scoutLater"); return q.length() == 0 ? null : q.optJSONObject(0).optString("task"); }

    private static synchronized void finished(Context c, String task, String answer) {
        try {
            JSONArray q = list(c, "scoutLater"), rest = new JSONArray();
            for (int i = 1; i < q.length(); i++) rest.put(q.get(i));
            JSONArray done = list(c, "scoutDone");
            done.put(new JSONObject().put("task", task).put("answer", answer).put("time", System.currentTimeMillis()));
            prefs(c).edit().putString("scoutLater", rest.toString()).putString("scoutDone", done.toString()).apply();
        } catch (org.json.JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    /** Results waiting to be shown, oldest first; they are removed. */
    static synchronized List<JSONObject> takeDone(Context c) {
        List<JSONObject> out = new ArrayList<>();
        JSONArray done = list(c, "scoutDone");
        for (int i = 0; i < done.length(); i++) if (done.optJSONObject(i) != null) out.add(done.optJSONObject(i));
        if (!out.isEmpty()) prefs(c).edit().remove("scoutDone").apply();
        return out;
    }

    static int waiting(Context c) { return list(c, "scoutLater").length(); }

    // ---- the run ----

    private static Set<String> trustedServers(Context c) {
        Set<String> out = new HashSet<>();
        JSONArray s = Scout.serverList(c);
        for (int i = 0; i < s.length(); i++) if (s.optJSONObject(i) != null && s.optJSONObject(i).optBoolean("trusted")) out.add(s.optJSONObject(i).optString("name").replaceAll("[^A-Za-z0-9_-]", "_"));
        return out;
    }

    /** One task: the answer text, with the changes it wanted to make listed at the end. */
    static String run(Context c, String task, Cancel cancel) {
        VaultStore.Snap snap = VaultStore.cached(c);
        String key = Model.key(c);
        if (snap == null || key.isEmpty()) return "Could not run: the vault or the OpenRouter key is not set up.";
        final Set<String> trusted = trustedServers(c);
        final List<String> wanted = new ArrayList<>();
        VaultTools.Confirm confirm = (title, detail) -> {
            if (title.startsWith("Call ")) return trusted.contains(title.substring(5).split("\\.")[0]); // a trusted server's tools only
            wanted.add(title + (detail.isEmpty() ? "" : "\n" + detail));
            return false; // nobody is here to approve a change
        };
        try {
            boolean research = task.startsWith("/research ");
            String text = research ? task.substring(10).trim() : task;
            Session session = Session.create(Scout.dir(c));
            session.setName("Later: " + (task.length() > 60 ? task.substring(0, 60) : task));
            Agent a = Scout.agent(c, snap, key, session, confirm, cancel, warning -> { }, research);
            JSONObject last = a.prompt(BG + Scout.expand(c, snap, text));
            String fix = last.optString("stopReason").equals("stop") && !cancel.stopped() ? Scout.check(a, last.optString("content")) : null;
            if (fix != null) last = a.prompt(fix);
            if (cancel.stopped()) return "Stopped before it finished.";
            if ("error".equals(last.optString("stopReason"))) return "Could not finish: " + last.optString("errorMessage");
            String answer = last.optString("content", "").trim();
            if (answer.isEmpty()) answer = "The model returned nothing.";
            if (!wanted.isEmpty()) answer += "\n\nChanges I would make (not made, since nobody could approve them; ask me in the chat to apply them):\n- " + String.join("\n- ", wanted);
            return answer;
        } catch (Exception e) { return "Could not finish: " + e.getMessage(); }
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Limits.interactive(false);
            String task;
            while (running == null || !running.stopped()) {
                if ((task = next(this)) == null) break;
                running = new Cancel();
                String answer = run(this, task, running);
                if (running.stopped()) break; // Android stopped the job: the task stays queued for the next run
                finished(this, task, answer);
                notifyDone(this, task);
            }
            jobFinished(params, running != null && running.stopped() && waiting(this) > 0);
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { Cancel c = running; if (c != null) c.stop(); return true; }

    private static void notifyDone(Context c, String task) {
        try {
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Scout results", NotificationManager.IMPORTANCE_LOW));
            PendingIntent open = PendingIntent.getActivity(c, 9, new Intent(c, MainActivity.class).putExtra(MainActivity.TAB, 3).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(9, new android.app.Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.notification_icon)
                .setContentTitle("Scout finished").setContentText(task.length() > 80 ? task.substring(0, 80) : task).setContentIntent(open).setAutoCancel(true).build());
        } catch (RuntimeException ignored) { }
    }
}
