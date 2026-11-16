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
                Fetch.Result page = Fetch.page(url, Model.key(context), watch);
                result.putString("title", page.title);
                result.putString("problem", page.problem);
                result.putInt("characters", page.text.length());
                if (page.text.isEmpty()) throw new AssertionError("No usable reel content: " + page.problem);
                if (watch && (page.thin || !page.text.contains("### Video content"))) throw new AssertionError("Reel was not watched");
            }
            result.putString("result", "PASS");
            finish(-1, result);
        } catch (Throwable failed) {
            result.putString("result", "FAIL: " + failed);
            finish(0, result);
        }
    }
}
