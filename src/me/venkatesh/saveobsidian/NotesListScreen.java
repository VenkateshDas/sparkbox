package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.List;

/** A plain list of notes (or plain lines) opened from a count row on the Topic page. Each row is {title, meta, path}; an empty path is not tappable. */
final class NotesListScreen extends Screen {
    private final String title;
    private final List<String[]> rows;

    NotesListScreen(String title, List<String[]> rows) { this.title = title; this.rows = rows; }

    @Override View build(Ui ui) {
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, title, v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        LinearLayout page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        if (rows.isEmpty()) page.addView(ui.text("Nothing here yet.", 15, Typeface.DEFAULT, ui.sub));
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(ui.c);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinimumHeight(ui.dp(56));
            row.setPadding(0, ui.dp(8), 0, ui.dp(8));
            row.addView(ui.text(r[0], 15, r[2].isEmpty() ? Typeface.DEFAULT : Ui.BOLD, ui.ink));
            if (!r[1].isEmpty()) row.addView(ui.text(r[1], 13, Typeface.DEFAULT, ui.sub));
            if (!r[2].isEmpty()) { row.setOnClickListener(v -> host.openNote(r[2])); Ui.buttonRole(row); }
            card.addView(row);
        }
        if (!rows.isEmpty()) page.addView(card);
        return root;
    }
}
