package me.venkatesh.saveobsidian;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

/** Lets Android run tagging and indexing with the app closed. Scheduled on app open and after each capture. */
public class BackgroundJob extends JobService {
    private static final int ID = 1;

    static void schedule(Context c) {
        if (Model.key(c).isEmpty()) return; // nothing to do without a key
        JobScheduler scheduler = c.getSystemService(JobScheduler.class);
        if (scheduler.getPendingJob(ID) != null) return; // also true while running; rescheduling would stop it
        scheduler.schedule(new JobInfo.Builder(ID, new ComponentName(c, BackgroundJob.class))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build());
    }

    @Override public boolean onStartJob(JobParameters params) {
        new Thread(() -> {
            Tagger.run(this);
            jobFinished(params, Tagger.waiting(this)); // only an unsent voice clip asks for a retry (with backoff); other errors wait for the next open
        }).start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) { Tagger.halt = true; return true; } // system stopped us early: run again later
}
