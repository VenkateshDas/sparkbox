package me.venkatesh.saveobsidian;

import android.Manifest;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One widget for everything: what was added on a chosen day (captures and notes, with their cards) and a 14-day chart, with arrows to move between days, and Write and Speak.
 * While recording, the action row turns into a live strip (timer, moving waveform, Stop) inside the widget.
 * The launcher picks the layout from the widget's size: from two columns (capture only) to a tall card.
 */
public class InboxWidget extends AppWidgetProvider {
    private static final int DAYS = 14;

    @Override public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        PendingResult pending = goAsync();
        new Thread(() -> { try { manager.updateAppWidget(ids, build(context)); } finally { pending.finish(); } }).start();
    }

    /** Redraw after the inbox or the recording state changed. Cheap no-op when no widget is placed. */
    static void refresh(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, InboxWidget.class));
        if (ids.length > 0) new Thread(() -> manager.updateAppWidget(ids, build(context))).start();
    }

    private static final String DAY = "me.venkatesh.saveobsidian.WIDGET_DAY";

    private static final class Stats {
        String message = "", label = "Today", heading = "Added";
        int total, cards, selected = DAYS - 1;
        boolean older, newer;
        final int[] perDay = new int[DAYS];
        String[] title = {"", "", ""}, meta = {"", "", ""};
    }

    /** The day the widget shows, as an epoch day within the last DAYS days. */
    private static long selectedDay(Context c) {
        long today = LocalDate.now().toEpochDay(), stored = c.getSharedPreferences("capture", 0).getLong("widgetDay", today);
        return Math.max(today - (DAYS - 1), Math.min(today, stored));
    }

    @Override public void onReceive(Context context, Intent intent) {
        if (DAY.equals(intent.getAction())) {
            long today = LocalDate.now().toEpochDay(), next = Math.max(today - (DAYS - 1), Math.min(today, selectedDay(context) + intent.getIntExtra("by", 0)));
            context.getSharedPreferences("capture", 0).edit().putLong("widgetDay", next).apply();
            refresh(context);
            return;
        }
        super.onReceive(context, intent);
    }

    /** The captures and notes added on the chosen day, how many cards they came with, and a 14-day bar chart that follows the choice. */
    private static Stats read(Context context) {
        Stats s = new Stats();
        if (context.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty()) { s.message = "Choose your vault folder in the app"; return s; }
        try {
            List<InboxFile.Entry> entries;
            try { entries = InboxFile.parse(CaptureService.readText(context)); } catch (Exception none) { entries = new java.util.ArrayList<>(); }
            VaultStore.Snap snap = VaultStore.cached(context);
            Vault.Graph graph = snap != null ? snap.graph : Vault.build(new java.util.ArrayList<Vault.Note>());
            long today = LocalDate.now().toEpochDay(), day = selectedDay(context);
            s.selected = DAYS - 1 - (int) (today - day);
            s.older = day > today - (DAYS - 1);
            s.newer = day < today;
            s.label = day == today ? "Today" : day == today - 1 ? "Yesterday" : LocalDate.ofEpochDay(day).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.getDefault()));
            int shown = 0;
            for (Feed.Item it : Feed.build(graph.notes, graph, entries, java.time.LocalDateTime.now())) {
                if (!Feed.SOURCE.equals(it.kind)) continue;
                long d = Math.floorDiv(it.minute, 24L * 60);
                long ago = today - d;
                if (ago >= 0 && ago < DAYS) s.perDay[DAYS - 1 - (int) ago]++;
                if (d != day) continue;
                s.total++;
                s.cards += it.cards;
                if (shown < 3) {
                    s.title[shown] = it.title;
                    s.meta[shown] = it.status + (it.cards > 0 ? " · " + it.cards + (it.cards == 1 ? " card" : " cards") : "");
                    shown++;
                }
            }
            if (s.total == 0) s.title[0] = "Nothing added this day";
        } catch (Exception unreadable) { s.message = "Open Sparkbox to reconnect the vault"; }
        return s;
    }

    /** Fourteen bars on a hairline, today in the accent with its count above it. Drawn at the size it is shown, so nothing is stretched. */
    private static Bitmap chart(Context c, Stats s) {
        Ui ui = new Ui(c);
        float density = c.getResources().getDisplayMetrics().density;
        int w = (int) (130 * density), h = (int) (46 * density);
        Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int max = 1;
        for (int n : s.perDay) max = Math.max(max, n);
        float label = 13 * density, base = h - 1.5f * density, room = base - label - 2 * density, slot = w / (float) DAYS, bar = slot * 0.62f, radius = bar / 2.4f, floor = 3 * density;
        paint.setColor(ui.line);
        canvas.drawRect(0, base, w, base + density, paint);
        for (int i = 0; i < DAYS; i++) {
            float top = base - (s.perDay[i] == 0 ? floor : floor + (room - floor) * s.perDay[i] / max), left = slot * i + (slot - bar) / 2;
            boolean today = i == s.selected; // the chosen day
            paint.setColor(today ? ui.accent : s.perDay[i] == 0 ? ui.line : (ui.ink & 0xFFFFFF) | 0x40000000);
            canvas.drawRoundRect(new RectF(left, top, left + bar, base - density), radius, radius, paint);
            if (today && s.perDay[i] > 0) {
                paint.setColor(ui.accent);
                paint.setTextSize(10 * density);
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTypeface(Ui.MEDIUM);
                canvas.drawText(String.valueOf(s.perDay[i]), Math.min(w - 5 * density, left + bar / 2), Math.max(10 * density, top - 3 * density), paint);
            }
        }
        return bitmap;
    }

    private static PendingIntent sheet(Context c, int code, String action) {
        Intent open = new Intent(c, QuickActivity.class);
        if (action != null) open.setAction(action);
        return PendingIntent.getActivity(c, code, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent app(Context c) {
        return PendingIntent.getActivity(c, 0, new Intent(c, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Timer and Stop for the live strip. */
    private static void strip(Context c, RemoteViews views) {
        long elapsed = System.currentTimeMillis() - RecordService.startedMs;
        views.setChronometer(R.id.w_clock, SystemClock.elapsedRealtime() - elapsed, null, true);
        views.setOnClickPendingIntent(R.id.w_stop, PendingIntent.getService(c, 3, new Intent(c, RecordService.class).setAction(RecordService.STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
    }

    private static void actions(Context c, RemoteViews views) {
        views.setOnClickPendingIntent(R.id.w_write, sheet(c, 1, null));
        // With the microphone already allowed the tap records at once; the first time it opens the sheet to ask.
        boolean allowed = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        views.setOnClickPendingIntent(R.id.w_speak, allowed
            ? PendingIntent.getForegroundService(c, 5, new Intent(c, RecordService.class).setAction(RecordService.START), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
            : sheet(c, 2, QuickActivity.VOICE));
    }

    private static String caption(Stats s, boolean oneLine) {
        if (!s.message.isEmpty()) return "";
        return (s.total == 1 ? "1 added" : s.total + " added") + (oneLine ? " · " : "\n") + (s.cards == 1 ? "1 card" : s.cards + " cards");
    }

    private static RemoteViews full(Context c, Stats s, boolean latest) {
        RemoteViews views = new RemoteViews(c.getPackageName(), R.layout.widget_spark_full);
        views.setTextViewText(R.id.w_label, s.message.isEmpty() ? s.label : s.message);
        views.setTextViewText(R.id.w_n1, s.message.isEmpty() ? String.valueOf(s.total) : "–");
        views.setTextViewText(R.id.w_heading, s.total > 3 ? "Latest of " + s.total : "Added");
        views.setViewVisibility(R.id.w_prev, s.older ? View.VISIBLE : View.INVISIBLE);
        views.setViewVisibility(R.id.w_next, s.newer ? View.VISIBLE : View.INVISIBLE);
        views.setOnClickPendingIntent(R.id.w_prev, PendingIntent.getBroadcast(c, 10, new Intent(c, InboxWidget.class).setAction(DAY).putExtra("by", -1), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        views.setOnClickPendingIntent(R.id.w_next, PendingIntent.getBroadcast(c, 11, new Intent(c, InboxWidget.class).setAction(DAY).putExtra("by", 1), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        views.setTextViewText(R.id.w_caption, caption(s, false));
        views.setImageViewBitmap(R.id.w_chart, chart(c, s));
        views.setViewVisibility(R.id.w_latest, latest ? View.VISIBLE : View.GONE);
        views.setTextViewText(R.id.w_t1, s.title[0]); views.setTextViewText(R.id.w_m1, s.meta[0]);
        views.setViewVisibility(R.id.w_row2, s.title[1].isEmpty() ? View.GONE : View.VISIBLE);
        views.setTextViewText(R.id.w_t2, s.title[1]); views.setTextViewText(R.id.w_m2, s.meta[1]);
        views.setViewVisibility(R.id.w_row3, s.title[2].isEmpty() ? View.GONE : View.VISIBLE);
        views.setTextViewText(R.id.w_t3, s.title[2]); views.setTextViewText(R.id.w_m3, s.meta[2]);
        boolean recording = RecordService.active;
        views.setViewVisibility(R.id.w_actions, recording ? View.GONE : View.VISIBLE);
        views.setViewVisibility(R.id.w_rec, recording ? View.VISIBLE : View.GONE);
        if (recording) strip(c, views); else actions(c, views);
        views.setOnClickPendingIntent(R.id.w_root, recording ? sheet(c, 2, QuickActivity.VOICE) : app(c));
        return views;
    }

    /** One row or two columns wide: capture only, no numbers. Two tiles, Write and Speak. */
    private static RemoteViews slim(Context c, boolean wide) {
        if (RecordService.active) {
            RemoteViews views = new RemoteViews(c.getPackageName(), wide ? R.layout.widget_rec_only : R.layout.widget_rec_micro);
            strip(c, views);
            views.setOnClickPendingIntent(R.id.w_root, sheet(c, 2, QuickActivity.VOICE));
            return views;
        }
        RemoteViews views = new RemoteViews(c.getPackageName(), wide ? R.layout.widget_spark_slim : R.layout.widget_spark_micro);
        actions(c, views);
        views.setOnClickPendingIntent(R.id.w_root, app(c));
        return views;
    }

    private static RemoteViews build(Context c) {
        Stats s = read(c);
        if (android.os.Build.VERSION.SDK_INT < 31) return full(c, s, false);
        Map<SizeF, RemoteViews> sizes = new HashMap<>(); // the launcher picks the largest layout that fits the widget's current size
        sizes.put(new SizeF(0, 0), slim(c, false));
        sizes.put(new SizeF(230, 56), slim(c, true));
        sizes.put(new SizeF(230, 110), full(c, s, false));
        sizes.put(new SizeF(230, 180), full(c, s, true));
        return new RemoteViews(sizes);
    }
}
