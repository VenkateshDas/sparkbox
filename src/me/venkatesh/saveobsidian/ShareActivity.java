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
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        publishShortcut(this);
        if (state == null) receive(getIntent().getCharSequenceExtra(Intent.EXTRA_TEXT), getIntent().getCharSequenceExtra(Intent.EXTRA_SUBJECT)); // a recreated activity must not queue the same share twice
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
            if (getSharedPreferences("capture", 0).getString("inbox", "").isEmpty())
                startActivity(new Intent(this, SetupActivity.class).putExtra("waiting", true)); // the capture is safe in the queue
            else CaptureService.start(this, null);
        } catch (Exception e) {
            Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
