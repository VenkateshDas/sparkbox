package me.venkatesh.saveobsidian;

import android.app.Instrumentation;
import android.os.Bundle;

/** Manual live checks. Uses the phone's key without exporting it. Only recover=true writes the vault. */
public class ReelTest extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle args) { arguments = args; start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            android.content.Context context = getTargetContext();
            if ("scout".equals(arguments.getString("suite"))) {
                ScoutDeviceTest.run(this, arguments, result);
            } else if ("pdf-worker".equals(arguments.getString("suite"))) {
                var ready = new java.util.concurrent.CompletableFuture<android.os.IBinder>();
                var connection = new android.content.ServiceConnection() {
                    @Override public void onServiceConnected(android.content.ComponentName name, android.os.IBinder remote) { ready.complete(remote); }
                    @Override public void onServiceDisconnected(android.content.ComponentName name) { }
                };
                var intent = new android.content.Intent(context, PdfVisionService.class);
                if (!context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE))
                    throw new AssertionError("Local PDF worker did not bind");
                var died = new java.util.concurrent.CountDownLatch(1);
                try {
                    var worker = ready.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (worker instanceof android.os.Binder) throw new AssertionError("PDF inference is inside the caller process");
                    worker.linkToDeath(died::countDown, 0);
                } finally { context.unbindService(connection); }
                if (!died.await(10, java.util.concurrent.TimeUnit.SECONDS))
                    throw new AssertionError("PDF worker did not exit after unbinding");
                result.putString("isolation", "Worker stopped and the caller survived");
            } else if ("pdf".equals(arguments.getString("suite"))) {
                if (!PdfCapture.supported()) throw new IllegalStateException("Android 15 or later required");
                byte[] input;
                android.graphics.pdf.PdfDocument pdf = new android.graphics.pdf.PdfDocument();
                try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                    for (int i = 1; i <= 2; i++) {
                        var page = pdf.startPage(new android.graphics.pdf.PdfDocument.PageInfo.Builder(500, 700, i).create());
                        var ink = new android.graphics.Paint();
                        ink.setTextSize(24);
                        page.getCanvas().drawText("Page " + i + " text extraction check", 30, 55, ink);
                        pdf.finishPage(page);
                    }
                    pdf.writeTo(out);
                    input = out.toByteArray();
                } finally { pdf.close(); }
                String text = PdfCapture.read(context, input, null);
                if (!text.contains("Page 1 text extraction check") || !text.contains("Page 2 text extraction check"))
                    throw new AssertionError("The isolated PDF renderer did not return both pages");
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
                Fetch.Result page = Fetch.page(context, url, Model.key(context), watch, null);
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
