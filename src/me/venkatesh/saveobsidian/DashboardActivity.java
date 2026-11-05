package me.venkatesh.saveobsidian;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Overview of the inbox: how much is waiting, how fast it grows, what it is made of. */
public class DashboardActivity extends Activity {
    private static final int DAYS = 14;
    private Ui ui;
    private LinearLayout page;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(ui.bg);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(32));
        scroll.addView(page);
        setContentView(scroll);
        Ui.insets(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        new Thread(() -> {
            List<InboxFile.Entry> entries;
            try { entries = InboxFile.parse(CaptureService.readText(this)); } catch (Exception e) { entries = null; }
            List<InboxFile.Entry> done = entries;
            runOnUiThread(() -> { if (!isDestroyed()) draw(done); });
        }).start();
    }

    private void draw(List<InboxFile.Entry> entries) {
        page.removeAllViews();
        TextView back = ui.text("‹  Inbox", 14, Ui.MEDIUM, ui.sub);
        back.setMinHeight(ui.dp(48));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setMinWidth(ui.dp(48));
        back.setOnClickListener(v -> finish());
        Ui.buttonRole(back);
        page.addView(back, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        page.addView(ui.text("Overview", 20, Ui.MEDIUM, ui.ink), ui.lp(8));
        page.addView(ui.text("Only items still in this inbox. Processing items in Obsidian changes these counts.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (entries == null) { page.addView(ui.text("Cannot read the inbox. Go back and choose the file again.", 14, Typeface.DEFAULT, ui.sub), ui.lp(16)); return; }

        LocalDate today = LocalDate.now();
        int notes = 0, mine = 0, week = 0, before = 0, busiest = 0, busiestAt = -1;
        int[] perDay = new int[DAYS];
        LocalDate oldest = null;
        Map<String, Integer> hosts = new HashMap<>(), types = new HashMap<>(), tags = new HashMap<>();
        for (InboxFile.Entry e : entries) {
            if (e.text) notes++;
            if (!e.why.isEmpty() || !e.ideas.isEmpty()) mine++;
            if (!e.text && !e.url.isEmpty()) hosts.merge(Ui.host(e.url), 1, Integer::sum);
            if (!e.type.isEmpty()) types.merge(e.type, 1, Integer::sum);
            for (String t : e.tags.split(", ")) if (!t.isEmpty()) tags.merge("#" + t, 1, Integer::sum);
            LocalDate d = InboxFile.day(e.time);
            if (d == null) continue;
            if (oldest == null || d.isBefore(oldest)) oldest = d;
            long ago = ChronoUnit.DAYS.between(d, today);
            if (ago >= 0 && ago < DAYS) perDay[DAYS - 1 - (int) ago]++;
            if (ago >= 0 && ago < 7) week++; else if (ago >= 7 && ago < 14) before++;
        }
        for (int i = 0; i < DAYS; i++) if (perDay[i] > busiest) { busiest = perDay[i]; busiestAt = i; }
        int total = entries.size();
        if (total == 0) { page.addView(ui.text("The inbox is empty. Share a link or text from any app to start.", 14, Typeface.DEFAULT, ui.sub), ui.lp(16)); return; }

        // Three plain figures, each with what it compares to.
        LinearLayout stats = new LinearLayout(this);
        boolean stacked = getResources().getConfiguration().screenWidthDp < 400 || getResources().getConfiguration().fontScale > 1.2f;
        if (stacked) stats.setOrientation(LinearLayout.VERTICAL);
        long age = oldest == null ? 0 : ChronoUnit.DAYS.between(oldest, today);
        stats.addView(stat("In inbox", String.valueOf(total), notes == 0 ? "all links" : notes + (notes == 1 ? " is a note" : " are notes")), cell(false, stacked));
        stats.addView(stat("Last 7 days", String.valueOf(week), "vs " + before + " in prior 7 days"), cell(true, stacked));
        stats.addView(stat("Oldest", oldest == null ? "–" : String.valueOf(age), oldest == null ? "no dates" : (age == 1 ? "day, " : "days, ") + "since " + day(oldest)), cell(true, stacked));
        page.addView(stats, ui.lp(24));

        LinearLayout chart = ui.card("Inbox items by capture date");
        chart.addView(new Bars(this, ui, perDay, today), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(160)));
        chart.addView(ui.text(busiest == 0 ? "Nothing captured in the last " + DAYS + " days" : "Busiest day: " + day(today.minusDays(DAYS - 1 - busiestAt)) + ", " + busiest + (busiest == 1 ? " capture" : " captures"),
            12, Typeface.DEFAULT, ui.muted), ui.lp(12));
        page.addView(chart, ui.lp(16));

        LinearLayout mix = ui.card("Contents");
        mix.addView(meter("Links", total - notes, total, ui.muted));
        mix.addView(meter("Text notes", notes, total, ui.muted), ui.lp(16));
        mix.addView(meter("With your why or ideas", mine, total, ui.accent), ui.lp(16));
        page.addView(mix, ui.lp(16));

        distribution("Types", types, 7);
        distribution("Top tags", tags, 10);
        distribution("Top sources", hosts, 6);
    }

    /** Biggest counts first, bars relative to the largest. Skipped when empty (nothing tagged yet). */
    private void distribution(String heading, Map<String, Integer> counts, int limit) {
        if (counts.isEmpty()) return;
        List<Map.Entry<String, Integer>> top = new ArrayList<>(counts.entrySet());
        top.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
        LinearLayout card = ui.card(heading);
        for (int i = 0; i < Math.min(limit, top.size()); i++)
            card.addView(meter(top.get(i).getKey(), top.get(i).getValue(), top.get(0).getValue(), ui.muted), ui.lp(i == 0 ? 0 : 16));
        page.addView(card, ui.lp(16));
    }

    private LinearLayout.LayoutParams cell(boolean gap, boolean stacked) {
        if (stacked) { LinearLayout.LayoutParams p = ui.lp(gap ? 8 : 0); return p; }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1);
        if (gap) p.leftMargin = ui.dp(8);
        return p;
    }

    /** A label, a figure, and what it compares to. */
    private View stat(String label, String figure, String compare) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        box.setBackground(ui.round(ui.surface, ui.line, 12));
        box.addView(ui.text(label, 12, Typeface.DEFAULT, ui.sub));
        box.addView(ui.text(figure, 28, Ui.MEDIUM, ui.ink), ui.lp(8));
        TextView c = ui.text(compare, 12, Typeface.DEFAULT, ui.muted);
        box.addView(c, ui.lp(4));
        return box;
    }

    private String day(LocalDate d) {
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(TextStyle.SHORT, Locale.getDefault());
    }

    /** Label and count on one line, a thin proportion bar under it. */
    private View meter(String label, int count, int of, int color) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout top = new LinearLayout(this);
        top.addView(ui.text(label, 14, Typeface.DEFAULT, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        top.addView(ui.text(String.valueOf(count), 14, Typeface.DEFAULT, ui.sub));
        box.addView(top);
        LinearLayout track = new LinearLayout(this);
        track.setBackground(ui.round(ui.raised, 0, 2));
        View fill = new View(this);
        fill.setBackground(ui.round(color, 0, 2));
        track.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, count));
        track.addView(new View(this), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, of - count)));
        LinearLayout.LayoutParams bar = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4));
        bar.topMargin = ui.dp(8);
        box.addView(track, bar);
        return box;
    }

    /** Captures per day: neutral bars on a hairline grid, today in the accent. Rises once on open. */
    private static final class Bars extends View {
        private final Ui ui;
        private final int[] counts;
        private final String[] letters = new String[DAYS];
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float rise;

        Bars(Context c, Ui ui, int[] counts, LocalDate today) {
            super(c);
            this.ui = ui;
            this.counts = counts;
            for (int i = 0; i < DAYS; i++) letters[i] = today.minusDays(DAYS - 1 - i).getDayOfWeek().getDisplayName(TextStyle.NARROW, Locale.getDefault());
            paint.setTextAlign(Paint.Align.CENTER);
            ValueAnimator rising = ValueAnimator.ofFloat(0, 1).setDuration(400);
            rising.setInterpolator(new android.view.animation.DecelerateInterpolator(2));
            rising.addUpdateListener(a -> { rise = (float) a.getAnimatedValue(); invalidate(); });
            if (ValueAnimator.areAnimatorsEnabled()) rising.start(); else rise = 1;
            StringBuilder description = new StringBuilder("Inbox items by capture date. ");
            for (int i = 0; i < DAYS; i++) description.append(today.minusDays(DAYS - 1 - i)).append(": ").append(counts[i]).append(" items. ");
            setContentDescription(description.toString());
            setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        }

        @Override protected void onDraw(Canvas canvas) {
            int max = 1;
            for (int n : counts) max = Math.max(max, n);
            float label = ui.dp(20), top = ui.dp(20), slot = getWidth() / (float) DAYS, barW = slot * 0.5f;
            float base = getHeight() - label, room = base - top;
            paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
            paint.setStrokeWidth(1);
            for (int g = 0; g <= 2; g++) { // grid at 0, half, max
                paint.setColor(ui.line);
                float y = base - room * g / 2f;
                canvas.drawLine(0, y, getWidth(), y, paint);
            }
            paint.setColor(ui.muted);
            paint.setTextAlign(Paint.Align.LEFT);
            canvas.drawText(String.valueOf(max), 0, top - ui.dp(6), paint);
            paint.setTextAlign(Paint.Align.CENTER);
            for (int i = 0; i < DAYS; i++) {
                float cx = slot * i + slot / 2;
                float h = counts[i] == 0 ? 0 : Math.max(ui.dp(4), room * counts[i] / max * rise);
                paint.setColor(i == DAYS - 1 ? ui.ink : (ui.ink & 0xFFFFFF) | 0x3D000000);
                if (h > 0) canvas.drawRoundRect(cx - barW / 2, base - h, cx + barW / 2, base, ui.dp(2), ui.dp(2), paint);
                paint.setColor(i == DAYS - 1 ? ui.ink : ui.muted);
                canvas.drawText(letters[i], cx, getHeight() - ui.dp(4), paint);
            }
        }
    }
}
