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
    /** The same rows as Compose navigation rows. */
    static java.util.List<Nav> navs(VaultStore.Snap snap, List<String[]> rows, MainActivity host) {
        java.util.List<Nav> out = new java.util.ArrayList<>();
        for (String[] r : rows) {
            Vault.Note to = snap == null ? null : snap.graph.resolve(r[1]);
            int others = to == null ? 0 : Math.max(0, snap.graph.in(to).size() - 1);
            String kind = r[0].equals("Topics") ? "Topic" : r[0].equals("People") ? "Person" : "Entity";
            out.add(new Nav(r[1], kind + (to == null ? "" : ", " + others + (others == 1 ? " other source" : " other sources")), "", r[2].equals("new"), to == null ? null : () -> host.openNote(to.path)));
        }
        return out;
    }

    @Override View build(Ui ui) {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        Vault.Note n = snap == null ? null : snap.graph.byPath(path);
        List<String[]> rows = n == null ? new ArrayList<String[]>() : rows(n);
        int topics = 0, people = 0, entities = 0;
        for (String[] r : rows) { if (r[0].equals("Topics")) topics++; else if (r[0].equals("People")) people++; else entities++; }
        StringBuilder sub = new StringBuilder("1 note");
        if (topics > 0) sub.append(", ").append(topics).append(topics == 1 ? " topic" : " topics");
        if (people > 0) sub.append(", ").append(people).append(people == 1 ? " person" : " people");
        if (entities > 0) sub.append(", ").append(entities).append(entities == 1 ? " entity" : " entities");
        List<Block> b = new ArrayList<>();
        b.add(new Body(sub.toString(), true, false));
        if (n == null) b.add(new Body("This note is no longer in the vault.", true, false));
        else {
            b.add(new Title(n.title));
            if (!n.meta("source").isEmpty()) b.add(new Body(Feed.host(n.meta("source")), true, false));
            for (String group : new String[]{"Topics", "People", "Entities"}) {
                List<String[]> mine = new ArrayList<>();
                for (String[] r : rows) if (r[0].equals(group)) mine.add(r);
                if (mine.isEmpty()) continue;
                b.add(new Heading(group));
                b.add(new Group(navs(snap, mine, host)));
            }
        }
        b.add(new Primary("Done", () -> host.onBackPressed()));
        PageState page = new PageState();
        page.publish(b);
        return PageView.create(ui.c, ui, "What was added", page, () -> host.onBackPressed());
    }
}
