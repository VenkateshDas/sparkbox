package me.venkatesh.saveobsidian;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Home screen: setup checklist, captures that need attention, the inbox list. */
public class SetupActivity extends Activity {
    private LinearLayout page;
    private Ui ui;
    private boolean waiting;
    private int filter; // 0 all, 1 links, 2 notes, 3 with your why or ideas
    private String lastText, lastError;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ShareActivity.publishShortcut(this);
        ui = new Ui(this);
        waiting = getIntent().getBooleanExtra("waiting", false);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true); // target 35 is edge-to-edge; keep content clear of the bars
        scroll.setBackgroundColor(ui.bg);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(32));
        scroll.addView(page);
        setContentView(scroll);
    }
    @Override protected void onResume() { super.onResume(); render(); InboxWidget.refresh(this); }

    private SharedPreferences prefs() { return getSharedPreferences("capture", 0); }
    private boolean notificationsOk() { return getSystemService(NotificationManager.class).areNotificationsEnabled(); }

    /** Re-read the inbox file (so deletions made in Obsidian show up) and redraw. */
    private void render() {
        new Thread(() -> {
            String text = null, error = null;
            if (!prefs().getString("inbox", "").isEmpty()) {
                try { text = CaptureService.readText(this); } catch (Exception e) { error = "Cannot read the inbox. Choose the file again."; }
            }
            String t = text, err = error;
            runOnUiThread(() -> { if (!isDestroyed()) draw(t, err); });
        }).start();
    }

    private void draw(String inboxText, String readError) {
        lastText = inboxText; lastError = readError;
        page.removeAllViews();
        String inbox = prefs().getString("inbox", ""), name = CaptureService.inboxName(this);
        boolean ready = !inbox.isEmpty(), notifications = notificationsOk();
        File[] jobs = CaptureService.pending(this);

        header(ready, name, inboxText != null);
        if (waiting && !ready && jobs.length > 0) {
            LinearLayout card = ui.card("Waiting to save");
            card.addView(ui.text(jobs.length + " capture" + (jobs.length > 1 ? "s are" : " is") + " safe in the queue. Choose your inbox file and it will be saved.", 14, Typeface.DEFAULT, ui.ink));
            page.addView(card, ui.lp(24));
        }
        if (!ready || !notifications) setupCard(ready, name, notifications);
        if (jobs.length > 0 && ready) attentionCard(jobs);
        inboxList(inboxText, readError);

        if (ready) {
            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.CENTER);
            actions.addView(quietLink("Open in Obsidian", v -> openInObsidian(inbox)));
            actions.addView(quietLink("Check access", v -> checkAccess(inbox)));
            actions.addView(quietLink("Change file", v -> chooseInbox()));
            if (Build.VERSION.SDK_INT >= 36)
                actions.addView(quietLink("Live updates", v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
            page.addView(actions, ui.lp(32));
        }
    }

    private TextView quietLink(String label, View.OnClickListener click) {
        TextView link = ui.text(label, 12, Typeface.DEFAULT, ui.sub);
        link.setMinHeight(ui.dp(44));
        link.setGravity(Gravity.CENTER);
        link.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        link.setOnClickListener(click);
        return link;
    }

    private void header(boolean ready, String name, boolean hasList) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(ui.text("Sparkbox", 20, Ui.MEDIUM, ui.ink));
        words.addView(ui.text(ready ? name : "No inbox file", 12, Typeface.DEFAULT, ui.muted), ui.lp(4));
        row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (hasList) row.addView(ui.button("Overview", false, v -> startActivity(new Intent(this, DashboardActivity.class))));
        page.addView(row);
        if (!ready) page.addView(ui.text("Choose your inbox file, then share a link or text from any app. It is added without opening Obsidian.", 14, Typeface.DEFAULT, ui.sub), ui.lp(16));
    }

    private void setupCard(boolean ready, String name, boolean notifications) {
        LinearLayout card = ui.card("Set up");
        card.addView(step(ready, ready ? "Inbox file: " + name : "Inbox file not chosen"));
        card.addView(step(notifications, notifications ? "Notifications allowed" : "Notifications blocked"), ui.lp(12));
        card.addView(ui.button(!ready ? "Choose inbox file" : "Allow notifications", true, v -> { if (!ready) chooseInbox(); else requestNotifications(); }), ui.lp(20));
        page.addView(card, ui.lp(24));
    }
    private View step(boolean done, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams dot = new LinearLayout.LayoutParams(ui.dp(8), ui.dp(8));
        dot.rightMargin = ui.dp(12);
        row.addView(ui.dot(done), dot);
        row.addView(ui.text(label, 14, Typeface.DEFAULT, done ? ui.ink : ui.sub));
        return row;
    }

    private void attentionCard(File[] jobs) {
        LinearLayout card = null;
        for (File file : jobs) {
            JSONObject job;
            try { job = CaptureService.readJob(file); } catch (Exception e) { continue; }
            // A job still being saved is not a problem yet; show failed or stale (>2 min) ones.
            if (!"failed".equals(job.optString("phase")) && System.currentTimeMillis() - file.lastModified() < 120000) continue;
            if (card == null) {
                card = ui.card("Needs attention");
                page.addView(card, ui.lp(24));
            }
            String kind = job.optString("kind", "link"), shown = job.optString("label");
            if (shown.isEmpty()) shown = "link".equals(kind) ? job.optString("url") : Capture.clean(job.optString("text"));
            String error = job.optString("error");
            if (error.isEmpty()) error = "failed".equals(job.optString("phase")) ? "Not saved." : "Not saved yet. Retrying is safe: duplicates are skipped.";
            card.addView(ui.text(shown, 16, Ui.MEDIUM, ui.ink));
            card.addView(ui.text(error, 14, Typeface.DEFAULT, ui.sub), ui.lp(4));
            String copy = job.optString("entry", shown);
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(ui.button("Retry", true, v -> { CaptureService.start(this, file.getName()); render(); }));
            row.addView(quietLink("Copy", v -> copy(copy)));
            row.addView(quietLink("Discard", v -> { new AtomicFile(file).delete(); render(); }));
            card.addView(row, ui.lp(16));
        }
    }

    private interface Task { void run() throws Exception; }

    private boolean matches(InboxFile.Entry e) {
        switch (filter) {
            case 1: return !e.text;
            case 2: return e.text;
            case 3: return !e.why.isEmpty() || !e.ideas.isEmpty();
            default: return true;
        }
    }

    private void inboxList(String text, String error) {
        if (text == null && error == null) return; // no inbox chosen yet
        if (error != null) { page.addView(ui.text(error, 14, Typeface.DEFAULT, ui.sub), ui.lp(32)); return; }
        List<InboxFile.Entry> entries = InboxFile.parse(text);
        int notes = 0, annotated = 0;
        for (InboxFile.Entry e : entries) { if (e.text) notes++; if (!e.why.isEmpty() || !e.ideas.isEmpty()) annotated++; }

        page.addView(ui.label("Inbox"), ui.lp(32));
        if (entries.isEmpty()) {
            page.addView(ui.text("The inbox is empty", 16, Ui.MEDIUM, ui.ink), ui.lp(16));
            page.addView(ui.text("Share a link or text from any app and it is added here and to your Obsidian file.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8));
            return;
        }
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        String[] names = {"All", "Links", "Notes", "With why"};
        int[] counts = {entries.size(), entries.size() - notes, notes, annotated};
        for (int i = 0; i < names.length; i++) {
            int which = i;
            boolean on = filter == i;
            TextView chip = ui.text(names[i] + "  " + counts[i], 14, on ? Ui.MEDIUM : Typeface.DEFAULT, on ? ui.ink : ui.sub);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(ui.dp(40));
            chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
            chip.setBackground(ui.ripple(ui.round(on ? ui.raised : 0, on ? ui.line : 0, 10)));
            chip.setOnClickListener(v -> { filter = which; draw(lastText, lastError); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            p.rightMargin = ui.dp(4);
            chips.addView(chip, p);
        }
        chipScroll.addView(chips);
        page.addView(chipScroll, ui.lp(8));

        int shown = 0, total = 0;
        for (int i = entries.size() - 1; i >= 0; i--) {
            InboxFile.Entry entry = entries.get(i);
            if (!matches(entry)) continue;
            total++;
            if (shown >= 50) continue;
            shown++;
            page.addView(row(entry), ui.lp(shown == 1 ? 8 : 0));
        }
        if (total == 0) page.addView(ui.text("Nothing matches this filter", 14, Typeface.DEFAULT, ui.sub), ui.lp(16));
        if (total > 50) page.addView(ui.text((total - 50) + " older entries are in Obsidian", 12, Typeface.DEFAULT, ui.muted), ui.lp(16));
    }

    /** One inbox entry. A thin accent rail marks entries that carry your own words. */
    private View row(InboxFile.Entry entry) {
        boolean mine = !entry.why.isEmpty() || !entry.ideas.isEmpty();
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(this);
        View rail = new View(this);
        if (mine) rail.setBackgroundColor(ui.accent);
        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(ui.dp(2), ViewGroup.LayoutParams.MATCH_PARENT);
        railParams.topMargin = ui.dp(12); railParams.bottomMargin = ui.dp(12);
        row.addView(rail, railParams);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(ui.dp(12), ui.dp(12), 0, ui.dp(12));
        TextView title = ui.text(entry.title, 16, Typeface.DEFAULT, ui.ink);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title);
        String source = entry.text || entry.url.isEmpty() ? "Note" : Ui.host(entry.url);
        body.addView(ui.text(source + " · " + Ui.when(entry) + (entry.ideas.isEmpty() ? "" : " · Has ideas"), 12, Typeface.DEFAULT, ui.muted), ui.lp(4));
        if (!entry.why.isEmpty()) {
            TextView why = ui.text(entry.why.split("\n")[0], 14, Typeface.DEFAULT, ui.sub);
            why.setMaxLines(2);
            why.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(why, ui.lp(8));
        }
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.setBackground(ui.ripple(null));
        row.setOnClickListener(v -> showEntry(entry));
        row.setOnLongClickListener(v -> { copy(entry.url.isEmpty() ? entry.title : entry.url); return true; });
        box.addView(row);
        View rule = new View(this);
        rule.setBackgroundColor(ui.line);
        box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return box;
    }

    private void showEntry(InboxFile.Entry entry) {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), 0);
        if (!entry.url.isEmpty()) {
            TextView link = ui.text(entry.url, 12, Typeface.DEFAULT, ui.accent);
            link.setOnClickListener(v -> open(entry.url));
            form.addView(link);
        }
        form.addView(ui.label("Why I saved this"), ui.lp(20));
        EditText why = field(entry.why, 2);
        form.addView(why, ui.lp(8));
        form.addView(ui.label("My ideas"), ui.lp(16));
        EditText ideas = field(entry.ideas, 4);
        form.addView(ideas, ui.lp(8));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(form);
        new AlertDialog.Builder(this).setTitle(entry.title).setView(scroll)
            .setPositiveButton("Save", (d, w) -> run("Saved to Obsidian", () -> CaptureService.edit(this,
                t -> InboxFile.setNotes(t, entry.raw, why.getText().toString(), ideas.getText().toString()))))
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Delete", (d, w) -> new AlertDialog.Builder(this).setTitle("Delete this entry?")
                .setMessage(entry.title + "\n\nIt is removed from the inbox file.")
                .setPositiveButton("Delete", (d2, w2) -> run("Deleted", () -> CaptureService.edit(this, t -> InboxFile.delete(t, entry.raw))))
                .setNegativeButton("Cancel", null).show())
            .show();
    }
    private EditText field(String value, int lines) {
        EditText edit = new EditText(this);
        edit.setText(value);
        edit.setMinLines(lines);
        edit.setGravity(Gravity.TOP);
        edit.setTextColor(ui.ink);
        edit.setTextSize(15);
        edit.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        edit.setBackground(ui.round(0, ui.line, 8));
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        return edit;
    }
    /** Run a file edit off the main thread, report the result, then redraw from the file. */
    private void run(String done, Task task) {
        new Thread(() -> {
            String message = done;
            try { task.run(); } catch (Exception e) { message = String.valueOf(e.getMessage()); }
            String shown = message;
            runOnUiThread(() -> { toast(shown); render(); });
        }).start();
    }

    // --- actions ---
    private void chooseInbox() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Documents/Obsidian"));
        startActivityForResult(pick, 1);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != 1 || result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            String name = "";
            try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
            }
            if (!name.toLowerCase().endsWith(".md")) { toast("Choose a Markdown (.md) file."); return; }
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException();
            getContentResolver().takePersistableUriPermission(uri, flags);
            prefs().edit().putString("inbox", uri.toString()).putString("name", name).commit();
            if (CaptureService.pending(this).length > 0) CaptureService.start(this, null);
            render();
            if (!notificationsOk()) requestNotifications();
        } catch (Exception e) { toast("Could not get write access. Choose the file again."); }
    }
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        else startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) { render(); }

    private void checkAccess(String inbox) {
        try {
            Uri uri = Uri.parse(inbox);
            // Opening for append writes nothing; it proves the grant still works.
            try (ParcelFileDescriptor d = getContentResolver().openFileDescriptor(uri, "wa")) { if (d == null) throw new java.io.IOException(); }
            try (InputStream in = getContentResolver().openInputStream(uri)) { if (in == null) throw new java.io.IOException(); in.read(); }
            toast("Inbox is reachable and writable.");
        } catch (Exception e) { toast("Cannot reach the inbox. Choose the file again."); }
    }
    /** Assumes Documents/Obsidian/<vault>/<path>.md, the standard layout; otherwise just opens Obsidian. */
    private void openInObsidian(String inbox) {
        try {
            String id = DocumentsContract.getDocumentId(Uri.parse(inbox));
            String[] parts = id.substring(id.indexOf(':') + 1).split("/");
            int at = -1;
            for (int i = 0; i < parts.length - 2; i++) if (parts[i].equalsIgnoreCase("Obsidian")) { at = i; break; }
            if (at >= 0) {
                StringBuilder file = new StringBuilder();
                for (int i = at + 2; i < parts.length; i++) file.append(i > at + 2 ? "/" : "").append(parts[i]);
                String path = file.toString().replaceAll("(?i)\\.md$", "");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("obsidian://open?vault=" + Uri.encode(parts[at + 1]) + "&file=" + Uri.encode(path))));
                return;
            }
        } catch (Exception ignored) { }
        try { startActivity(getPackageManager().getLaunchIntentForPackage("md.obsidian")); }
        catch (Exception e) { toast("Obsidian is not installed."); }
    }
    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No app can open this link."); }
    }
    private void copy(String value) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Sparkbox", value));
        toast("Copied");
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

}
