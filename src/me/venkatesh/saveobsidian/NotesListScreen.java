package me.venkatesh.saveobsidian;

import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** A plain list of notes ({title, subtitle, path}); a row with a path opens it. */
final class NotesListScreen extends Screen {
    private final String title;
    private final List<String[]> rows;

    NotesListScreen(String title, List<String[]> rows) { this.title = title; this.rows = rows; }

    @Override View build(Ui ui) {
        PageState page = new PageState();
        List<Block> b = new ArrayList<>();
        if (rows.isEmpty()) b.add(new Body("Nothing here yet.", true, false));
        else {
            List<Nav> navs = new ArrayList<>();
            for (String[] r : rows) navs.add(new Nav(r[0], r[1], "", false, r[2].isEmpty() ? null : () -> host.openNote(r[2])));
            b.add(new Group(navs));
        }
        page.publish(b);
        return PageView.create(ui.c, ui, title, page, () -> host.onBackPressed());
    }
}
