package me.venkatesh.saveobsidian;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** The cards of one note: question, answer and your own note for each, all editable, and a way to add your own. */
final class CardsScreen extends Screen {
    private final String path;
    private Ui ui;
    private LinearLayout page;

    CardsScreen(String path) { this.path = path; }

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Cards", v -> host.onBackPressed()));
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

    private void draw() {
        page.removeAllViews();
        int count = 0;
        for (Cards.Card c : CardStore.all(ui.c)) {
            if (!c.path.equals(path)) continue;
            count++;
            LinearLayout card = new LinearLayout(ui.c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(8));
            card.setBackground(ui.round(ui.surface, ui.line, 12));
            LinearLayout top = new LinearLayout(ui.c);
            top.setGravity(Gravity.CENTER_VERTICAL);
            if (c.mine()) top.addView(ui.chip("Mine", 1));
            if (c.flag) { top.addView(new View(ui.c), new LinearLayout.LayoutParams(ui.dp(8), 1)); top.addView(ui.chip("Flagged", 2)); }
            if (top.getChildCount() > 0) card.addView(top);
            card.addView(ui.text(c.q, 16, Ui.BOLD, ui.ink), ui.lp(top.getChildCount() > 0 ? 8 : 0));
            card.addView(ui.text(c.a, 15, Typeface.DEFAULT, ui.ink), ui.lp(8));
            if (!c.take.isEmpty()) card.addView(ui.text("Your note: " + c.take, 14, Typeface.create(Typeface.DEFAULT, Typeface.ITALIC), ui.sub), ui.lp(8));
            LinearLayout actions = new LinearLayout(ui.c);
            actions.addView(small("Edit", v -> CardSheet.show(host, ui, c, null, this::draw)));
            actions.addView(small(c.flag ? "Unflag" : "Flag", v -> { CardStore.flag(ui.c, c, !c.flag); draw(); }));
            if (c.mine()) actions.addView(small("Delete", v -> { CardStore.remove(ui.c, c); draw(); }));
            card.addView(actions, ui.lp(8));
            page.addView(card, ui.lp(12));
        }
        if (count == 0) page.addView(ui.text("No cards on this note yet.", 15, Typeface.DEFAULT, ui.sub));
        page.addView(ui.button("Add card", true, v -> CardSheet.show(host, ui, null, path, this::draw)), ui.lp(16));
    }

    private TextView small(String label, View.OnClickListener click) {
        TextView b = ui.text(label, 14, Ui.MEDIUM, ui.accent);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(ui.dp(48));
        b.setMinWidth(ui.dp(64));
        b.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        b.setOnClickListener(click);
        Ui.buttonRole(b);
        return b;
    }
}
