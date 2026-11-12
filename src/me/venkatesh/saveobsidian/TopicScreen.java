package me.venkatesh.saveobsidian;

import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A topic: its core idea, your own take on it, and the sources, related topics, people and open questions around it. */
final class TopicScreen extends Screen {
    private final String path;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Ui ui;
    private LinearLayout page;
    private String text = "";

    TopicScreen(String path) { this.path = path; }

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Topic", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        draw();
        load();
        return root;
    }

    private VaultStore.Snap snap() { return VaultStore.cached(ui.c); }

    private void load() {
        final VaultStore.Snap snap = snap();
        final Vault.Note n = snap == null ? null : snap.graph.byPath(path);
        if (n == null) return;
        new Thread(() -> {
            String t = "";
            try { t = VaultStore.text(ui.c, snap.uri(n)); } catch (Exception unreadable) { }
            final String read = Vault.clipLines(t.replace("\r\n", "\n"));
            main.post(() -> { text = read; draw(); });
        }).start();
    }

    private static String firstSentence(String s) {
        String flat = Vault.plain(s).replaceAll("\\s+", " ").trim();
        int dot = flat.indexOf(". ");
        return dot > 0 && dot < 220 ? flat.substring(0, dot + 1) : flat.length() > 220 ? flat.substring(0, 220).trim() + "…" : flat;
    }

    private void draw() {
        page.removeAllViews();
        VaultStore.Snap snap = snap();
        Vault.Note n = snap == null ? null : snap.graph.byPath(path);
        if (n == null) { page.addView(ui.text("This topic is no longer in the vault.", 15, Typeface.DEFAULT, ui.sub)); return; }
        Vault.Graph g = snap.graph;

        List<Vault.Note> sources = new ArrayList<>();
        String lane = "";
        long newest = -1;
        for (Vault.Note from : g.in(n)) {
            if (!Layout.inSources(from.path)) continue;
            sources.add(from);
            boolean[] t = new boolean[1];
            java.time.LocalDateTime at = Feed.when(from.meta("captured"), t);
            long key = at == null ? 0 : at.toEpochSecond(java.time.ZoneOffset.UTC);
            if (key >= newest) { newest = key; lane = Lane.of(from.path, from.meta); }
        }
        LinearLayout chips = new LinearLayout(ui.c);
        chips.addView(ui.chip("Topic", 0));
        if (!lane.isEmpty()) { chips.addView(new View(ui.c), new LinearLayout.LayoutParams(ui.dp(8), 1)); chips.addView(ui.chip(lane, 0)); }
        page.addView(chips);
        page.addView(ui.text(n.title, 24, Ui.BOLD, ui.ink), ui.lp(8));
        String core = Vault.section(text, "Core Mechanism");
        if (core.isEmpty()) core = n.description;
        if (!core.isEmpty()) page.addView(ui.text(firstSentence(core), 15, Typeface.DEFAULT, ui.sub), ui.lp(8));
        TextView ask = ui.button("Ask about this", true, v -> host.startActivity(new Intent(host, AskActivity.class).putExtra(AskActivity.QUESTION, "Tell me about " + n.title)));
        page.addView(ask, ui.lp(12));
        page.addView(takeCard(n), ui.lp(12));

        List<String[]> appears = new ArrayList<>(), related = new ArrayList<>(), people = new ArrayList<>(), questions = new ArrayList<>();
        for (Vault.Note s : sources) appears.add(new String[]{s.title, (Feed.host(s.meta("source")).isEmpty() ? s.meta("via") : Feed.host(s.meta("source"))), s.path});
        for (String name : n.topics) { Vault.Note to = g.resolve(name); if (to != null && to.path.startsWith("Knowledge/Topics/")) related.add(new String[]{to.title, "Topic", to.path}); }
        Map<String, Vault.Note> seen = new LinkedHashMap<>();
        for (Vault.Note s : sources) for (Vault.Note o : g.out(s)) if (o.path.startsWith("Knowledge/People/")) seen.put(o.path, o);
        for (Vault.Note o : g.out(n)) if (o.path.startsWith("Knowledge/People/")) seen.put(o.path, o);
        for (Vault.Note p : seen.values()) people.add(new String[]{p.title, "Person", p.path});
        for (String q : Vault.bullets(text, "Contradictions / Open Questions")) questions.add(new String[]{Vault.plain(q), "", ""});

        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        card.addView(countRow("Appears in", appears.size(), "source", "sources", appears));
        card.addView(countRow("Related topics", related.size(), "topic", "topics", related));
        card.addView(countRow("People mentioned", people.size(), "person", "people", people));
        card.addView(countRow("Open questions", questions.size(), "open", "open", questions));
        page.addView(card, ui.lp(12));
    }

    private View countRow(String title, int count, String one, String many, List<String[]> rows) {
        LinearLayout row = new LinearLayout(ui.c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56));
        row.addView(ui.text(title, 16, Ui.BOLD, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(ui.text(count + " " + (count == 1 ? one : many), 13, Typeface.DEFAULT, ui.sub));
        row.addView(ui.text("  ›", 17, Typeface.DEFAULT, ui.sub));
        if (count > 0) { row.setOnClickListener(v -> host.push(new NotesListScreen(title, rows))); Ui.buttonRole(row); }
        return row;
    }

    /** "Your take": the topic page's "## My take" section, with an Edit button. */
    private View takeCard(Vault.Note n) {
        String section = Vault.section(text, "My take"), body = NoteEdit.takeText(section), edited = NoteEdit.takeEdited(section);
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        LinearLayout top = new LinearLayout(ui.c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        String label = "Your take";
        if (!edited.isEmpty()) { try { long d = ChronoUnit.DAYS.between(LocalDate.parse(edited), LocalDate.now()); label += ", edited " + (d <= 0 ? "today" : d + "d ago"); } catch (RuntimeException bad) { } }
        top.addView(ui.text(label, 12, Ui.BOLD, ui.sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView editButton = ui.button(body.isEmpty() ? "Add" : "Edit", false, v -> edit(n, body));
        editButton.setMinWidth(ui.dp(80));
        top.addView(editButton);
        card.addView(top);
        TextView shown = ui.text(body.isEmpty() ? "Write what you think about this topic, in your own words." : body, 15, body.isEmpty() ? Typeface.DEFAULT : Typeface.create(Typeface.DEFAULT, Typeface.ITALIC), body.isEmpty() ? ui.sub : ui.ink);
        card.addView(shown, ui.lp(4));
        return card;
    }

    private void edit(Vault.Note n, String current) {
        EditText input = new EditText(ui.c);
        input.setText(current);
        input.setHint("Your take");
        input.setMinLines(3);
        input.setGravity(Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12));
        new AlertDialog.Builder(host).setTitle("Your take").setView(input)
            .setPositiveButton("Save", (d, w) -> {
                String body = NoteEdit.takeBody(input.getText().toString(), LocalDate.now().toString());
                Edits.section(ui.c, n.path, "My take", body, new String[]{"Core Mechanism"}, problem -> {
                    if (problem.isEmpty()) { text = Vault.clipLines(NoteEdit.setSection(text, "My take", body, "Core Mechanism")); draw(); }
                    else Toast.makeText(ui.c, problem, Toast.LENGTH_LONG).show();
                });
            }).setNegativeButton("Cancel", null).show();
    }
}
