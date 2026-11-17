package me.venkatesh.saveobsidian;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

/** One quiet reminder a day, at the time you choose, only when cards are due. Inexact on purpose: no exact-alarm permission needed. */
public class Reminder extends BroadcastReceiver {
    private static final String CHANNEL = "spark_reminder";

    static boolean on(Context c) { return c.getSharedPreferences("capture", 0).getBoolean("reminderOn", false); }
    static int minutes(Context c) { return c.getSharedPreferences("capture", 0).getInt("reminderAt", 8 * 60 + 30); }
    static String time(Context c) { int m = minutes(c); return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60); }

    /** Sets the next alarm, or cancels it when the reminder is off. */
    static void schedule(Context c) {
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        PendingIntent pi = PendingIntent.getBroadcast(c, 7, new Intent(c, Reminder.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        alarms.cancel(pi);
        if (!on(c)) return;
        LocalDateTime next = LocalDateTime.now().with(LocalTime.of(minutes(c) / 60, minutes(c) % 60));
        if (!next.isAfter(LocalDateTime.now())) next = next.plusDays(1);
        long at = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        alarms.setWindow(AlarmManager.RTC, at, 15L * 60 * 1000, pi);
    }

    @Override public void onReceive(Context c, Intent intent) {
        schedule(c); // the next one, whatever this one does (also covers a reboot)
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()) || !on(c)) return;
        try {
            Spark.Plan plan = Spark.plan(CardStore.all(c), System.currentTimeMillis(), ZoneId.systemDefault(),
                Spark.clampCap(c.getSharedPreferences("capture", 0).getInt("cardsPerDay", Spark.CAP_DEFAULT)), CardStore.newestFirst());
            if (plan.queue.isEmpty()) return; // nothing due: no notification
            NotificationManager nm = c.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Daily Spark reminder", NotificationManager.IMPORTANCE_LOW));
            int n = plan.queue.size();
            PendingIntent open = PendingIntent.getActivity(c, 8, new Intent(c, MainActivity.class).putExtra(MainActivity.TAB, 1).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            nm.notify(7, new android.app.Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.notification_icon)
                .setContentTitle("Spark").setContentText(n + (n == 1 ? " card" : " cards") + " due, about " + plan.minutes() + " min").setContentIntent(open).setAutoCancel(true).build());
        } catch (RuntimeException ignored) { }
    }
}
