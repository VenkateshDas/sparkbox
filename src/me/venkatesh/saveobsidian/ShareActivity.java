package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.widget.Toast;

/** Receives a share, queues it privately, and gets out of the way. All UI lives in SetupActivity. */
public class ShareActivity extends Activity {
    /** A sharing shortcut puts Sparkbox in the share sheet's top "direct share" row. */
    static void publishShortcut(android.content.Context ctx) {
        try {
            ShortcutManager manager = ctx.getSystemService(ShortcutManager.class);
            manager.setDynamicShortcuts(java.util.Collections.singletonList(new ShortcutInfo.Builder(ctx, "save")
                .setShortLabel("Sparkbox").setLongLabel("Save to Obsidian inbox")
                .setIcon(Icon.createWithResource(ctx, R.drawable.notification_icon))
                .setCategories(java.util.Collections.singleton("me.venkatesh.saveobsidian.SAVE"))
                .setIntent(new Intent(Intent.ACTION_MAIN).setClass(ctx, SetupActivity.class)).build()));
        } catch (RuntimeException ignored) { } // the share itself must never depend on this
    }
    /** Start filing queued captures, or ask for an inbox first. The capture itself is already safe in the queue. */
    static void dispatch(android.app.Activity a) {
        if (a.getSharedPreferences("capture", 0).getString("inbox", "").isEmpty()) a.startActivity(new Intent(a, SetupActivity.class).putExtra("waiting", true));
        else CaptureService.start(a, null);
    }

    /** Photos, documents and audio: copy each into private storage now, because the sender's access ends with this screen. */
    @SuppressWarnings("deprecation")
    private void receiveFiles(CharSequence caption) {
        java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        try {
            if (Intent.ACTION_SEND_MULTIPLE.equals(getIntent().getAction())) {
                java.util.List<android.net.Uri> many = getIntent().getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (many != null) uris.addAll(many);
            } else {
                android.net.Uri one = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                if (one != null) uris.add(one);
            }
        } catch (RuntimeException malformed) { } // a crafted extra must not crash the receiver
        // Any app can send this intent. A file: URI would be read with Sparkbox's own access (its key and queue), so only content: is taken.
        uris.removeIf(u -> u == null || !"content".equals(u.getScheme()));
        int saved = 0, files = 0, voice = 0;
        String problem = null;
        for (android.net.Uri uri : uris.subList(0, Math.min(uris.size(), 10))) {
            try {
                String original = Attach.displayName(this, uri), ext = Attach.extension(original), id = java.util.UUID.randomUUID().toString().substring(0, 4);
                String mime = Attach.mime(original), time = Capture.timestamp();
                java.io.File copy = Attach.stash(this, uri);
                try {
                String format = Enrich.AUDIO.get(ext);
                if (format != null) {
                    CaptureService.enqueue(this, new org.json.JSONObject().put("kind", "voice").put("audio", copy.getPath()).put("format", format)
                        .put("label", "Voice note: " + original).put("time", time));
                    voice++;
                } else {
                    CaptureService.enqueue(this, new org.json.JSONObject().put("kind", "file").put("path", copy.getPath()).put("name", Capture.fileName(time, original, id))
                        .put("mime", mime).put("original", original).put("title", caption == null ? "" : caption.toString()).put("time", time));
                    files++;
                }
                } catch (Exception queueFailed) { copy.delete(); throw queueFailed; } // no orphan copy when queueing fails
                saved++;
            } catch (Exception e) { problem = e.getMessage(); }
        }
        if (problem != null) Toast.makeText(this, "Could not save: " + problem, Toast.LENGTH_LONG).show();
        if (saved == 0) return;
        if (voice > 0) BackgroundJob.schedule(this);
        if (files > 0) {
            if (!Attach.ready(this)) Toast.makeText(this, "Saved on this phone. Choose the attachments folder in Settings to finish.", Toast.LENGTH_LONG).show();
            else dispatch(this);
        }
        if (voice > 0 && Model.key(this).isEmpty()) Toast.makeText(this, "Add an OpenRouter key in Settings to transcribe audio.", Toast.LENGTH_LONG).show();
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        publishShortcut(this);
        if (state == null && (getIntent().hasExtra(Intent.EXTRA_STREAM))) {
            CharSequence caption = getIntent().getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            if (caption == null) caption = getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT);
            receiveFiles(caption);
        } else if (state == null) receive(getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT), getIntent().getCharSequenceExtra(Intent.EXTRA_SUBJECT)); // a recreated activity must not queue the same share twice
        finish();
    }
    private void receive(CharSequence extra, CharSequence subject) {
        try {
            String text = extra == null ? "" : extra.toString();
            if (text.trim().isEmpty()) { Toast.makeText(this, "Nothing to save", Toast.LENGTH_SHORT).show(); return; }
            String found = null;
            try { found = Capture.firstUrl(text); } catch (IllegalArgumentException noLink) { }
            if (found == null) CaptureService.enqueue(this, "text", "", "", text);
            else {
                String title = Capture.chooseTitle(subject == null ? "" : subject.toString(), Capture.leadingText(text, found), "", Capture.cleanUrl(found));
                CaptureService.enqueue(this, "link", Capture.cleanUrl(found), title, text);
            }
            dispatch(this); // the capture is safe in the queue either way
        } catch (Exception e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
