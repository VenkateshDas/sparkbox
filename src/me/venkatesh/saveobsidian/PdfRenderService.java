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
import androidx.annotation.RequiresApi;
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
            if (code != RENDER || android.os.Build.VERSION.SDK_INT < 35) return false;
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

    @RequiresApi(35)
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
            StringBuilder text = new StringBuilder();
            List<Page> candidates = new ArrayList<>();
            int count = Math.min(pdf.getPageCount(), MAX_PAGES), scans = 0;
            for (int i = 0; i < count; i++) {
                try (PdfRenderer.Page page = pdf.openPage(i)) {
                    StringBuilder body = new StringBuilder();
                    for (var block : page.getTextContents()) {
                        if (!block.getText().isBlank()) body.append(block.getText()).append('\n');
                        if (body.length() >= MAX_TEXT) break;
                    }
                    boolean scan = body.toString().isBlank();
                    if (scan) scans++;
                    else if (text.length() < MAX_TEXT) {
                        text.append("### Page ").append(i + 1).append('\n');
                        // Source headings belong inside Raw Content, never alongside its sections.
                        String pageText = body.toString().replaceAll("(?m)^(\\h*)##(?=\\h|$)", "$1###");
                        text.append(pageText, 0, Math.min(pageText.length(), MAX_TEXT - Math.min(text.length(), MAX_TEXT))).append('\n');
                    }
                    if (figures) {
                        int priority = scan ? 3 : !page.getImageContents().isEmpty() ? 2
                            : CAPTION.matcher(body).find() ? 1 : 0;
                        if (priority > 0) candidates.add(new Page(i, priority, scan));
                    }
                }
            }

            if (count < pdf.getPageCount() || text.length() >= MAX_TEXT)
                text.append("\n[PDF text limited to 150 pages or 400,000 characters.]\n");
            byte[] bytes = text.toString().trim().getBytes(StandardCharsets.UTF_8);
            sink.writeInt(bytes.length);
            sink.write(bytes);
            sink.writeInt(scans);
            sink.writeInt(candidates.size());
            candidates.sort(Comparator.comparingInt(Page::priority).reversed());
            List<Page> selected = new ArrayList<>(candidates.subList(0, Math.min(MAX_IMAGES, candidates.size())));
            selected.sort(Comparator.comparingInt(Page::index));
            sink.writeInt(selected.size());
            for (Page item : selected) {
                byte[] jpeg = pageImage(pdf, item.index());
                sink.writeInt(item.index() + 1);
                sink.writeBoolean(item.scan());
                sink.writeInt(jpeg.length);
                sink.write(jpeg);
            }
        }
    }
    @RequiresApi(35)
    private static byte[] pageImage(PdfRenderer renderer, int index) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            float scale = Math.min(2f, 960f / Math.max(1, Math.max(page.getWidth(), page.getHeight())));
            Bitmap image = Bitmap.createBitmap(Math.max(1, Math.round(page.getWidth() * scale)),
                Math.max(1, Math.round(page.getHeight() * scale)), Bitmap.Config.ARGB_8888);
            try {
                image.eraseColor(Color.WHITE);
                page.render(image, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                java.io.ByteArrayOutputStream jpeg = new java.io.ByteArrayOutputStream();
                if (image.compress(Bitmap.CompressFormat.JPEG, 85, jpeg) && jpeg.size() <= MAX_JPEG) return jpeg.toByteArray();
            } finally { image.recycle(); }
        } catch (RuntimeException failure) {
            android.util.Log.w("Sparkbox", "Could not render PDF figure page " + (index + 1), failure);
        }
        return new byte[0];
    }

}
