package me.venkatesh.saveobsidian;

import android.app.Instrumentation;
import android.os.Bundle;

/** Manual live checks. Uses the phone's key without exporting it. Only recover=true writes the vault. */
public class ReelTest extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        android.content.Context context = getTargetContext();
        try {
            if ("scout".equals(arguments.getString("suite"))) {
                ScoutDeviceTest.run(this, arguments, result);
            } else if ("pdf".equals(arguments.getString("suite"))) {
                if (!PdfCapture.supported()) throw new IllegalStateException("Android 15 or later required");
                if ("token-economizer".equals(arguments.getString("fixture"))) {
                    var input = java.nio.file.Files.readAllBytes(new java.io.File(context.getCacheDir(), "pdf-fixture.pdf").toPath());
                    String text = PdfCapture.read(context, input);
                    if (!text.contains("TokenEconomizer") || !text.contains("### Page 26") || text.length() < 50_000)
                        throw new AssertionError("Android did not extract the full TokenEconomizer paper");
                    result.putInt("characters", text.length());
                    result.putString("result", "PASS");
                    finish(-1, result);
                    return;
                }
                byte[] input;
                android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
                try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    for (int i = 1; i <= 6; i++) {
                        var page = pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(500, 700, i).create());
                        var ink = new android.graphics.Paint();
                        ink.setTextSize(24);
                        if (i > 2) {
                            var bitmap = android.graphics.Bitmap.createBitmap(500, 100, android.graphics.Bitmap.Config.ARGB_8888);
                            bitmap.eraseColor(android.graphics.Color.WHITE);
                            new android.graphics.Canvas(bitmap).drawText("Scanned orchard number " + i, 30, 55, ink);
                            page.getCanvas().drawBitmap(bitmap, 0, 0, null);
                        } else page.getCanvas().drawText("Page " + i + " text extraction check", 30, 55, ink);
                        pdf.finishPage(page);
                    }
                    pdf.writeTo(out);
                    input = out.toByteArray();
                } finally { pdf.close(); }
                String text = PdfCapture.read(context, input);
                if (!text.contains("Page 1 text extraction check") || !text.contains("Page 2 text extraction check"))
                    throw new AssertionError("The isolated PDF renderer did not return both pages");
                for (int i = 3; i <= 6; i++) {
                    if (!text.contains("### Page " + i + " (OCR)") || !text.contains("Scanned orchard number " + i))
                        throw new AssertionError("Missing scanned page " + i + ": " + text);
                }
            } else if ("true".equals(arguments.getString("recover"))) {
                int count = Ingest.retryAll(context);
                long deadline = System.currentTimeMillis() + 15 * 60000L;
                int remaining;
                do {
                    Thread.sleep(1000);
                    remaining = 0;
                    boolean working = false;
                    for (InboxFile.Entry entry : InboxFile.parse(CaptureService.readText(context))) {
                        if (Instagram.shortcode(entry.url).isEmpty()) continue;
                        remaining++;
                        if (Ingest.queued(context, entry) || Ingest.stage(context, entry) >= 0) working = true;
                    }
                    if (remaining == 0 || !working) break;
                } while (System.currentTimeMillis() < deadline);
                result.putInt("retried", count);
                result.putInt("remaining", remaining);
                if (remaining > 0) throw new AssertionError("Instagram captures still pending; inspect ingest errors");
            } else if (arguments.containsKey("file")) {
                String file = arguments.getString("file");
                byte[] data = java.nio.file.Files.readAllBytes(new java.io.File(context.getFilesDir(), file).toPath());
                String[] description = Model.describeVideo(Model.key(context), file, data);
                if (description == null || description[2].trim().isEmpty()) throw new AssertionError("No video summary");
                android.content.Intent share = new android.content.Intent(android.content.Intent.ACTION_SEND).setType("video/mp4").setPackage(context.getPackageName());
                if (context.getPackageManager().resolveActivity(share, 0) == null) throw new AssertionError("Video share receiver missing");
                result.putString("summary", description[2]);
            } else {
                String url = arguments.getString("url", "");
                if (Instagram.shortcode(url).isEmpty()) throw new IllegalArgumentException("Supply -e url with a public Instagram post");
                boolean watch = "true".equals(arguments.getString("watch"));
                Fetch.Result page = Fetch.page(context, url, Model.key(context), watch);
                if (watch) page.text = Visuals.read(context, Model.key(context), url, page);
                result.putString("title", page.title);
                result.putString("problem", page.problem);
                result.putInt("characters", page.text.length());
                if (page.text.isEmpty() && page.media.isEmpty()) throw new AssertionError("No usable reel content: " + page.problem);
                if (watch && (!page.text.contains("#### Video ") && !page.text.contains("#### Image "))) throw new AssertionError("Reel was not watched");
            }
            result.putString("result", "PASS");
            finish(-1, result);
        } catch (Throwable failed) {
            result.putString("result", "FAIL: " + failed);
            finish(0, result);
        }
    }
}
