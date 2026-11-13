package me.venkatesh.saveobsidian;

import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** The recall cards of one note: edit, flag, delete your own, add one. */
final class CardsScreen extends Screen {
    private final String path;
    private final PageState page = new PageState();
    private Ui ui;

    CardsScreen(String path) { this.path = path; }

    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        return PageView.create(ui.c, ui, "Cards", page, () -> host.onBackPressed());
    }

    @Override void onShow() { draw(); }

    private void draw() {
        List<Block> b = new ArrayList<>();
        for (Cards.Card c : CardStore.all(ui.c)) {
            if (!c.path.equals(path)) continue;
            List<Tag> tags = new ArrayList<>();
            if (c.mine()) tags.add(new Tag("Mine", 1));
            if (c.flag) tags.add(new Tag("Flagged", 2));
            List<Act> acts = new ArrayList<>();
            acts.add(new Act("Edit", () -> CardSheet.show(host, ui, c, null, this::draw)));
            acts.add(new Act(c.flag ? "Unflag" : "Flag", () -> { CardStore.flag(ui.c, c, !c.flag); draw(); }));
            if (c.mine()) acts.add(new Act("Delete", () -> { CardStore.remove(ui.c, c); draw(); }));
            b.add(new Entry(tags, c.q, c.a, c.take.isEmpty() ? "" : "Your note: " + c.take, acts));
        }
        if (b.isEmpty()) b.add(new Body("No cards on this note yet.", true, false));
        b.add(new Primary("Add card", () -> CardSheet.show(host, ui, null, path, this::draw)));
        page.publish(b);
    }
}
