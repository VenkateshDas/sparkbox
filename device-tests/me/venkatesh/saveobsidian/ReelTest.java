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
            if ("release-worker".equals(arguments.getString("suite"))) {
                // The service and wire protocol remain accessible when R8 renames internal Java helpers.
                var bound = new java.util.concurrent.CompletableFuture<android.os.IBinder>();
                var connection = new android.content.ServiceConnection() {
                    public void onServiceConnected(android.content.ComponentName n, android.os.IBinder b) { bound.complete(b); }
                    public void onServiceDisconnected(android.content.ComponentName n) {}
                };
                var intent = new android.content.Intent().setClassName(context, "me.venkatesh.saveobsidian.PdfVisionService");
                if (!context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE)) throw new AssertionError("Bind failed");
                try {
                    var pipe = android.os.ParcelFileDescriptor.createPipe();
                    try (var input = new java.io.DataInputStream(new android.os.ParcelFileDescriptor.AutoCloseInputStream(pipe[0]));
                         var writer = pipe[1]) {
                        var request = android.os.Parcel.obtain();
                        try {
                            writer.writeToParcel(request, 0);
                            request.writeString("Reply with only the fruit named here: mango.");
                            request.writeInt(32);
                            if (!bound.get(10, java.util.concurrent.TimeUnit.SECONDS).transact(android.os.IBinder.FIRST_CALL_TRANSACTION + 2,
                                request, null, android.os.IBinder.FLAG_ONEWAY)) throw new AssertionError("Request refused");
                        } finally { request.recycle(); }
                        writer.close();
                        if (!input.readBoolean()) throw new AssertionError(input.readUTF());
                        String answer = input.readUTF();
                        if (!answer.toLowerCase(java.util.Locale.ROOT).contains("mango")) throw new AssertionError(answer);
                        result.putString("answer", answer);
                    }
                } finally { context.unbindService(connection); }
            } else if ("scout".equals(arguments.getString("suite"))) {
                ScoutDeviceTest.run(this, arguments, result);
            } else if ("local-text".equals(arguments.getString("suite"))) {
                String answer = PdfVision.text(context, "Reply with only the fruit named in the text.", "The fruit in the basket is mango.", 32);
                if (!answer.toLowerCase(java.util.Locale.ROOT).contains("mango")) throw new AssertionError("Unexpected local answer: " + answer);
                result.putString("answer", answer);
            } else if ("local-speech".equals(arguments.getString("suite"))) {
                var clip = new java.io.File(context.getCacheDir(), "sparkbox-speech-check.m4a");
                String transcript = LocalSpeech.read(clip);
                if (!transcript.toLowerCase(java.util.Locale.ROOT).contains("mango")) throw new AssertionError(transcript);
                result.putString("transcript", transcript);
            } else if ("local-plan".equals(arguments.getString("suite"))) {
                try (PdfVision worker = new PdfVision(context)) {
                    var plan = worker.plan("The orchard tested drip irrigation on 12 mango trees. Water use fell by 25 percent over four weeks.", "Orchard field trial", "", "", "");
                    if (plan.optJSONArray("topics") == null || plan.getJSONArray("topics").length() == 0 ||
                        !plan.toString().contains("25")) throw new AssertionError("Local plan missing topic or measured result");
                    result.putString("summary", plan.optString("tldr"));
                }
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
                var staleConnection = new android.content.ContextWrapper(context) {
                    @Override public void unbindService(android.content.ServiceConnection ignored) {
                        throw new java.util.NoSuchElementException("Death link does not exist (NAME_NOT_FOUND)");
                    }
                };
                PdfCapture.unbind(staleConnection, connection);
                result.putString("isolation", "Worker stopped and the caller survived a framework unbind race");
            } else if ("pdf".equals(arguments.getString("suite"))) {
                if (!PdfCapture.supported()) throw new IllegalStateException("Android 15 or later required");
                if ("token-economizer".equals(arguments.getString("fixture"))) {
                    var input = java.nio.file.Files.readAllBytes(new java.io.File(context.getCacheDir(), "pdf-fixture.pdf").toPath());
                    String text = PdfCapture.read(context, input, null);
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
                String text = PdfCapture.read(context, input, null);
                if (!text.contains("Page 1 text extraction check") || !text.contains("Page 2 text extraction check"))
                    throw new AssertionError("The isolated PDF renderer did not return both pages");
                for (int i = 3; i <= 6; i++) {
                    if (!text.contains("### Page " + i + " (OCR)") || !text.contains("Scanned orchard number " + i))
                        throw new AssertionError("Missing scanned page " + i + ": " + text);
                }
                var modes = context.getSharedPreferences("pdf-model", 0);
                String old = modes.getString("mode", null);
                try {
                    modes.edit().putString("mode", "LOCAL").commit();
                    try {
                        ScoutUsage.get("models", "stale-key");
                        throw new AssertionError("Local mode allowed provider traffic");
                    } catch (java.io.IOException expected) {
                        if (!expected.getMessage().contains("disabled in local mode")) throw expected;
                    }
                } finally { modes.edit().putString("mode", old).commit(); }
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
