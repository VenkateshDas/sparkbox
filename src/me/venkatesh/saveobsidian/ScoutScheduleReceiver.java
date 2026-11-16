package me.venkatesh.saveobsidian;

/** Scheduling broadcasts are separate from Reminder's daily Spark notification path. */
public final class ScoutScheduleReceiver extends android.content.BroadcastReceiver {
    @Override public void onReceive(android.content.Context context, android.content.Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!(android.content.Intent.ACTION_BOOT_COMPLETED.equals(action) || android.content.Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
            || android.content.Intent.ACTION_TIME_CHANGED.equals(action) || android.content.Intent.ACTION_TIMEZONE_CHANGED.equals(action))) return;
        android.content.BroadcastReceiver.PendingResult pending = goAsync();
        new Thread(() -> {
            try { ScoutJob.reschedule(context.getApplicationContext()); }
            finally { pending.finish(); }
        }, "scout-reschedule").start();
    }
}
