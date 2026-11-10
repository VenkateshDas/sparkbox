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

/** Capture-first inbox; configuration and recovery live in Settings. */
public class SetupActivity extends Activity {
    static final String FIND = "find";
    static final String ANNOTATE = "annotate"; // raw entry just saved; opens its editor. Harmless from other apps: it only opens an entry already in the file, and nothing is written until Save.
    private String pendingEntry;
    static volatile boolean findFromApp; // set by Ask just before it sends FIND: this activity is exported, so other apps must not drive the filter
    private LinearLayout page;
    private Ui ui;
    private boolean waiting;
    private boolean settings;
    private int inboxScroll, settingsScroll;
    private int filter; // 0 all, 1 links, 2 notes, 3 with your why or ideas
    private String lastText, lastError;
    private int renderGeneration;
    private ScrollView scroll;
    private View fab, scrim, askPill;
    private LinearLayout dial;
    private boolean dialOpen;
    private AlertDialog editor;
    private EditText draftWhy, draftIdeas;
    private InboxFile.Entry draftEntry;
    private String draftSnapshot, draftDestination;
    static volatile boolean editing; // read by Tagger so background edits wait while the editor is open
    private List<String> semanticRaw; // raw text of the closest matches, best first; null = plain word match
    private String query = "", tag = "";
    private boolean saving;
    private final String draftId = java.util.UUID.randomUUID().toString();
    private static final Object DRAFT_LOCK = new Object();
    private int visibleLimit = 50;
    private int restoredScroll;
    private Bundle restoredDraft;
    private boolean listening;
    private final android.content.BroadcastReceiver inboxUpdates = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context context, Intent intent) { render(); }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false); // the app switcher must not show inbox contents
        ShareActivity.publishShortcut(this);
        ui = new Ui(this);
        waiting = getIntent().getBooleanExtra("waiting", false);
        pendingEntry = getIntent().getStringExtra(ANNOTATE);
        getIntent().removeExtra(ANNOTATE);
        if (state != null) { settings = state.getBoolean("settings"); inboxScroll = state.getInt("inboxScroll"); settingsScroll = state.getInt("settingsScroll"); restoredScroll = state.getInt("scroll"); filter = state.getInt("filter"); query = state.getString("query", ""); tag = state.getString("tag", ""); visibleLimit = state.getInt("visibleLimit", 50);  }
        {
            String savedDraft = "";
            File draft = new File(getFilesDir(), "draft.json");
            if (draft.exists()) try (InputStream input = new AtomicFile(draft).openRead()) {
                savedDraft = new String(CaptureService.readAll(input), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) { toast("Draft could not be read. It remains in private storage."); }
            if (!savedDraft.isEmpty()) try {
                JSONObject d = new JSONObject(savedDraft);
                restoredDraft = new Bundle();
                for (String key : new String[]{"snapshot", "destination", "why", "ideas"}) restoredDraft.putString(key, d.getString(key));
                restoredDraft.putInt("start", d.getInt("start"));
            } catch (Exception ignored) { toast("Draft could not be opened. It remains in private storage."); }
        }
        scroll = new ScrollView(this);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(ui.bg);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(24), ui.dp(16), ui.dp(24), ui.dp(32));
        scroll.addView(page);
        frame.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        TextView plus = ui.text("+", 28, Ui.MEDIUM, ui.bg);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(ui.ripple(ui.round(ui.ink, 0, 26)));
        plus.setElevation(ui.dp(6));
        plus.setContentDescription("Capture");
        plus.setOnClickListener(v -> toggleDial());
        Ui.buttonRole(plus);
        fab = plus;
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(ui.dp(52), ui.dp(52), Gravity.BOTTOM | Gravity.END);
        at.setMargins(0, 0, ui.dp(20), ui.dp(20));
        scrim = new View(this);
        scrim.setBackgroundColor((ui.bg & 0xFFFFFF) | 0xD9000000);
        scrim.setVisibility(View.GONE);
        scrim.setOnClickListener(v -> toggleDial());
        frame.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        dial = new LinearLayout(this);
        dial.setOrientation(LinearLayout.VERTICAL);
        dial.setVisibility(View.GONE);
        dial.addView(dialRow("Link or text", R.drawable.ic_dial_link, this::captureText), ui.lp(0));
        dial.addView(dialRow("Photo or file", R.drawable.ic_dial_file, this::capturePick), ui.lp(16));
        dial.addView(dialRow("Voice note", R.drawable.ic_dial_mic, this::captureVoice), ui.lp(16));
        FrameLayout.LayoutParams above = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.END);
        above.setMargins(0, 0, ui.dp(24), ui.dp(92));
        frame.addView(dial, above);
        // Ask sits beside the capture button: capture adds to the inbox, Ask reads everything in it and in the vault.
        TextView ask = ui.text("Ask", 15, Ui.MEDIUM, ui.ink);
        ask.setGravity(Gravity.CENTER);
        ask.setBackground(ui.ripple(ui.round(ui.raised, ui.line, 26)));
        ask.setElevation(ui.dp(6));
        ask.setPadding(ui.dp(24), 0, ui.dp(24), 0);
        ask.setContentDescription("Ask your notes");
        ask.setOnClickListener(v -> startActivity(new Intent(this, AskActivity.class)));
        Ui.buttonRole(ask);
        askPill = ask;
        FrameLayout.LayoutParams beside = new FrameLayout.LayoutParams(-2, ui.dp(52), Gravity.BOTTOM | Gravity.END);
        beside.setMargins(0, 0, ui.dp(84), ui.dp(20));
        frame.addView(ask, beside);
        frame.addView(fab, at);
        setContentView(frame);
        Ui.insets(frame); // target 35 is edge-to-edge; keep content clear of the bars
    }

    /** Gmail-style speed dial: options rise out of the + one above the other. They reuse the share queue and ShareActivity. */
    private void toggleDial() {
        dialOpen = !dialOpen;
        fab.animate().rotation(dialOpen ? 45 : 0).setDuration(180);
        askPill.animate().alpha(dialOpen ? 0 : 1).setDuration(150); // the dial owns the corner while open
        askPill.setClickable(!dialOpen);
        if (dialOpen) {
            scrim.setAlpha(0); scrim.setVisibility(View.VISIBLE); scrim.animate().alpha(1).setDuration(180);
            dial.setVisibility(View.VISIBLE);
            for (int i = 0; i < dial.getChildCount(); i++) { // nearest the button first
                View row = dial.getChildAt(dial.getChildCount() - 1 - i);
                row.setAlpha(0); row.setTranslationY(ui.dp(24));
                row.animate().alpha(1).translationY(0).setStartDelay(i * 40L).setDuration(180);
            }
        } else {
            scrim.animate().alpha(0).setDuration(150).withEndAction(() -> { if (!dialOpen) scrim.setVisibility(View.GONE); });
            for (int i = 0; i < dial.getChildCount(); i++) dial.getChildAt(i).animate().alpha(0).translationY(ui.dp(24)).setStartDelay(0).setDuration(150);
            dial.postDelayed(() -> { if (!dialOpen) dial.setVisibility(View.GONE); }, 160);
        }
    }

    private View dialRow(String label, int icon, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        TextView name = ui.text(label, 14, Ui.MEDIUM, ui.ink);
        name.setPadding(ui.dp(12), ui.dp(8), ui.dp(12), ui.dp(8));
        name.setBackground(ui.round(ui.surface, ui.line, 8));
        name.setElevation(ui.dp(4));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2);
        gap.rightMargin = ui.dp(12);
        row.addView(name, gap);
        ImageView disc = new ImageView(this);
        disc.setImageResource(icon);
        disc.setColorFilter(ui.bg);
        disc.setPadding(ui.dp(11), ui.dp(11), ui.dp(11), ui.dp(11));
        disc.setBackground(ui.round(ui.ink, 0, 22));
        disc.setElevation(ui.dp(4));
        row.addView(disc, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        row.setContentDescription(label);
        row.setOnClickListener(v -> { toggleDial(); action.run(); });
        Ui.buttonRole(row);
        return row;
    }

    private void captureText() {
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8));
        View handle = new View(this);
        handle.setBackground(ui.round((ui.ink & 0xFFFFFF) | 0x33000000, 0, 2));
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        form.addView(handle, grip);
        form.addView(ui.text("New capture", 20, Ui.MEDIUM, ui.ink), ui.lp(16));
        form.addView(ui.text("Paste a link or write a note", 14, Typeface.DEFAULT, ui.sub), ui.lp(4));
        EditText input = field("", 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS); // no autocorrect: it breaks pasted URLs
        form.addView(input, ui.lp(16));
        AlertDialog d = new AlertDialog.Builder(this).setView(form)
            .setPositiveButton("Save", (dialog, w) -> {
                String value = input.getText().toString().trim();
                if (!value.isEmpty()) startActivity(new Intent(this, ShareActivity.class).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value));
            }).setNegativeButton("Cancel", null).create();
        d.show();
        sheet(d, false);
        input.requestFocus();
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
    }
    private void capturePick() {
        startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), 5);
    }
    private void captureVoice() { startActivity(new Intent(this, QuickActivity.class).setAction(QuickActivity.VOICE)); }

    @Override protected void onResume() {
        super.onResume();
        android.content.IntentFilter changed = new android.content.IntentFilter(CaptureService.CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(inboxUpdates, changed, android.content.Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(inboxUpdates, changed);
        listening = true;
        if (VaultStore.ready(this) && VaultStore.last == null) new Thread(() -> VaultStore.cached(this)).start(); // warm the Knowledge screen while the inbox draws
        render(); InboxWidget.refresh(this);
        BackgroundJob.schedule(this);
        InboxWidget.refresh(this);
    }

    private SharedPreferences prefs() { return getSharedPreferences("capture", 0); }
    private boolean notificationsOk() { return getSystemService(NotificationManager.class).areNotificationsEnabled(); }

    /** Re-read the inbox file (so deletions made in Obsidian show up) and redraw. */
    private void render() {
        int generation = ++renderGeneration;
        new Thread(() -> {
            String text = null, error = null;
            if (!prefs().getString("inbox", "").isEmpty()) {
                try { text = CaptureService.readText(this); } catch (Exception e) { error = "Cannot read the inbox. Choose the file again."; }
            }
            String t = text, err = error;
            runOnUiThread(() -> { if (!isDestroyed() && generation == renderGeneration) { draw(t, err); openPending(t); } });
        }).start();
    }

    private void openPending(String text) {
        String raw = pendingEntry;
        pendingEntry = null;
        if (raw == null || text == null || editor != null) return;
        for (InboxFile.Entry e : InboxFile.parse(text)) if (e.raw.trim().equals(raw.trim())) { Usage.hit(this, "island"); showEntry(e, text, prefs().getString("inbox", ""), e.why, e.ideas); return; }
    }

    private void draw(String inboxText, String readError) {
        lastText = inboxText; lastError = readError;
        fab.setVisibility(settings ? View.GONE : View.VISIBLE);
        askPill.setVisibility(settings ? View.GONE : View.VISIBLE);
        if (settings && dialOpen) toggleDial();
        int position = restoredScroll > 0 ? restoredScroll : scroll.getScrollY(); restoredScroll = 0;
        page.removeAllViews();
        String inbox = prefs().getString("inbox", ""), name = CaptureService.inboxName(this);
        boolean ready = !inbox.isEmpty(), notifications = notificationsOk();
        File[] jobs = CaptureService.pending(this);

        if (settings) {
            settingsPage(ready, name, notifications, inbox, jobs);
        } else {
            header();
            if (!ready) {
                if (waiting && jobs.length > 0) page.addView(ui.text("Your capture is kept on this phone. Choose an inbox to finish saving.", 14, Typeface.DEFAULT, ui.sub), ui.lp(16));
                setupCard(false, name, notifications);
            }
            int attention = attentionCount(jobs);
            if (attention > 0 || CaptureService.recoveries(this).length > 0) {
                TextView notice = settingsLink("Inbox needs attention · Review", v -> showSettings(true));
                notice.setTextColor(ui.accent);
                page.addView(notice, ui.lp(12));
            }
            inboxList(inboxText, readError);
        }

        scroll.post(() -> scroll.scrollTo(0, position));
        if (!settings && restoredDraft != null && !editing) {
            Bundle d = restoredDraft; restoredDraft = null;
            String snapshot = d.getString("snapshot", "");
            for (InboxFile.Entry e : InboxFile.parse(snapshot)) if (e.start == d.getInt("start")) {
                if (d.getString("why", "").equals(e.why) && d.getString("ideas", "").equals(e.ideas)) { new AtomicFile(new File(getFilesDir(), "draft.json")).delete(); break; } // saved by an older build with nothing typed: not a draft
                showEntry(e, snapshot, d.getString("destination", ""), d.getString("why", ""), d.getString("ideas", "")); break;
            }
        }
    }

    private TextView quietLink(String label, View.OnClickListener click) {
        TextView link = ui.text(label, 12, Typeface.DEFAULT, ui.sub);
        link.setMinHeight(ui.dp(48));
        link.setMinWidth(ui.dp(48));
        link.setGravity(Gravity.CENTER);
        link.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        link.setOnClickListener(click);
        Ui.buttonRole(link);
        return link;
    }

    /** Opened from an Ask citation: show the inbox filtered to that capture. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        pendingEntry = intent.getStringExtra(ANNOTATE);
        intent.removeExtra(ANNOTATE);
        if (pendingEntry != null) render();
        String find = intent.getStringExtra(FIND);
        if (find == null) return;
        intent.removeExtra(FIND);
        boolean ours = findFromApp;
        findFromApp = false;
        if (!ours) return;
        query = find; filter = 0; tag = ""; semanticRaw = null; visibleLimit = 50;
        if (settings) showSettings(false); else draw(lastText, lastError);
    }

    private void header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView burger = quietLink("☰", v -> menu());
        burger.setTextSize(22);
        burger.setTextColor(ui.ink);
        burger.setContentDescription("Menu");
        burger.setPadding(0, 0, ui.dp(12), 0);
        row.addView(burger);
        row.addView(ui.text("Sparkbox", 16, Ui.MEDIUM, ui.sub), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(row);
    }

    /** Navigation drawer: pages, actions and tag browsing, so the home screen stays just the inbox. */
    private void menu() {
        android.app.Dialog drawer = new android.app.Dialog(this);
        drawer.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(ui.dp(24), ui.dp(32), ui.dp(24), ui.dp(24));
        list.addView(ui.text("Sparkbox", 20, Ui.MEDIUM, ui.ink));
        boolean ready = !prefs().getString("inbox", "").isEmpty();
        list.addView(menuItem(drawer, "Overview", () -> startActivity(new Intent(this, DashboardActivity.class))), ui.lp(16));
        list.addView(menuItem(drawer, "Knowledge", () -> startActivity(new Intent(this, VaultActivity.class))));
        list.addView(menuItem(drawer, "Settings", () -> showSettings(true)));
        if (attentionCount(CaptureService.pending(this)) > 0 || CaptureService.recoveries(this).length > 0) {
            TextView attention = menuItem(drawer, "Needs attention", () -> showSettings(true));
            attention.setTextColor(ui.accent);
            list.addView(attention);
        }
        if (ready) {
            list.addView(menuItem(drawer, "Refresh inbox", this::render));
            list.addView(menuItem(drawer, "Open in Obsidian", () -> openInObsidian(prefs().getString("inbox", ""))));
        }
        java.util.Map<String, Integer> tagCounts = new java.util.HashMap<>();
        if (lastText != null) for (InboxFile.Entry e : InboxFile.parse(lastText)) for (String t : e.tags.split(", ")) if (!t.isEmpty()) tagCounts.merge(t, 1, Integer::sum);
        if (!tagCounts.isEmpty()) {
            List<java.util.Map.Entry<String, Integer>> top = new java.util.ArrayList<>(tagCounts.entrySet());
            top.sort((a, b) -> b.getValue() != a.getValue().intValue() ? b.getValue() - a.getValue() : a.getKey().compareTo(b.getKey()));
            list.addView(ui.label("Tags"), ui.lp(24));
            for (java.util.Map.Entry<String, Integer> t : top.subList(0, Math.min(top.size(), 20))) {
                String name = t.getKey();
                TextView item = menuItem(drawer, "#" + name + "  " + t.getValue(), () -> { tag = tag.equals(name) ? "" : name; visibleLimit = 50; showSettings(false); draw(lastText, lastError); });
                if (tag.equals(name)) item.setTypeface(Ui.MEDIUM);
                list.addView(item);
            }
        }
        ScrollView content = new ScrollView(this);
        content.addView(list);
        drawer.setContentView(content);
        android.view.Window w = drawer.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(ui.surface));
        w.setLayout(ui.dp(300), ViewGroup.LayoutParams.MATCH_PARENT);
        w.setGravity(Gravity.START);
        drawer.show();
    }

    private TextView menuItem(android.app.Dialog drawer, String label, Runnable action) {
        return settingsLink(label, v -> { drawer.dismiss(); action.run(); });
    }

    /** Reuse the native page and existing actions; no new navigation or storage layer. */
    private void showSettings(boolean show) {
        if (settings == show) return;
        if (settings) settingsScroll = scroll.getScrollY(); else inboxScroll = scroll.getScrollY();
        settings = show;
        restoredScroll = show ? settingsScroll : inboxScroll;
        scroll.scrollTo(0, restoredScroll);
        draw(lastText, lastError);
    }
    @Override public void onBackPressed() {
        if (dialOpen) toggleDial(); else if (settings) showSettings(false); else super.onBackPressed();
    }
    private TextView settingsLink(String label, View.OnClickListener click) {
        TextView link = quietLink(label, click);
        link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        link.setTextSize(14);
        link.setTextColor(ui.ink);
        link.setPadding(0, ui.dp(8), 0, ui.dp(8));
        link.setBackground(ui.ripple(null));
        return link;
    }
    private int attentionCount(File[] jobs) {
        int count = 0;
        for (File file : jobs) {
            try {
                String phase = CaptureService.readJob(file).optString("phase");
                if ("failed".equals(phase) || "uncertain".equals(phase) || System.currentTimeMillis() - file.lastModified() >= 120000) count++;
            } catch (Exception unreadable) { count++; }
        }
        return count;
    }
    private void settingsPage(boolean ready, String name, boolean notifications, String inbox, File[] jobs) {
        page.addView(settingsLink("Back to inbox", v -> showSettings(false)));
        page.addView(ui.text("Settings", 28, Ui.MEDIUM, ui.ink), ui.lp(16));

        LinearLayout destination = ui.card("Inbox file");
        TextView location = ui.text(ready ? CaptureService.inboxLocation(this) : "No inbox chosen", 14, Typeface.DEFAULT, ui.ink);
        location.setTextIsSelectable(true);
        destination.addView(location);
        destination.addView(ui.text("Saved on this phone. Your existing Obsidian sync handles other devices.", 13, Typeface.DEFAULT, ui.sub), ui.lp(12));
        destination.addView(settingsLink(ready ? "Change inbox file" : "Choose inbox file", v -> chooseInbox()), ui.lp(12));
        if (ready) {
            destination.addView(settingsLink("Check inbox access", v -> checkAccess(inbox)));
            destination.addView(settingsLink("Open in Obsidian", v -> openInObsidian(inbox)));
            destination.addView(settingsLink("Refresh inbox", v -> render()));
        }
        page.addView(destination, ui.lp(24));

        LinearLayout feedback = ui.card("Notifications");
        feedback.addView(ui.text(notifications ? "Save confirmations are enabled" : "Saving works without notifications", 14, Typeface.DEFAULT, ui.ink));
        feedback.addView(settingsLink("Notification settings", v -> requestNotifications()), ui.lp(8));
        if (Build.VERSION.SDK_INT >= 36) {
            feedback.addView(settingsLink("Live updates", v -> startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()))));
            feedback.addView(ui.text("On OnePlus, enable Live Alerts in notification settings to see the brief Added confirmation.", 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        }
        page.addView(feedback, ui.lp(16));

        android.appwidget.AppWidgetManager widgets = android.appwidget.AppWidgetManager.getInstance(this);
        if (widgets.isRequestPinAppWidgetSupported()) {
            LinearLayout home = ui.card("Home screen");
            home.addView(ui.text("One widget with your inbox, the last 14 days, the latest captures, and Write and Speak. Resize it to show more or less.", 13, Typeface.DEFAULT, ui.sub));
            home.addView(settingsLink("Add the Sparkbox widget", v -> widgets.requestPinAppWidget(new android.content.ComponentName(this, InboxWidget.class), null, null)), ui.lp(12));
            page.addView(home, ui.lp(16));
        }

        LinearLayout files = ui.card("Photos and documents");
        files.addView(ui.text("Shared photos, PDFs and documents are copied into one folder of your vault and linked from the inbox. Pick the folder once.", 13, Typeface.DEFAULT, ui.sub));
        files.addView(ui.text(Attach.ready(this) ? Attach.label(this) : "No folder chosen", 14, Typeface.DEFAULT, ui.ink), ui.lp(12));
        files.addView(settingsLink(Attach.ready(this) ? "Change folder" : "Choose folder", v -> chooseAttach()), ui.lp(8));
        page.addView(files, ui.lp(16));

        LinearLayout notes = ui.card("Capture notes");
        notes.addView(ui.text("Each tagged link is also saved as its own note (summary, your Why and Ideas, and the whole page text) in one vault folder, for example Sources/Captures. Sparkbox writes only there, and only changes notes it created. Off until you pick a folder.", 13, Typeface.DEFAULT, ui.sub));
        notes.addView(ui.text(Captures.ready(this) ? Captures.label(this) : "Off", 14, Typeface.DEFAULT, ui.ink), ui.lp(12));
        String capturesProblem = prefs().getString("capturesProblem", "");
        if (Captures.ready(this) && !capturesProblem.isEmpty()) notes.addView(ui.text(capturesProblem, 13, Typeface.DEFAULT, ui.accent), ui.lp(8));
        notes.addView(settingsLink(Captures.ready(this) ? "Change folder" : "Choose Captures folder", v -> chooseCaptures()), ui.lp(8));
        if (Captures.ready(this)) notes.addView(settingsLink("Stop filing notes", v -> { prefs().edit().remove("captures").remove("capturesProblem").apply(); render(); }));
        page.addView(notes, ui.lp(16));

        LinearLayout vault = ui.card("Knowledge base");
        vault.addView(ui.text("Your vault folder. Sparkbox reads only Knowledge and Sources, and never changes them. Daily notes, health and other folders are not read. With a smart tags key, note titles and short digests are sent to OpenRouter so search can find them. Ask also sends the notes and inbox captures it reads, up to 20,000 characters at a time.", 13, Typeface.DEFAULT, ui.sub));
        vault.addView(ui.text(VaultStore.ready(this) ? VaultStore.label(this) : "No folder chosen", 14, Typeface.DEFAULT, ui.ink), ui.lp(12));
        vault.addView(settingsLink(VaultStore.ready(this) ? "Change folder" : "Choose vault folder", v -> startActivity(new Intent(this, VaultActivity.class).putExtra(VaultActivity.PICK, true))), ui.lp(8));
        page.addView(vault, ui.lp(16));
        LinearLayout usage = ui.card("Feature usage");
        usage.addView(ui.text("How often you use each feature, counted on this phone only. Nothing is sent anywhere.", 13, Typeface.DEFAULT, ui.sub));
        for (String line : Usage.lines(this)) usage.addView(ui.text(line, 14, Typeface.DEFAULT, ui.ink), ui.lp(8));
        page.addView(usage, ui.lp(16));

        LinearLayout smart = ui.card("Smart tags (optional)");
        smart.addView(ui.text("Adds a type, tags and a one-line summary to new links with your OpenRouter key. Sent to OpenRouter: link titles and page text (not the part of a link after ?), the text of longer notes, and a short digest of every capture for search. Off until you save a key.", 13, Typeface.DEFAULT, ui.sub));
        boolean keySaved = !Model.key(this).isEmpty();
        EditText key = field("", 1), model = field(prefs().getString("aiModel", Model.DEFAULT), 1), askModel = field(Ask.model(this), 1);
        key.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        key.setHint(keySaved ? "Key saved. Paste a new one to replace it." : "OpenRouter API key");
        model.setInputType(InputType.TYPE_CLASS_TEXT);
        askModel.setInputType(InputType.TYPE_CLASS_TEXT);
        smart.addView(key, ui.lp(12));
        smart.addView(model, ui.lp(8));
        smart.addView(ui.text("Model for Ask. It must support tool calls.", 13, Typeface.DEFAULT, ui.sub), ui.lp(12));
        smart.addView(askModel, ui.lp(8));
        smart.addView(ui.button("Save", true, v -> saveSmart(key.getText().toString().trim(), model.getText().toString().trim(), askModel.getText().toString().trim())), ui.lp(12));
        String problem = prefs().getString("aiProblem", "");
        if (keySaved && !problem.isEmpty()) smart.addView(ui.text(problem, 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (keySaved) {
            int counted = 0, tagged = 0;
            if (lastText != null) for (InboxFile.Entry e : InboxFile.parse(lastText)) if (e.file.isEmpty() && (!e.text || e.body.length() >= 40)) { counted++; if (!e.type.isEmpty()) tagged++; }
            smart.addView(ui.text(tagged + " of " + counted + " links and notes tagged", 13, Typeface.DEFAULT, ui.muted), ui.lp(8));
            boolean describe = prefs().getBoolean("describe", false);
            smart.addView(ui.text("Voice notes are transcribed with this key. Photos and PDFs are only described if you turn this on, and then they are sent to OpenRouter.", 13, Typeface.DEFAULT, ui.sub), ui.lp(12));
            smart.addView(settingsLink("Describe photos and PDFs: " + (describe ? "On" : "Off"), v -> { prefs().edit().putBoolean("describe", !describe).apply(); render(); BackgroundJob.schedule(this); }), ui.lp(4));
            smart.addView(settingsLink("Remove key", v -> { Model.clear(this); render(); }), ui.lp(8));
        }
        page.addView(smart, ui.lp(16));

        page.addView(ui.text("Recovery", 18, Ui.MEDIUM, ui.ink), ui.lp(32));
        page.addView(ui.text("Backups and captures that need checking stay here until you resolve them.", 13, Typeface.DEFAULT, ui.sub), ui.lp(8));
        if (attentionCount(jobs) > 0) attentionCard(jobs);
        int before = page.getChildCount();
        recoveryCards();
        if (before == page.getChildCount() && attentionCount(jobs) == 0)
            page.addView(ui.text("Nothing needs recovery", 14, Typeface.DEFAULT, ui.muted), ui.lp(16));
    }

    private void setupCard(boolean ready, String name, boolean notifications) {
        LinearLayout card = ui.card(ready ? "Save notifications (optional)" : "Set up");
        card.addView(step(ready, ready ? "Inbox file: " + name : "Inbox file not chosen"));
        card.addView(step(notifications, notifications ? "Notifications allowed" : "Saving works without notifications"), ui.lp(12));
        card.addView(ui.button(!ready ? "Choose inbox file" : "Allow notifications", true, v -> { if (!ready) chooseInbox(); else requestNotifications(); }), ui.lp(20));
        if (ready && !notifications) card.addView(quietLink("Dismiss", v -> { prefs().edit().putBoolean("hideNotifications", true).apply(); render(); }), ui.lp(8));
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
            try { job = CaptureService.readJob(file); } catch (Exception e) {
                LinearLayout unreadable = ui.card("Unreadable queued capture");
                unreadable.addView(ui.button("Export recovery file", false, v -> exportFile(file)));
                unreadable.addView(ui.button("Move to retained captures", false, v -> new AlertDialog.Builder(this)
                    .setTitle("Remove unreadable capture from queue?").setMessage("A private recovery copy is retained. Export it before clearing app data.")
                    .setPositiveButton("Retain and remove", (d, w) -> run("Private copy retained", () -> CaptureService.discard(this, file)))
                    .setNegativeButton("Cancel", null).show()), ui.lp(8));
                page.addView(unreadable, ui.lp(16)); continue;
            }
            // A job still being saved is not a problem yet; show failed or stale (>2 min) ones.
            if (!"failed".equals(job.optString("phase")) && !"uncertain".equals(job.optString("phase")) && System.currentTimeMillis() - file.lastModified() < 120000) continue;
            if (card == null) {
                card = ui.card("Needs attention");
                page.addView(card, ui.lp(24));
            }
            String kind = job.optString("kind", "link"), shown = job.optString("label");
            if (shown.isEmpty()) shown = "link".equals(kind) ? job.optString("url") : Capture.clean(job.optString("text"));
            String error = job.optString("error");
            if (error.isEmpty() && "voice".equals(kind)) error = "Waiting to be transcribed. This needs a network connection and your OpenRouter key in Settings.";
            else if (error.isEmpty()) error = "failed".equals(job.optString("phase")) ? "Not saved." : "Queued or interrupted. Retry checks uncertain saves without appending again.";
            card.addView(ui.text(shown, 16, Ui.MEDIUM, ui.ink));
            card.addView(ui.text(error, 14, Typeface.DEFAULT, ui.sub), ui.lp(4));
            String copy = job.optString("text");
            if (copy.isEmpty()) copy = job.optString("entry", shown);
            final String recoveryText = copy;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.addView(ui.button("Retry / check", true, v -> {
                if ("voice".equals(kind)) { try { JSONObject j = CaptureService.readJob(file); j.remove("error"); CaptureService.writeJob(file, j); } catch (Exception ignored) { } BackgroundJob.schedule(this); }
                else CaptureService.start(this, file.getName());
                render(); }));
            row.addView(quietLink("Copy", v -> copy(recoveryText)));
            row.addView(quietLink("Discard", v -> { new AlertDialog.Builder(this).setTitle("Discard this capture?")
                .setMessage("It leaves the queue. A private recovery copy is kept until app data is cleared.")
                .setPositiveButton("Discard", (d, w) -> run("Discarded; private copy kept", () -> CaptureService.discard(this, file)))
                .setNegativeButton("Cancel", null).show(); }));
            card.addView(row, ui.lp(16));
            card.addView(quietLink("Export full capture", v -> exportFile(file)), ui.lp(4));
        }
    }

    private interface Task { void run() throws Exception; }

    private boolean matches(InboxFile.Entry e) {
        if (!tag.isEmpty() && !e.hasTag(tag)) return false;
        if (semanticRaw == null && !e.contains(query)) return false;
        switch (filter) {
            case 1: return !e.text && e.file.isEmpty();
            case 2: return e.text;
            case 3: return !e.file.isEmpty();
            case 4: return !e.why.isEmpty() || !e.ideas.isEmpty();
            default: return true;
        }
    }

    private HorizontalScrollView chipRow(LinearLayout chips) {
        HorizontalScrollView row = new HorizontalScrollView(this);
        row.setHorizontalScrollBarEnabled(false);
        row.addView(chips);
        return row;
    }

    private void addChip(LinearLayout chips, String label, boolean on, Runnable select) {
        TextView chip = ui.text(label, 14, on ? Ui.MEDIUM : Typeface.DEFAULT, on ? ui.ink : ui.sub);
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(ui.dp(48));
        chip.setPadding(ui.dp(12), 0, ui.dp(12), 0);
        chip.setBackground(ui.ripple(ui.round(on ? ui.raised : 0, 0, 8)));
        chip.setSelected(on);
        Ui.buttonRole(chip);
        chip.setOnClickListener(v -> { select.run(); visibleLimit = 50; draw(lastText, lastError); });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.rightMargin = ui.dp(4);
        chips.addView(chip, p);
    }

    private void inboxList(String text, String error) {
        if (text == null && error == null) return; // no inbox chosen yet
        if (error != null) { page.addView(ui.text(error, 14, Typeface.DEFAULT, ui.sub), ui.lp(32)); return; }
        List<InboxFile.Entry> entries = InboxFile.parse(text);
        int notes = 0, annotated = 0, files = 0;
        for (InboxFile.Entry e : entries) { if (e.text) notes++; if (!e.file.isEmpty()) files++; if (!e.why.isEmpty() || !e.ideas.isEmpty()) annotated++; }

        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(ui.text("Inbox", 32, Ui.MEDIUM, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(heading, ui.lp(20));
        page.addView(ui.text(entries.size() + (entries.size() == 1 ? " saved capture" : " saved captures"), 13, Typeface.DEFAULT, ui.muted), ui.lp(8));
        if (entries.isEmpty()) {
            page.addView(ui.text("The inbox is empty", 16, Ui.MEDIUM, ui.ink), ui.lp(16));
            page.addView(ui.text("Share a link or text from any app. It is saved on this phone; your existing Obsidian sync handles other devices.", 14, Typeface.DEFAULT, ui.sub), ui.lp(8));
            return;
        }
        EditText search = new EditText(this);
        search.setSingleLine();
        search.setHint("Search");
        search.setText(query);
        search.setTextColor(ui.ink);
        search.setHintTextColor(ui.muted);
        search.setTextSize(15);
        search.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        search.setBackground(ui.round(0, ui.line, 8));
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, action, event) -> { query = v.getText().toString().trim(); visibleLimit = 50; semanticSearch(); return true; });
        page.addView(search, ui.lp(16));

        String[] names = {"All", "Links", "Notes", "Files", "Annotated"};
        int[] counts = {entries.size(), entries.size() - notes - files, notes, files, annotated};
        LinearLayout chips = new LinearLayout(this);
        for (int i = 0; i < names.length; i++) {
            int which = i;
            addChip(chips, names[i] + "  " + counts[i], filter == i, () -> { filter = which; });
        }
        page.addView(chipRow(chips), ui.lp(8));

        boolean tagExists = false;
        for (InboxFile.Entry e : entries) if (e.hasTag(tag)) { tagExists = true; break; }
        if (!tagExists) tag = ""; // its last entry is gone; do not strand the filter
        if (!tag.isEmpty()) {
            LinearLayout active = new LinearLayout(this);
            addChip(active, "#" + tag + "  ✕", true, () -> { tag = ""; });
            page.addView(chipRow(active), ui.lp(0));
        }

        List<InboxFile.Entry> ordered = new java.util.ArrayList<>();
        if (semanticRaw != null) {
            for (String raw : semanticRaw) for (InboxFile.Entry e : entries) if (e.raw.equals(raw)) { ordered.add(e); break; }
            page.addView(ui.text("Closest matches", 12, Typeface.DEFAULT, ui.muted), ui.lp(12));
        } else for (int i = entries.size() - 1; i >= 0; i--) ordered.add(entries.get(i));
        int shown = 0, total = 0;
        for (InboxFile.Entry entry : ordered) {
            if (!matches(entry)) continue;
            total++;
            if (shown >= visibleLimit) continue;
            shown++;
            page.addView(row(entry), ui.lp(shown == 1 ? 8 : 0));
        }
        if (total == 0) page.addView(ui.text("Nothing matches this filter", 14, Typeface.DEFAULT, ui.sub), ui.lp(16));
        if (total > visibleLimit) page.addView(ui.button("Show older entries (" + (total - visibleLimit) + ")", false, v -> { visibleLimit += 50; draw(lastText, lastError); }), ui.lp(16));
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
        body.setPadding(ui.dp(12), ui.dp(16), 0, ui.dp(16));
        TextView title = ui.text(entry.shownTitle(), 17, Ui.MEDIUM, ui.ink);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        body.addView(title);
        String source = !entry.file.isEmpty() ? "File" : entry.text || entry.url.isEmpty() ? "Note" : Ui.host(entry.url);
        body.addView(ui.text(source + " · " + Ui.when(entry) + (mine ? " · Annotated" : ""), 12, Typeface.DEFAULT, ui.muted), ui.lp(4));
        if (!entry.tldr.isEmpty()) {
            TextView tldr = ui.text(entry.tldr, 14, Typeface.DEFAULT, ui.sub);
            tldr.setMaxLines(2);
            tldr.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(tldr, ui.lp(8));
        }
        if (!entry.why.isEmpty()) {
            TextView why = ui.text(entry.why.split("\n")[0], 14, Typeface.DEFAULT, ui.sub);
            why.setMaxLines(2);
            why.setEllipsize(TextUtils.TruncateAt.END);
            body.addView(why, ui.lp(8));
        }
        row.addView(body, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams thumb = new LinearLayout.LayoutParams(ui.dp(64), ui.dp(64));
        thumb.topMargin = ui.dp(16); thumb.leftMargin = ui.dp(12);
        row.addView(Thumb.view(this, ui, entry, 64, 64), thumb);
        row.setBackground(ui.ripple(null));
        row.setOnClickListener(v -> showEntry(entry, lastText, prefs().getString("inbox", ""), entry.why, entry.ideas));
        Ui.buttonRole(row);
        row.setOnLongClickListener(v -> { copy(entry.text ? entry.body : entry.file.isEmpty() ? entry.url : entry.file); return true; });
        box.addView(row);
        View rule = new View(this);
        rule.setBackgroundColor(ui.line);
        box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return box;
    }

    private void showEntry(InboxFile.Entry entry, String snapshot, String destination, String whyValue, String ideasValue) {
        if (editing) return;
        editing = true; draftEntry = entry; draftSnapshot = snapshot; draftDestination = destination;
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(ui.dp(24), ui.dp(12), ui.dp(24), ui.dp(8));
        View handle = new View(this);
        handle.setBackground(ui.round((ui.ink & 0xFFFFFF) | 0x33000000, 0, 2));
        LinearLayout.LayoutParams grip = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        grip.gravity = Gravity.CENTER_HORIZONTAL;
        form.addView(handle, grip);
        if (!entry.text) {
            LinearLayout.LayoutParams banner = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(168));
            banner.topMargin = ui.dp(16);
            form.addView(Thumb.view(this, ui, entry, 0, 168), banner);
        }
        String kind = entry.type.isEmpty() ? "" : Character.toUpperCase(entry.type.charAt(0)) + entry.type.substring(1) + " · ";
        String source = !entry.file.isEmpty() ? "File" : entry.text || entry.url.isEmpty() ? "Note" : Ui.host(entry.url);
        form.addView(ui.text(kind + source + " · " + Ui.when(entry), 12, Typeface.DEFAULT, ui.muted), ui.lp(16));
        if (entry.text) {
            TextView note = ui.text(entry.body, 18, Typeface.DEFAULT, ui.ink);
            note.setTextIsSelectable(true);
            form.addView(note, ui.lp(8));
        } else {
            TextView heading = ui.text(entry.shownTitle(), 22, Ui.MEDIUM, ui.ink);
            heading.setTextIsSelectable(true);
            heading.setMaxLines(4);
            heading.setEllipsize(TextUtils.TruncateAt.END);
            form.addView(heading, ui.lp(8));
        }
        if (!entry.tldr.isEmpty()) form.addView(ui.text(entry.tldr, 16, Typeface.DEFAULT, ui.sub), ui.lp(12));
        if (!entry.tags.isEmpty()) form.addView(ui.text("#" + entry.tags.replace(", ", "  #"), 12, Typeface.DEFAULT, ui.muted), ui.lp(12));
        LinearLayout actions = new LinearLayout(this);
        if (!entry.url.isEmpty() || !entry.file.isEmpty()) actions.addView(ui.button(entry.file.isEmpty() ? "Open source" : "Open file", true,
            v -> { if (entry.file.isEmpty()) open(entry.url); else openFile(entry.file); }), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (entry.file.isEmpty()) {
            LinearLayout.LayoutParams copyAt = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (actions.getChildCount() > 0) copyAt.leftMargin = ui.dp(8);
            actions.addView(ui.button(entry.text ? "Copy note" : "Copy link", false, v -> copy(entry.text ? entry.body : entry.url)), copyAt);
        }
        form.addView(actions, ui.lp(20));
        if (!entry.text && !entry.url.isEmpty() && Content.has(this, entry.url)) {
            LinearLayout saved = new LinearLayout(this);
            saved.setOrientation(LinearLayout.VERTICAL);
            TextView toggle = quietLink("Read saved page text", null);
            toggle.setOnClickListener(v -> {
                if (saved.getChildCount() > 0) { saved.removeAllViews(); toggle.setText("Read saved page text"); return; }
                String text = Content.load(this, entry.url, 30000); // the whole page stays saved; a screenful is plenty to read here
                TextView body = ui.text(text.isEmpty() ? "Nothing saved." : text, 14, Typeface.DEFAULT, ui.sub);
                body.setTextIsSelectable(true);
                body.setLineSpacing(0, 1.3f);
                saved.addView(body);
                toggle.setText("Hide saved page text");
            });
            form.addView(toggle);
            form.addView(saved, ui.lp(12));
        }
        if (!entry.text && !entry.sharedText.isEmpty() && !entry.sharedText.equals(entry.url)) {
            TextView shared = ui.text(entry.sharedText, 14, Typeface.DEFAULT, ui.muted);
            shared.setTextIsSelectable(true);
            form.addView(shared, ui.lp(16));
        }
        View rule = new View(this);
        rule.setBackgroundColor(ui.line);
        LinearLayout.LayoutParams ruleAt = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        ruleAt.topMargin = ui.dp(24);
        form.addView(rule, ruleAt);
        TextView whyLabel = ui.label("Why I saved this");
        form.addView(whyLabel, ui.lp(24));
        EditText why = field(whyValue, 2); draftWhy = why;
        why.setId(View.generateViewId()); whyLabel.setLabelFor(why.getId());
        form.addView(intentChips(why), ui.lp(8));
        form.addView(why, ui.lp(8));
        TextView ideasLabel = ui.label("My ideas"); form.addView(ideasLabel, ui.lp(16));
        EditText ideas = field(ideasValue, 4); draftIdeas = ideas;
        ideas.setId(View.generateViewId()); ideasLabel.setLabelFor(ideas.getId());
        form.addView(ideas, ui.lp(8));
        form.addView(quietLink("Copy draft annotations", v -> copy("Why: " + why.getText() + "\nIdeas: " + ideas.getText())), ui.lp(8));
        form.addView(ui.text("Captured " + entry.time, 12, Typeface.DEFAULT, ui.muted), ui.lp(8));
        relatedSection(form, entry, snapshot, why, ideas);
        ScrollView content = new ScrollView(this); content.addView(form);
        editor = new AlertDialog.Builder(this).setView(content)
            .setPositiveButton("Save", null).setNegativeButton("Close", null).setNeutralButton("Delete", null).create();
        editor.setCanceledOnTouchOutside(false);
        editor.setOnCancelListener(d -> keepDraft());
        editor.setOnDismissListener(d -> { editing = false; editor = null; if (!isDestroyed()) render(); });
        editor.setOnShowListener(d -> {
            editor.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                if (why.getText().toString().equals(entry.why) && ideas.getText().toString().equals(entry.ideas)) { clearDraft(); editor.dismiss(); }
                else new AlertDialog.Builder(this).setTitle("Keep this draft?").setMessage("Keep it for next time, or discard your unsaved changes.")
                    .setPositiveButton("Keep draft", (dialog, w) -> { keepDraft(); editor.dismiss(); })
                    .setNegativeButton("Discard changes", (dialog, w) -> { clearDraft(); editor.dismiss(); }).show();
            });
            editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String whyText = why.getText().toString(), ideasText = ideas.getText().toString();
                keepDraft(); setEditorEnabled(false);
                new Thread(() -> {
                    try {
                        editRebased(destination, snapshot, entry, (t, e) -> InboxFile.setNotes(t, e, whyText, ideasText));
                        Usage.hit(this, "annotate");
                        BackgroundJob.schedule(this); // a new Why or Idea re-summarises the capture
                        runOnUiThread(() -> { clearDraft(); if (isDestroyed()) return; if (editor != null) editor.dismiss(); toast("Saved to inbox on this phone"); render(); });
                    } catch (Exception failure) {
                        runOnUiThread(() -> { setEditorEnabled(true); if (!isDestroyed()) new AlertDialog.Builder(this)
                            .setTitle("Draft kept; edit not confirmed").setMessage(failure.getMessage())
                            .setPositiveButton("OK", null).show(); });
                    }
                }).start();
            });
            editor.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> new AlertDialog.Builder(this).setTitle("Delete this entry?")
                .setMessage("It is removed from the inbox file. Unsaved annotation changes are not applied.")
                .setPositiveButton("Delete", (dialog, w) -> {
                    keepDraft(); setEditorEnabled(false);
                    new Thread(() -> {
                        try {
                            editRebased(destination, snapshot, entry, InboxFile::delete);
                            Usage.hit(this, "delete");
                            runOnUiThread(() -> { clearDraft(); if (isDestroyed()) return; if (editor != null) editor.dismiss(); toast("Deleted"); render(); });
                        } catch (Exception failure) { runOnUiThread(() -> { setEditorEnabled(true); if (!isDestroyed()) new AlertDialog.Builder(this)
                            .setTitle("Delete not confirmed").setMessage(failure.getMessage()).setPositiveButton("OK", null).show(); }); }
                    }).start();
                }).setNegativeButton("Cancel", null).show());
        });
        editor.show();
        sheet(editor, true);
        keepDraft();
    }
    /**
     * Background tagging rewrites the entry (summary, tags, title) while its editor is open, so the editor's snapshot goes stale and the
     * verified edit refuses. Retry once on the fresh file, only when the same capture is still there with the same Why and Ideas you opened it with.
     */
    private void editRebased(String destination, String snapshot, InboxFile.Entry entry, java.util.function.BiFunction<String, InboxFile.Entry, String> change) throws Exception {
        try { CaptureService.edit(this, destination, snapshot, t -> change.apply(t, entry)); }
        catch (java.io.IOException stale) {
            if (!String.valueOf(stale.getMessage()).startsWith("Inbox changed. Refresh")) throw stale;
            String fresh = CaptureService.readText(this);
            InboxFile.Entry same = null;
            for (InboxFile.Entry e : InboxFile.parse(fresh)) if (e.time.equals(entry.time) && e.url.equals(entry.url) && (!e.url.isEmpty() || e.body.equals(entry.body))) {
                if (same != null) throw stale; // two look alike: do not guess
                same = e;
            }
            if (same == null || !same.why.equals(entry.why) || !same.ideas.equals(entry.ideas)) throw stale; // gone, or you changed it elsewhere
            InboxFile.Entry target = same;
            CaptureService.edit(this, destination, fresh, t -> change.apply(t, target));
        }
    }

    private static final String[] INTENTS = {"To read", "To try", "To cite", "Idea"};

    /** One tap states why you saved it: the chip becomes the start of the Why text (tap again to clear). Saved with the normal Save. */
    private View intentChips(EditText why) {
        LinearLayout row = new LinearLayout(this);
        TextView[] chips = new TextView[INTENTS.length];
        Runnable paint = () -> {
            String text = why.getText().toString();
            for (int i = 0; i < chips.length; i++) {
                boolean on = text.equals(INTENTS[i]) || text.startsWith(INTENTS[i] + " · ");
                chips[i].setTextColor(on ? ui.bg : ui.sub);
                chips[i].setBackground(ui.ripple(ui.round(on ? ui.ink : 0, on ? 0 : ui.line, 16)));
                chips[i].setSelected(on);
            }
        };
        for (int i = 0; i < chips.length; i++) {
            String intent = INTENTS[i];
            TextView chip = ui.text(intent, 14, Ui.MEDIUM, ui.sub);
            chip.setGravity(Gravity.CENTER);
            chip.setMinHeight(ui.dp(36));
            chip.setPadding(ui.dp(16), 0, ui.dp(16), 0);
            Ui.buttonRole(chip);
            chip.setOnClickListener(v -> {
                String text = why.getText().toString();
                boolean same = false;
                for (String other : INTENTS) {
                    if (text.equals(other)) { same = other.equals(intent); text = ""; break; }
                    if (text.startsWith(other + " · ")) { same = other.equals(intent); text = text.substring(other.length() + 3); break; }
                }
                if (!same) text = intent + (text.isEmpty() ? "" : " · " + text);
                why.setText(text);
                why.setSelection(text.length());
            });
            LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            at.rightMargin = ui.dp(8);
            row.addView(chip, at);
            chips[i] = chip;
        }
        why.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable e) { paint.run(); }
        });
        paint.run();
        return chipRow(row);
    }

    /** Bottom sheet look for a dialog: rounded top, surface fill, quiet buttons with one primary. */
    private void sheet(AlertDialog d, boolean tall) {
        android.view.Window w = d.getWindow();
        GradientDrawable shape = ui.round(ui.surface, ui.line, 0);
        shape.setCornerRadii(new float[]{ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24), 0, 0, 0, 0});
        w.setBackgroundDrawable(shape);
        w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, tall ? (int) (getResources().getDisplayMetrics().heightPixels * 0.92f) : ViewGroup.LayoutParams.WRAP_CONTENT);
        w.setGravity(Gravity.BOTTOM);
        w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        int[] ids = {AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL};
        for (int id : ids) {
            Button b = d.getButton(id);
            if (b == null) continue;
            b.setAllCaps(false);
            b.setTextSize(14);
            b.setTypeface(Ui.MEDIUM);
            b.setMinHeight(ui.dp(48));
            b.setTextColor(id == AlertDialog.BUTTON_POSITIVE ? ui.bg : id == AlertDialog.BUTTON_NEGATIVE ? ui.ink : ui.muted);
            if (id == AlertDialog.BUTTON_POSITIVE) { b.setBackground(ui.ripple(ui.round(ui.ink, 0, 10))); b.setMinWidth(ui.dp(96)); }
        }
        Button any = d.getButton(AlertDialog.BUTTON_POSITIVE);
        if (any != null && any.getParent() instanceof View) ((View) any.getParent()).setPadding(ui.dp(16), ui.dp(8), ui.dp(24), ui.dp(16));
    }

    private void setEditorEnabled(boolean enabled) {
        saving = !enabled;
        if (editor == null) return;
        draftWhy.setEnabled(enabled); draftIdeas.setEnabled(enabled);
        for (int button : new int[]{AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL})
            editor.getButton(button).setEnabled(enabled);
        editor.setCancelable(enabled);
    }
    private void keepDraft() {
        if (saving || draftEntry == null || draftWhy == null) return;
        if (draftWhy.getText().toString().equals(draftEntry.why) && draftIdeas.getText().toString().equals(draftEntry.ideas)) { dropDraftFile(); return; } // nothing typed: a restored empty editor helps no one
        synchronized (DRAFT_LOCK) {
        AtomicFile draft = new AtomicFile(new File(getFilesDir(), "draft.json"));
        java.io.FileOutputStream out = null;
        try {
            byte[] bytes = new JSONObject().put("id", draftId).put("snapshot", draftSnapshot).put("destination", draftDestination)
                .put("start", draftEntry.start).put("why", draftWhy.getText().toString()).put("ideas", draftIdeas.getText().toString()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out = draft.startWrite(); out.write(bytes); draft.finishWrite(out);
        } catch (Exception e) { if (out != null) draft.failWrite(out); toast("Could not keep draft. Copy your text before leaving."); }
        }
    }

    private void clearDraft() {
        saving = false;
        dropDraftFile();
        draftEntry = null; draftWhy = null; draftIdeas = null;
    }
    /** Removes this editor's saved draft, if it is the one on disk; the open editor keeps its fields. */
    private void dropDraftFile() {
        synchronized (DRAFT_LOCK) {
            AtomicFile file = new AtomicFile(new File(getFilesDir(), "draft.json"));
            try (InputStream input = file.openRead()) {
                JSONObject saved = new JSONObject(new String(CaptureService.readAll(input), java.nio.charset.StandardCharsets.UTF_8));
                if (draftId.equals(saved.optString("id"))) file.delete();
            } catch (Exception ignored) { } // leave unreadable recovery intact
        }
    }
    @Override protected void onPause() {
        if (listening) { unregisterReceiver(inboxUpdates); listening = false; }
        if (editing) keepDraft(); super.onPause();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("settings", settings);
        state.putInt("inboxScroll", settings ? inboxScroll : scroll.getScrollY());
        state.putInt("settingsScroll", settings ? scroll.getScrollY() : settingsScroll);
        state.putInt("scroll", scroll.getScrollY()); state.putInt("filter", filter); state.putString("query", query); state.putString("tag", tag); state.putInt("visibleLimit", visibleLimit);
        if (editing) keepDraft();
    }

    private void export(String text, String name) { exportBytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8), name); }
    private void exportBytes(byte[] bytes, String name) {
        AtomicFile pending = new AtomicFile(new File(getFilesDir(), "export.txt"));
        java.io.FileOutputStream out = null;
        try { out = pending.startWrite(); out.write(bytes); pending.finishWrite(out); }
        catch (Exception e) { if (out != null) pending.failWrite(out); toast("Cannot prepare export"); return; }
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType(name.endsWith(".json") ? "application/json" : "text/markdown").putExtra(Intent.EXTRA_TITLE, name), 3);
    }
    private void exportFile(File file) {
        try (InputStream input = new AtomicFile(file).openRead()) { exportBytes(CaptureService.readAll(input), file.getName().endsWith(".bak") ? "Sparkbox-earlier-inbox.md" : "Sparkbox-recovery.json"); }
        catch (Exception failure) { toast("Cannot read recovery file. It remains in private storage."); }
    }
    private String recoveryValue(JSONObject recovery, String key) {
        return "base64".equals(recovery.optString("encoding"))
            ? new String(android.util.Base64.decode(recovery.optString(key), android.util.Base64.DEFAULT), java.nio.charset.StandardCharsets.UTF_8)
            : recovery.optString(key);
    }
    private void recoveryCards() {
        for (File file : CaptureService.recoveries(this)) {
            LinearLayout card = ui.card("Edit recovery");
            try {
                JSONObject recovery = CaptureService.readJob(file);
                card.addView(ui.text(recovery.optString("name") + " · " + recovery.optString("time") + "\nAn edit was not confirmed. Export both copies, inspect the inbox, then resolve this recovery.", 14, Typeface.DEFAULT, ui.ink));
                card.addView(ui.button("Export original", false, v -> export(recoveryValue(recovery, "before"), "Sparkbox-original.md")), ui.lp(12));
                card.addView(ui.button("Export attempted edit", false, v -> export(recoveryValue(recovery, "after"), "Sparkbox-attempted.md")), ui.lp(8));
            } catch (Exception failure) {
                card.addView(ui.text("Recovery could not be read. Export the file before resolving it.", 14, Typeface.DEFAULT, ui.ink));
                card.addView(ui.button("Export recovery file", false, v -> exportFile(file)), ui.lp(12));
            }
            card.addView(quietLink("I checked the inbox; resolve recovery", v -> new AlertDialog.Builder(this)
                .setTitle("Resolve this recovery?").setMessage("Only do this after inspecting the inbox and exporting any copies you need. Private recovery copies will be removed.")
                .setPositiveButton("Resolve", (d, w) -> { CaptureService.dismissRecovery(file); render(); })
                .setNegativeButton("Cancel", null).show()), ui.lp(12));
            page.addView(card, ui.lp(16));
        }
        File legacy = new File(getFilesDir(), "inbox.bak");
        if (legacy.exists()) {
            LinearLayout card = ui.card("Earlier edit backup");
            card.addView(ui.button("Export earlier inbox backup", false, v -> exportFile(legacy)));
            page.addView(card, ui.lp(16));
        }
        File[] discarded = new File(getFilesDir(), "discarded").listFiles((dir, name) -> name.endsWith(".json"));
        if (discarded != null && discarded.length > 0) {
            LinearLayout card = ui.card("Retained captures (discarded or duplicate)");
            for (File file : discarded) card.addView(ui.button("Export retained capture", false, v -> exportFile(file)), ui.lp(8));
            page.addView(card, ui.lp(16));
        }
    }
    private void saveSmart(String key, String model, String askModel) {
        try {
            if (!key.isEmpty()) Model.saveKey(this, key);
            prefs().edit().putString("aiModel", model.isEmpty() ? Model.DEFAULT : model).putString("askModel", askModel.isEmpty() ? Ask.DEFAULT : askModel).apply();
            toast("Saved");
            render();
            BackgroundJob.schedule(this);
        } catch (Exception e) { toast("Could not save the key"); }
    }

    /** Word match shows at once; closest-meaning results replace it when the query embedding returns. */
    private void semanticSearch() {
        semanticRaw = null;
        draw(lastText, lastError);
        String key = Model.key(this), q = query, snapshot = lastText;
        if (q.isEmpty() || key.isEmpty() || snapshot == null) return;
        new Thread(() -> {
            List<String> hits = new java.util.ArrayList<>();
            try {
                Index index = Index.load(new File(getFilesDir(), "index.bin"));
                float[] vector = Model.embed(key, java.util.Collections.singletonList(q)).get(0);
                for (InboxFile.Entry e : index.rank(vector, InboxFile.parse(snapshot), 15, Index.MIN_QUERY, 0)) hits.add(e.raw);
            } catch (Exception offline) { hits.clear(); } // no key, no network, no index: the word match stays
            runOnUiThread(() -> { if (!isDestroyed() && q.equals(query) && !hits.isEmpty()) { semanticRaw = hits; draw(lastText, lastError); } });
        }).start();
    }

    /** Closest captures and vault notes. Embeds this entry on demand when its vector is missing (new, or edited since the last index run). */
    private void relatedSection(LinearLayout form, InboxFile.Entry entry, String snapshot, EditText why, EditText ideas) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        form.addView(box, ui.lp(16));
        new Thread(() -> {
            List<InboxFile.Entry> near = new ArrayList<>();
            List<Vault.Note> notes = new ArrayList<>();
            try {
                File file = new File(getFilesDir(), "index.bin");
                Index index = Index.load(file);
                String text = Index.text(entry);
                float[] v = index.vector(text);
                String key = Model.key(this);
                if (v == null && !key.isEmpty()) { // ponytail: one call per open, only while the vector is missing
                    index.put(text, Model.embed(key, java.util.Collections.singletonList(text)).get(0));
                    index.save(file); // new vector only; Tagger.embed prunes stale ones
                    v = index.vector(text);
                }
                if (v != null) {
                    near = index.related(entry, InboxFile.parse(snapshot), 3);
                    VaultStore.Snap vault = VaultStore.ready(this) ? VaultStore.cached(this) : null;
                    if (vault != null) { notes = VaultStore.nearest(this, vault, v, 6, Index.MIN_RELATED, null); notes.removeIf(n -> n.path.contains("Sources/Captures/")); if (notes.size() > 3) notes = new ArrayList<>(notes.subList(0, 3)); } // the filed copy of this very capture is not related, it is the same
                }
            } catch (Exception offline) { } // stored results still show; nothing to say without network
            List<InboxFile.Entry> inbox = near;
            List<Vault.Note> vaultNotes = notes;
            runOnUiThread(() -> {
                if (isDestroyed() || editor == null || inbox.isEmpty() && vaultNotes.isEmpty()) return;
                box.addView(ui.label("Related"));
                for (InboxFile.Entry other : inbox) {
                    TextView row = relatedRow(other.shownTitle(), v -> {
                        if (!why.getText().toString().equals(entry.why) || !ideas.getText().toString().equals(entry.ideas)) { toast("Save or close this entry first"); return; }
                        Usage.hit(this, "related"); clearDraft(); editor.dismiss(); showEntry(other, snapshot, prefs().getString("inbox", ""), other.why, other.ideas);
                    });
                    box.addView(row);
                }
                for (Vault.Note n : vaultNotes) {
                    box.addView(relatedRow(n.title + "  ·  Knowledge", v -> {
                        if (!why.getText().toString().equals(entry.why) || !ideas.getText().toString().equals(entry.ideas)) { toast("Save or close this entry first"); return; }
                        Usage.hit(this, "related"); startActivity(new Intent(this, VaultActivity.class).putExtra(VaultActivity.OPEN, n.path));
                    }));
                }
            });
        }).start();
    }

    private TextView relatedRow(String label, View.OnClickListener click) {
        TextView row = quietLink(label, click);
        row.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        row.setTextSize(14);
        row.setMaxLines(2);
        row.setEllipsize(TextUtils.TruncateAt.END);
        return row;
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
    private void chooseAttach() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Obsidian"));
        startActivityForResult(pick, 4);
    }
    private void chooseCaptures() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Obsidian"));
        startActivityForResult(pick, 6);
    }
    private void openFile(String name) {
        Uri uri = Attach.find(this, name);
        if (uri == null) { toast("That file is not in the attachments folder"); return; }
        try { startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(uri, Attach.mime(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)); }
        catch (Exception noViewer) { toast("No app can open this file"); }
    }
    private void chooseInbox() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Documents/Obsidian"));
        startActivityForResult(pick, 1);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 5 && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (picked.isEmpty()) return;
            Intent send = new Intent(this, ShareActivity.class).setAction(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, picked).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            android.content.ClipData clip = android.content.ClipData.newRawUri("files", picked.get(0));
            for (int i = 1; i < picked.size(); i++) clip.addItem(new android.content.ClipData.Item(picked.get(i)));
            send.setClipData(clip);
            startActivity(send);
            return;
        }
        if (request == 3 && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri target = data.getData();
                if (target.toString().equals(prefs().getString("inbox", ""))) throw new java.io.IOException("Export must use a new file, not the inbox.");
                byte[] bytes;
                try (InputStream input = new AtomicFile(new File(getFilesDir(), "export.txt")).openRead()) { bytes = CaptureService.readAll(input); }
                try (java.io.OutputStream out = getContentResolver().openOutputStream(target, "wt")) {
                    if (out == null) throw new java.io.IOException("Export unavailable"); out.write(bytes); out.flush();
                }
                try (InputStream input = getContentResolver().openInputStream(target)) {
                    if (!java.util.Arrays.equals(bytes, CaptureService.readAll(input))) throw new java.io.IOException("Export read-back differs");
                }
                toast("Recovery copy exported and verified");
            } catch (Exception e) { toast("Export failed; private recovery copy is kept"); }
            return;
        }
        if (request == 6 && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                prefs().edit().putString("captures", tree.toString()).remove("capturesProblem").commit();
                BackgroundJob.schedule(this);
                render();
            } catch (Exception e) { toast("Could not get write access to that folder."); }
            return;
        }
        if (request == 4 && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree, data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                prefs().edit().putString("attach", tree.toString()).commit();
                if (CaptureService.pending(this).length > 0) CaptureService.start(this, null);
                render();
            } catch (Exception e) { toast("Could not get write access to that folder."); }
            return;
        }
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
        run("Inbox is reachable and writable.", () -> {
            Uri uri = Uri.parse(inbox);
            try (ParcelFileDescriptor d = getContentResolver().openFileDescriptor(uri, "wa")) {
                if (d == null) throw new java.io.IOException("Cannot reach the inbox. Choose the file again.");
            }
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException("Cannot read the inbox. Choose the file again."); in.read();
            }
        });
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
        try {
            startActivity(getPackageManager().getLaunchIntentForPackage("md.obsidian"));
            toast("Opened Obsidian. Automatic inbox navigation is unavailable for this file location.");
        }
        catch (Exception e) { toast("Obsidian is not installed."); }
    }
    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("No app can open this link."); }
    }
    private void copy(String value) {
        ClipData clip = ClipData.newPlainText("Sparkbox", value);
        if (Build.VERSION.SDK_INT >= 33) { android.os.PersistableBundle flag = new android.os.PersistableBundle(); flag.putBoolean("android.content.extra.IS_SENSITIVE", true); clip.getDescription().setExtras(flag); } // keeps notes out of the keyboard's clipboard preview
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        toast("Copied");
    }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }

}
