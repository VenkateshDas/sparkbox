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
    private Ui ui;
    private LinearLayout page;
    private TextView subtitle;
    private boolean revealed;
    private String currentId = "";
    private long shownAt;
    private int seconds;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout head = new LinearLayout(ui.c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(16), 0, ui.dp(16), 0);
        LinearLayout titles = new LinearLayout(ui.c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(ui.text("Spark", 20, Ui.BOLD, ui.ink));
        subtitle = ui.text("", 12, Typeface.DEFAULT, ui.sub);
        titles.addView(subtitle);
        head.addView(titles);
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));
        View rule = new View(ui.c);
        rule.setBackgroundColor(ui.line);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        ScrollView scroll = new ScrollView(ui.c);
        page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        draw();
        return root;
    }

    @Override void onShow() { draw(); }
    @Override void onHide() { CardStore.flushNow(ui.c, null); }

    static int cap(Ui ui) { return Spark.clampCap(ui.c.getSharedPreferences("capture", 0).getInt("cardsPerDay", Spark.CAP_DEFAULT)); }

    /** Today's plan from every card in the vault. */
    static Spark.Plan plan(Ui ui) {
        return Spark.plan(CardStore.all(ui.c), CardStore.kept(), System.currentTimeMillis(), ZoneId.systemDefault(), cap(ui), CardStore.newestFirst());
    }

    private void draw() {
        page.removeAllViews();
        Spark.Plan plan = plan(ui);
        boolean writable = VaultWrite.writable(ui.c);
        if (!writable) {
            TextView warn = ui.text("Allow write access to your vault folder in Settings to review cards.", 13, Typeface.DEFAULT, ui.warn);
            warn.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            warn.setBackground(ui.round(ui.warnBg, 0, 8));
            page.addView(warn, ui.lp(0));
        }
        switch (plan.state) {
            case Spark.QUESTION: card(plan, writable); break;
            case Spark.CAUGHT_UP: message("All caught up", plan.nextDue > 0 ? "Next card " + due(plan.nextDue) + "." : "Nothing is waiting.", false); subtitle.setText(plan.reviewedToday + " reviewed today"); break;
            case Spark.CAP_REACHED: message("Done for today", "You reviewed " + plan.reviewedToday + (plan.reviewedToday == 1 ? " card." : " cards."), false); subtitle.setText("Daily limit reached"); break;
            default: message("No cards yet", CardGen.on(ui.c) || CardGen.running() ? "Cards are being made from your notes. They appear here as they are ready." : "Make cards from the notes in your vault, or write your own.", true); subtitle.setText("");
        }
    }

    private String due(long at) {
        long mins = Math.max(0, (at - System.currentTimeMillis()) / 60000);
        if (mins < 60) return "in " + Math.max(1, mins) + " min";
        if (mins < 24 * 60) return "in " + (mins / 60) + " h";
        return "in " + (mins / 1440) + " d";
    }

    private void message(String title, String body, boolean makeCards) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(ui.dp(16), ui.dp(48), ui.dp(16), ui.dp(16));
        box.addView(ui.text(title, 20, Ui.BOLD, ui.ink));
        TextView b = ui.text(body, 15, Typeface.DEFAULT, ui.sub);
        b.setGravity(Gravity.CENTER);
        box.addView(b, ui.lp(8));
        if (makeCards && !CardGen.on(ui.c)) box.addView(ui.button("Make cards", true, v -> host.push(new CardGenScreen())), ui.lp(20));
        else if (makeCards) box.addView(ui.button("See progress", false, v -> host.push(new CardGenScreen())), ui.lp(20));
        box.addView(ui.button("Write your own card", false, v -> CardSheet.show(host, ui, null, null, this::draw)), ui.lp(makeCards ? 8 : 20));
        page.addView(box);
    }

    private void card(Spark.Plan plan, boolean writable) {
        Cards.Card c = plan.queue.get(0);
        if (!c.id.equals(currentId)) { currentId = c.id; revealed = false; shownAt = System.currentTimeMillis(); }
        int total = Math.min(plan.cap, plan.reviewedToday + plan.queue.size());
        subtitle.setText("Card " + (plan.reviewedToday + 1) + " of " + total + ", about " + plan.minutes() + " min left");
        LinearLayout bar = new LinearLayout(ui.c);
        bar.setBackground(ui.round(ui.raised, 0, 2));
        View fill = new View(ui.c);
        fill.setBackground(ui.round(ui.accent, 0, 2));
        bar.addView(fill, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, plan.reviewedToday)));
        bar.addView(new View(ui.c), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, total - plan.reviewedToday)));
        page.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(4)));

        VaultStore.Snap snap = VaultStore.cached(ui.c);
        Vault.Note note = snap == null ? null : snap.graph.byPath(c.path);
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        LinearLayout top = new LinearLayout(ui.c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String lane = note == null ? "" : Lane.of(note.path, note.meta);
        if (!lane.isEmpty()) { top.addView(ui.chip(lane, 0)); top.addView(new View(ui.c), new LinearLayout.LayoutParams(ui.dp(8), 1)); }
        TextView from = ui.text(c.mine() && note == null ? "My card" : note == null ? "Note" : note.title, 13, Typeface.DEFAULT, ui.sub);
        from.setSingleLine(true);
        from.setEllipsize(android.text.TextUtils.TruncateAt.END);
        top.addView(from, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(top);
        TextView q = ui.text(c.q, 20, Ui.BOLD, ui.ink);
        q.setLineSpacing(0, 1.2f);
        card.addView(q, ui.lp(12));
        if (!revealed) {
            TextView show = ui.button("Show answer", true, v -> { seconds = (int) Math.max(1, (System.currentTimeMillis() - shownAt) / 1000); revealed = true; draw(); });
            show.setEnabled(writable);
            show.setAlpha(writable ? 1f : 0.4f);
            card.addView(show, ui.lp(16));
        } else {
            LinearLayout answer = block("Answer", c.a);
            card.addView(answer, ui.lp(16));
            card.addView(takeBlock(c), ui.lp(12));
            if (note != null && Layout.inSources(note.path) && !note.path.endsWith("/My cards.md"))
                card.addView(link("Source", v -> host.push(new UnitScreen(note.path))), ui.lp(12));
        }
        page.addView(card, ui.lp(12));

        if (revealed) {
            page.addView(ui.text("How well did you recall it?", 12, Ui.BOLD, ui.sub), ui.lp(16));
            LinearLayout grades = new LinearLayout(ui.c);
            long now = System.currentTimeMillis();
            for (String g : new String[]{Sched.AGAIN, Sched.HARD, Sched.GOOD, Sched.EASY}) {
                LinearLayout b = new LinearLayout(ui.c);
                b.setOrientation(LinearLayout.VERTICAL);
                b.setGravity(Gravity.CENTER);
                b.setMinimumHeight(ui.dp(56));
                b.setBackground(ui.ripple(ui.round(ui.raised, ui.line, 24)));
                TextView name = ui.text(g.substring(0, 1).toUpperCase() + g.substring(1), 15, Ui.BOLD, ui.ink);
                name.setGravity(Gravity.CENTER);
                b.addView(name, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView when = ui.text(Sched.label(c.state, g, now), 12, Typeface.DEFAULT, ui.sub);
                when.setGravity(Gravity.CENTER);
                b.addView(when, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                b.setOnClickListener(v -> { CardStore.grade(ui.c, c, g, seconds); revealed = false; currentId = ""; draw(); });
                Ui.buttonRole(b);
                LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                p.rightMargin = ui.dp(4);
                p.leftMargin = ui.dp(4);
                grades.addView(b, p);
            }
            page.addView(grades, ui.lp(8));
        }
        LinearLayout foot = new LinearLayout(ui.c);
        foot.addView(small("Park 24h", v -> { CardStore.park(ui.c, c, System.currentTimeMillis() + 24L * 3600 * 1000); revealed = false; currentId = ""; draw(); }));
        foot.addView(small("Flag", v -> { CardStore.flag(ui.c, c, true); revealed = false; currentId = ""; Toast.makeText(ui.c, "Flagged. It will not come back.", Toast.LENGTH_SHORT).show(); draw(); }));
        foot.addView(small("Source", v -> { if (note != null && !c.mine()) host.openNote(note.path); else Toast.makeText(ui.c, "This card has no source note", Toast.LENGTH_SHORT).show(); }));
        foot.addView(small("Edit", v -> CardSheet.show(host, ui, c, null, this::draw)));
        page.addView(foot, ui.lp(12));
    }

    private TextView small(String label, View.OnClickListener click) {
        TextView b = ui.button(label, false, click);
        b.setTextSize(Ui.type(14));
        b.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        b.setSingleLine(true);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, ui.dp(48), 1);
        p.rightMargin = ui.dp(4);
        p.leftMargin = ui.dp(4);
        b.setLayoutParams(p);
        return b;
    }

    private LinearLayout block(String label, String body) {
        LinearLayout b = new LinearLayout(ui.c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        b.setBackground(ui.round(ui.raised, 0, 8));
        b.addView(ui.text(label, 12, Ui.BOLD, ui.sub));
        b.addView(ui.text(body, 15, Typeface.DEFAULT, ui.ink), ui.lp(4));
        return b;
    }

    /** "Your note": the card's own take. Tap to add or change it. */
    private View takeBlock(Cards.Card c) {
        LinearLayout b = new LinearLayout(ui.c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        b.setBackground(ui.ripple(ui.round(ui.raised, 0, 8)));
        b.addView(ui.text("Your note", 12, Ui.BOLD, ui.sub));
        TextView t = ui.text(c.take.isEmpty() ? "Add your own words" : c.take, 15, c.take.isEmpty() ? Typeface.DEFAULT : Typeface.create(Typeface.DEFAULT, Typeface.ITALIC), c.take.isEmpty() ? ui.sub : ui.ink);
        b.addView(t, ui.lp(4));
        b.setOnClickListener(v -> CardSheet.take(host, ui, c, this::draw));
        Ui.buttonRole(b);
        return b;
    }

    private View link(String label, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(ui.c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.dp(48));
        r.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        r.setBackground(ui.ripple(ui.round(ui.raised, 0, 8)));
        r.addView(ui.text(label, 15, Ui.BOLD, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        r.addView(ui.text("›", 17, Typeface.DEFAULT, ui.sub));
        r.setOnClickListener(click);
        Ui.buttonRole(r);
        return r;
    }
}
