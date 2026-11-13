package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** The Ask tab: a chat over your vault. Answers carry numbered markers and a list of the notes they came from. */
final class AskScreen extends Screen {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final JSONArray history = new JSONArray(); // user questions and final answers only
    private Ui ui;
    private ScrollView scroll;
    private LinearLayout thread, banner;
    private EditText input;
    private TextView send, subtitle, status, live, newChat;
    private boolean busy;
    private Cancel asking;
    private String lastQuestion = "", pendingQuestion;
    private LinearLayout running;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(ui.c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(ui.dp(16), 0, ui.dp(8), 0);
        LinearLayout titles = new LinearLayout(ui.c);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(ui.text("Ask", 20, Ui.BOLD, ui.ink));
        subtitle = ui.text("", 12, Typeface.DEFAULT, ui.sub);
        titles.addView(subtitle);
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        newChat = ui.text("New chat", 14, Ui.MEDIUM, ui.accent);
        newChat.setMinHeight(ui.dp(48));
        newChat.setGravity(Gravity.CENTER);
        newChat.setPadding(ui.dp(8), 0, ui.dp(8), 0);
        newChat.setVisibility(View.GONE);
        newChat.setOnClickListener(v -> { if (!busy) { while (history.length() > 0) history.remove(0); save(); intro(); newChat.setVisibility(View.GONE); } });
        Ui.buttonRole(newChat);
        head.addView(newChat);
        root.addView(head, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(64)));
        View rule = new View(ui.c);
        rule.setBackgroundColor(ui.line);
        root.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        banner = new LinearLayout(ui.c);
        banner.setOrientation(LinearLayout.VERTICAL);
        root.addView(banner);

        scroll = new ScrollView(ui.c);
        thread = new LinearLayout(ui.c);
        thread.setOrientation(LinearLayout.VERTICAL);
        thread.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(16));
        scroll.addView(thread);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        LinearLayout bar = new LinearLayout(ui.c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
        input = new EditText(ui.c);
        input.setHint("Ask your vault");
        input.setHintTextColor(ui.sub);
        input.setTextColor(ui.ink);
        input.setTextSize(Ui.type(15));
        input.setMaxLines(4);
        input.setRawInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, action, event) -> { ask(input.getText().toString().trim()); return true; });
        input.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        input.setBackground(ui.round(ui.raised, ui.line, 24));
        bar.addView(input, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        send = ui.text("↑", 22, Ui.BOLD, ui.onAccent);
        send.setGravity(Gravity.CENTER);
        send.setBackground(ui.round(ui.accent, 0, 24));
        send.setContentDescription("Send");
        send.setOnClickListener(v -> ask(input.getText().toString().trim()));
        Ui.buttonRole(send);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48));
        sp.leftMargin = ui.dp(8);
        bar.addView(send, sp);
        View top = new View(ui.c);
        top.setBackgroundColor(ui.line);
        root.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        root.addView(bar);

        intro();
        restore();
        return root;
    }

    @Override void onShow() {
        drawBanner();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        int n = 0;
        if (snap != null) for (Vault.Note note : snap.graph.notes) if (!note.catalog && !note.path.endsWith("/My cards.md")) n++;
        subtitle.setText(n == 0 ? "Your vault is not loaded yet" : String.format(java.util.Locale.US, "%,d notes in your vault", n));
        if (pendingQuestion != null) { String q = pendingQuestion; pendingQuestion = null; ask(q); }
    }

    /** A question from another screen (Ask about this): shown when this tab opens. */
    void prefill(String question) {
        if (input == null) { pendingQuestion = question; return; }
        main.post(() -> ask(question));
    }

    // ---- state ----

    private boolean online() {
        ConnectivityManager cm = (ConnectivityManager) ui.c.getSystemService(Context.CONNECTIVITY_SERVICE);
        return cm == null || cm.getActiveNetwork() != null;
    }

    private void drawBanner() {
        banner.removeAllViews();
        boolean off = !online();
        if (off) {
            TextView v = ui.text("You are offline. Your earlier answers stay readable. Ask needs a network connection.", 13, Typeface.DEFAULT, ui.warn);
            v.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            v.setBackground(ui.round(ui.warnBg, 0, 8));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.setMargins(ui.dp(16), ui.dp(12), ui.dp(16), 0);
            banner.addView(v, p);
        }
        input.setEnabled(!off);
        input.setHint(off ? "You are offline" : "Ask your vault");
        send.setAlpha(off || busy ? 0.4f : 1f);
    }

    private void intro() {
        thread.removeAllViews();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        if (!VaultStore.ready(ui.c) || snap == null) { thread.addView(ui.text("Choose your vault folder first.", 15, Typeface.DEFAULT, ui.sub)); return; }
        if (Model.key(ui.c).isEmpty()) { thread.addView(ui.text("Ask needs an OpenRouter key. Add one in Setup.", 15, Typeface.DEFAULT, ui.sub)); return; }
        thread.addView(ui.text("Answers come only from your notes and cite each one. What you ask, and the notes it reads, are sent to OpenRouter.", 15, Typeface.DEFAULT, ui.sub));
        List<Vault.Note> hubs = new ArrayList<>();
        for (Vault.Note n : snap.graph.notes) if (!n.catalog && n.path.startsWith("Knowledge/Topics/")) hubs.add(n);
        hubs.sort((a, b) -> snap.graph.in(b).size() - snap.graph.in(a).size());
        if (!hubs.isEmpty()) thread.addView(ui.text("Try", 12, Ui.BOLD, ui.sub), ui.lp(24));
        for (Vault.Note n : hubs.subList(0, Math.min(3, hubs.size()))) {
            String q = "What do my notes say about " + n.title + "?";
            TextView chip = ui.text(q, 15, Typeface.DEFAULT, ui.ink);
            chip.setMinHeight(ui.dp(48));
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8));
            chip.setBackground(ui.ripple(ui.round(ui.raised, ui.line, 12)));
            chip.setOnClickListener(v -> ask(q));
            Ui.buttonRole(chip);
            thread.addView(chip, ui.lp(8));
        }
    }

    // ---- asking ----

    private void ask(String question) { run(question, false); }

    private void run(String question, boolean again) {
        if (busy || question.isEmpty()) return;
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        String key = Model.key(ui.c);
        if (snap == null || key.isEmpty()) { intro(); return; }
        if (!online()) { drawBanner(); Toast.makeText(ui.c, "You are offline", Toast.LENGTH_SHORT).show(); return; }
        if (history.length() == 0 && !again) thread.removeAllViews();
        input.setText("");
        ((android.view.inputmethod.InputMethodManager) ui.c.getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(input.getWindowToken(), 0);
        busy = true;
        lastQuestion = question;
        final Cancel cancel = asking = new Cancel();
        send.setAlpha(0.4f);
        if (!again) thread.addView(bubble(question), ui.lp(thread.getChildCount() == 0 ? 0 : 20));
        running = new LinearLayout(ui.c);
        running.setOrientation(LinearLayout.VERTICAL);
        status = ui.text("Thinking…", 13, Typeface.DEFAULT, ui.sub);
        running.addView(status);
        live = ui.text("", 15, Typeface.DEFAULT, ui.ink);
        live.setLineSpacing(0, 1.3f);
        live.setVisibility(View.GONE);
        running.addView(live, ui.lp(8));
        running.addView(ui.button("Cancel", false, v -> { cancel.stop(); status.setText("Cancelling…"); }), ui.lp(12));
        thread.addView(running, ui.lp(12));
        down();
        JSONArray sent;
        try {
            sent = new JSONArray(history.toString());
            sent.put(new JSONObject().put("role", "user").put("content", question));
        } catch (Exception impossible) { busy = false; return; }
        final JSONArray messages = sent;
        new Thread(() -> {
            String answer = null, error = null;
            try {
                answer = Ask.run(ui.c, snap, key, messages, line -> main.post(() -> { if (status != null) status.setText(line); }),
                    soFar -> main.post(() -> { if (live != null) { live.setText(soFar); live.setVisibility(soFar.isEmpty() ? View.GONE : View.VISIBLE); down(); } }), cancel);
            } catch (Exception e) { error = String.valueOf(e.getMessage()).replace("Smart tags paused: ", ""); }
            final String a = answer, e = error;
            main.post(() -> finish(question, a, e));
        }).start();
    }

    private void finish(String question, String answer, String error) {
        busy = false;
        boolean cancelled = asking != null && asking.stopped();
        asking = null;
        String partial = live == null ? "" : live.getText().toString();
        thread.removeView(running);
        running = null; status = null; live = null;
        drawBanner();
        if (cancelled) {
            String shown = answer != null && !answer.isEmpty() ? answer : partial;
            if (!shown.isEmpty()) thread.addView(answerView(shown), ui.lp(12));
            thread.addView(ui.text("Cancelled.", 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
            down();
            return;
        }
        if (answer == null || answer.isEmpty()) {
            thread.addView(failure(error != null && !error.isEmpty() ? "Could not finish the answer. " + error : "Could not finish the answer. The model returned nothing."), ui.lp(12));
            down();
            return;
        }
        try {
            history.put(new JSONObject().put("role", "user").put("content", question));
            history.put(new JSONObject().put("role", "assistant").put("content", answer));
        } catch (Exception ignored) { }
        save();
        thread.addView(answerView(answer), ui.lp(12));
        newChat.setVisibility(View.VISIBLE);
        down();
    }

    // ---- drawing ----

    private View bubble(String text) {
        TextView v = ui.text(text, 15, Typeface.DEFAULT, ui.onContainer);
        v.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(12));
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(ui.container);
        float r = ui.dp(16), small = ui.dp(4);
        shape.setCornerRadii(new float[]{r, r, r, r, small, small, r, r});
        v.setBackground(shape);
        LinearLayout row = new LinearLayout(ui.c);
        row.setGravity(Gravity.END);
        row.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View failure(String message) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        box.setBackground(ui.round(ui.errBg, 0, 8));
        box.addView(ui.text(message, 13, Typeface.DEFAULT, ui.err));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = ui.dp(12);
        TextView retry = ui.button("Retry", true, v -> { thread.removeView(box); run(lastQuestion, true); });
        retry.setMinWidth(ui.dp(96));
        box.addView(retry, p);
        return box;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        return card;
    }

    private static final Pattern MARK = Pattern.compile("\\[\\d+\\]");

    private View answerView(String answer) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        if (Cites.notInKnowledge(answer)) return notFound(answer);
        Cites.Result cited = Cites.number(answer);
        LinearLayout card = card();
        if (cited.sources.size() == 1) {
            TextView warn = ui.text("Single source: 1 note supports this.", 13, Typeface.DEFAULT, ui.warn);
            warn.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
            warn.setBackground(ui.round(ui.warnBg, 0, 8));
            LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wp.bottomMargin = ui.dp(12);
            card.addView(warn, wp);
        }
        SpannableStringBuilder text = Md.inlineOnly(ui, cited.text, raw -> { });
        Matcher m = MARK.matcher(text.toString());
        while (m.find()) {
            text.setSpan(new ForegroundColorSpan(ui.accent), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new StyleSpan(Typeface.BOLD), m.start(), m.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        TextView body = ui.text("", 15, Typeface.DEFAULT, ui.ink);
        body.setLineSpacing(0, 1.3f);
        body.setText(text);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        body.setOnLongClickListener(v -> {
            ((android.content.ClipboardManager) ui.c.getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Answer", body.getText()));
            Toast.makeText(ui.c, "Answer copied", Toast.LENGTH_SHORT).show();
            return true;
        });
        card.addView(body);
        box.addView(card);
        if (!cited.sources.isEmpty()) {
            box.addView(ui.text("Sources", 12, Ui.BOLD, ui.sub), ui.lp(16));
            LinearLayout list = card();
            list.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
            for (String[] s : cited.sources) list.addView(sourceRow(s));
            box.addView(list, ui.lp(8));
        }
        return box;
    }

    private View notFound(String answer) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = card();
        card.addView(ui.text("Not in your knowledge base", 17, Ui.BOLD, ui.ink));
        card.addView(ui.text("No notes cover this. Ask does not guess.", 15, Typeface.DEFAULT, ui.sub), ui.lp(4));
        LinearLayout actions = new LinearLayout(ui.c);
        TextView search = ui.button("Search vault", true, v -> { host.select(2); });
        TextView add = ui.button("Add a note", false, v -> ui.c.startActivity(new Intent(ui.c, QuickActivity.class)));
        actions.addView(search, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ap.leftMargin = ui.dp(8);
        actions.addView(add, ap);
        card.addView(actions, ui.lp(16));
        box.addView(card);
        Cites.Result near = Cites.number(answer);
        if (!near.sources.isEmpty()) {
            box.addView(ui.text("Closest notes", 12, Ui.BOLD, ui.sub), ui.lp(16));
            LinearLayout list = card();
            list.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
            for (String[] s : near.sources) list.addView(sourceRow(s));
            box.addView(list, ui.lp(8));
        }
        return box;
    }

    /** One cited note: number, title (up to two lines), a short meta line, and a chevron. */
    private View sourceRow(String[] s) {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        Vault.Note note = snap == null ? null : snap.graph.resolve(s[1]);
        LinearLayout row = new LinearLayout(ui.c);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(ui.dp(56));
        row.setPadding(0, ui.dp(8), 0, ui.dp(8));
        TextView n = ui.text(s[0], 13, Ui.BOLD, ui.onContainer);
        n.setGravity(Gravity.CENTER);
        n.setBackground(ui.round(ui.container, 0, 14));
        row.addView(n, new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        LinearLayout text = new LinearLayout(ui.c);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(ui.dp(12), 0, ui.dp(8), 0);
        TextView title = ui.text(note != null ? note.title : s[2], 15, Ui.BOLD, ui.ink);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.addView(title);
        String meta = "";
        if (Layout.isInbox(s[1])) meta = "Inbox capture";
        else if (note != null && Layout.inSources(note.path)) { String host = Feed.host(note.meta("source")); String lane = Lane.of(note.path, note.meta); meta = (lane.isEmpty() ? "Source" : lane) + (host.isEmpty() ? "" : ", " + host); }
        else if (note != null && note.path.startsWith("Knowledge/Topics/")) meta = "Topic";
        else if (note != null && note.path.startsWith("Knowledge/People/")) meta = "Person";
        else if (note == null) meta = "Not in your vault";
        if (!meta.isEmpty()) text.addView(ui.text(meta, 13, Typeface.DEFAULT, ui.sub));
        row.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(ui.text("›", 17, Typeface.DEFAULT, ui.sub));
        row.setOnClickListener(v -> openSource(s[1], note));
        Ui.buttonRole(row);
        return row;
    }

    private void openSource(String path, Vault.Note note) {
        if (Layout.isInbox(path)) { host.select(0); return; } // the inbox lives in the Feed
        if (note == null) { Toast.makeText(ui.c, "That note is not in your vault", Toast.LENGTH_SHORT).show(); return; }
        host.openNote(note.path);
    }

    // ---- history ----

    private void save() {
        JSONArray keep = new JSONArray();
        for (int i = Math.max(0, history.length() - 20); i < history.length(); i++) keep.put(history.opt(i));
        ui.c.getSharedPreferences("capture", 0).edit().putString("askHistory", keep.toString()).apply();
    }

    private void restore() {
        try {
            JSONArray saved = new JSONArray(ui.c.getSharedPreferences("capture", 0).getString("askHistory", "[]"));
            if (saved.length() == 0 || VaultStore.cached(ui.c) == null) return;
            thread.removeAllViews();
            for (int i = 0; i < saved.length(); i++) {
                JSONObject m = saved.getJSONObject(i);
                history.put(m);
                if (m.getString("role").equals("user")) thread.addView(bubble(m.getString("content")), ui.lp(i == 0 ? 0 : 20));
                else thread.addView(answerView(m.getString("content")), ui.lp(12));
            }
            newChat.setVisibility(View.VISIBLE);
            down();
        } catch (Exception damaged) { while (history.length() > 0) history.remove(0); }
    }

    private void down() { scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN)); }
}
