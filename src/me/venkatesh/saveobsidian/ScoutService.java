package me.venkatesh.saveobsidian;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps the process alive while any Scout chat is running (a foreground service with a quiet notification), so leaving the app does not stop a run. Stops itself when none is. */
public class ScoutService extends Service {
    private static final String CHANNEL = "scout_running";

    /** Starts or stops the service to match the number of active runs. Starting needs the app to be visible, which it is when a run begins. */
    static void sync(Context c) {
        Intent i = new Intent(c, ScoutService.class);
        if (ScoutRuns.active() > 0) { try { c.startForegroundService(i); } catch (RuntimeException notAllowed) { } }
        else c.stopService(i);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Scout is working", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon).setContentTitle("Scout is working")
            .setContentText("Your chats keep running while you are away").setOngoing(true).build();
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(5, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(5, n);
        if (ScoutRuns.active() == 0) stopSelf();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
