package me.venkatesh.saveobsidian;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.pdf.PdfRenderer;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Decodes untrusted PDFs in an isolated process with no vault or network permissions. */
public final class PdfRenderService extends Service {
    static final int RENDER = IBinder.FIRST_CALL_TRANSACTION;
    static final int MAX_TEXT = 400000, MAX_PAGES = 150, MAX_IMAGES = 3, MAX_JPEG = 2 * 1024 * 1024;
    private static final java.util.regex.Pattern CAPTION = java.util.regex.Pattern.compile(
        "(?im)^\\h*(?:fig(?:ure)?\\.?|table)\\h+\\d+[a-z]?(?:[.:]|\\h)");

    private final Binder renderer = new Binder() {
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
            if (code != RENDER) return false;
            try (ParcelFileDescriptor pdf = ParcelFileDescriptor.CREATOR.createFromParcel(data);
                 ParcelFileDescriptor output = ParcelFileDescriptor.CREATOR.createFromParcel(data);
                 DataOutputStream sink = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(output.dup()))) {
                render(pdf.dup(), sink, data.readInt() != 0);
            } catch (IOException | RuntimeException failure) {
                android.util.Log.w("Sparkbox", "Local PDF renderer failed", failure);
            }
            return true;
        }
    };

    @Override public IBinder onBind(Intent intent) { return renderer; }

    private record Page(int index, int priority, boolean scan) { }

    private static void render(ParcelFileDescriptor input, DataOutputStream sink, boolean figures) throws IOException {
        PdfRenderer pdf;
        try {
            pdf = new PdfRenderer(input);
        } catch (IOException | RuntimeException failure) {
            input.close();
            sink.writeInt(-1);
            sink.writeUTF(failure instanceof SecurityException ? "PDF is locked or encrypted" : "Android could not open this PDF");
            return;
        }
        try (pdf) {
            sink.writeInt(pdf.getPageCount());
            int emitted = 0;
            List<Page> candidates = new ArrayList<>();
            int count = Math.min(pdf.getPageCount(), MAX_PAGES);
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = pdf.openPage(i)) {
                    StringBuilder body = new StringBuilder();
                    if (android.os.Build.VERSION.SDK_INT >= 35) for (var block : page.getTextContents()) {
                        String value = block.getText();
                        if (!value.isBlank()) body.append(value, 0, Math.min(value.length(), MAX_TEXT - body.length())).append('\n');
                        if (body.length() >= MAX_TEXT) break;
                    }
                    boolean scan = body.length() == 0;
                    if (scan && emitted < MAX_TEXT) {
                        sink.writeInt(-2);
                        byte[] jpeg = pageImage(page, 1600);
                        sink.writeInt(jpeg.length);
                        sink.write(jpeg);
                    } else {
                        String excerpt = body.substring(0, Math.min(body.length(), MAX_TEXT - emitted));
                        emitted += excerpt.length();
                        byte[] bytes = excerpt.getBytes(StandardCharsets.UTF_8);
                        sink.writeInt(bytes.length);
                        sink.write(bytes);
                    }
                    if (figures && !scan && android.os.Build.VERSION.SDK_INT >= 35) {
                        int priority = !page.getImageContents().isEmpty() ? 2
                            : CAPTION.matcher(body).find() ? 1 : 0;
                        if (priority > 0) candidates.add(new Page(i, priority, scan));
                    }
                }
            }

            sink.writeInt(candidates.size());
            candidates.sort(Comparator.comparingInt(Page::priority).reversed());
            List<Page> selected = new ArrayList<>(candidates.subList(0, Math.min(MAX_IMAGES, candidates.size())));
            selected.sort(Comparator.comparingInt(Page::index));
            sink.writeInt(selected.size());
            for (Page item : selected) {
                byte[] jpeg;
                try (PdfRenderer.Page page = pdf.openPage(item.index())) { jpeg = pageImage(page, 960); }
                sink.writeInt(item.index() + 1);
                sink.writeBoolean(item.scan());
                sink.writeInt(jpeg.length);
                sink.write(jpeg);
            }
        }
    }
    private static byte[] pageImage(PdfRenderer.Page page, int edge) {
        try {
            float scale = Math.min(2f, (float) edge / Math.max(1, Math.max(page.getWidth(), page.getHeight())));
            Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            try {
                image.eraseColor(Color.WHITE);
                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
                if (image.compress(Bitmap.CompressFormat.JPEG, 85, jpeg) && jpeg.size() <= MAX_JPEG) return jpeg.toByteArray();
            } finally { image.recycle(); }
        } catch (RuntimeException failure) {
            android.util.Log.w("Sparkbox", "Could not render PDF figure page " + (page.getIndex() + 1), failure);
        }
        return new byte[0];
    }

}
