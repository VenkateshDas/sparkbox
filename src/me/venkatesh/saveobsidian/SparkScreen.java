package me.venkatesh.saveobsidian;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.ZoneId;
import java.util.List;

/** The daily review: a capped set of cards, question first, then the answer, your own note and a grade. */
final class SparkScreen extends Screen {
    private final SparkState view = new SparkState();
    private Ui ui;
    private boolean revealed;
    private String currentId = "";
    private long shownAt;
    private int seconds;
    private Cards.Card card;
    private Vault.Note note;

    /** Drawn by SparkView; the review logic stays here. */
    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        return SparkView.create(ui.c, ui, view, actions);
    }

    private void next() { revealed = false; currentId = ""; draw(); }

    private final SparkActions actions = new SparkActions() {
        @Override public void reveal() { seconds = (int) Math.max(1, (System.currentTimeMillis() - shownAt) / 1000); revealed = true; draw(); }
        @Override public void park() { if (card != null) { CardStore.park(ui.c, card, System.currentTimeMillis() + 24L * 3600 * 1000); next(); } }
        @Override public void flag() { if (card != null) { CardStore.flag(ui.c, card, true); Toast.makeText(ui.c, "Flagged. It will not come back.", Toast.LENGTH_SHORT).show(); next(); } }
        @Override public void source() { if (note != null) host.openNote(note.path); }
        @Override public void edit() { if (card != null) CardSheet.show(host, ui, card, null, SparkScreen.this::draw); }
        @Override public void take() { if (card != null) CardSheet.take(host, ui, card, SparkScreen.this::draw); }
    };

    @Override void onShow() { draw(); }
    @Override void onHide() { CardStore.flushNow(ui.c, null); }

    static int cap(Ui ui) { return Spark.clampCap(ui.c.getSharedPreferences("capture", 0).getInt("cardsPerDay", Spark.CAP_DEFAULT)); }

    /** Today's plan from every card in the vault. */
    static Spark.Plan plan(Ui ui) {
        return Spark.plan(CardStore.all(ui.c), CardStore.kept(), System.currentTimeMillis(), ZoneId.systemDefault(), cap(ui), CardStore.newestFirst());
    }

    private void draw() {
        Spark.Plan plan = plan(ui);
        boolean writable = VaultWrite.writable(ui.c);
        card = null; note = null;
        switch (plan.state) {
            case Spark.QUESTION: {
                Cards.Card c = card = plan.queue.get(0);
                if (!c.id.equals(currentId)) { currentId = c.id; revealed = false; shownAt = System.currentTimeMillis(); }
                int total = Math.min(plan.cap, plan.reviewedToday + plan.queue.size());
                VaultStore.Snap snap = VaultStore.cached(ui.c);
                note = snap == null ? null : snap.graph.byPath(c.path);
                List<Grade> grades = new java.util.ArrayList<>();
                long now = System.currentTimeMillis();
                for (String g : new String[]{Sched.AGAIN, Sched.HARD, Sched.GOOD, Sched.EASY})
                    grades.add(new Grade(g.substring(0, 1).toUpperCase() + g.substring(1), Sched.label(c.state, g, now), () -> { CardStore.grade(ui.c, c, g, seconds); next(); }));
                view.publish(new SparkCard(c.id, note == null ? "" : Lane.of(note.path, note.meta), c.mine() && note == null ? "My card" : note == null ? "Note" : note.title,
                    c.q, c.a, c.take, plan.reviewedToday, total, revealed, writable, note != null && !c.mine(), grades),
                    "Card " + (plan.reviewedToday + 1) + " of " + total + ", about " + plan.minutes() + " min left", writable);
                break;
            }
            case Spark.CAUGHT_UP: view.publish(message("All caught up", plan.nextDue > 0 ? "Next card " + due(plan.nextDue) + "." : "Nothing is waiting.", false), plan.reviewedToday + " reviewed today", writable); break;
            case Spark.CAP_REACHED: view.publish(message("Done for today", "You reviewed " + plan.reviewedToday + (plan.reviewedToday == 1 ? " card." : " cards."), false), "Daily limit reached", writable); break;
            default: view.publish(message("No cards yet", CardGen.on(ui.c) || CardGen.running() ? "Cards are being made from your notes. They appear here as they are ready." : "Make cards from the notes in your vault, or write your own.", true), "", writable);
        }
    }

    private String due(long at) {
        long mins = Math.max(0, (at - System.currentTimeMillis()) / 60000);
        if (mins < 60) return "in " + Math.max(1, mins) + " min";
        if (mins < 24 * 60) return "in " + (mins / 60) + " h";
        return "in " + (mins / 1440) + " d";
    }

    private SparkMessage message(String title, String body, boolean makeCards) {
        List<SparkButton> b = new java.util.ArrayList<>();
        if (makeCards) b.add(new SparkButton(CardGen.on(ui.c) ? "See progress" : "Make cards", !CardGen.on(ui.c), () -> host.push(new CardGenScreen())));
        b.add(new SparkButton("Write your own card", false, () -> CardSheet.show(host, ui, null, null, this::draw)));
        return new SparkMessage(title, body, b);
    }
}
