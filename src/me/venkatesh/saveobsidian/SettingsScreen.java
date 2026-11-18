package me.venkatesh.saveobsidian;

import android.app.AlertDialog;
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
    private boolean visible;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final java.util.Map<String, Long> checkVersions = new java.util.HashMap<>();
    private long checkVersion;
    private final java.util.Map<String, String> serverChecks = new java.util.HashMap<>();
    private final SettingsState shown = new SettingsState();
    private java.util.List<SettingGroup> groups;
    private java.util.List<SettingRow> rows;

    /** Rendered by Compose (SettingsView.kt); this class keeps the data and the actions. */
    @Override View build(Ui ui) {
        this.ui = ui;
        draw();
        return SettingsView.create(ui.c, ui, shown, () -> host.onBackPressed());
    }

    @Override void onShow() { visible = true; draw(); }
    @Override void onHide() { visible = false; main.removeCallbacks(refreshPdfModel); }

    private final Runnable refreshPdfModel = () -> { if (visible) draw(); };

    private void pdfModelDialog() {
        AlertDialog.Builder dialog = new AlertDialog.Builder(host).setTitle("PDF vision model").setNegativeButton("Close", null);
        PdfModel.Status status = PdfModel.status(ui.c);
        if (status.downloading()) {
            dialog.setMessage("Android is downloading Gemma 4 E2B in the background. Sparkbox loads it automatically for the next PDF.")
                .setNeutralButton("Cancel download", (d, w) -> { PdfModel.cancel(ui.c); draw(); });
        } else if (PdfModel.file(ui.c).isFile()) {
            boolean loaded = PdfModel.enabled(ui.c);
            dialog.setMessage(loaded ? "Ready for the next PDF. Unload to stop using the model and keep the file."
                    : "The model file is saved. Load it to use it for PDFs again.")
                .setPositiveButton(loaded ? "Unload model" : "Load model", (d, w) -> { PdfModel.enable(ui.c, !loaded); draw(); })
                .setNeutralButton("Delete model", (d, w) -> new AlertDialog.Builder(host).setTitle("Delete PDF model?")
                    .setMessage("This frees the model’s storage. You can download it again.")
                    .setNegativeButton("Cancel", null).setPositiveButton("Delete", (confirm, action) -> {
                        try { PdfModel.delete(ui.c); }
                        catch (java.io.IOException failed) { android.widget.Toast.makeText(ui.c, "Could not delete the model.", android.widget.Toast.LENGTH_LONG).show(); }
                        draw();
                    }).show());
        } else {
            dialog.setMessage("Download Gemma 4 E2B (2.59 GB) on an unmetered connection for local text and vision. Choose AI processing to set its scope.")
                .setPositiveButton("Download & load", (d, w) -> {
                    try { PdfModel.download(ui.c); }
                    catch (java.io.IOException | RuntimeException failed) {
                        android.widget.Toast.makeText(ui.c, failed.getMessage(), android.widget.Toast.LENGTH_LONG).show();
                    }
                    draw();
                });
        }
        dialog.show();
    }

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

        vault.add(row("Dashboard", "Vault, habits, recall, Scout and AI costs", () -> host.push(new DashboardScreen())));

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
            if (!Ingest.on(ui.c) && Model.key(ui.c).isEmpty() && !PdfCapture.modelReady(ui.c)) { android.widget.Toast.makeText(ui.c, "Add an OpenRouter key or install the PDF model first", android.widget.Toast.LENGTH_SHORT).show(); return; }
            prefs().edit().putBoolean("ingest", !Ingest.on(ui.c)).apply(); BackgroundJob.schedule(ui.c); draw();
        }));
        ingest.add(row("Vault write access", canWrite ? "Granted" : "Not granted", canWrite ? null : () -> host.startActivity(new Intent(host, VaultActivity.class).putExtra(VaultActivity.PICK_WRITE, true))));
        if (PdfCapture.supported()) {
            String[] modes = {"Existing behavior (local PDFs when loaded)", "Local models only", "Remote models"};
            ingest.add(row("AI processing", modes[PdfModel.mode(ui.c).ordinal()], () -> new AlertDialog.Builder(host)
                .setTitle("AI processing").setSingleChoiceItems(modes, PdfModel.mode(ui.c).ordinal(), (dialog, which) -> {
                    PdfModel.mode(ui.c, PdfModel.Mode.values()[which]); dialog.dismiss(); draw();
                }).show()));
            if (PdfModel.local(ui.c)) ingest.add(note("Gemma handles text, images and recall. Search uses keywords. Voice uses installed offline speech. Videos use sampled frames. Scout answers from local note excerpts. Web tools and editing require remote mode. Shared links still contact the source website.", false));
            PdfModel.Status model = PdfModel.status(ui.c);
            ingest.add(row("Gemma 4 E2B", model.label(), this::pdfModelDialog));
            if (PdfModel.mode(ui.c) != PdfModel.Mode.REMOTE)
                ingest.add(toggle("Interpret PDF figures", PdfModel.figures(ui.c), () -> { PdfModel.figures(ui.c, !PdfModel.figures(ui.c)); draw(); }));
            main.removeCallbacks(refreshPdfModel);
            if (visible && model.downloading()) main.postDelayed(refreshPdfModel, 1500);
        }
        ingest.add(row("Ingest model", Ingest.model(ui.c), () -> modelDialog("Model for ingest", "An OpenRouter model id. A stronger model gives better topics.", "ingestModel", Ingest.model(ui.c), Ingest.DEFAULT_MODEL)));
        ingest.add(row("Lane and folder model", Classify.model(ui.c), () -> modelDialog("Model for Craft or Life", "An OpenRouter model id. It decides the lane and the subject folder of each new note.", "classifyModel", Classify.model(ui.c), Classify.DEFAULT_MODEL)));
        ingest.add(row("Instagram access", InstagramApi.signedIn() ? "Signed in on this phone" : "Sign in for reels unavailable publicly", () -> InstagramActivity.open(ui.c, "")));
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
        ai.add(row("OpenRouter key", PdfModel.local(ui.c) ? "Disabled in local mode" : keySaved ? "Saved" : "Not set", () -> keyDialog()));
        ai.add(row("Tagging model", prefs().getString("aiModel", Model.DEFAULT), () -> modelDialog("Model for tags", "An OpenRouter model id.", "aiModel", prefs().getString("aiModel", Model.DEFAULT), Model.DEFAULT)));
        ai.add(row("Scout model", Ask.model(ui.c), () -> modelDialog("Model for Scout", "It must support tool calls.", "askModel", Ask.model(ui.c), Ask.DEFAULT)));
        ai.add(row("Scout thinking", Scout.thinking(ui.c), () -> { String now = Scout.thinking(ui.c); int next = (java.util.Arrays.asList(Scout.LEVELS).indexOf(now) + 1) % Scout.LEVELS.length; prefs().edit().putString("scoutThinking", Scout.LEVELS[next]).apply(); draw(); }));
        ai.add(row("Card model", CardGen.model(ui.c), () -> modelDialog("Model for cards", "An OpenRouter model id.", "cardModel", CardGen.model(ui.c), CardGen.DEFAULT_MODEL)));
        if (keySaved || PdfModel.localReady(ui.c)) {
            boolean describe = prefs().getBoolean("describe", false);
            ai.add(toggle("Describe photos, PDFs and videos", describe, () -> { prefs().edit().putBoolean("describe", !describe).apply(); BackgroundJob.schedule(ui.c); draw(); }));
            if (keySaved) ai.add(row("Remove key", "", () -> { Model.clear(ui.c); draw(); }));
        }

        java.util.List<SettingRow> tools = group("Scout tools (MCP servers)");
        org.json.JSONArray servers = Scout.serverList(ui.c);
        for (int i = 0; i < servers.length(); i++) {
            final int at = i;
            org.json.JSONObject s = servers.optJSONObject(i);
            if (s != null) tools.add(row(s.optString("name"), (s.optBoolean("enabled", true) ? "Enabled" : "Disabled") + " · " + serverChecks.getOrDefault(s.optString("name"), "not tested"), () -> serverDialog(at)));
        }
        tools.add(row("Add a server", "Remote MCP over HTTPS. Save and test the connection.", () -> addServerDialog()));

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

        boolean askConf = SparkScreen.askConfidence(ui.c);
        spark.add(row("Ask how sure you are", askConf ? "On" : "Off", () -> {
            ui.c.getSharedPreferences("capture", 0).edit().putBoolean("cardConfidence", !askConf).apply();
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
        try {
            var build = ui.c.getPackageManager().getPackageInfo(ui.c.getPackageName(), 0);
            more.add(row("Sparkbox version", build.versionName + " (" + build.versionCode + ")", null));
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        shown.publish(groups);
    }

    private android.content.SharedPreferences prefs() { return ui.c.getSharedPreferences("capture", 0); }

    private void modelDialog(String title, String hint, String pref, String current, String fallback) {
        ModelPicker.show(host, title, current, pref.equals("askModel"), id -> {
            prefs().edit().putString(pref, id).apply(); draw();
        });
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

    private android.widget.EditText field(android.widget.LinearLayout box, String hint, boolean secret) {
        android.widget.EditText e = new android.widget.EditText(ui.c);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | (secret ? android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD : android.text.InputType.TYPE_TEXT_VARIATION_URI));
        box.addView(e, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    private void addServerDialog() { serverEditor(-1); }

    private void serverEditor(int at) {
        org.json.JSONArray list = Scout.serverList(ui.c);
        org.json.JSONObject previous = at < 0 ? null : list.optJSONObject(at);
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        android.widget.EditText name = field(box, "Name (letters, digits, - or _)", false), url = field(box, "https://server.example/mcp", false);
        android.widget.Spinner auth = new android.widget.Spinner(ui.c);
        auth.setAdapter(new android.widget.ArrayAdapter<>(ui.c, android.R.layout.simple_spinner_dropdown_item, new String[]{"Bearer token", "X-API-Key"}));
        box.addView(auth);
        android.widget.EditText token = field(box, previous == null ? "Token or API key (optional)" : "Token or API key (blank keeps current)", true);
        if (previous != null) {
            name.setText(previous.optString("name")); name.setEnabled(false);
            url.setText(previous.optString("url"));
            auth.setSelection(previous.optString("authHeader", "Authorization").equals("X-API-Key") ? 1 : 0);
        }
        new android.app.AlertDialog.Builder(host).setTitle(previous == null ? "Add an MCP server" : "Edit MCP connection")
            .setMessage("Use the server's HTTPS MCP endpoint and token. Browser sign-in in another app is not shared. Tokens stay encrypted on this device.")
            .setView(box).setPositiveButton("Save and test", (d, w) -> {
                String n = name.getText().toString().trim().replaceAll("[^A-Za-z0-9_-]", "_"), u = url.getText().toString().trim();
                try {
                    java.net.URI endpoint = java.net.URI.create(u);
                    if (n.isEmpty() || !"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getHost() == null || endpoint.getUserInfo() != null) throw new Exception("Needs a name and an HTTPS address without embedded credentials.");
                    if (previous == null) for (int i = 0; i < list.length(); i++) if (n.equals(list.getJSONObject(i).optString("name"))) throw new Exception("A server already uses this name. Edit that connection instead.");
                    String header = auth.getSelectedItemPosition() == 1 ? "X-API-Key" : "Authorization";
                    String secret = token.getText().toString().trim();
                    Mcp.authHeaders(header, secret); // Reject malformed credentials before saving.
                    if (!secret.isEmpty()) Model.seal(ui.c, "mcpToken:" + n, secret);
                    org.json.JSONObject entry = previous == null ? new org.json.JSONObject().put("enabled", true).put("trusted", false) : new org.json.JSONObject(previous.toString());
                    entry.put("name", n).put("url", u).put("authHeader", header);
                    if (previous == null) list.put(entry); else list.put(at, entry);
                    Scout.saveServers(ui.c, list);
                    draw(); testServer(entry);
                } catch (Exception failed) { android.widget.Toast.makeText(ui.c, failed.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("Cancel", null).show();
    }

    private void testServer(org.json.JSONObject config) {
        final String name = config.optString("name"), revision = config.toString();
        final long version = ++checkVersion; checkVersions.put(name, version);
        serverChecks.put(name, "testing…"); draw();
        new Thread(() -> {
            String report, summary;
            try {
                report = Scout.inspectServer(ui.c, config);
                if (!config.optBoolean("enabled", true)) report += "\n\nThis server is disabled in Scout. Turn it on to expose these tools.";
                summary = report.substring(0, report.indexOf('\n'));
            } catch (Exception failed) { report = String.valueOf(failed.getMessage()); summary = "Connection failed"; }
            final String result = report, status = summary;
            main.post(() -> {
                boolean current = false;
                org.json.JSONArray configured = Scout.serverList(ui.c);
                for (int i = 0; i < configured.length(); i++) if (revision.equals(String.valueOf(configured.optJSONObject(i)))) current = true;
                if (!current || !java.util.Objects.equals(checkVersions.get(name), version)) return;
                serverChecks.put(name, status);
                if (!visible || host.isFinishing() || host.isDestroyed()) return;
                draw();
                new android.app.AlertDialog.Builder(host).setTitle(name + " · Connection test").setMessage(result).setPositiveButton("Close", null).show();
            });
        }, "scout-mcp-test").start();
    }

    private void serverDialog(int at) {
        org.json.JSONArray list = Scout.serverList(ui.c);
        org.json.JSONObject s = list.optJSONObject(at);
        if (s == null) return;
        boolean on = s.optBoolean("enabled", true);
        new android.app.AlertDialog.Builder(host).setTitle(s.optString("name")).setItems(new String[]{on ? "Turn off" : "Turn on", "Allowed tools: " + (s.optString("allow").isEmpty() ? "all" : s.optString("allow")), "Read-only research tools: " + (s.optString("readOnly").isEmpty() ? "none" : s.optString("readOnly")), "Test connection", "Edit connection", "Remove"}, (d, which) -> {
            try {
                if (which == 1) { allowDialog(at, "allow"); return; }
                if (which == 2) { allowDialog(at, "readOnly"); return; }
                if (which == 3) { testServer(s); return; }
                if (which == 4) { serverEditor(at); return; }
                if (which == 0) s.put("enabled", !on);
                else if (which == 5) { org.json.JSONArray keep = new org.json.JSONArray(); for (int i = 0; i < list.length(); i++) if (i != at) keep.put(list.get(i)); Scout.saveServers(ui.c, keep); ui.c.getSharedPreferences("capture", 0).edit().remove("mcpToken:" + s.optString("name")).apply(); serverChecks.remove(s.optString("name")); draw(); return; }
                Scout.saveServers(ui.c, list);
                serverChecks.remove(s.optString("name"));
            } catch (Exception ignored) { }
            draw();
        }).show();
    }

    private void allowDialog(int at, String key) {
        org.json.JSONArray list = Scout.serverList(ui.c);
        org.json.JSONObject s = list.optJSONObject(at);
        if (s == null) return;
        LinearLayout box = new LinearLayout(ui.c);
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0);
        android.widget.EditText e = field(box, "search, fetch_content (empty = every tool)", false);
        e.setText(s.optString(key));
        new android.app.AlertDialog.Builder(host).setTitle(key.equals("readOnly") ? "Verified read-only research tools" : "Allowed tools").setMessage(key.equals("readOnly") ? "Enter only tool names you verified perform read-only research. These calls can run without approval. Server annotation hints alone do not grant this permission. Empty means no automatic external calls." : "The server's own tool names, separated by commas. Only these are offered to Scout. Empty means all tools.").setView(box)
            .setPositiveButton("Save", (d, w) -> { try { s.put(key, e.getText().toString().trim()); Scout.saveServers(ui.c, list); } catch (Exception ignored) { } draw(); }).setNegativeButton("Cancel", null).show();
    }
}
