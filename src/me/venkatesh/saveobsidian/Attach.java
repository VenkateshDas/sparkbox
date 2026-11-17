package me.venkatesh.saveobsidian;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.UUID;

/** Photos and documents: a private copy at share time, then one verified copy into the vault folder the user picked. */
final class Attach {
    static final long MAX = 50L * 1024 * 1024;
    private static final String[] COLUMNS = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE};

    static String folder(Context c) { return c.getSharedPreferences("capture", 0).getString("attach", ""); }
    static boolean ready(Context c) { return !folder(c).isEmpty(); }

    /** "Documents/Obsidian/Sources/Attachments" for the Settings line. */
    static String label(Context c) {
        try { String id = DocumentsContract.getTreeDocumentId(Uri.parse(folder(c))); return id.substring(id.indexOf(':') + 1); }
        catch (RuntimeException unreadable) { return "Chosen folder"; }
    }

    static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String mime(String name) {
        String type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension(name));
        return type == null ? "application/octet-stream" : type;
    }

    static String displayName(Context c, Uri uri) {
        try (Cursor q = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (q != null && q.moveToFirst() && q.getString(0) != null) return q.getString(0);
        } catch (RuntimeException ignored) { }
        String last = uri.getLastPathSegment();
        return last == null ? "file" : last;
    }

    /** Streams the shared content into the private folder before the sender's permission expires. */
    static File stash(Context c, Uri source) throws IOException {
        File directory = new File(c.getFilesDir(), "files");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create the file queue");
        File copy = new File(directory, UUID.randomUUID().toString());
        try (InputStream in = c.getContentResolver().openInputStream(source); OutputStream out = new java.io.FileOutputStream(copy)) {
            if (in == null) throw new IOException("The sender did not share the file");
            byte[] buffer = new byte[65536]; long total = 0; int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > MAX) throw new IOException("Files over 50 MB are not saved. Nothing was copied.");
                out.write(buffer, 0, n);
            }
        } catch (IOException | RuntimeException e) { copy.delete(); throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage()); }
        return copy;
    }

    private static Uri tree(Context c) throws IOException {
        if (!ready(c)) throw new IOException("Choose the attachments folder in Settings, then retry.");
        return Uri.parse(folder(c));
    }

    /** {document uri, size} of a file directly inside the folder, or null. */
    static Object[] lookup(Context c, Uri tree, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor q = c.getContentResolver().query(children, COLUMNS, null, null, null)) {
            while (q != null && q.moveToNext()) if (name.equals(q.getString(1)))
                return new Object[]{DocumentsContract.buildDocumentUriUsingTree(tree, q.getString(0)), q.isNull(2) ? -1L : q.getLong(2)};
        } catch (RuntimeException unreadable) { }
        return null;
    }

    static Uri find(Context c, String name) {
        try { Object[] found = lookup(c, tree(c), name); return found == null ? null : (Uri) found[0]; }
        catch (IOException none) { return null; }
    }

    /** Copies source into the folder under name (or reuses an identical earlier copy). Returns the name the provider really gave it. */
    static String store(Context c, File source, String name, String mime) throws Exception {
        Uri tree = tree(c);
        android.content.ContentResolver resolver = c.getContentResolver();
        Object[] existing = lookup(c, tree, name);
        if (existing != null) {
            if ((Long) existing[1] == source.length()) return name; // a retry after an interrupted save
            DocumentsContract.deleteDocument(resolver, (Uri) existing[0]); // partial copy of this very file
        }
        Uri out = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree)), mime, name);
        if (out == null) throw new IOException("Cannot create the file in the attachments folder");
        try (InputStream in = new FileInputStream(source); OutputStream sink = resolver.openOutputStream(out, "w")) {
            if (sink == null) throw new IOException("Attachments folder unavailable");
            byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) sink.write(buffer, 0, n);
        }
        try (Cursor q = resolver.query(out, new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (q == null || !q.moveToFirst() || q.getLong(1) != source.length()) {
                try { DocumentsContract.deleteDocument(resolver, out); } catch (Exception ignored) { }
                throw new IOException("The copy in your vault does not match the original. Nothing was saved.");
            }
            return q.getString(0);
        }
    }

    /** Whole file, refusing anything larger than max bytes. */
    static byte[] read(Context c, Uri uri, int max) throws IOException {
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("File unavailable");
            ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[65536]; int n;
            while ((n = in.read(buffer)) != -1) { out.write(buffer, 0, n); if (out.size() > max) throw new IOException("File too large to describe"); }
            return out.toByteArray();
        }
    }

    /** Bounded decode of downloaded images; inspect dimensions before allocating pixels. */
    static byte[] jpeg(byte[] data, int edge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > edge)
            options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (bitmap == null) return null;
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, out);
            return out.toByteArray();
        } finally { bitmap.recycle(); }
    }

    /** Photo shrunk so its longest side is at most edge pixels, as JPEG. Null when it cannot be decoded. */
    static byte[] jpeg(Context c, Uri uri, int edge) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(in, null, bounds); }
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= edge) sample *= 2;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        Bitmap bitmap;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(in, null, options); }
        if (bitmap == null) return null;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        bitmap.recycle();
        return out.toByteArray();
    }
}
