package me.venkatesh.saveobsidian;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** You and your knowledge base: the vault in use, what it holds, and how the app has been used. Reached from the menu. */
final class ProfileScreen extends Screen {
    private Ui ui;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Profile", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        LinearLayout page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        VaultStore.Snap snap = VaultStore.cached(ui.c);
        int sources = 0, topics = 0, people = 0, entities = 0, cards = 0;
        if (snap != null) for (Vault.Note n : snap.graph.notes) {
            if (n.catalog) continue;
            if (Layout.inSources(n.path)) sources++;
            else if (n.path.startsWith("Knowledge/Topics/")) topics++;
            else if (n.path.startsWith("Knowledge/People/")) people++;
            else if (n.path.startsWith("Knowledge/Entities/")) entities++;
            if (n.cardsText != null && !n.cardsText.isEmpty()) cards++;
        }

        LinearLayout head = new LinearLayout(ui.c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = ui.text("S", 24, Ui.BOLD, ui.onAccent);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(ui.round(ui.accent, 0, 32));
        head.addView(badge, new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64)));
        LinearLayout who = new LinearLayout(ui.c);
        who.setOrientation(LinearLayout.VERTICAL);
        who.setPadding(ui.dp(16), 0, 0, 0);
        who.addView(ui.text("Your Sparkbox", 20, Ui.BOLD, ui.ink));
        who.addView(ui.text(VaultStore.ready(ui.c) ? VaultStore.label(ui.c) : "No vault chosen yet", 13, Typeface.DEFAULT, ui.sub));
        head.addView(who, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(head, ui.lp(8));

        LinearLayout base = group(page, "Knowledge base");
        base.addView(line("Notes", snap == null ? "—" : String.format(java.util.Locale.US, "%,d", snap.graph.notes.size())));
        base.addView(line("Sources", String.valueOf(sources)));
        base.addView(line("Topics", String.valueOf(topics)));
        base.addView(line("People", String.valueOf(people)));
        base.addView(line("Entities", String.valueOf(entities)));
        base.addView(line("Notes with recall cards", String.valueOf(cards)));

        LinearLayout account = group(page, "Account");
        account.addView(line("OpenRouter key", Model.key(ui.c).isEmpty() ? "Not set" : "Saved"));
        account.addView(line("Process captures into notes", Ingest.on(ui.c) ? "On" : "Off"));
        account.addView(line("Ingest model", Ingest.model(ui.c)));

        LinearLayout use = group(page, "Activity");
        for (String[] f : Usage.FEATURES) use.addView(line(f[1], String.valueOf(ui.c.getSharedPreferences("usage", 0).getInt(f[0], 0))));
        TextView note = ui.text("Counts stay on this phone.", 12, Typeface.DEFAULT, ui.sub);
        note.setPadding(0, ui.dp(8), 0, 0);
        page.addView(note);
        return root;
    }

    private LinearLayout group(LinearLayout page, String title) {
        page.addView(ui.text(title, 14, Ui.BOLD, ui.sub), ui.lp(20));
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        page.addView(card, ui.lp(8));
        return card;
    }

    private View line(String label, String value) {
        LinearLayout r = new LinearLayout(ui.c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.dp(48));
        r.addView(ui.text(label, 15, Typeface.DEFAULT, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = ui.text(value, 15, Ui.MEDIUM, ui.sub);
        v.setGravity(Gravity.END);
        r.addView(v);
        return r;
    }
}
