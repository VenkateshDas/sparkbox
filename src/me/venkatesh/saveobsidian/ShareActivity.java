package me.venkatesh.saveobsidian;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.widget.*;
import java.io.File;

public class ShareActivity extends Activity {
    private boolean started;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        boolean sharing = Intent.ACTION_SEND.equals(getIntent().getAction());
        boolean ready = !getSharedPreferences("capture", 0).getString("inbox", "").isEmpty();
        if (!sharing || !ready) setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(state);
        if (state != null && state.getBoolean("started")) { finish(); return; }
        if (sharing && ready) { receive(); return; }
        showSetup();
    }
    private void showSetup() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad * 2, pad, pad);
        status = new TextView(this);
        status.setTextSize(17);
        status.setTextIsSelectable(true);
        String uri = getSharedPreferences("capture", 0).getString("inbox", "");
        status.setText((uri.isEmpty() ? "Select your inbox once to enable background saving." : "Ready: background saving is enabled.")
            + "\n\nShare → Save to Obsidian returns to your current app. A notification confirms when the local file is saved.\n\nGSync uploads these entries when Obsidian next opens and syncs.\n\nDestination: Tiny experiments / Sources / Inbox.md\n");
        layout.addView(status);
        Button choose = new Button(this);
        choose.setText(uri.isEmpty() ? "Choose Inbox.md" : "Choose inbox again");
        choose.setOnClickListener(v -> chooseInbox());
        layout.addView(choose);
        Button notifications = new Button(this);
        notifications.setText("Enable save notifications");
        notifications.setOnClickListener(v -> requestNotifications());
        layout.addView(notifications);
        if (Build.VERSION.SDK_INT >= 36) {
            Button live = new Button(this);
            live.setText("Live update settings");
            live.setOnClickListener(v -> {
                try { startActivity(new Intent("android.settings.APP_NOTIFICATION_SETTINGS").putExtra("android.provider.extra.APP_PACKAGE", getPackageName())); }
                catch (RuntimeException e) { status.append("\nLive update settings are not exposed on this device."); }
            });
            layout.addView(live);
        }
        File[] pending = new File(getFilesDir(), "pending").listFiles((dir, name) -> name.endsWith(".json"));
        if (pending != null && pending.length > 0) {
            status.append("\nRecovery captures: " + pending.length + ". Kept privately; check inbox before retrying.\n");
            for (File file : pending) {
                try { status.append("\n" + CaptureService.readJob(file).optString("entry", "Pending title lookup") + "\n"); }
                catch (Exception ignored) { }
            }
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(layout);
        setContentView(scroll);
    }
    private void chooseInbox() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        pick.putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents", "primary:Documents/Obsidian/Tiny experiments/Sources"));
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
            if (!"Inbox.md".equalsIgnoreCase(name)) { status.append("\nPlease select Sources/Inbox.md."); return; }
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if ((flags & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == 0) throw new SecurityException();
            getContentResolver().takePersistableUriPermission(uri, flags);
            getSharedPreferences("capture", 0).edit().putString("inbox", uri.toString()).commit();
            if (Intent.ACTION_SEND.equals(getIntent().getAction())) receive();
            else { showSetup(); requestNotifications(); }
        } catch (Exception e) { status.append("\nCould not obtain write access. Choose the local inbox again."); }
    }
    private void requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
    }
    private void receive() {
        try {
            CharSequence text = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
            CharSequence subject = getIntent().getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            String url = Capture.firstUrl(text == null ? "" : text.toString());
            CaptureService.enqueue(this, url, subject == null ? "" : subject.toString());
            startForegroundService(new Intent(this, CaptureService.class));
            started = true;
            finish();
        } catch (Exception e) {
            showSetup();
            status.append("\nCould not start this capture: " + e.getMessage());
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("started", started);
        super.onSaveInstanceState(state);
    }
}
