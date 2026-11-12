package me.venkatesh.saveobsidian;

import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** Make cards for the notes already in the vault: one confirmation with the size of the job, then progress with a Pause switch. */
final class CardGenScreen extends Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private LinearLayout page;
    private boolean watching;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Make cards", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        draw();
        return root;
    }

    @Override void onShow() { watching = true; tick(); }
    @Override void onHide() { watching = false; }

    private void tick() {
        if (!watching) return;
        draw();
        main.postDelayed(this::tick, 3000);
    }

    private static String num(long n) { return String.format(Locale.US, "%,d", n); }

    private void draw() {
        page.removeAllViews();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) { page.addView(ui.text("Choose your vault folder first.", 15, Typeface.DEFAULT, ui.sub)); return; }
        int[] p = CardGen.progress(snap);
        long[] est = CardGen.estimate(snap);
        boolean on = CardGen.on(ui.c);
        page.addView(ui.text("Each note gets a curiosity question and two to four recall cards, written by an AI model from the note's own text. Cards are written into the note, under a Cards heading. Nothing else in the note changes.", 15, Typeface.DEFAULT, ui.sub));

        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        card.addView(ui.text(on || p[0] > 0 ? "Making cards: " + num(p[0]) + " of " + num(p[1]) + " notes" : num(est[0]) + " notes have no cards yet", 17, Ui.BOLD, ui.ink));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(ui.c, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(Math.max(1, p[1]));
        bar.setProgress(p[0]);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ui.accent));
        card.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)));
        ((LinearLayout.LayoutParams) bar.getLayoutParams()).topMargin = ui.dp(12);
        if (est[0] > 0) card.addView(ui.text("Left to do: about " + num(est[1]) + " characters of text, roughly " + num(est[2]) + " input tokens, with " + CardGen.model(ui.c) + ". Read your key's usage on openrouter.ai for the exact cost.", 13, Typeface.DEFAULT, ui.sub), ui.lp(12));
        else card.addView(ui.text("Every note in your vault has been looked at.", 13, Typeface.DEFAULT, ui.sub), ui.lp(12));
        page.addView(card, ui.lp(16));

        String problem = CardGen.problem(ui.c);
        if (!problem.isEmpty()) {
            TextView warn = ui.text(problem, 13, Typeface.DEFAULT, ui.err);
            warn.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            warn.setBackground(ui.round(ui.errBg, 0, 8));
            page.addView(warn, ui.lp(12));
        }
        if (est[0] == 0) return;
        TextView action;
        if (!on) action = ui.button(p[0] > 0 ? "Continue" : "Start", true, v -> { CardGen.on(ui.c, true); start(); draw(); });
        else if (CardGen.running()) action = ui.button("Pause", false, v -> { CardGen.on(ui.c, false); CardGen.stop = true; draw(); });
        else action = ui.button("Resume", true, v -> { start(); draw(); });
        page.addView(action, ui.lp(16));
        if (on) page.addView(ui.text("It keeps going in the background while the app is open and in the next background runs. Pause stops after the note in progress.", 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
    }

    private void start() {
        new Thread(() -> CardGen.loop(ui.c.getApplicationContext())).start();
    }
}
