package me.venkatesh.saveobsidian;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** 2x1 home-screen widget: the first row of the Overview, items in the inbox, this week, oldest. Tap opens the app. */
public class InboxWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PendingResult pending = goAsync();
        new Thread(() -> { try { manager.updateAppWidget(ids, build(context)); } finally { pending.finish(); } }).start();
    }

    /** Redraw after the inbox changed. Cheap no-op when no widget is placed. */
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, InboxWidget.class));
        if (ids.length > 0) new Thread(() -> manager.updateAppWidget(ids, build(context))).start();
    }

    private static RemoteViews build(Context context) {
        String total = "–", week = "–", oldest = "–", status = "Choose inbox";
        if (!context.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty()) {
            status = "Reconnect inbox";
            try {
                java.util.List<InboxFile.Entry> entries = InboxFile.parse(CaptureService.readText(context));
                LocalDate today = LocalDate.now(), first = null;
                int thisWeek = 0;
                for (InboxFile.Entry e : entries) {
                    LocalDate d = InboxFile.day(e.time);
                    if (d == null) continue;
                    if (first == null || d.isBefore(first)) first = d;
                    long ago = ChronoUnit.DAYS.between(d, today);
                    if (ago >= 0 && ago < 7) thisWeek++;
                }
                status = "Sparkbox · remaining inbox";
                total = String.valueOf(entries.size());
                week = String.valueOf(thisWeek);
                if (first != null) oldest = ChronoUnit.DAYS.between(first, today) + "d";
            } catch (Exception unreadable) { }
        }
        RemoteViews views = new RemoteViews(context.getPackageName(), R.layout.widget_inbox);
        views.setTextViewText(R.id.w_status, status);
        views.setTextViewText(R.id.w_n1, total);
        views.setTextViewText(R.id.w_n2, week);
        views.setTextViewText(R.id.w_n3, oldest);
        views.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 0, new Intent(context, SetupActivity.class),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        return views;
    }
}
