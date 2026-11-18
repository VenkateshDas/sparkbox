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
    private final PageState page = new PageState();
    private Ui ui;
    private boolean watching;

    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        return PageView.create(ui.c, ui, "Make cards", page, () -> host.onBackPressed());
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
        java.util.List<Block> b = new java.util.ArrayList<>();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (snap == null) { b.add(new Body("Choose your vault folder first.", true, false)); page.publish(b); return; }
        int[] p = CardGen.progress(snap);
        long[] est = CardGen.estimate(snap);
        boolean on = CardGen.on(ui.c);
        b.add(new Body("Each note gets a curiosity question and two to four recall cards, written by an AI model from the note's own text. Cards are written into the note, under a Cards heading. Nothing else in the note changes.", true, false));
        b.add(new Heading(on || p[0] > 0 ? "Making cards: " + num(p[0]) + " of " + num(p[1]) + " notes" : num(est[0]) + " notes have no cards yet"));
        b.add(new Meter(p[0], Math.max(1, p[1])));
        b.add(new Body(est[0] > 0 ? "Left to do: about " + num(est[1]) + " characters of text, roughly " + num(est[2]) + " input tokens, with " + (CardGen.model(ui.c) + ". Read your key's usage on openrouter.ai for the exact cost.") : "Every note in your vault has been looked at.", true, false));
        String problem = CardGen.problem(ui.c);
        if (!problem.isEmpty()) b.add(new Banner(problem, true));
        if (est[0] > 0) {
            if (!on) b.add(new Primary(p[0] > 0 ? "Continue" : "Start", () -> { CardGen.on(ui.c, true); start(); draw(); }));
            else if (CardGen.running()) b.add(new Buttons(java.util.Collections.singletonList(new Act("Pause", () -> { CardGen.on(ui.c, false); CardGen.stop = true; draw(); }))));
            else b.add(new Primary("Resume", () -> { start(); draw(); }));
            if (on) b.add(new Body("It keeps going in the background while the app is open and in the next background runs. Pause stops after the note in progress.", true, false));
        }
        page.publish(b);
    }

    private void start() {
        new Thread(() -> CardGen.loop(ui.c.getApplicationContext())).start();
    }
}
