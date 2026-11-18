package me.venkatesh.saveobsidian;

/** Initializes private metadata tracking for activities, services and worker-only launches. */
public final class SparkboxApplication extends android.app.Application {
    @Override public void onCreate() {
        super.onCreate();
        if (android.os.Build.VERSION.SDK_INT >= 28 && (android.os.Process.isIsolated() || !getPackageName().equals(getProcessName()))) return;
        Metrics.configure(new java.io.File(getFilesDir(), "metrics.jsonl"), getSharedPreferences("metrics", 0).getBoolean("enabled", true));
        new Thread(() -> { Metrics.prune(); Tracking.prune(this); }, "statistics-retention").start();
    }
}
