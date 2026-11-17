package me.venkatesh.saveobsidian;

import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Full, unabridged Markdown. A stale snapshot never overwrites a newer note. */
final class NoteEditorScreen extends Screen {
    private final String path;
    private String tree, before, draft;
    private boolean busy, uncertain, leaving;
    private Ui ui;
    private EditText input;
    private TextView status;
    private Button save;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkpoint = this::remember;
    private static final java.util.concurrent.ExecutorService IO = Executors.newSingleThreadExecutor();
    NoteEditorScreen(String path) { this.path = path; }
    String path() { return path; }
    private File file() { return new File(ui.c.getFilesDir(), "note-drafts/" + Long.toHexString(Index.key(tree + "|" + path)) + ".json"); }

    @Override View build(Ui ui) {
        this.ui = ui;
        if (tree == null) tree = VaultStore.tree(ui.c);
        LinearLayout box = new LinearLayout(ui.c); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        box.setBackgroundColor(ui.bg);
        LinearLayout bar = new LinearLayout(ui.c);
        Button back = new Button(ui.c); back.setText("Back"); back.setOnClickListener(v -> host.onBackPressed()); bar.addView(back);
        save = new Button(ui.c); save.setText("Save"); save.setEnabled(false); save.setOnClickListener(v -> save()); bar.addView(save);
        Button reload = new Button(ui.c); reload.setText("Reload"); reload.setOnClickListener(v -> reload()); bar.addView(reload); box.addView(bar);
        status = ui.text(path + " · Loading…", 13, Ui.MEDIUM, ui.sub); box.addView(status);
        input = new EditText(ui.c); input.setTextColor(ui.ink); input.setBackgroundColor(ui.bg); input.setGravity(android.view.Gravity.TOP);
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setTypeface(android.graphics.Typeface.MONOSPACE); input.setTextSize(15); input.setContentDescription("Full Markdown note"); input.setEnabled(false);
        box.addView(input, new LinearLayout.LayoutParams(-1, 0, 1));
        input.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int count, int after) {
                if (before == null || busy) return;
                draft = s.toString(); main.removeCallbacks(checkpoint); main.postDelayed(checkpoint, 250); status.setText(path + " · Draft on this phone");
            }
            public void afterTextChanged(Editable e) { }
        });
        CardStore.flushNow(ui.c, () -> {
            if (CardStore.pendingFor(ui.c, path)) { status.setText("Pending card/bookmark changes. Resolve vault access, then reopen editor."); return; }
            IO.execute(() -> load(true));
        });
        return box;
    }
    private void load(boolean restore) {
        try {
            String current = VaultWrite.readPinned(ui.c, tree, path);
            if (current == null) throw new java.io.IOException("Note no longer exists.");
            String base = current, value = current; boolean attempted = false;
            if (restore && (file().exists() || new File(file().getPath() + ".bak").exists())) {
                JSONObject old = CaptureService.readJob(file());
                base = old.getString("before"); value = old.getString("draft"); attempted = old.optBoolean("attempted");
                if (attempted && current.equals(value)) { base = current; attempted = false; }
            }
            final String original = base, text = value; final boolean check = attempted;
            host.runOnUiThread(() -> {
                busy = true; before = original; draft = text; uncertain = check; input.setText(text); busy = false;
                input.setEnabled(true); save.setEnabled(!uncertain);
                status.setText(uncertain ? "Previous save uncertain. Copy your draft, then Reload to inspect the note." : path + " · Save checks for changes in Obsidian");
            });
        } catch (Exception e) { host.runOnUiThread(() -> status.setText("Cannot open: " + e.getMessage())); }
    }
    private void persist(String base, String text, boolean attempted) throws Exception {
        file().getParentFile().mkdirs();
        CaptureService.writeJob(file(), new JSONObject().put("before", base).put("draft", text).put("attempted", attempted));
    }
    private void remember() {
        final String base = before, text = draft; final boolean attempted = uncertain;
        if (base == null || busy) return;
        IO.execute(() -> { try { persist(base, text, attempted); } catch (Exception e) { host.runOnUiThread(() -> status.setText("Draft not saved on this phone. Copy it before leaving.")); } });
    }
    private void save() {
        if (busy || uncertain || before == null) return;
        final String base = before, text = before.contains("\r\n") ? draft.replace("\r\n", "\n").replace("\n", "\r\n") : draft;
        if (text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 2 * 1024 * 1024) { status.setText("Note exceeds 2 MiB. Draft kept; shorten it before saving."); return; }
        busy = true; save.setEnabled(false); input.setEnabled(false); status.setText("Saving…");
        IO.execute(() -> {
            boolean attempted = false;
            try {
                persist(base, text, true);
                if (CardStore.pendingFor(ui.c, path)) throw new VaultWrite.Stale(path);
                attempted = true;
                VaultWrite.writePinned(ui.c, tree, path, base, text);
                persist(text, text, false);
                VaultStore.scan(ui.c);
                host.runOnUiThread(() -> { before = draft = text; uncertain = false; busy = false; input.setEnabled(true); save.setEnabled(true); status.setText("Saved"); });
            } catch (Exception e) {
                boolean stale = e instanceof VaultWrite.Stale;
                final boolean check = attempted && !stale;
                try { persist(base, text, check); } catch (Exception ignored) { }
                host.runOnUiThread(() -> {
                    uncertain = check; busy = false; input.setEnabled(true); save.setEnabled(!check);
                    status.setText(stale ? "Note changed. Draft kept. Copy it or Reload; no overwrite." : "Save not verified. Draft kept. " + e.getMessage());
                });
            }
        });
    }
    private void reload() {
        if (busy) return;
        new AlertDialog.Builder(host).setTitle("Reload current note?").setMessage("Copy your draft first if needed. A recovery copy also stays on this phone.")
            .setNeutralButton("Copy draft", (d, w) -> copy()).setPositiveButton("Reload", (d, w) -> {
                IO.execute(() -> { try {
                    if (file().exists()) CaptureService.writeJob(new File(file().getParentFile(), file().getName() + ".recovery"), CaptureService.readJob(file()));
                    load(false);
                } catch (Exception e) { host.runOnUiThread(() -> status.setText("Reload failed; draft kept.")); } });
            }).setNegativeButton("Cancel", null).show();
    }
    private void copy() { ((android.content.ClipboardManager) ui.c.getSystemService(android.content.Context.CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Markdown draft", draft == null ? "" : draft)); }
    @Override void onHide() { main.removeCallbacks(checkpoint); remember(); }
    @Override boolean onBack() {
        if (leaving || before == null || before.equals(draft)) return false;
        if (busy) return true;
        new AlertDialog.Builder(host).setTitle("Unsaved Markdown")
            .setPositiveButton("Keep draft", (d, w) -> { remember(); leaving = true; host.onBackPressed(); })
            .setNeutralButton("Discard", (d, w) -> { draft = before; remember(); leaving = true; host.onBackPressed(); })
            .setNegativeButton("Continue editing", null).show();
        return true;
    }
}
