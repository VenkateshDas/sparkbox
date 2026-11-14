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
    private final PageState page = new PageState();
    private Ui ui;
    private String text = "", draft;

    TopicScreen(String path) { this.path = path; }

    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        load();
        return PageView.create(ui.c, ui, "Topic", page, () -> host.onBackPressed());
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
        List<Block> b = new ArrayList<>();
        VaultStore.Snap snap = snap();
        Vault.Note n = snap == null ? null : snap.graph.byPath(path);
        if (n == null) { b.add(new Body("This topic is no longer in the vault.", true, false)); page.publish(b); return; }
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
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag("Topic", 0));
        if (!lane.isEmpty()) tags.add(new Tag(lane, 0));
        b.add(new Tags(tags, ""));
        b.add(new Title(n.title));
        String core = Vault.section(text, "Core Mechanism");
        if (core.isEmpty()) core = n.description;
        if (!core.isEmpty()) b.add(new Panel("Core idea", firstSentence(core), Origin.AI));

        String section = Vault.section(text, "My take"), body = NoteEdit.takeText(section), edited = NoteEdit.takeEdited(section);
        String label = "Your take";
        if (!edited.isEmpty()) { try { long d = ChronoUnit.DAYS.between(LocalDate.parse(edited), LocalDate.now()); label += ", edited " + (d <= 0 ? "today" : d + "d ago"); } catch (RuntimeException bad) { } }
        if (draft == null) draft = body;
        b.add(new Editor(label, body, draft, "What you think about this topic, in your own words", d -> draft = d, (value, done) -> {
            String stamped = NoteEdit.takeBody(value, LocalDate.now().toString());
            Edits.section(ui.c, n.path, "My take", stamped, new String[]{"Core Mechanism"}, problem -> {
                if (problem.isEmpty()) { Toast.makeText(ui.c, "Saved", Toast.LENGTH_SHORT).show(); draft = value; text = Vault.clipLines(NoteEdit.setSection(text, "My take", stamped, "Core Mechanism")); draw(); }
                else Toast.makeText(ui.c, problem, Toast.LENGTH_LONG).show();
                done.accept(problem);
            });
        }));

        if (!text.isEmpty()) {
            b.add(new Heading("Full note"));
            b.add(new Rich(() -> Md.view(ui, text, new Md.Open() {
                @Override public void open(String raw) { Vault.Note to = snap().graph.resolve(Vault.target(raw)); if (to != null) host.openNote(to.path); }
                @Override public boolean exists(String raw) { return snap().graph.resolve(Vault.target(raw)) != null; }
            })));
        }

        List<String[]> appears = new ArrayList<>(), related = new ArrayList<>(), people = new ArrayList<>(), questions = new ArrayList<>();
        for (Vault.Note s : sources) appears.add(new String[]{s.title, (Feed.host(s.meta("source")).isEmpty() ? s.meta("via") : Feed.host(s.meta("source"))), s.path});
        for (String name : n.topics) { Vault.Note to = g.resolve(name); if (to != null && to.path.startsWith("Knowledge/Topics/")) related.add(new String[]{to.title, "Topic", to.path}); }
        Map<String, Vault.Note> seen = new LinkedHashMap<>();
        for (Vault.Note s : sources) for (Vault.Note o : g.out(s)) if (o.path.startsWith("Knowledge/People/")) seen.put(o.path, o);
        for (Vault.Note o : g.out(n)) if (o.path.startsWith("Knowledge/People/")) seen.put(o.path, o);
        for (Vault.Note p : seen.values()) people.add(new String[]{p.title, "Person", p.path});
        for (String q : Vault.bullets(text, "Contradictions / Open Questions")) questions.add(new String[]{Vault.plain(q), "", ""});
        List<Nav> rows = new ArrayList<>();
        rows.add(count("Appears in", appears, "source", "sources"));
        rows.add(count("Related topics", related, "topic", "topics"));
        rows.add(count("People mentioned", people, "person", "people"));
        rows.add(count("Open questions", questions, "open", "open"));
        b.add(new Group(rows));
        final Study.Scope study = Study.topicOf(g, n.path, n.title);
        int k = study == null ? 0 : Study.filter(CardStore.all(ui.c), study, System.currentTimeMillis()).size();
        if (k > 0) b.add(new Primary("Study " + k + (k == 1 ? " card" : " cards") + " in Spark", () -> host.study(study)));
        b.add(new Primary("Ask about this", () -> host.askAbout(n.path, n.title)));
        page.publish(b);
    }

    private Nav count(String title, List<String[]> rows, String one, String many) {
        int n = rows.size();
        return new Nav(title, "", n + " " + (n == 1 ? one : many), false, n == 0 ? null : () -> host.push(new NotesListScreen(title, rows)));
    }
}
