package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings, a plain list: vault, capture, ingest, AI key, Spark, theme. Only recovery tools still live in the older Setup page. */
final class SettingsScreen extends Screen {
    private Ui ui;
    private LinearLayout page;

    @Override View build(Ui ui) {
        this.ui = ui;
        LinearLayout root = new LinearLayout(ui.c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.addView(Bars.header(ui, "Settings", v -> host.onBackPressed()));
        ScrollView scroll = new ScrollView(ui.c);
        page = new LinearLayout(ui.c);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24));
        scroll.addView(page);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        draw();
        return root;
    }

    @Override void onShow() { draw(); }

    private LinearLayout group(String title) {
        page.addView(ui.text(title, 14, Ui.BOLD, ui.sub), ui.lp(20));
        LinearLayout card = new LinearLayout(ui.c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4));
        card.setBackground(ui.round(ui.surface, ui.line, 12));
        page.addView(card, ui.lp(8));
        return card;
    }

    private View row(String title, String value, View.OnClickListener click) {
        boolean add = value.equals("Add");
        return row(title, add ? "" : value, add ? "Add" : value.isEmpty() ? "Open" : value.equals("On") || value.equals("Off") ? "Switch" : "Change", click);
    }

    /** A settings line in three fixed columns: label, current value, button. Lines with no action keep the button column empty so every value lines up. */
    private View row(String title, String value, String action, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(ui.c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.dp(56));
        r.addView(ui.text(title, 16, Typeface.DEFAULT, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 5));
        TextView v = ui.text(value, 13, Typeface.DEFAULT, ui.sub);
        v.setMaxLines(2);
        v.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 4);
        vp.leftMargin = ui.dp(8);
        r.addView(v, vp);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ui.dp(92), ui.dp(40));
        bp.leftMargin = ui.dp(12);
        if (click != null) { TextView b = ui.button(action, false, click); b.setMinHeight(ui.dp(40)); b.setPadding(ui.dp(8), 0, ui.dp(8), 0); r.addView(b, bp); }
        else r.addView(new View(ui.c), bp);
        return r;
    }

    private void draw() {
        page.removeAllViews();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        LinearLayout vault = group("Vault");
        vault.addView(row("Vault folder", VaultStore.ready(ui.c) ? VaultStore.label(ui.c) : "Not chosen", v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true))));
        vault.addView(row("Notes", snap == null ? "" : String.format(java.util.Locale.US, "%,d", snap.graph.notes.size()), null));

        LinearLayout capture = group("Capture");
        boolean inboxOk = !ui.c.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty();
        capture.addView(row("Inbox", inboxOk ? "Knowledge/Sources/Inbox.md" : "Choose the vault folder with write access", null));
        capture.addView(row("Photos and documents folder", Attach.ready(ui.c) ? Attach.label(ui.c) : "Not chosen", v -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pick.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Obsidian"));
            host.startActivityForResult(pick, MainActivity.PICK_ATTACH);
        }));
        capture.addView(row("Notifications", "", v -> host.startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ui.c.getPackageName()))));
        android.appwidget.AppWidgetManager widgets = android.appwidget.AppWidgetManager.getInstance(ui.c);
        if (widgets.isRequestPinAppWidgetSupported()) capture.addView(row("Home screen widget", "Add", v -> widgets.requestPinAppWidget(new android.content.ComponentName(ui.c, InboxWidget.class), null, null)));

        LinearLayout ingest = group("Ingest");
        boolean canWrite = VaultWrite.writable(ui.c);
        ingest.addView(row("Process captures into notes", Ingest.on(ui.c) ? "On" : "Off", v -> {
            if (!Ingest.on(ui.c) && !canWrite) { android.widget.Toast.makeText(ui.c, "Allow write access to the vault first", android.widget.Toast.LENGTH_SHORT).show(); return; }
            if (!Ingest.on(ui.c) && Model.key(ui.c).isEmpty()) { android.widget.Toast.makeText(ui.c, "Add your OpenRouter key first", android.widget.Toast.LENGTH_SHORT).show(); return; }
            prefs().edit().putBoolean("ingest", !Ingest.on(ui.c)).apply(); BackgroundJob.schedule(ui.c); draw();
        }));
        ingest.addView(row("Vault write access", canWrite ? "Granted" : "Not granted", canWrite ? null : v -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true))));
        ingest.addView(row("Ingest model", Ingest.model(ui.c), v -> modelDialog("Model for ingest", "An OpenRouter model id. A stronger model gives better topics.", "ingestModel", Ingest.model(ui.c), Ingest.DEFAULT_MODEL)));
        ingest.addView(row("Lane and folder model", Classify.model(ui.c), v -> modelDialog("Model for Craft or Life", "An OpenRouter model id. It decides the lane and the subject folder of each new note.", "classifyModel", Classify.model(ui.c), Classify.DEFAULT_MODEL)));
        ingest.addView(row("Retry failed captures", "", "Retry now", v -> {
            int n = Ingest.retryAll(ui.c);
            android.widget.Toast.makeText(ui.c, n == 0 ? "Nothing has failed" : "Trying " + n + (n == 1 ? " capture" : " captures") + " again", android.widget.Toast.LENGTH_SHORT).show();
            draw();
        }));
        String classifyProblem = prefs().getString("classifyProblem", "");
        if (!classifyProblem.isEmpty()) { TextView w = ui.text("Lane and folder model: " + classifyProblem, 13, Typeface.DEFAULT, ui.warn); w.setPadding(0, ui.dp(8), 0, ui.dp(8)); ingest.addView(w); }
        String problem = prefs().getString("ingestProblem", ""), log = prefs().getString("ingestLog", "");
        if (!problem.isEmpty()) { TextView w = ui.text(problem, 13, Typeface.DEFAULT, ui.warn); w.setPadding(0, ui.dp(8), 0, ui.dp(8)); ingest.addView(w); }
        if (!log.isEmpty()) { TextView w = ui.text("Recently ingested\n" + String.join("\n", java.util.Arrays.copyOf(log.split("\n"), Math.min(5, log.split("\n").length))), 12, Typeface.DEFAULT, ui.sub); w.setPadding(0, ui.dp(8), 0, ui.dp(12)); ingest.addView(w); }

        LinearLayout ai = group("AI key");
        boolean keySaved = !Model.key(ui.c).isEmpty();
        ai.addView(row("OpenRouter key", keySaved ? "Saved" : "Not set", v -> keyDialog()));
        ai.addView(row("Tagging model", prefs().getString("aiModel", Model.DEFAULT), v -> modelDialog("Model for tags", "An OpenRouter model id.", "aiModel", prefs().getString("aiModel", Model.DEFAULT), Model.DEFAULT)));
        ai.addView(row("Ask model", Ask.model(ui.c), v -> modelDialog("Model for Ask", "It must support tool calls.", "askModel", Ask.model(ui.c), Ask.DEFAULT)));
        ai.addView(row("Card model", CardGen.model(ui.c), v -> modelDialog("Model for cards", "An OpenRouter model id.", "cardModel", CardGen.model(ui.c), CardGen.DEFAULT_MODEL)));
        if (keySaved) {
            boolean describe = prefs().getBoolean("describe", false);
            ai.addView(row("Describe photos and PDFs", describe ? "On" : "Off", v -> { prefs().edit().putBoolean("describe", !describe).apply(); BackgroundJob.schedule(ui.c); draw(); }));
            ai.addView(row("Remove key", "", "Remove", v -> { Model.clear(ui.c); draw(); }));
        }

        LinearLayout spark = group("Spark");
        int[] p = snap == null ? new int[]{0, 0} : CardGen.progress(snap);
        String state = CardGen.on(ui.c) ? (CardGen.running() ? "Making cards " + p[0] + " of " + p[1] : "Paused at " + p[0] + " of " + p[1]) : p[0] > 0 && p[0] >= p[1] ? "All notes done" : "Not started";
        spark.addView(row("Make cards for existing notes", state, v -> host.push(new CardGenScreen())));
        spark.addView(row("Daily reminder", Reminder.on(ui.c) ? Reminder.time(ui.c) : "Off", v -> {
            if (Reminder.on(ui.c)) { ui.c.getSharedPreferences("capture", 0).edit().putBoolean("reminderOn", false).apply(); Reminder.schedule(ui.c); draw(); return; }
            if (android.os.Build.VERSION.SDK_INT >= 33 && host.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) host.requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 3);
            int m = Reminder.minutes(ui.c);
            new android.app.TimePickerDialog(host, (picker, h, min) -> {
                ui.c.getSharedPreferences("capture", 0).edit().putBoolean("reminderOn", true).putInt("reminderAt", h * 60 + min).apply();
                Reminder.schedule(ui.c);
                draw();
            }, m / 60, m % 60, true).show();
        }));
        int cap = ui.c.getSharedPreferences("capture", 0).getInt("cardsPerDay", Spark.CAP_DEFAULT);
        spark.addView(row("Cards per day", String.valueOf(cap), v -> {
            int next = cap >= Spark.CAP_MAX ? Spark.CAP_MIN : cap + 1;
            ui.c.getSharedPreferences("capture", 0).edit().putInt("cardsPerDay", Spark.clampCap(next)).apply();
            draw();
        }));

        LinearLayout look = group("Theme");
        String theme = ui.c.getSharedPreferences("capture", 0).getString("theme", "system");
        look.addView(row("Theme", theme.substring(0, 1).toUpperCase() + theme.substring(1), v -> {
            String next = theme.equals("system") ? "dark" : theme.equals("dark") ? "light" : "system";
            ui.c.getSharedPreferences("capture", 0).edit().putString("theme", next).apply();
            host.recreate();
        }));

        LinearLayout more = group("More");
        more.addView(row("Browse vault", "", v -> host.startActivity(new Intent(host, VaultActivity.class))));
    }

    private android.content.SharedPreferences prefs() { return ui.c.getSharedPreferences("capture", 0); }

    private void modelDialog(String title, String hint, String pref, String current, String fallback) {
        android.widget.EditText e = new android.widget.EditText(ui.c);
        e.setText(current);
        e.setSingleLine(true);
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(host).setTitle(title).setMessage(hint).setView(box)
            .setPositiveButton("Save", (d, w) -> { String m = e.getText().toString().trim(); prefs().edit().putString(pref, m.isEmpty() ? fallback : m).apply(); draw(); })
            .setNegativeButton("Cancel", null).show();
    }

    private void keyDialog() {
        android.widget.EditText e = new android.widget.EditText(ui.c);
        e.setHint("OpenRouter API key");
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new android.app.AlertDialog.Builder(host).setTitle("OpenRouter key").setMessage("Stored encrypted on this phone. Used for tags, ingest, Ask, cards and voice notes.").setView(box)
            .setPositiveButton("Save", (d, w) -> {
                String k = e.getText().toString().trim();
                if (k.isEmpty()) return;
                try { Model.saveKey(ui.c, k); BackgroundJob.schedule(ui.c); } catch (Exception failed) { android.widget.Toast.makeText(ui.c, "Could not save the key", android.widget.Toast.LENGTH_SHORT).show(); }
                draw();
            }).setNegativeButton("Cancel", null).show();
    }
}
