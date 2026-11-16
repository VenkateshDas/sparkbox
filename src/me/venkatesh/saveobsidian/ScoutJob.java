package me.venkatesh.saveobsidian;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import org.json.JSONObject;

/** Dedicated discovery owner; never starts ScoutService or touches the ingest job. */
public final class ScoutJob extends JobService {
    static final int ID = 7316;
    // Release gate: enable only after Scout's disposable-vault scheduling/device matrix passes.
    static final boolean QUALIFIED = false;
    private volatile String ownedFile;
    private volatile boolean stopped;

    static JSONObject schedule(Context c) throws Exception {
        JSONObject state = ScoutDiscovery.state(c), schedule = state.optJSONObject("schedule");
        if (schedule != null) return schedule;
        return new JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("enabled", false).put("hour", 9)
            .put("frequency", "daily").put("weekday", 1).put("windowHours", 4).put("followDevice", true)
            .put("zone", ZoneId.systemDefault().getId()).put("unmetered", true).put("batteryNotLow", true).put("cap", 3);
    }
    static synchronized void configure(Context c, JSONObject schedule) throws Exception {
        if (schedule.optBoolean("enabled") && !QUALIFIED) throw new IllegalStateException("Schedules await device qualification");
        if (schedule.optInt("hour") < 0 || schedule.optInt("hour") > 23) throw new IllegalArgumentException("Hour must be 0–23");
        ZoneId.of(schedule.optString("zone", ZoneId.systemDefault().getId()));
        JSONObject state = ScoutDiscovery.state(c); state.put("schedule", schedule); ScoutDiscovery.save(c, state); reschedule(c);
    }
    static long next(JSONObject schedule, long after) {
        ZoneId zone = schedule.optBoolean("followDevice", true) ? ZoneId.systemDefault() : ZoneId.of(schedule.optString("zone"));
        ZonedDateTime now = java.time.Instant.ofEpochMilli(after).atZone(zone);
        ZonedDateTime due = now.toLocalDate().atTime(schedule.optInt("hour", 9), 0).atZone(zone);
        if (!due.isAfter(now)) due = due.plusDays(1);
        if (schedule.optString("frequency").equals("weekly")) while (due.getDayOfWeek().getValue() != schedule.optInt("weekday", 1)) due = due.plusDays(1);
        return due.toInstant().toEpochMilli();
    }
    static synchronized void reschedule(Context c) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        try {
            JSONObject schedule = schedule(c);
            if (!QUALIFIED || !schedule.optBoolean("enabled")) { scheduler.cancel(ID); return; }
            long due = schedule.optLong("next");
            if (due <= 0) {
                due = next(schedule, System.currentTimeMillis()); schedule.put("next", due);
                JSONObject state = ScoutDiscovery.state(c); state.put("schedule", schedule); ScoutDiscovery.save(c, state);
            }
            scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(c, ScoutJob.class)).setPersisted(true)
                .setRequiredNetworkType(schedule.optBoolean("unmetered", true) ? JobInfo.NETWORK_TYPE_UNMETERED : JobInfo.NETWORK_TYPE_ANY)
                .setRequiresBatteryNotLow(schedule.optBoolean("batteryNotLow", true))
                .setMinimumLatency(Math.max(0, due - System.currentTimeMillis())).build());
        } catch (Exception failed) { scheduler.cancel(ID); }
    }
    static String description(Context c) {
        try {
            JSONObject schedule = schedule(c);
            return (QUALIFIED ? "Optional; Android may delay execution." : "Disabled pending device qualification. No scheduled research runs yet.")
                + "\nState: " + (schedule.optBoolean("enabled") ? "enabled" : "disabled")
                + "\nPreferred window: " + schedule.optInt("hour", 9) + ":00 + " + schedule.optInt("windowHours", 4) + " hours · " + schedule.optString("frequency", "daily")
                + "\nTimezone: " + (schedule.optBoolean("followDevice", true) ? "follow device (" + ZoneId.systemDefault() + ")" : schedule.optString("zone"))
                + "\nNext: " + (schedule.optLong("next") == 0 ? "not scheduled" : java.time.Instant.ofEpochMilli(schedule.optLong("next")))
                + "\nLast actual run: " + schedule.optString("lastActual", "none") + "\nStatus: " + schedule.optString("status", "disabled")
                + "\nNetwork: " + (schedule.optBoolean("unmetered", true) ? "unmetered" : "any") + " · battery not low: " + schedule.optBoolean("batteryNotLow", true)
                + "\nAt most 3 suggestions; normal research limits apply. Quiet hours: 22:00–08:00. Force-stop prevents jobs until the app reopens.";
        } catch (Exception failed) { return "Schedule state unavailable; no work scheduled."; }
    }
    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> start(params), "scout-discovery-job").start();
        return true;
    }
    private void start(JobParameters params) {
        try {
            if (!QUALIFIED) { jobFinished(params, false); return; }
            JSONObject schedule;
            JSONObject interest = null;
            String file;
            boolean resume;
            synchronized (ScoutJob.class) {
                schedule = schedule(this);
                if (!schedule.optBoolean("enabled")) { jobFinished(params, false); return; }
                if (Model.key(this).isEmpty() || !ScoutDiscovery.capable(this)) throw new IllegalStateException("Add key and verified read-only research tools in Settings");
                JSONObject state = ScoutDiscovery.state(this);
                org.json.JSONArray interests = state.getJSONArray("interests");
                for (int i = 0; i < interests.length(); i++) if (interests.getJSONObject(i).optString("id").equals(schedule.optString("interestId"))) interest = interests.getJSONObject(i);
                if (interest == null || !interest.optString("tree").equals(VaultStore.tree(this))) throw new IllegalStateException("Select an interest for the current vault");
                JSONObject occurrence = schedule.optJSONObject("occurrence");
                resume = occurrence != null && occurrence.optString("status").equals("interrupted");
                if (occurrence != null && occurrence.optString("status").equals("running")) {
                    if (ScoutRuns.run(occurrence.optString("file")) != null) { jobFinished(params, true); return; }
                    resume = true;
                }
                file = resume ? occurrence.getString("file") : ScoutRuns.create(this);
                if (!resume) occurrence = new JSONObject().put("id", schedule.optString("id") + ":" + schedule.optLong("next")).put("file", file);
                occurrence.put("status", "running"); schedule.put("occurrence", occurrence).put("status", "running").put("lastActual", java.time.Instant.now().toString());
                state.put("schedule", schedule); ScoutDiscovery.save(this, state); // durable reservation before any runner dispatch
            }
            ownedFile = file;
            if (stopped) { interrupted(); return; }
            JSONObject selected = interest;
            if (!ScoutRuns.start(this, file, resume ? null : ScoutDiscovery.prompt(selected, 3), resume, true, true, selected, true, () -> settled(params, file))) {
                interrupted(); jobFinished(params, true);
            }
        } catch (Exception failed) {
            try {
                JSONObject state = ScoutDiscovery.state(this), schedule = schedule(this);
                schedule.put("status", "paused: " + failed.getMessage()).put("enabled", false); state.put("schedule", schedule); ScoutDiscovery.save(this, state);
            } catch (Exception ignored) { }
            jobFinished(params, false);
        }
    }
    private void settled(JobParameters params, String file) {
        try {
            synchronized (ScoutJob.class) {
                JSONObject state = ScoutDiscovery.state(this), schedule = schedule(this), occurrence = schedule.optJSONObject("occurrence");
                if (occurrence == null || !file.equals(occurrence.optString("file"))) return;
                if (stopped) { interrupted(); return; }
                Session session = ScoutRuns.session(this, file);
                JSONObject findings = ScoutState.latest(session, "discovery_findings");
                String status = findings == null ? "paused: discovery interrupted; review chat" : "complete";
                occurrence.put("status", findings == null ? "interrupted" : "complete"); schedule.put("status", status);
                if (findings == null) schedule.put("enabled", false);
                else schedule.put("next", next(schedule, System.currentTimeMillis()));
                state.put("schedule", schedule); ScoutDiscovery.save(this, state);
                int hour = java.time.ZonedDateTime.now().getHour();
                if (hour >= 8 && hour < 22 && (findings == null || findings.getJSONArray("candidates").length() > 0))
                    ScoutRuns.notify(this, file, findings == null ? "Scout needs attention" : "Scout found sources", ScoutRuns.title(this, file));
            }
            jobFinished(params, false); reschedule(this);
        } catch (Exception failed) { jobFinished(params, false); }
    }
    private void interrupted() {
        try {
            synchronized (ScoutJob.class) {
                JSONObject state = ScoutDiscovery.state(this), schedule = schedule(this), occurrence = schedule.optJSONObject("occurrence");
                if (occurrence != null && java.util.Objects.equals(ownedFile, occurrence.optString("file"))) occurrence.put("status", "interrupted");
                schedule.put("status", "delayed / interrupted"); state.put("schedule", schedule); ScoutDiscovery.save(this, state);
            }
        } catch (Exception ignored) { }
    }
    @Override public boolean onStopJob(JobParameters params) {
        stopped = true;
        if (ownedFile != null) ScoutRuns.cancel(ownedFile);
        interrupted();
        return true;
    }
}
