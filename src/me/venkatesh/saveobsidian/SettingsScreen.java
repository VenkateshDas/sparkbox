package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings, a plain list. Rows that already live in the older Setup page open it; the rest are here. */
final class SettingsScreen extends Screen {
    private Ui ui;
    private LinearLayout page;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Settings", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        draw();
        return root;
    }

    @Override void onShow() { draw(); }

    private LinearLayout group(String title) {
        page.addView(ui.text(title, 14, Ui.BOLD, ui.sub), ui.lp(20));
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        page.addView(card, ui.lp(8));
        return card;
    }

    private View row(String title, String value, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(ui.c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.dp(56));
        r.addView(ui.text(title, 16, Typeface.DEFAULT, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (!value.isEmpty()) { TextView v = ui.text(value, 13, Typeface.DEFAULT, ui.sub); v.setMaxLines(2); r.addView(v); }
        if (click != null) { r.addView(ui.text("  ›", 17, Typeface.DEFAULT, ui.sub)); r.setOnClickListener(click); Ui.buttonRole(r); }
        return r;
    }

    private void draw() {
        page.removeAllViews();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        LinearLayout vault = group("Vault");
        vault.addView(row("Vault folder", VaultStore.ready(ui.c) ? VaultStore.label(ui.c) : "Not chosen", v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true))));
        vault.addView(row("Notes", snap == null ? "" : String.format(java.util.Locale.US, "%,d", snap.graph.notes.size()), null));

        LinearLayout spark = group("Spark");
        int[] p = snap == null ? new int[]{0, 0} : CardGen.progress(snap);
        String state = CardGen.on(ui.c) ? (CardGen.running() ? "Making cards " + p[0] + " of " + p[1] : "Paused at " + p[0] + " of " + p[1]) : p[0] > 0 && p[0] >= p[1] ? "All notes done" : "Not started";
        spark.addView(row("Make cards for existing notes", state, v -> host.push(new CardGenScreen())));
        int cap = ui.c.getSharedPreferences("capture", 0).getInt("cardsPerDay", Spark.CAP_DEFAULT);
        spark.addView(row("Cards per day", String.valueOf(cap), v -> {
            int next = cap >= Spark.CAP_MAX ? Spark.CAP_MIN : cap + 1;
            ui.c.getSharedPreferences("capture", 0).edit().putInt("cardsPerDay", Spark.clampCap(next)).apply();
            draw();
        }));

        LinearLayout look = group("Theme");
        String theme = ui.c.getSharedPreferences("capture", 0).getString("theme", "system");
        look.addView(row("Theme", theme.substring(0, 1).toUpperCase() + theme.substring(1), v -> {
            String next = theme.equals("system") ? "dark" : theme.equals("dark") ? "light" : "system";
            ui.c.getSharedPreferences("capture", 0).edit().putString("theme", next).apply();
            host.recreate();
        }));

        LinearLayout more = group("More");
        more.addView(row("Capture, ingest and API key", "", v -> host.startActivity(new Intent(host, SetupActivity.class))));
        more.addView(row("Browse vault", "", v -> host.startActivity(new Intent(host, VaultActivity.class))));
    }
}
