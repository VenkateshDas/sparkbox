package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** "What was added": the topics, people and entities one capture made or touched. */
final class CaptureMapScreen extends Screen {
    private final String path;
    private Ui ui;

    CaptureMapScreen(String path) { this.path = path; }

    private static final Pattern LINK = Pattern.compile("\\[\\[([^\\]|]+)");

    private static void add(List<String[]> out, String group, String list, boolean fresh) {
        Matcher m = LINK.matcher(list);
        while (m.find()) out.add(new String[]{group, m.group(1).trim(), fresh ? "new" : ""});
    }

    /** {group, name, "new" or ""} from the made-* keys; the note's own topics and people (no New badges) when it records none. */
    static List<String[]> rows(Vault.Note n) {
        List<String[]> out = new ArrayList<>();
        add(out, "Topics", n.meta("made-topics-new"), true);
        add(out, "Topics", n.meta("made-topics-updated"), false);
        add(out, "People", n.meta("made-people-new"), true);
        add(out, "People", n.meta("made-people-updated"), false);
        add(out, "Entities", n.meta("made-entities-new"), true);
        add(out, "Entities", n.meta("made-entities-updated"), false);
        if (!out.isEmpty()) return out;
        List<String> people = new ArrayList<>();
        add(out, "People", n.meta("people"), false);
        for (String[] p : out) people.add(p[1]);
        out.clear();
        for (String t : n.topics) if (!people.contains(t)) out.add(new String[]{"Topics", t, ""});
        for (String p : people) out.add(new String[]{"People", p, ""});
        return out;
    }

    /** One row: name, a count line from the graph, a New badge, and a chevron. Opens the note when it exists. */
    static View line(Ui ui, VaultStore.Snap snap, String[] r, MainActivity host) {
        Vault.Note to = snap == null ? null : snap.graph.resolve(r[1]);
        LinearLayout row = new LinearLayout(ui.c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56));
        LinearLayout text = new LinearLayout(ui.c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.addView(ui.text(r[1], 15, Ui.BOLD, ui.ink));
        int others = to == null ? 0 : Math.max(0, snap.graph.in(to).size() - 1);
        String kind = r[0].equals("Topics") ? "Topic" : r[0].equals("People") ? "Person" : "Entity";
        text.addView(ui.text(kind + (to == null ? "" : ", " + others + (others == 1 ? " other source" : " other sources")), 13, Typeface.DEFAULT, ui.sub));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (r[2].equals("new")) row.addView(ui.chip("New", 1));
        row.addView(ui.text("  ›", 17, Typeface.DEFAULT, ui.sub));
        if (to != null) {
            row.setOnClickListener(v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.OPEN, to.path)));
            Ui.buttonRole(row);
        }
        return row;
    }

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        Vault.Note n = snap == null ? null : snap.graph.byPath(path);
        List<String[]> rows = n == null ? new ArrayList<String[]>() : rows(n);
        int topics = 0, people = 0, entities = 0;
        for (String[] r : rows) { if (r[0].equals("Topics")) topics++; else if (r[0].equals("People")) people++; else entities++; }
        StringBuilder sub = new StringBuilder("1 note");
        if (topics > 0) sub.append(", ").append(topics).append(topics == 1 ? " topic" : " topics");
        if (people > 0) sub.append(", ").append(people).append(people == 1 ? " person" : " people");
        if (entities > 0) sub.append(", ").append(entities).append(entities == 1 ? " entity" : " entities");
        root.addView(Bars.header(ui, "What was added", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        LinearLayout page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        page.addView(ui.text(sub.toString(), 13, Typeface.DEFAULT, ui.sub));
        if (n == null) { page.addView(ui.text("This note is no longer in the vault.", 15, Typeface.DEFAULT, ui.sub), ui.lp(12)); return root; }
        LinearLayout head = new LinearLayout(ui.c);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        head.setBackground(ui.round(ui.surface, ui.line, 12));
        head.addView(ui.text(n.title, 17, Ui.BOLD, ui.ink));
        String url = n.meta("source");
        if (!url.isEmpty()) head.addView(ui.text(Feed.host(url), 13, Typeface.DEFAULT, ui.sub), ui.lp(4));
        page.addView(head, ui.lp(12));
        for (String group : new String[]{"Topics", "People", "Entities"}) {
            boolean any = false;
            LinearLayout card = new LinearLayout(ui.c);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
            card.setBackground(ui.round(ui.surface, ui.line, 12));
            for (String[] r : rows) if (r[0].equals(group)) { card.addView(line(ui, snap, r, host)); any = true; }
            if (!any) continue;
            page.addView(ui.text(group, 17, Ui.BOLD, ui.ink), ui.lp(16));
            page.addView(card, ui.lp(8));
        }
        TextView view = ui.button("View source note", false, v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.OPEN, n.path)));
        page.addView(view, ui.lp(20));
        page.addView(ui.button("Done", true, v -> host.onBackPressed()), ui.lp(8));
        return root;
    }
}
