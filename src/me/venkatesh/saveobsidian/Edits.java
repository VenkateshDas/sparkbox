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
}
