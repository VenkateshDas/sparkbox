package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/**
 * The app shell: three tabs (Feed, Spark, Ask; search lives at the top of the Feed) under a bottom bar, and a back stack of detail screens on top of the current tab.
 * Each screen is a Screen; tab screens stay alive while the activity does, so scroll position and filters survive switching tabs.
 */
public class MainActivity extends androidx.activity.ComponentActivity {
    static final String TAB = "tab";
    private static final String[] NAMES = {"Feed", "Spark", "Search", "Scout"};
    /** Search has no tab of its own any more: slot 2 stays so the other tab numbers (widgets and notifications use them) do not move. */
    private static final int[] SHOWN = {0, 1, 3};
    private static final int[] ICONS = {R.drawable.ic_nav_feed, R.drawable.ic_nav_spark, 0, R.drawable.ic_nav_ask};

    private Ui ui;
    private FrameLayout content;
    private LinearLayout nav;
    private final Screen[] tabs = new Screen[4];
    private final View[] built = new View[4];
    private final List<Screen> stack = new ArrayList<>();
    private final List<View> stackViews = new ArrayList<>();
    private int tab;
    private boolean dark;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false);
        tab = state != null ? state.getInt(TAB, 0) : Math.max(0, Math.min(3, getIntent().getIntExtra(TAB, 0)));
        for (int i = 0; i < tabs.length; i++) { tabs[i] = screen(i); tabs[i].host = this; }
        draw();
        // Back goes through the androidx dispatcher (predictive back on Android 13+, and Compose screens can add their own callbacks above this one)
        getOnBackPressedDispatcher().addCallback(this, shellBack);
        CardGen.resume(this);
        Reminder.schedule(this);
        BackgroundJob.schedule(this); // a force-stop or crash drops scheduled jobs: opening the app picks up unfinished captures
        if (state == null) handleIntent(getIntent());
    }

    /** The screen behind each tab. Replaced by the real ones as they are built. */
    private Screen screen(int index) { return index == 0 ? new FeedScreen() : index == 3 ? new AskScreen() : index == 1 ? new SparkScreen() : new Placeholder(NAMES[index]); }

    static final int PICK_FILES = 5, PICK_ATTACH = 4;

    /** Photos and files picked from the Feed capture button go through the same share path as the share sheet. */
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_ATTACH && result == RESULT_OK && data != null && data.getData() != null) {
            try {
                android.net.Uri tree = data.getData();
                getContentResolver().takePersistableUriPermission(tree, data.getFlags() & (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION | android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION));
                getSharedPreferences("capture", 0).edit().putString("attach", tree.toString()).commit();
                if (CaptureService.pending(this).length > 0) CaptureService.start(this, null);
            } catch (Exception e) { android.widget.Toast.makeText(this, "Could not get write access to that folder.", android.widget.Toast.LENGTH_SHORT).show(); }
            return;
        }
        if (request != PICK_FILES || result != RESULT_OK || data == null) return;
        ArrayList<android.net.Uri> picked = new ArrayList<>();
        if (data.getClipData() != null) for (int i = 0; i < data.getClipData().getItemCount(); i++) picked.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) picked.add(data.getData());
        if (picked.isEmpty()) return;
        android.content.Intent send = new android.content.Intent(this, ShareActivity.class).setAction(android.content.Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, picked).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        android.content.ClipData clip = android.content.ClipData.newRawUri("files", picked.get(0));
        for (int i = 1; i < picked.size(); i++) clip.addItem(new android.content.ClipData.Item(picked.get(i)));
        send.setClipData(clip);
        startActivity(send);
    }

    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putInt(TAB, tab); }

    @Override protected void onResume() {
        super.onResume();
        InboxWidget.refresh(this); // the widget shows the inbox as it is now
        new Thread(() -> VaultWrite.healInbox(getApplicationContext())).start(); // sets the inbox from the vault folder, or repairs it after a move
        if (Ui.dark(this) != dark) { draw(); return; } // the Theme setting changed while another screen was open
        current().onShow();
        zapper.removeCallbacks(zap);
        zapper.postDelayed(zap, 3000);
    }

    @Override protected void onPause() { super.onPause(); current().onHide(); zapper.removeCallbacks(zap); }

    private Screen current() { return stack.isEmpty() ? tabs[tab] : stack.get(stack.size() - 1); }

    /** Builds the whole shell. Runs at start and when the theme changes. */
    private void draw() {
        ui = new Ui(this);
        dark = ui.dark;
        ui.bars(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(ui.bg);
        content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        View line = new View(this);
        line.setBackgroundColor(ui.line);
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        final View navLine = line;
        nav = new LinearLayout(this);
        nav.setBackgroundColor(ui.bg);
        fillNav();
        root.addView(nav);
        setContentView(root);
        // System bars pad the shell. While the keyboard is open the tab bar steps aside, so typing never floats it above the keys.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            boolean keyboard = false;
            int left, top, right, bottom;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
                keyboard = insets.isVisible(android.view.WindowInsets.Type.ime());
                android.graphics.Insets ime = insets.getInsets(android.view.WindowInsets.Type.ime());
                left = bars.left; top = bars.top; right = bars.right; bottom = keyboard ? ime.bottom : bars.bottom;
            } else { left = insets.getSystemWindowInsetLeft(); top = insets.getSystemWindowInsetTop(); right = insets.getSystemWindowInsetRight(); bottom = insets.getSystemWindowInsetBottom(); }
            v.setPadding(left, top, right, bottom);
            nav.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            navLine.setVisibility(keyboard ? View.GONE : View.VISIBLE);
            return insets;
        });
        root.requestApplyInsets();
        for (int i = 0; i < built.length; i++) built[i] = null;
        List<Screen> keep = new ArrayList<>(stack);
        stack.clear();
        stackViews.clear();
        showTab(); // the tab first, so back from the re-opened screens has it to return to
        for (Screen s : keep) push(s);
    }

    // ---- the Spark icon zaps now and then when cards are due, so it asks for attention without a card in the Feed ----

    private static final long ZAP_EVERY = 9000;
    private android.widget.ImageView sparkIcon;
    private final android.os.Handler zapper = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable zap = new Runnable() {
        @Override public void run() {
            zapper.postDelayed(this, ZAP_EVERY);
            if (sparkIcon == null || tab == 1 || !stack.isEmpty() || SparkScreen.plan(ui).queue.isEmpty()) return;
            final android.widget.ImageView v = sparkIcon;
            v.setImageTintList(ColorStateList.valueOf(ui.accent));
            v.animate().scaleX(1.35f).scaleY(1.35f).setDuration(140).withEndAction(() -> {
                android.animation.ObjectAnimator wiggle = android.animation.ObjectAnimator.ofFloat(v, View.ROTATION, 0f, -16f, 14f, -10f, 7f, 0f);
                wiggle.setDuration(520);
                wiggle.addListener(new android.animation.AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(android.animation.Animator a) { v.animate().scaleX(1f).scaleY(1f).setDuration(160).withEndAction(() -> v.setImageTintList(ColorStateList.valueOf(tab == 1 ? ui.accent : ui.sub))).start(); }
                });
                wiggle.start();
            }).start();
        }
    };

    private void fillNav() {
        nav.removeAllViews();
        for (int i : SHOWN) nav.addView(navItem(i), new LinearLayout.LayoutParams(0, ui.dp(64), 1));
    }

    /** One bottom-bar item: a 24 dp icon over a 12 sp label, centred together in a 64 dp cell. */
    private View navItem(int i) {
        boolean on = i == tab;
        int color = on ? ui.accent : ui.sub;
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(ICONS[i]);
        if (i == 1) sparkIcon = icon;
        icon.setImageTintList(ColorStateList.valueOf(color));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        item.addView(icon, new LinearLayout.LayoutParams(ui.dp(24), ui.dp(24)));
        TextView label = ui.text(NAMES[i], 12, on ? Ui.BOLD : Ui.MEDIUM, color);
        label.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = ui.dp(4);
        item.addView(label, lp);
        item.setContentDescription(NAMES[i]);
        item.setSelected(on);
        item.setOnClickListener(v -> select(i));
        Ui.buttonRole(item);
        return item;
    }

    /** Switches tab, closing any detail screens opened on the one being left. */
    void select(int i) {
        if (i == tab && stack.isEmpty()) { if (i == 1) ((SparkScreen) tabs[1]).reset(); return; } // tapping Spark again goes back to the topic picker
        if (i == 1) ((SparkScreen) tabs[1]).reset();
        current().onHide();
        stack.clear();
        stackViews.clear();
        tab = i;
        fillNav();
        showTab();
    }

    private void showTab() {
        if (built[tab] == null) built[tab] = tabs[tab].build(ui);
        show(built[tab]);
        tabs[tab].onShow();
    }

    /** The Theme setting changed: rebuild the shell in place, keeping the open screens (recreate() would drop them). */
    void retheme() { draw(); }

    /** Study cards in Spark: opens the Spark tab on one scope (a note or a topic). */
    void study(Study.Scope scope) {
        select(1);
        ((SparkScreen) tabs[1]).openScope(scope);
    }

    /** Opens the Ask tab with a question already sent. */
    void ask(String question) {
        select(3);
        ((AskScreen) tabs[3]).prefill(question);
    }

    /** Ask about this: opens the Ask tab with the note in context and the message left for you to write. */
    void askAbout(String path, String title) {
        select(3);
        ((AskScreen) tabs[3]).attach(path, title);
    }

    /** Opens the Ask tab with this text in the message box, not sent. */
    void draft(String text) {
        select(3);
        ((AskScreen) tabs[3]).draft(text);
    }

    /** Opens one Scout chat (from the Chats list or a notification). */
    void openSession(String file) {
        select(3);
        ((AskScreen) tabs[3]).open(file);
    }

    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(android.content.Intent intent) {
        String q = intent.getStringExtra(AskActivity.QUESTION);
        if (q != null && !q.trim().isEmpty()) { intent.removeExtra(AskActivity.QUESTION); ask(q.trim()); }
        else if (intent.hasExtra(ScoutRuns.SESSION)) { String f = intent.getStringExtra(ScoutRuns.SESSION); intent.removeExtra(ScoutRuns.SESSION); openSession(f); }
        else if (intent.hasExtra(TAB)) select(intent.getIntExtra(TAB, 0) == 3 ? 3 : intent.getIntExtra(TAB, 0) == 1 ? 1 : 0);
    }

    /** Opens a note in the right screen: a Source in its Unit, a Topic on its page, anything else in the note reader. */
    void openNote(String path) {
        if (Layout.inSources(path)) push(new UnitScreen(path));
        else if (path.startsWith("Knowledge/Topics/")) push(new TopicScreen(path));
        else startActivity(new android.content.Intent(this, VaultActivity.class).putExtra(VaultActivity.OPEN, path));
    }

    /** Opens a detail screen over the current tab. Back closes it. */
    void push(Screen s) {
        s.host = this;
        View v = s.build(ui);
        current().onHide();
        stack.add(s);
        stackViews.add(v);
        show(v);
        s.onShow();
    }

    private void show(View v) {
        content.removeAllViews();
        if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
        content.addView(v, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private final androidx.activity.OnBackPressedCallback shellBack = new androidx.activity.OnBackPressedCallback(true) {
        @Override public void handleOnBackPressed() {
            if (current().onBack()) return;
            if (!stack.isEmpty()) {
                current().onHide();
                stack.remove(stack.size() - 1);
                stackViews.remove(stackViews.size() - 1);
                show(stack.isEmpty() ? built[tab] : stackViews.get(stackViews.size() - 1));
                current().onShow();
            } else if (tab != 0) select(0);
            else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); } // the system's own back: leave the app
        }
    };

    /** A tab that is not built yet: its name and a way to the existing screens, so nothing is lost while the real screens arrive. */
    private static final class Placeholder extends Screen {
        private final String name;
        Placeholder(String name) { this.name = name; }
        @Override View build(Ui ui) {
            LinearLayout box = new LinearLayout(ui.c);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            box.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16));
            box.addView(ui.text(name, 20, Ui.BOLD, ui.ink));
            box.addView(ui.text("Coming next.", 15, android.graphics.Typeface.DEFAULT, ui.sub), ui.lp(8));
            return box;
        }
    }
}
