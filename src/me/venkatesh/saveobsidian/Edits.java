package me.venkatesh.saveobsidian;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.util.function.Consumer;

/** Changes one section of a vault note off the main thread: read fresh text, edit it, write it back only if the file is unchanged, and retry on a conflict. */
final class Edits {
    private Edits() { }

    /** done runs on the main thread with "" on success or a short reason. */
    static void section(Context c, String path, String heading, String body, String[] after, Consumer<String> done) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!VaultWrite.writable(c)) { done.accept("Allow write access to your vault folder in Setup."); return; }
        new Thread(() -> {
            String result = "";
            try {
                for (int attempt = 0; ; attempt++) {
                    String before = VaultWrite.read(c, path);
                    if (before == null) { result = "That note is no longer in the vault."; break; }
                    String next = NoteEdit.setSection(before, heading, body, after);
                    if (next.equals(before)) break;
                    try { VaultWrite.write(c, path, before, next); break; }
                    catch (VaultWrite.Stale changed) { if (attempt >= 2) { result = "That note changed while you were editing. Try again."; break; } }
                }
                if (result.isEmpty()) VaultStore.scan(c); // re-reads only the changed file
            } catch (Exception e) { result = "Could not save: " + e.getMessage(); }
            final String message = result;
            main.post(() -> done.accept(message));
        }).start();
    }

    /** Asks first, then moves the note to the vault's .trash folder. after runs on the main thread once it is gone. */
    static void confirmDelete(final android.app.Activity host, final String path, final String title, final Runnable after) {
        new android.app.AlertDialog.Builder(host).setTitle("Delete this note?")
            .setMessage("\u201c" + title + "\u201d moves to the .trash folder in your vault, so Obsidian can restore it. Its cards go with it. Topics and people keep what they learned from it.")
            .setPositiveButton("Delete", (d, k) -> delete(host, path, problem -> {
                if (problem.isEmpty()) { android.widget.Toast.makeText(host, "Deleted", android.widget.Toast.LENGTH_SHORT).show(); after.run(); }
                else new android.app.AlertDialog.Builder(host).setTitle("Not deleted").setMessage(problem).setPositiveButton("OK", null).show();
            })).setNegativeButton("Cancel", null).show();
    }

    /** done runs on the main thread with "" on success or a short reason. */
    static void delete(Context c, String path, Consumer<String> done) {
        final Handler main = new Handler(Looper.getMainLooper());
        if (!VaultWrite.writable(c)) { done.accept("Allow write access to your vault folder in Settings."); return; }
        new Thread(() -> {
            String result = "";
            try {
                String before = VaultWrite.read(c, path);
                if (before != null) VaultWrite.trash(c, path, before);
                VaultStore.scan(c);
                InboxWidget.refresh(c);
            } catch (VaultWrite.Stale changed) { result = "That note changed just now. Open it again and retry."; }
            catch (Exception e) { result = "Could not delete: " + e.getMessage(); }
            final String message = result;
            main.post(() -> done.accept(message));
        }).start();
    }
}
