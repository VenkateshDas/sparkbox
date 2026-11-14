package me.venkatesh.saveobsidian;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;

/** Compact capture entry in the widget picker; shares actions, recording state and day/night resources. */
public final class CaptureWidget extends AppWidgetProvider {
    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PendingResult pending = goAsync();
        new Thread(() -> {
            try { manager.updateAppWidget(ids, InboxWidget.capture(context)); }
            finally { pending.finish(); }
        }).start();
    }
}
