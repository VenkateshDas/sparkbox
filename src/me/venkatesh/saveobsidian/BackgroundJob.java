package me.venkatesh.saveobsidian;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Lets Android run tagging and indexing with the app closed. Scheduled on app open and after each capture. */
public class BackgroundJob extends JobService {
    private static final int ID = 1, RETRY = 2;

    static void schedule(Context c) {
        if (Model.key(c).isEmpty()) return; // nothing to do without a key
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) != null) return; // also true while running; rescheduling would stop it
        scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(c, BackgroundJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    /** Comes back after a wait, for a capture whose processing failed and is due for another try. Replaces an earlier wait. */
    static void scheduleLater(Context c, long delayMs, int id) {
        if (Model.key(c).isEmpty()) return;
        c.getSystemService(JobScheduler.class).schedule(new JobInfo.Builder(id, new ComponentName(c, BackgroundJob.class))
            .setMinimumLatency(delayMs).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Tagger.run(this);
            long wait = Ingest.retryDelay(this);
            if (wait >= 0) scheduleLater(this, wait, params.getJobId() == RETRY ? RETRY + 1 : RETRY); // not its own id: that would stop this very job
            jobFinished(params, Tagger.waiting(this)); // only an unsent voice clip asks for a retry (with backoff); other errors wait for the next open
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { Tagger.halt = true; return true; } // system stopped us early: run again later
}
