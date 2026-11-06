package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Ask your notes. Answers cite notes as [[path|Title]]; tapping a citation opens the note. */
public class AskActivity extends Activity {
    static final String QUESTION = "q";
    private Ui ui;
    private ScrollView scroll;
    private LinearLayout thread;
    private EditText input;
    private TextView send, status, clear;
    private final JSONArray history = new JSONArray(); // user questions and final answers only
    private boolean busy;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        ui = new Ui(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        TextView back = ui.text("‹  Knowledge", 14, Ui.MEDIUM, ui.sub);
        back.setMinHeight(ui.dp(48));
        back.setMinWidth(ui.dp(48));
        back.setGravity(Gravity.CENTER_VERTICAL);
        back.setOnClickListener(v -> finish());
        Ui.buttonRole(back);
        top.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        clear = ui.text("New chat", 14, Ui.MEDIUM, ui.sub);
        clear.setMinHeight(ui.dp(48));
        clear.setMinWidth(ui.dp(48));
        clear.setGravity(Gravity.CENTER);
        clear.setVisibility(android.view.View.GONE);
        clear.setOnClickListener(v -> { if (!busy) { while (history.length() > 0) history.remove(0); intro(); clear.setVisibility(android.view.View.GONE); } });
        Ui.buttonRole(clear);
        top.addView(clear);
        root.addView(top);

        scroll = new ScrollView(this);
        thread = new LinearLayout(this);
        thread.setOrientation(LinearLayout.VERTICAL);
        thread.setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(16));
        scroll.addView(thread);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), ui.dp(12));
        input = new EditText(this);
        input.setHint("Ask your notes");
        input.setHintTextColor(ui.muted);
        input.setTextColor(ui.ink);
        input.setTextSize(15);
        input.setMaxLines(4);
        input.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); // wraps like a multi-line field, and the keyboard's action key sends
        input.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, action, event) -> { ask(input.getText().toString().trim()); return true; });
        input.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        input.setBackground(ui.round(0, ui.line, 8));
        bottom.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = ui.button("Ask", true, v -> ask(input.getText().toString().trim()));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ui.dp(48));
        sp.leftMargin = ui.dp(8);
        bottom.addView(send, sp);
        root.addView(bottom);
        setContentView(root);
        Ui.insets(root);
        intro();
        String q = getIntent().getStringExtra(QUESTION);
        if (q != null && !q.trim().isEmpty()) { getIntent().removeExtra(QUESTION); root.post(() -> ask(q.trim())); }
        else if (VaultStore.ready(this) && !Model.key(this).isEmpty()) input.postDelayed(() -> {
            input.requestFocus();
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(input, 0);
        }, 150);
    }

    private VaultStore.Snap snap() { return VaultStore.cached(this); }

    private void intro() {
        thread.removeAllViews();
        thread.addView(ui.text("Ask", 20, Ui.MEDIUM, ui.ink), ui.lp(4));
        if (!VaultStore.ready(this) || snap() == null) { thread.addView(ui.text("Choose your vault folder in Knowledge first.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8)); return; }
        if (Model.key(this).isEmpty()) {
            thread.addView(ui.text("Ask needs an OpenRouter key. Add one in Settings under Smart tags.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8));
            return;
        }
        thread.addView(ui.text("Answers come only from your notes, learnings, sources and inbox, and cite each one. Searches and the text it reads are sent to OpenRouter.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8));
        // Starter questions from the notes with the most links, so they are about this vault and nothing else.
        List<Vault.Note> hubs = new ArrayList<>();
        for (Vault.Note n : snap().graph.notes) if (!n.catalog && n.path.startsWith("Knowledge/Topics/")) hubs.add(n);
        hubs.sort((a, b) -> snap().graph.in(b).size() - snap().graph.in(a).size());
        if (!hubs.isEmpty()) thread.addView(ui.label("Try"), ui.lp(24));
        for (Vault.Note n : hubs.subList(0, Math.min(3, hubs.size()))) {
            String q = "What do my notes say about " + n.title + "?";
            TextView chip = ui.text(q, 14, Typeface.DEFAULT, ui.ink);
            chip.setMinHeight(ui.dp(48));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            chip.setBackground(ui.ripple(ui.round(ui.raised, ui.line, 8)));
            chip.setOnClickListener(v -> ask(q));
            Ui.buttonRole(chip);
            thread.addView(chip, ui.lp(8));
        }
    }

    private void ask(String question) {
        if (busy || question.isEmpty()) return;
        VaultStore.Snap snap = snap();
        String key = Model.key(this);
        if (snap == null || key.isEmpty()) { intro(); return; }
        if (history.length() == 0) thread.removeAllViews();
        input.setText("");
        ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0); // leave room to read the answer
        busy = true;
        send.setAlpha(0.4f);
        TextView q = ui.text(question, 15, Ui.MEDIUM, ui.ink);
        q.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        q.setBackground(ui.round(ui.raised, 0, 10));
        thread.addView(q, ui.lp(history.length() == 0 ? 8 : 24));
        status = ui.text("Thinking", 12, Typeface.MONOSPACE, ui.muted);
        thread.addView(status, ui.lp(12));
        down();
        JSONArray messages;
        try {
            messages = new JSONArray(history.toString());
            messages.put(new JSONObject().put("role", "user").put("content", question));
        } catch (Exception impossible) { busy = false; return; }
        JSONArray sent = messages;
        new Thread(() -> {
            String answer = null, error = null;
            try { answer = Ask.run(this, snap, key, sent, line -> runOnUiThread(() -> { if (status != null) status.setText(line); })); }
            catch (Exception e) { error = String.valueOf(e.getMessage()).replace("Smart tags paused: ", ""); }
            String a = answer, e = error;
            runOnUiThread(() -> finish(question, a, e));
        }).start();
    }

    private void finish(String question, String answer, String error) {
        busy = false;
        send.setAlpha(1f);
        if (isDestroyed()) return;
        thread.removeView(status);
        status = null;
        if (answer == null || answer.isEmpty()) {
            thread.addView(ui.text(error != null ? error : "The model returned no answer. Try again.", 14, Typeface.DEFAULT, ui.sub), ui.lp(12));
            down();
            return;
        }
        try {
            history.put(new JSONObject().put("role", "user").put("content", question));
            history.put(new JSONObject().put("role", "assistant").put("content", answer));
        } catch (Exception ignored) { }
        clear.setVisibility(android.view.View.VISIBLE);
        TextView t = ui.text("", 15, Typeface.DEFAULT, ui.ink);
        t.setLineSpacing(0, 1.35f);
        t.setText(Md.inlineOnly(ui, answer, raw -> open(raw)));
        t.setOnLongClickListener(v -> {
            ((android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Answer", t.getText()));
            Toast.makeText(this, "Answer copied", Toast.LENGTH_SHORT).show();
            return true;
        });
        t.setMovementMethod(LinkMovementMethod.getInstance());
        t.setHighlightColor(0x33000000 | (ui.accent & 0xFFFFFF));
        thread.addView(t, ui.lp(12));
        down();
    }

    private void open(String raw) {
        if (Vault.target(raw).equalsIgnoreCase(Ask.INBOX)) { // an inbox capture: show it in the inbox list
            startActivity(new Intent(this, SetupActivity.class).putExtra(SetupActivity.FIND, Vault.label(raw)).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            return;
        }
        VaultStore.Snap snap = snap();
        Vault.Note n = snap == null ? null : snap.graph.resolve(Vault.target(raw));
        if (n == null) { Toast.makeText(this, "That note is not in your vault", Toast.LENGTH_SHORT).show(); return; }
        startActivity(new Intent(this, VaultActivity.class).putExtra(VaultActivity.OPEN, n.path).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
    }

    private void down() { scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN)); }
}
