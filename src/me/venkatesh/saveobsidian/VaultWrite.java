package me.venkatesh.saveobsidian;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Writes into the vault the user granted write access to. Existing files are only changed from the exact text that was read (compare before
 * write), a private copy of the old text is kept first, and every write is read back. Used only by Ingest.
 */
final class VaultWrite {
    static final class Stale extends IOException { Stale(String path) { super(path + " changed while it was being edited. It will be tried again."); } }

    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME};
    private static final String RUN = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());

    static boolean writable(Context c) {
        if (!VaultStore.ready(c)) return false;
        Uri tree = Uri.parse(VaultStore.tree(c));
        for (android.content.UriPermission p : c.getContentResolver().getPersistedUriPermissions()) if (p.getUri().equals(tree) && p.isWritePermission()) return true;
        return false;
    }

    private static Uri tree(Context c) { return Uri.parse(VaultStore.tree(c)); }

    /** Id of the child with this name, or null when there is none. A provider that cannot list the folder throws: "unreadable" must never look like "missing", or a write would duplicate a file or drop a change. */
    private static String child(Context c, Uri tree, String parentId, String name) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor q = c.getContentResolver().query(children, COLUMNS, null, null, null)) {
            if (q == null) throw new IOException("Vault unavailable");
            while (q.moveToNext()) if (name.equals(q.getString(1))) return q.getString(0);
        } catch (RuntimeException unreadable) { throw new IOException("Vault unavailable: " + unreadable.getMessage()); }
        return null;
    }

    /** Document id of the file or folder at this vault path ("Knowledge/Topics/X.md"), or null. */
    private static String find(Context c, Uri tree, String path) throws IOException {
        String id = DocumentsContract.getTreeDocumentId(tree);
        for (String part : path.split("/")) { id = child(c, tree, id, part); if (id == null) return null; }
        return id;
    }

    /** Text of the file at this path, or null when it does not exist. */
    static String read(Context c, String path) throws Exception {
        Uri tree = tree(c);
        String id = find(c, tree, path);
        return id == null ? null : VaultStore.text(c, DocumentsContract.buildDocumentUriUsingTree(tree, id));
    }

    /**
     * Creates the file (before == null; missing folders are created) or replaces it, but only if it still holds exactly "before".
     * Throws Stale when it changed, and IOException when a new file's name is already taken.
     */
    static void write(Context c, String path, String before, String after) throws Exception { write(c, path, before, after, true); }

    /** Same checks as write, without the private backup. For edits that change one small block of a note, such as a graded card. */
    static void writeNoBackup(Context c, String path, String before, String after) throws Exception { write(c, path, before, after, false); }

    private static void write(Context c, String path, String before, String after, boolean backup) throws Exception {
        VaultLock.L.lock();
        try { locked(c, path, before, after, backup); } finally { VaultLock.L.unlock(); }
    }

    private static void locked(Context c, String path, String before, String after, boolean backup) throws Exception {
        Uri tree = tree(c);
        android.content.ContentResolver resolver = c.getContentResolver();
        String[] parts = path.split("/");
        String parent = DocumentsContract.getTreeDocumentId(tree);
        for (int i = 0; i < parts.length - 1; i++) {
            String next = child(c, tree, parent, parts[i]);
            if (next == null) {
                Uri made = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), DocumentsContract.Document.MIME_TYPE_DIR, parts[i]);
                if (made == null) throw new IOException("Cannot create the folder " + parts[i]);
                next = DocumentsContract.getDocumentId(made);
            }
            parent = next;
        }
        String name = parts[parts.length - 1], id = child(c, tree, parent, name);
        Uri doc;
        if (before == null) {
            if (id != null) throw new IOException(path + " already exists");
            doc = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, parent), "application/octet-stream", name); // keeps the .md name exactly
            if (doc == null) throw new IOException("Cannot create " + path);
        } else {
            if (id == null) throw new Stale(path);
            doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
            String now = VaultStore.text(c, doc);
            if (!now.equals(before)) throw new Stale(path);
            if (backup) {
                File copy = new File(new File(c.getFilesDir(), "ingest-backup/" + RUN), path.replace('/', '_'));
                copy.getParentFile().mkdirs();
                Files.write(copy.toPath(), now.getBytes(StandardCharsets.UTF_8)); // the old text, in case an edit has to be undone by hand
            }
        }
        try (OutputStream out = resolver.openOutputStream(doc, "wt")) {
            if (out == null) throw new IOException("Vault unavailable");
            out.write(after.getBytes(StandardCharsets.UTF_8));
        }
        if (!VaultStore.text(c, doc).equals(after)) throw new IOException(path + " does not match what was written. Check the file.");
    }

    /**
     * Deletes a note the way Obsidian does: a copy goes into the vault's .trash folder first (so it can be put back), then the note is removed and checked gone.
     * Only if the note still holds exactly "before". A private copy is kept too. A note that is already gone counts as deleted.
     */
    static void trash(Context c, String path, String before) throws Exception {
        VaultLock.L.lock();
        try { trashLocked(c, path, before); } finally { VaultLock.L.unlock(); }
    }

    private static void trashLocked(Context c, String path, String before) throws Exception {
        Uri tree = tree(c);
        String id = find(c, tree, path);
        if (id == null) return;
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, id);
        if (!VaultStore.text(c, doc).equals(before)) throw new Stale(path);
        File copy = new File(new File(c.getFilesDir(), "ingest-backup/" + RUN), path.replace('/', '_'));
        copy.getParentFile().mkdirs();
        Files.write(copy.toPath(), before.getBytes(StandardCharsets.UTF_8));
        String file = path.substring(path.lastIndexOf('/') + 1), name = file;
        if (find(c, tree, ".trash/" + name) != null) name = file.replaceFirst("(\\.md)?$", " " + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".md");
        write(c, ".trash/" + name, null, before, false); // verified by the read-back inside write
        if (!DocumentsContract.deleteDocument(c.getContentResolver(), doc)) throw new IOException("The vault would not delete " + file);
        if (find(c, tree, path) != null) throw new IOException(file + " is still in the vault");
    }

    /**
     * The inbox is a stored file address (also set the first time the vault folder is chosen). When the Sources folder was moved (Sources to Knowledge/Sources) that address goes dead and
     * every capture fails. If the stored inbox cannot be opened and the vault folder is writable, point it at the Inbox.md found in the vault now.
     */
    static void healInbox(Context c) {
        try {
            android.content.SharedPreferences prefs = c.getSharedPreferences("capture", 0);
            String saved = prefs.getString("inbox", "");
            if (!writable(c)) return;
            if (!saved.isEmpty()) try (java.io.InputStream in = c.getContentResolver().openInputStream(Uri.parse(saved))) { if (in != null) return; } catch (Exception dead) { /* fall through */ }
            Uri tree = tree(c);
            for (String path : new String[]{Layout.NEW + "/Inbox.md", Layout.OLD + "/Inbox.md"}) {
                String id = find(c, tree, path);
                if (id != null) { prefs.edit().putString("inbox", DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()).putString("name", "Inbox.md").commit(); return; }
            }
        } catch (Exception unreadable) { }
    }

    /** Deletes backups older than two weeks. */
    static void pruneBackups(Context c) {
        File[] runs = new File(c.getFilesDir(), "ingest-backup").listFiles();
        if (runs == null) return;
        long old = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
        for (File run : runs) if (run.lastModified() < old) { File[] files = run.listFiles(); if (files != null) for (File f : files) f.delete(); run.delete(); }
    }
}
