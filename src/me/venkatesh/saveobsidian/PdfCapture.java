package me.venkatesh.saveobsidian;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** PDF bytes go only to Android's isolated renderer. */
final class PdfCapture {
    private record Image(int page, boolean scan, byte[] jpeg) { }
    private record Document(String text, int scans, int candidates, List<Image> images) { }

    static boolean supported() { return android.os.Build.VERSION.SDK_INT >= 35; }

    static String read(Context context, byte[] bytes) throws IOException {
        return read(context, new java.io.ByteArrayInputStream(bytes));
    }

    static String read(Context context, android.net.Uri uri) throws IOException {
        try (var input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException("PDF unavailable");
            return read(context, input);
        }
    }

    private static String read(Context context, java.io.InputStream input) throws IOException {
        Context app = context.getApplicationContext();
        File temp = File.createTempFile("capture-", ".pdf", app.getCacheDir());
        Document pdf;
        try {
            try (var out = Files.newOutputStream(temp.toPath())) {
                byte[] buffer = new byte[64 * 1024];
                int total = 0;
                for (int n; (n = input.read(buffer)) != -1;) {
                    if (Tagger.halt()) throw new IOException("PDF reading paused");
                    total += n;
                    if (total > IngestText.PDF_BYTES) throw new IOException("PDF exceeds the 40 MiB processing limit.");
                    out.write(buffer, 0, n);
                }
            }
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)) {
                pdf = extract(app, fd, false);
            }
        } finally { temp.delete(); }
        if (Tagger.halt()) throw new IOException("Processing paused");
        if (pdf.text().isEmpty())
            throw new IOException("No readable text found in the PDF text layer or Latin-script OCR.");
        StringBuilder notes = new StringBuilder();
        if (pdf.scans() > 0) notes.append("\n- ").append(pdf.scans()).append(" page(s) had no readable text after OCR.");
        return pdf.text() + (notes.length() == 0 ? "" : IngestText.PDF_VISUAL_HEADING + notes);
    }

    private static Document extract(Context c, ParcelFileDescriptor pdf, boolean figures) throws IOException {
        CompletableFuture<IBinder> binder = new CompletableFuture<>();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { binder.complete(service); }
            @Override public void onServiceDisconnected(ComponentName name) { binder.completeExceptionally(new IOException("PDF renderer stopped")); }
            @Override public void onNullBinding(ComponentName name) { binder.completeExceptionally(new IOException("PDF renderer unavailable")); }
        };
        if (!c.bindService(new Intent(c, PdfRenderService.class), connection, Context.BIND_AUTO_CREATE))
            throw new IOException("Could not start the PDF renderer");
        try {
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor writer = pipe[1];
                 var input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                 DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(timed(input, pipe[0], 240_000, Tagger::halt, "PDF rendering")))) {
                Parcel args = Parcel.obtain();
                try {
                    pdf.writeToParcel(args, 0);
                    writer.writeToParcel(args, 0);
                    args.writeInt(figures ? 1 : 0);
                    if (!binder.get(10, TimeUnit.SECONDS).transact(PdfRenderService.RENDER, args, null, IBinder.FLAG_ONEWAY))
                        throw new IOException("PDF renderer unavailable");
                } finally { args.recycle(); }
                writer.close();
                int pages = in.readInt();
                if (pages == -1) throw new IOException(in.readUTF());
                if (pages < 0) throw new IOException("Invalid PDF page count");
                StringBuilder text = new StringBuilder();
                int scans = 0;
                com.google.mlkit.vision.text.TextRecognizer ocr = null;
                try {
                    for (int i = 0; i < Math.min(pages, PdfRenderService.MAX_PAGES); i++) {
                        int length = in.readInt();
                        boolean scan = length == -2;
                        String body;
                        if (scan) {
                            byte[] jpeg = read(in, in.readInt(), PdfRenderService.MAX_JPEG);
                            if (text.length() >= PdfRenderService.MAX_TEXT) continue;
                            if (ocr == null) ocr = com.google.mlkit.vision.text.TextRecognition.getClient(
                                com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS);
                            body = recognize(ocr, jpeg);
                            if (body.trim().isEmpty()) scans++;
                        } else body = new String(read(in, length, 4 * PdfRenderService.MAX_TEXT), StandardCharsets.UTF_8);
                        if (body.trim().isEmpty() || text.length() >= PdfRenderService.MAX_TEXT) continue;
                        text.append("### Page ").append(i + 1).append(scan ? " (OCR)\n" : "\n");
                        String clean = body.replaceAll("(?m)^(\\h*)##(?=\\h|$)", "$1###");
                        text.append(clean, 0, Math.min(clean.length(), Math.max(0, PdfRenderService.MAX_TEXT - text.length()))).append('\n');
                    }
                } finally { if (ocr != null) ocr.close(); }
                if (text.length() > 0 && (pages > PdfRenderService.MAX_PAGES || text.length() >= PdfRenderService.MAX_TEXT))
                    text.append("\n[PDF text limited to 150 pages or 400,000 characters.]\n");
                int candidates = in.readInt(), count = in.readInt();
                if (count < 0 || count > PdfRenderService.MAX_IMAGES) throw new IOException("Invalid rendered page count");
                List<Image> images = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int page = in.readInt();
                    boolean scan = in.readBoolean();
                    byte[] jpeg = read(in, in.readInt(), PdfRenderService.MAX_JPEG);
                    if (jpeg.length > 0) images.add(new Image(page, scan, jpeg));
                }
                return new Document(text.toString().trim(), scans, candidates, images);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            throw new IOException("Processing paused", stop);
        } catch (java.io.EOFException failed) {
            throw new IOException("The PDF renderer stopped before finishing", failed);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException | android.os.RemoteException failed) {
            throw new IOException("The PDF renderer could not start", failed);
        } finally { unbind(c, connection); }
    }

    private static String recognize(com.google.mlkit.vision.text.TextRecognizer ocr, byte[] jpeg)
            throws InterruptedException, IOException {
        if (jpeg.length == 0) throw new IOException("Could not render a scanned PDF page");
        var bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth < 1 || bounds.outHeight < 1 || Math.max(bounds.outWidth, bounds.outHeight) > 1600)
            throw new IOException("Invalid PDF scan dimensions");
        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bitmap == null) throw new IOException("Could not decode a scanned PDF page");
        var task = ocr.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bitmap, 0));
        task.addOnCompleteListener(Runnable::run, ignored -> bitmap.recycle());
        try { return com.google.android.gms.tasks.Tasks.await(task, 30, TimeUnit.SECONDS).getText(); }
        catch (java.util.concurrent.TimeoutException timeout) { throw new IOException("PDF scan recognition timed out", timeout); }
        catch (java.util.concurrent.ExecutionException failed) { throw new IOException("PDF scan recognition failed", failed); }
    }

    static void unbind(Context context, ServiceConnection connection) {
        try { context.unbindService(connection); }
        catch (java.util.NoSuchElementException gone) {
            // Android also unlinks its ServiceConnection listener when the worker has died.
            if (!isMissingDeathLink(gone.getMessage())) throw gone;
            android.util.Log.w("Sparkbox", "PDF worker already disconnected during unbind");
        }
    }

    static boolean isMissingDeathLink(String message) {
        return "Death link does not exist (NAME_NOT_FOUND)".equals(message) || "Death link does not exist".equals(message);
    }

    static java.io.InputStream timed(java.io.InputStream input, ParcelFileDescriptor descriptor, long timeout,
            java.util.function.BooleanSupplier stopped, String operation) {
        android.system.StructPollfd event = new android.system.StructPollfd();
        event.fd = descriptor.getFileDescriptor();
        event.events = (short) android.system.OsConstants.POLLIN;
        android.system.StructPollfd[] events = {event};
        long deadline = android.os.SystemClock.elapsedRealtime() + timeout;
        return new java.io.FilterInputStream(input) {
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                try {
                    do {
                        if (stopped.getAsBoolean()) throw new IOException(operation + " paused");
                        if (android.os.SystemClock.elapsedRealtime() >= deadline) throw new IOException(operation + " timed out");
                    } while (android.system.Os.poll(events, 1000) == 0);
                    return super.read(bytes, offset, length);
                } catch (android.system.ErrnoException failure) {
                    throw new IOException(operation + " pipe could not be read", failure);
                }
            }
        };
    }

    private static byte[] read(DataInputStream in, int length, int limit) throws IOException {
        if (length < 0 || length > limit) throw new IOException("Invalid PDF renderer reply");
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return bytes;
    }
}
