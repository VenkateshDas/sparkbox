package me.venkatesh.saveobsidian;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;

/** One Android-owned processor, with persisted wakeups for new captures and retries. */
public class BackgroundJob extends JobService {
    private static final int ID = 1, RETRY = 2;
    private static final Object LOCK = new Object();
    private static Run active;
    private static boolean requested;

    private static final class Run {
        final JobParameters params;
        final Cancel cancel = new Cancel();
        Run(JobParameters params) { this.params = params; }
    }

    static void schedule(Context c) {
        if (Model.key(c).isEmpty() && CaptureService.pending(c).length == 0 && !Ingest.ready(c)) return;
        synchronized (LOCK) {
            if (active != null) { requested = true; return; }
            JobScheduler scheduler = c.getSystemService(JobScheduler.class);
            JobInfo pending = scheduler.getPendingJob(ID);
            if (pending != null && pending.getMinLatencyMillis() == 0) return;
            submit(c, 0, ID, true);
        }
    }

    /** Fresh captures and manual retries use the same durable owner. */
    static void now(Context c) { schedule(c); }

    private static boolean submit(Context c, long delayMs, int id, boolean fresh) {
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        JobInfo.Builder job = new JobInfo.Builder(id, new ComponentName(c, BackgroundJob.class))
            .setPersisted(true);
        // Local destination writes must run offline; network calls retain their own bounded retries.
        boolean local = false;
        for (java.io.File f : CaptureService.pending(c)) try { org.json.JSONObject j = CaptureService.readJob(f); local |= DirectCapture.direct(j) && !j.has("error"); } catch (Exception ignored) { }
        if (!local) job.setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY);
        if (delayMs > 0) job.setMinimumLatency(delayMs);
        if (fresh && Build.VERSION.SDK_INT >= 31) job.setExpedited(true);
        int result = scheduler.schedule(job.build());
        if (result != JobScheduler.RESULT_SUCCESS && fresh && Build.VERSION.SDK_INT >= 31)
            result = scheduler.schedule(job.setExpedited(false).build());
        if (result != JobScheduler.RESULT_SUCCESS)
            android.util.Log.w("Sparkbox", "Could not schedule capture processing job " + id);
        return result == JobScheduler.RESULT_SUCCESS;
    }

    /** Reconcile durable work after update or reboot; do not start a foreground service at boot. */
    public static class Recover extends android.content.BroadcastReceiver {
        @Override public void onReceive(Context c, android.content.Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (android.content.Intent.ACTION_BOOT_COMPLETED.equals(action)
                || android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) schedule(c);
        }
    }

    @Override public boolean onStartJob(JobParameters params) {
        final Run run;
        synchronized (LOCK) {
            // A retry wakeup can overlap the initial job. Only the active job owns work.
            if (active != null) { requested = true; jobFinished(params, true); return true; }
            active = run = new Run(params);
            requested = false;
        }
        new Thread(() -> {
            long wait = 60_000L; // an unexpected exception must still leave a wakeup
            try {
                Tagger.run(this, run.cancel);
                wait = Ingest.retryDelay(this);
                if (Tagger.waiting(this) || DirectCapture.pending(this, true)) wait = wait < 0 ? 120_000L : Math.min(wait, 120_000L);
            } catch (Exception failed) {
                android.util.Log.w("Sparkbox", "Capture processor interrupted", failed);
            } finally {
                synchronized (LOCK) {
                    if (active == run) {
                        // Schedule a different ID before releasing this job's lifecycle protection.
                        if (requested || run.cancel.stopped()) wait = 0;
                        boolean scheduled = wait >= 0 && submit(this, wait, params.getJobId() == ID ? RETRY : ID, false);
                        if (!run.cancel.stopped()) jobFinished(params, wait >= 0 && !scheduled);
                        active = null;
                        requested = false;
                    }
                }
            }
        }, "sparkbox-processing").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        synchronized (LOCK) {
            if (active != null && active.params.getJobId() == params.getJobId()) active.cancel.stop();
        }
        return true;
    }
}
