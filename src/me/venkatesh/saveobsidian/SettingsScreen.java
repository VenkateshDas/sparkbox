package me.venkatesh.saveobsidian;

import android.content.Intent;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Settings, a plain list: vault, capture, ingest, AI key, Spark, theme. Drawn by Compose (the pilot screen); data and actions stay here. */
final class SettingsScreen extends Screen {
    private Ui ui;
    private final SettingsState shown = new SettingsState();
    private java.util.List<SettingGroup> groups;
    private java.util.List<SettingRow> rows;

    /** Rendered by Compose (SettingsView.kt); this class keeps the data and the actions. */
    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        return SettingsView.create(ui.c, ui, shown, () -> host.onBackPressed());
    }

    @Override void onShow() { draw(); }

    private java.util.List<SettingRow> group(String title) {
        rows = new java.util.ArrayList<>();
        groups.add(new SettingGroup(title, rows));
        return rows;
    }

    private SettingRow row(String title, String value, Runnable click) { return new SettingRow(title, value, click, null, 0); }
    private SettingRow toggle(String title, boolean on, Runnable click) { return new SettingRow(title, "", click, on, 0); }
    private SettingRow note(String text, boolean warning) { return new SettingRow(text, "", null, null, warning ? 1 : 2); }

    private void draw() {
        groups = new java.util.ArrayList<>();
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        java.util.List<SettingRow> vault = group("Vault");
        vault.add(row("Vault folder", VaultStore.ready(ui.c) ? VaultStore.label(ui.c) : "Not chosen", () -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true))));
        vault.add(row("Notes", snap == null ? "" : String.format(java.util.Locale.US, "%,d", snap.graph.notes.size()), null));

        java.util.List<SettingRow> capture = group("Capture");
        boolean inboxOk = !ui.c.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty();
        capture.add(row("Inbox", inboxOk ? "Knowledge/Sources/Inbox.md" : "Choose the vault folder with write access", null));
        capture.add(row("Photos and documents folder", Attach.ready(ui.c) ? Attach.label(ui.c) : "Not chosen", () -> {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            pick.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents/Obsidian"));
            host.startActivityForResult(pick, MainActivity.PICK_ATTACH);
        }));
        capture.add(row("Notifications", "", () -> host.startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ui.c.getPackageName()))));
        android.appwidget.AppWidgetManager widgets = android.appwidget.AppWidgetManager.getInstance(ui.c);
        if (widgets.isRequestPinAppWidgetSupported()) capture.add(row("Home screen widget", "", () -> widgets.requestPinAppWidget(new android.content.ComponentName(ui.c, InboxWidget.class), null, null)));

        java.util.List<SettingRow> ingest = group("Ingest");
        boolean canWrite = VaultWrite.writable(ui.c);
        ingest.add(toggle("Process captures into notes", Ingest.on(ui.c), () -> {
            if (!Ingest.on(ui.c) && !canWrite) { android.widget.Toast.makeText(ui.c, "Allow write access to the vault first", android.widget.Toast.LENGTH_SHORT).show(); return; }
            if (!Ingest.on(ui.c) && Model.key(ui.c).isEmpty()) { android.widget.Toast.makeText(ui.c, "Add your OpenRouter key first", android.widget.Toast.LENGTH_SHORT).show(); return; }
            prefs().edit().putBoolean("ingest", !Ingest.on(ui.c)).apply(); BackgroundJob.schedule(ui.c); draw();
        }));
        ingest.add(row("Vault write access", canWrite ? "Granted" : "Not granted", canWrite ? null : () -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true))));
        ingest.add(row("Ingest model", Ingest.model(ui.c), () -> modelDialog("Model for ingest", "An OpenRouter model id. A stronger model gives better topics.", "ingestModel", Ingest.model(ui.c), Ingest.DEFAULT_MODEL)));
        ingest.add(row("Lane and folder model", Classify.model(ui.c), () -> modelDialog("Model for Craft or Life", "An OpenRouter model id. It decides the lane and the subject folder of each new note.", "classifyModel", Classify.model(ui.c), Classify.DEFAULT_MODEL)));
        ingest.add(row("Retry failed captures", "", () -> {
            int n = Ingest.retryAll(ui.c);
            android.widget.Toast.makeText(ui.c, n == 0 ? "Nothing has failed" : "Trying " + n + (n == 1 ? " capture" : " captures") + " again", android.widget.Toast.LENGTH_SHORT).show();
            draw();
        }));
        String classifyProblem = prefs().getString("classifyProblem", "");
        if (!classifyProblem.isEmpty()) ingest.add(note("Lane and folder model: " + classifyProblem, true));
        String problem = prefs().getString("ingestProblem", ""), log = prefs().getString("ingestLog", "");
        if (!problem.isEmpty()) ingest.add(note(problem, true));
        if (!log.isEmpty()) ingest.add(note("Recently ingested\n" + String.join("\n", java.util.Arrays.copyOf(log.split("\n"), Math.min(5, log.split("\n").length))), false));

        java.util.List<SettingRow> ai = group("AI key");
        boolean keySaved = !Model.key(ui.c).isEmpty();
        ai.add(row("OpenRouter key", keySaved ? "Saved" : "Not set", () -> keyDialog()));
        ai.add(row("Tagging model", prefs().getString("aiModel", Model.DEFAULT), () -> modelDialog("Model for tags", "An OpenRouter model id.", "aiModel", prefs().getString("aiModel", Model.DEFAULT), Model.DEFAULT)));
        ai.add(row("Ask model", Ask.model(ui.c), () -> modelDialog("Model for Ask", "It must support tool calls.", "askModel", Ask.model(ui.c), Ask.DEFAULT)));
        ai.add(row("Card model", CardGen.model(ui.c), () -> modelDialog("Model for cards", "An OpenRouter model id.", "cardModel", CardGen.model(ui.c), CardGen.DEFAULT_MODEL)));
        if (keySaved) {
            boolean describe = prefs().getBoolean("describe", false);
            ai.add(toggle("Describe photos and PDFs", describe, () -> { prefs().edit().putBoolean("describe", !describe).apply(); BackgroundJob.schedule(ui.c); draw(); }));
            ai.add(row("Remove key", "", () -> { Model.clear(ui.c); draw(); }));
        }

        java.util.List<SettingRow> spark = group("Spark");
        int[] p = snap == null ? new int[]{0, 0} : CardGen.progress(snap);
        String state = CardGen.on(ui.c) ? (CardGen.running() ? "Making cards " + p[0] + " of " + p[1] : "Paused at " + p[0] + " of " + p[1]) : p[0] > 0 && p[0] >= p[1] ? "All notes done" : "Not started";
        spark.add(row("Make cards for existing notes", state, () -> host.push(new CardGenScreen())));
        spark.add(row("Daily reminder", Reminder.on(ui.c) ? Reminder.time(ui.c) : "Off", () -> {
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
        spark.add(row("Cards per day", String.valueOf(cap), () -> {
            int next = cap >= Spark.CAP_MAX ? Spark.CAP_MIN : cap + 1;
            ui.c.getSharedPreferences("capture", 0).edit().putInt("cardsPerDay", Spark.clampCap(next)).apply();
            draw();
        }));

        java.util.List<SettingRow> look = group("Theme");
        String theme = ui.c.getSharedPreferences("capture", 0).getString("theme", "system");
        look.add(row("Theme", theme.substring(0, 1).toUpperCase() + theme.substring(1), () -> {
            String next = theme.equals("system") ? "dark" : theme.equals("dark") ? "light" : "system";
            ui.c.getSharedPreferences("capture", 0).edit().putString("theme", next).apply();
            host.retheme();
        }));

        java.util.List<SettingRow> more = group("More");
        more.add(row("Browse vault", "", () -> host.startActivity(new Intent(host, VaultActivity.class))));
        shown.publish(groups);
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
