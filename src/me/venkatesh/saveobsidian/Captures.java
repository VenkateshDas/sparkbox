package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Files each tagged link as its own Markdown note in one vault folder the user picked (Sources/Captures). This is the only place Sparkbox
 * writes notes into the vault, and it only ever rewrites notes it created itself. The inbox keeps working next to it.
 */
final class Captures {
    static String folder(Context c) { return c.getSharedPreferences("capture", 0).getString("captures", ""); }
    static boolean ready(Context c) { return !folder(c).isEmpty(); }

    static String label(Context c) {
        try { String id = DocumentsContract.getTreeDocumentId(Uri.parse(folder(c))); return id.substring(id.indexOf(':') + 1); }
        catch (RuntimeException unreadable) { return "Chosen folder"; }
    }

    /** Creates or updates up to ten notes per run. A note is rewritten only when its summary, Why, Ideas or page text changed. */
    static void file(Context c) throws Exception {
        if (!ready(c)) return;
        Uri tree = Uri.parse(folder(c));
        boolean writable = false;
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) if (p.getUri().equals(tree) && p.isWritePermission()) writable = true;
        if (!writable) throw new IOException("Write access to the Captures folder was lost. Choose the folder again in Settings.");
        android.content.ContentResolver resolver = c.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        SharedPreferences filed = c.getSharedPreferences("filed", 0);
        int written = 0;
        for (InboxFile.Entry e : InboxFile.parse(CaptureService.readText(c))) {
            if (written >= 10 || Tagger.halt || SetupActivity.editing) return;
            if (e.text || e.url.isEmpty() || !e.file.isEmpty() || e.type.isEmpty() || e.heading.isEmpty()) continue; // links that are tagged and titled
            String key = Long.toHexString(Index.key(e.time + "|" + e.url));
            byte[] bytes = Enrich.sourceNote(e.heading, e.url, e.time, e.tags, e.tldr, e.why, e.ideas, Content.load(c, e.url, 400000)).getBytes(StandardCharsets.UTF_8);
            String hash = Long.toHexString(Index.key(new String(bytes, StandardCharsets.UTF_8)));
            String[] have = filed.getString(key, "").split("\\|", 2);
            boolean ours = have.length == 2;
            if (ours && have[1].equals(hash)) continue;
            String name = ours ? have[0] : Enrich.noteName(e.time, e.heading);
            Object[] existing = Attach.lookup(c, tree, name);
            if (existing != null && !ours) { // a different file already has this name: never touch it
                name = name.replaceFirst("\\.md$", "") + " " + key.substring(0, Math.min(4, key.length())) + ".md";
                existing = Attach.lookup(c, tree, name);
                if (existing != null) continue;
            }
            Uri doc;
            if (existing != null) doc = (Uri) existing[0];
            else {
                doc = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", name); // octet-stream keeps the .md name exactly
                if (doc == null) throw new IOException("Cannot create a note in the Captures folder");
            }
            try (OutputStream out = resolver.openOutputStream(doc, "wt")) {
                if (out == null) throw new IOException("Captures folder unavailable");
                out.write(bytes);
            }
            String actual = name;
            try (Cursor q = resolver.query(doc, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (q == null || !q.moveToFirst() || q.getLong(1) != bytes.length) throw new IOException("The note in your vault does not match what was written. Check the Captures folder.");
                actual = q.getString(0);
            }
            filed.edit().putString(key, actual + "|" + hash).apply();
            Usage.hit(c, "filed");
            written++;
        }
    }
}
