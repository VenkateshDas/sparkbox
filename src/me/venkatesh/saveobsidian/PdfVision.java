package me.venkatesh.saveobsidian;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import org.json.JSONException;
import org.json.JSONObject;

/** Keeps native inference and its memory out of the process that owns the inbox and UI. */
final class PdfVision implements AutoCloseable {
    private final Context app;
    private ServiceConnection connection;
    private CompletableFuture<IBinder> binder;

    PdfVision(Context context) { app = context.getApplicationContext(); }

    boolean available() { return PdfCapture.modelReady(app); }

    String describe(byte[] jpeg, boolean scan) throws IOException {
        File image = File.createTempFile("pdf-figure-", ".jpg", app.getCacheDir());
        try {
            Files.write(image.toPath(), jpeg);
            try (var fd = ParcelFileDescriptor.open(image, ParcelFileDescriptor.MODE_READ_ONLY)) {
                return call(PdfVisionService.DESCRIBE, 55_000, request -> {
                    fd.writeToParcel(request, 0);
                    request.writeInt(scan ? 1 : 0);
                });
            }
        } finally { image.delete(); }
    }

    JSONObject plan(String evidence, String title, String why, String ideas, String topics) throws IOException {
        String result = call(PdfVisionService.PLAN, 100_000, request -> {
            request.writeString(IngestText.pdfSummaryPrompt(evidence, 6200));
            request.writeString(cap(title, 160));
            request.writeString(cap(why, 350));
            request.writeString(cap(ideas, 350));
            request.writeString(cap(topics, 400));
        });
        try { return new JSONObject(result); }
        catch (JSONException invalid) { throw new IOException("Local PDF model returned unreadable notes.", invalid); }
    }

    private static String cap(String text, int max) { return text.substring(0, Math.min(text.length(), max)); }

    private IBinder connect() throws IOException, ExecutionException, InterruptedException, TimeoutException {
        if (connection == null) {
            CompletableFuture<IBinder> next = new CompletableFuture<>();
            ServiceConnection service = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder remote) { next.complete(remote); }
                @Override public void onServiceDisconnected(ComponentName name) { next.completeExceptionally(new IOException("PDF worker disconnected")); }
                @Override public void onBindingDied(ComponentName name) { next.completeExceptionally(new IOException("PDF worker could not bind")); }
                @Override public void onNullBinding(ComponentName name) { next.completeExceptionally(new IOException("PDF worker unavailable")); }
            };
            if (!app.bindService(new Intent(app, PdfVisionService.class), service, Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND))
                throw new IOException("Could not start the local PDF worker.");
            connection = service;
            binder = next;
        }
        return binder.get(10, TimeUnit.SECONDS);
    }

    private String call(int code, long timeout, Consumer<Parcel> input) throws IOException {
        if (!available()) throw new IOException("The PDF model is unloaded.");
        try {
            IBinder worker = connect();
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try (ParcelFileDescriptor writer = pipe[1];
                 var reader = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                 DataInputStream reply = new DataInputStream(new java.io.BufferedInputStream(PdfCapture.timed(
                     reader, pipe[0], timeout, () -> Tagger.halt() || !PdfModel.enabled(app), "Local PDF inference")))) {
                Parcel request = Parcel.obtain();
                try {
                    writer.writeToParcel(request, 0);
                    input.accept(request);
                    if (!worker.transact(code, request, null, IBinder.FLAG_ONEWAY)) throw new IOException("Local PDF worker unavailable.");
                } finally { request.recycle(); }
                writer.close();
                if (!reply.readBoolean()) throw new IOException(reply.readUTF());
                return reply.readUTF();
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Processing paused", stop);
        } catch (android.os.RemoteException | ExecutionException | TimeoutException | EOFException stopped) {
            close();
            throw new IOException("The local PDF model process stopped unexpectedly. Retry the capture or unload the PDF model.", stopped);
        } catch (IOException | RuntimeException failed) {
            close(); // in particular, retry text on a fresh engine after a figure call fails
            throw failed;
        }
    }

    @Override public void close() {
        if (connection == null) return;
        IBinder remote = binder.isCompletedExceptionally() ? null : binder.getNow(null);
        CompletableFuture<Void> exited = new CompletableFuture<>();
        IBinder.DeathRecipient death = () -> exited.complete(null);
        try {
            if (remote != null) remote.linkToDeath(death, 0);
            else exited.complete(null);
        } catch (android.os.RemoteException gone) { exited.complete(null); }
        ServiceConnection bound = connection;
        connection = null;
        binder = null;
        PdfCapture.unbind(app, bound);
        // A text retry can bind straight away. Give the old worker time to release native state.
        try { exited.get(2, TimeUnit.SECONDS); }
        catch (InterruptedException stop) { Thread.currentThread().interrupt(); }
        catch (ExecutionException | TimeoutException ignored) { }
        finally {
            if (remote != null && !exited.isDone()) {
                try { remote.unlinkToDeath(death, 0); }
                catch (java.util.NoSuchElementException gone) { /* the worker died while unlinking */ }
            }
        }
    }
}
