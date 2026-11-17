package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** Inbox stays one tap; direct destinations are explicit per capture. */
final class DestinationPicker {
    static String label(JSONObject d) {
        if ("existing".equals(d.optString("destination"))) return d.optString("target");
        if ("new".equals(d.optString("destination"))) return "New note · " + (d.optString("noteTitle").isEmpty() ? "AI title" : d.optString("noteTitle"));
        return "Inbox";
    }
    static void show(Activity host, JSONObject current, java.util.function.Consumer<JSONObject> picked) {
        new AlertDialog.Builder(host).setTitle("Save to").setItems(new String[]{"Inbox", "Existing note", "New note"}, (d, at) -> {
            if (at == 0) { picked.accept(new JSONObject()); return; }
            if (!VaultWrite.writable(host)) { Toast.makeText(host, "Allow write access to your vault in Settings first.", Toast.LENGTH_LONG).show(); return; }
            String tree = VaultStore.tree(host);
            if (at == 1) existing(host, tree, picked);
            else {
                EditText title = new EditText(host); title.setHint("Title (optional; AI titles it when blank)"); title.setSingleLine(true); title.setText(current.optString("noteTitle"));
                AlertDialog dialog = new AlertDialog.Builder(host).setTitle("New personal note").setMessage("Saved in Knowledge/Topics. Your words stay unchanged.").setView(title)
                    .setPositiveButton("Use", null).setNegativeButton("Cancel", null).create();
                dialog.show(); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String raw = title.getText().toString().trim(), safe = IngestText.safe(raw);
                    if (!raw.isEmpty() && (safe.isEmpty() || safe.equals(".") || safe.equals(".."))) { title.setError("Enter a usable title"); return; }
                    try { picked.accept(new JSONObject().put("destination", "new").put("destinationVersion", 1).put("vault", tree).put("noteTitle", safe)); dialog.dismiss(); }
                    catch (Exception e) { title.setError("Could not select destination"); }
                });
            }
        }).show();
    }
    private static void existing(Activity host, String tree, java.util.function.Consumer<JSONObject> picked) {
        LinearLayout box = new LinearLayout(host); box.setOrientation(LinearLayout.VERTICAL);
        EditText search = new EditText(host); search.setSingleLine(true); search.setHint("Search note title or path"); box.addView(search);
        ListView list = new ListView(host); box.addView(list, new LinearLayout.LayoutParams(-1, (int)(280 * host.getResources().getDisplayMetrics().density)));
        List<Vault.Note> notes = new ArrayList<>(), shown = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(host, android.R.layout.simple_list_item_1); list.setAdapter(adapter);
        Runnable update = () -> {
            String query = search.getText().toString().toLowerCase(java.util.Locale.ROOT); shown.clear(); adapter.clear();
            for (Vault.Note n : notes) if ((n.title + " " + n.path).toLowerCase(java.util.Locale.ROOT).contains(query)) { shown.add(n); adapter.add(n.title + "\n" + n.path); }
        };
        search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int a, int c, int f) { } public void onTextChanged(CharSequence s, int a, int b, int c) { update.run(); } public void afterTextChanged(Editable e) { } });
        AlertDialog dialog = new AlertDialog.Builder(host).setTitle("Existing note").setView(box).setNegativeButton("Cancel", null).create(); dialog.show();
        list.setOnItemClickListener((parent, v, at, id) -> { try {
            picked.accept(new JSONObject().put("destination", "existing").put("destinationVersion", 1).put("vault", tree).put("target", shown.get(at).path)); dialog.dismiss();
        } catch (Exception e) { Toast.makeText(host, "Could not select destination", Toast.LENGTH_SHORT).show(); } });
        new Thread(() -> {
            try {
                VaultStore.Snap snap = VaultStore.cached(host); if (snap == null) snap = VaultStore.scan(host);
                List<Vault.Note> found = new ArrayList<>();
                for (Vault.Note n : snap.graph.notes) if (!n.catalog && DirectText.allowed(n.path)) found.add(n);
                found.sort((a, b) -> a.title.compareToIgnoreCase(b.title));
                host.runOnUiThread(() -> { if (!dialog.isShowing()) return; notes.addAll(found); update.run(); if (notes.isEmpty()) dialog.setTitle("No writable notes found"); });
            } catch (Exception e) { host.runOnUiThread(() -> dialog.setTitle("Cannot read vault. Check Settings.")); }
        }, "capture-note-picker").start();
    }
}
