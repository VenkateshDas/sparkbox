package me.venkatesh.saveobsidian;

import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Manage original cards, grouped by owner when opened from a Topic. */
final class CardsScreen extends Screen {
    private final String path;
    private final PageState page = new PageState();
    private Ui ui;
    CardsScreen(String path) { this.path = path; }
    @Override View build(Ui ui) { this.ui = ui; draw(); return PageView.create(ui.c, ui, "Cards", page, () -> host.onBackPressed()); }
    @Override void onShow() { CardStore.flushNow(ui.c, this::draw); draw(); }
    private void draw() {
        List<Block> b = new ArrayList<>();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        java.util.Set<String> paths = new java.util.LinkedHashSet<>(); paths.add(path);
        if (snap != null && path.startsWith("Knowledge/Topics/")) {
            Study.Scope scope = Study.topicOf(snap.graph, path, "Topic"); if (scope != null) paths.addAll(scope.paths);
        }
        List<Cards.Card> all = CardStore.all(ui.c);
        for (String owner : paths) {
            Vault.Note note = snap == null ? null : snap.graph.byPath(owner);
            boolean heading = false;
            for (Cards.Card c : all) {
                if (!c.path.equals(owner)) continue;
                if (!heading) { b.add(new Heading(note == null ? owner : note.title)); b.add(new Body(owner, true, false)); heading = true; }
                List<Tag> tags = new ArrayList<>(); tags.add(new Tag(c.mine() ? "Mine" : "AI", 0));
                if (c.flag) tags.add(new Tag("Flagged", 2)); if (c.parked > System.currentTimeMillis()) tags.add(new Tag("Parked", 0));
                List<Act> acts = new ArrayList<>();
                acts.add(new Act("Edit", () -> CardSheet.show(host, ui, c, null, this::draw)));
                acts.add(new Act(c.flag ? "Unflag" : "Flag", () -> { CardStore.flag(ui.c, c, !c.flag); draw(); }));
                acts.add(new Act("Delete", () -> new android.app.AlertDialog.Builder(host).setTitle("Delete card?")
                    .setMessage(c.q + "\n\nFrom " + owner).setPositiveButton("Delete", (d, w) -> { CardStore.remove(ui.c, c); draw(); })
                    .setNegativeButton("Cancel", null).show()));
                b.add(new Entry(tags, c.q, c.a, c.take.isEmpty() ? "" : "Your note: " + c.take, acts));
            }
        }
        if (b.isEmpty()) b.add(new Body("No cards yet.", true, false));
        b.add(new Primary("Add card to this note", () -> CardSheet.show(host, ui, null, path, this::draw)));
        boolean pending = false; for (String owner : paths) pending |= CardStore.pendingFor(ui.c, owner);
        if (pending) b.add(new Body("Changes pending on this phone.", true, false));
        b.add(new Primary("Save pending changes", () -> CardStore.flushNow(ui.c, this::draw)));
        page.publish(b);
    }
}
