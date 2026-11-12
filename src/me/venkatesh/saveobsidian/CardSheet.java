package me.venkatesh.saveobsidian;

import android.app.AlertDialog;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** A small dialog to write or edit a card: question, answer and your own note. */
final class CardSheet {
    private CardSheet() { }

    private static EditText field(Ui ui, String hint, String value, int lines) {
        EditText e = new EditText(ui.c);
        e.setHint(hint);
        e.setText(value);
        e.setMinLines(lines);
        e.setGravity(Gravity.TOP);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return e;
    }

    /** Edit an existing card, or (existing null) write a new one on the note at path, or in My cards when path is null. done runs after it is saved. */
    static void show(MainActivity host, Ui ui, Cards.Card existing, String path, Runnable done) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        EditText q = field(ui, "Question", existing == null ? "" : existing.q, 2);
        EditText a = field(ui, "Answer", existing == null ? "" : existing.a, 2);
        EditText take = field(ui, "Your note (optional)", existing == null ? "" : existing.take, 2);
        box.addView(q);
        box.addView(a);
        box.addView(take);
        new AlertDialog.Builder(host).setTitle(existing == null ? "Write your own card" : "Edit card").setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String qs = q.getText().toString().trim(), as = a.getText().toString().trim();
                if (qs.isEmpty() || as.isEmpty()) { Toast.makeText(ui.c, "A card needs a question and an answer", Toast.LENGTH_SHORT).show(); return; }
                if (!VaultWrite.writable(ui.c)) { Toast.makeText(ui.c, "Allow write access to your vault folder in Settings.", Toast.LENGTH_LONG).show(); return; }
                if (existing == null) CardStore.addOwn(ui.c, path, qs, as, take.getText().toString());
                else CardStore.edit(ui.c, existing, qs, as, take.getText().toString());
                if (done != null) done.run();
            }).setNegativeButton("Cancel", null).show();
    }

    /** Just the note on a card: add or change it. */
    static void take(MainActivity host, Ui ui, Cards.Card card, Runnable done) {
        EditText e = field(ui, "Your note", card.take, 3);
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(host).setTitle("Your note").setView(box)
            .setPositiveButton("Save", (d, w) -> { CardStore.setTake(ui.c, card, e.getText().toString()); if (done != null) done.run(); })
            .setNegativeButton("Cancel", null).show();
    }
}
