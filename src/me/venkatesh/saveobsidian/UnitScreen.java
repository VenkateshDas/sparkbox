package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** One Source note: what it says, what you think, where it came from, and ways to read, open or ask about it. Drawn by PageView; each section says whether it is from the source, the AI or you. */
final class UnitScreen extends Screen {
    private final String path;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PageState page = new PageState();
    private Ui ui;
    private String text = "", draft;
    private boolean loadedMine;

    UnitScreen(String path) { this.path = path; }

    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        load();
        return PageView.create(ui.c, ui, "Source", page, () -> host.onBackPressed());
    }

    private Vault.Note note() {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        return snap == null ? null : snap.graph.byPath(path);
    }

    /** The note's text, read off the main thread, for the sections that live in its body. */
    private void load() {
        final VaultStore.Snap snap = VaultStore.cached(ui.c);
        final Vault.Note n = note();
        if (snap == null || n == null) return;
        new Thread(() -> {
            String t = "";
            try { t = VaultStore.text(ui.c, snap.uri(n)); } catch (Exception unreadable) { }
            final String read = Vault.clipLines(t.replace("\r\n", "\n"));
            main.post(() -> { text = read; draw(); });
        }).start();
    }

    private void draw() {
        List<Block> b = new ArrayList<>();
        Vault.Note n = note();
        if (n == null) { b.add(new Body("This note is no longer in the vault.", true, false)); page.publish(b); return; }
        boolean processed = n.processed();
        List<Tag> tags = new ArrayList<>();
        tags.add(new Tag(processed ? Feed.PROCESSED : Feed.INGESTED, processed ? 1 : 0));
        String lane = Lane.of(n.path, n.meta);
        if (!lane.isEmpty()) tags.add(new Tag(lane, 0));
        boolean[] time = new boolean[1];
        b.add(new Tags(tags, Feed.age(Feed.when(n.meta("captured"), time), time[0], LocalDateTime.now())));
        b.add(new Title(n.title));
        String url = n.meta("source"), name = url.isEmpty() ? n.meta("via") : Feed.host(url);
        if (!url.isEmpty()) b.add(new Link(name + "  ↗", () -> open(url)));
        else if (!name.isEmpty()) b.add(new Body("via " + name, true, false));
        String quality = n.meta("quality");
        if (quality.equals("title_only") || quality.equals("failed")) b.add(new Banner("Only the title was saved. No cards were made.", false));
        if (!processed) b.add(new Banner(Ingest.ready(ui.c) ? "Not processed yet. It will be processed when you are online." : "Not processed yet. The agent on your Mac has not read this note.", false));

        String why = Vault.plain(Vault.section(text, "Why I captured this"));
        if (!why.isEmpty()) b.add(new Panel("Why I saved it", why, Origin.YOU));
        mine(b, n);
        if (!n.meta("curiosity").isEmpty()) b.add(new Panel("Curiosity prompt", n.meta("curiosity"), Origin.AI));
        List<String> claims = Vault.bullets(text, "Key claims");
        String summary = Vault.section(text, "Summary");
        if (processed) {
            if (summary.isEmpty() && !claims.isEmpty()) { summary = Vault.plain(claims.get(0)).replaceAll("\\s*\\((high|medium|low)\\)\\s*$", ""); claims = new ArrayList<>(claims.subList(1, claims.size())); } // the first claim became the summary, so it is not listed twice
            if (summary.isEmpty()) summary = n.description;
            if (!summary.isEmpty()) b.add(new Panel("Summary", Vault.plain(summary), Origin.AI));
            if (!claims.isEmpty()) {
                b.add(new Heading("Key notes", Origin.AI, null));
                for (String c : claims) b.add(new Bullet(Vault.plain(c).replaceAll("\\s*\\((high|medium|low)\\)\\s*$", ""), c.toLowerCase(java.util.Locale.ROOT).endsWith("(low)")));
            }
        } else if (!n.description.isEmpty()) b.add(new Panel("Description", n.description, Origin.SOURCE));

        List<String[]> made = CaptureMapScreen.rows(n); // {group, name, isNew}
        if (!made.isEmpty()) {
            b.add(new Heading("Added from this note", Origin.NONE, () -> host.push(new CaptureMapScreen(n.path))));
            b.add(new Group(CaptureMapScreen.navs(VaultStore.cached(ui.c), made, host)));
        }
        int cardCount = 0;
        for (Cards.Card k : CardStore.all(ui.c)) if (k.path.equals(n.path)) cardCount++;
        List<Nav> more = new ArrayList<>();
        more.add(new Nav("Cards", "", cardCount == 0 ? "None yet" : cardCount + (cardCount == 1 ? " card" : " cards"), false, () -> host.push(new CardsScreen(n.path))));
        more.add(new Nav(n.words > 0 ? "Full text" : "Open note", n.words > 0 ? "From the source" : "", n.words > 0 ? n.words + " words" : "", false,
            () -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.OPEN, n.path))));
        b.add(new Group(more));
        b.add(new Primary("Ask about this", () -> host.ask("Tell me about " + n.title)));
        b.add(new Danger("Move to trash", () -> Edits.confirmDelete(host, n.path, n.title, () -> host.onBackPressed())));
        page.publish(b);
    }

    /** "In my words": the note's "## My ideas" section, edited in place. */
    private void mine(List<Block> b, Vault.Note n) {
        final String saved = Vault.section(text, "My ideas");
        if (draft == null || !loadedMine) { draft = saved; loadedMine = !text.isEmpty(); }
        b.add(new Editor("In my words", saved, draft, "Add your own words", d -> draft = d, (value, done) ->
            Edits.section(ui.c, n.path, "My ideas", value, new String[]{"Why I captured this"}, problem -> {
                if (problem.isEmpty()) { Toast.makeText(ui.c, "Saved", Toast.LENGTH_SHORT).show(); draft = value; text = Vault.clipLines(NoteEdit.setSection(text, "My ideas", value, "Why I captured this")); draw(); }
                else Toast.makeText(ui.c, problem, Toast.LENGTH_LONG).show();
                done.accept(problem);
            })));
    }

    private void open(String url) {
        try { host.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (RuntimeException none) { Toast.makeText(ui.c, "No app can open this link", Toast.LENGTH_SHORT).show(); }
    }
}
