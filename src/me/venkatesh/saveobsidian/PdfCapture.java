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

/** PDF bytes go only to Android's isolated renderer. The local model sees sampled page images. */
final class PdfCapture {
    private record Image(int page, boolean scan, byte[] jpeg) { }
    private record Document(String text, int scans, int candidates, List<Image> images) { }

    static boolean supported() { return android.os.Build.VERSION.SDK_INT >= 35; }

    static boolean modelReady(Context c) { return supported() && PdfModel.enabled(c) && PdfModel.file(c).isFile(); }

    static String read(Context context, byte[] bytes, PdfVision vision) throws IOException {
        if (!supported()) throw new IOException("Local PDF reading requires Android 15 or later");
        Context app = context.getApplicationContext();
        File temp = File.createTempFile("capture-", ".pdf", app.getCacheDir());
        Document pdf;
        try {
            Files.write(temp.toPath(), bytes);
            try (ParcelFileDescriptor fd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)) {
                pdf = extract(app, fd, vision != null && vision.available());
            }
        } finally { temp.delete(); }
        if (Tagger.halt()) throw new IOException("Processing paused");
        if (pdf.text().isBlank() && pdf.images().isEmpty())
            throw new IOException("PDF has no text layer. Install the local PDF vision model to read scanned pages.");
        StringBuilder notes = new StringBuilder();
        if (!pdf.images().isEmpty()) {
            try {
                for (Image image : pdf.images()) {
                    if (Tagger.halt()) throw new IOException("Processing paused");
                    String description = vision.describe(image.jpeg(), image.scan()).replaceAll("\\s+", " ").trim();
                    if (!description.isBlank()) notes.append("\n- Page ").append(image.page()).append(": ").append(description);
                }
            } catch (Exception | LinkageError visionFailure) {
                if (Tagger.halt()) throw new IOException("Processing paused", visionFailure);
                if (pdf.text().isBlank()) throw new IOException("The local vision model could not read this scan. Check the PDF vision model in Settings.", visionFailure);
                notes.append("\n- The local vision model could not finish. Extracted text is available.");
            }
        }
        if (pdf.text().isBlank() && notes.length() == 0) throw new IOException("The local vision model found no readable scan content");
        if (!notes.isEmpty() && pdf.candidates() > pdf.images().size())
            notes.append("\n- Visual analysis sampled ").append(pdf.images().size()).append(" of ").append(pdf.candidates()).append(" candidate pages.");
        if (pdf.scans() > 0 && pdf.images().isEmpty()) notes.append("\n- ").append(pdf.scans()).append(" page(s) had no extractable text.");
        return pdf.text() + (notes.isEmpty() ? "" : IngestText.PDF_VISUAL_HEADING + notes);
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
                 DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(timed(input, pipe[0], 45_000, Tagger::halt, "PDF rendering")))) {
                Parcel args = Parcel.obtain();
                try {
                    pdf.writeToParcel(args, 0);
                    writer.writeToParcel(args, 0);
                    args.writeInt(figures ? 1 : 0);
                    if (!binder.get(10, TimeUnit.SECONDS).transact(PdfRenderService.RENDER, args, null, IBinder.FLAG_ONEWAY))
                        throw new IOException("PDF renderer unavailable");
                } finally { args.recycle(); }
                writer.close();
                int length = in.readInt();
                if (length == -1) throw new IOException(in.readUTF());
                String text = new String(read(in, length, 4 * PdfRenderService.MAX_TEXT + 512), StandardCharsets.UTF_8);
                int scans = in.readInt(), candidates = in.readInt(), count = in.readInt();
                if (count < 0 || count > PdfRenderService.MAX_IMAGES) throw new IOException("Invalid rendered page count");
                List<Image> images = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    int page = in.readInt();
                    boolean scan = in.readBoolean();
                    byte[] jpeg = read(in, in.readInt(), PdfRenderService.MAX_JPEG);
                    if (jpeg.length > 0) images.add(new Image(page, scan, jpeg));
                }
                return new Document(text, scans, candidates, images);
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
