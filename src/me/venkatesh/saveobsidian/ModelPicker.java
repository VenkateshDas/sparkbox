package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** One searchable picker for all existing configurable model roles. */
final class ModelPicker {
    static void show(Activity host, String title, String current, boolean tools, java.util.function.Consumer<String> picked) {
        new ModelPicker(host, title, current, tools, picked).open();
    }
    private final Activity host;
    private final String title, current;
    private final boolean tools;
    private final java.util.function.Consumer<String> picked;
    private JSONArray data = new JSONArray();
    private List<JSONObject> rows = new ArrayList<>();
    private final TextView status;
    private final EditText search;
    private final ListView list;
    private final Button refresh;
    private AlertDialog dialog;
    private boolean loading;
    private final android.content.SharedPreferences cache;
    private ModelPicker(Activity host, String title, String current, boolean tools, java.util.function.Consumer<String> picked) {
        this.host = host; this.title = title; this.current = current; this.tools = tools; this.picked = picked;
        status = new TextView(host); search = new EditText(host); list = new ListView(host); refresh = new Button(host);
        cache = host.getSharedPreferences("model_catalog", 0);
        try { data = new JSONArray(cache.getString("data", "[]")); } catch (Exception ignored) { }
    }
    private void open() {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(24, 8, 24, 0);
        search.setHint("Search provider, name or model ID"); search.setSingleLine(true); box.addView(search);
        box.addView(status); refresh.setText("Refresh catalog"); refresh.setOnClickListener(v -> fetch()); box.addView(refresh);
        box.addView(list, new LinearLayout.LayoutParams(-1, (int)(320 * host.getResources().getDisplayMetrics().density)));
        dialog = new AlertDialog.Builder(host).setTitle(title).setView(box).setNegativeButton("Cancel", null)
            .setNeutralButton("Custom ID", (d, w) -> custom()).create();
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) { }
            public void onTextChanged(CharSequence s, int st, int before, int count) { render(); }
            public void afterTextChanged(Editable e) { }
        });
        list.setOnItemClickListener((p, v, at, id) -> { picked.accept(rows.get(at).optString("id")); dialog.dismiss(); });
        dialog.show(); render();
        if (data.length() == 0 || System.currentTimeMillis() - cache.getLong("fetched", 0) >= 86400000L) fetch();
    }
    private void render() {
        rows = ModelCatalog.search(data, search.getText().toString(), tools);
        List<String> labels = new ArrayList<>();
        for (JSONObject m : rows) labels.add((m.optString("id").equals(current) ? "Selected · " : "") + m.optString("name", m.optString("id")) + "\n" + m.optString("id"));
        list.setAdapter(new ArrayAdapter<>(host, android.R.layout.simple_list_item_1, labels));
        boolean found = false; for (int i = 0; i < data.length(); i++) if (data.optJSONObject(i) != null && current.equals(data.optJSONObject(i).optString("id"))) found = true;
        status.setText("Current: " + current + (data.length() > 0 && !found ? " (not in catalog)" : "") + "\n" + (loading ? "Refreshing…" : data.length() == 0 ? "No catalog. Refresh or use Custom ID." : rows.isEmpty() ? "No matching models." : rows.size() + " models · cached"));
    }
    private void fetch() {
        if (loading) return;
        loading = true; refresh.setEnabled(false); render();
        new Thread(() -> {
            String problem = "";
            try {
                JSONArray next = ModelCatalog.parse(ScoutUsage.get("models", ""));
                if (!cache.edit().putString("data", next.toString()).putLong("fetched", System.currentTimeMillis()).commit()) throw new java.io.IOException("Could not cache models");
                data = next;
            } catch (Exception e) { problem = "Refresh failed. Cached models and current selection kept."; }
            final String error = problem;
            host.runOnUiThread(() -> { if (host.isDestroyed() || !dialog.isShowing()) return; loading = false; refresh.setEnabled(true); render(); if (!error.isEmpty()) status.append("\n" + error); });
        }, "model-catalog").start();
    }
    private void custom() {
        EditText field = new EditText(host); field.setSingleLine(true); field.setText(current);
        AlertDialog custom = new AlertDialog.Builder(host).setTitle("Custom OpenRouter model ID").setView(field).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        custom.show(); custom.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String id = field.getText().toString().trim();
            if (id.isEmpty() || id.matches(".*\\s.*")) { field.setError("Enter a model ID without spaces"); return; }
            picked.accept(id); custom.dismiss();
        });
    }
}
