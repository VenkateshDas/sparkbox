package me.venkatesh.saveobsidian;

import android.app.*;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.*;
import android.text.Html;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;

public class CaptureService extends Service {
    private static final String CHANNEL = "source-capture";
    private static final int ACTIVE = 100;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private int lastStart;
    private long lastPost;
    /** One writer at a time: appends from shares and edits from the home screen. */
    private static final Object LOCK = new Object();

    /** Queue one capture privately before any work. kind is "link" or "text". */
    static void enqueue(Context context, String kind, String url, String title, String text) throws Exception {
        File directory = new File(context.getFilesDir(), "pending");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create capture queue");
        File file = new File(directory, UUID.randomUUID().toString() + ".json");
        writeJob(file, new JSONObject().put("kind", kind).put("url", url).put("title", title).put("text", text)
            .put("time", Capture.timestamp()).put("phase", "queued"));
    }
    /** Chosen inbox file name; older installs did not store it, so fall back to the document id. */
    static String inboxName(Context context) {
        SharedPreferences prefs = context.getSharedPreferences("capture", 0);
        String name = prefs.getString("name", "");
        if (!name.isEmpty()) return name;
        try {
            String id = android.provider.DocumentsContract.getDocumentId(Uri.parse(prefs.getString("inbox", "")));
            return id.substring(id.lastIndexOf('/') + 1);
        } catch (Exception e) { return "inbox"; }
    }

    /**
     * Rewrite the inbox: delete an entry or change its notes. This is the one place the file is
     * truncated, so it keeps a private copy first, refuses if the file changed since it was read,
     * verifies the result, and puts the old bytes back if verification fails.
     */
    static void edit(Context context, java.util.function.Function<String, String> change) throws Exception {
        synchronized (LOCK) {
            String saved = context.getSharedPreferences("capture", 0).getString("inbox", "");
            if (saved.isEmpty()) throw new IOException("No inbox file chosen");
            Uri inbox = Uri.parse(saved);
            android.content.ContentResolver resolver = context.getContentResolver();
            byte[] before = readInbox(resolver, inbox);
            byte[] after = change.apply(new String(before, StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            if (java.util.Arrays.equals(before, after)) return;
            AtomicFile backup = new AtomicFile(new File(context.getFilesDir(), "inbox.bak"));
            FileOutputStream copy = backup.startWrite();
            try { copy.write(before); backup.finishWrite(copy); } catch (Exception e) { backup.failWrite(copy); throw e; }
            if (!java.util.Arrays.equals(before, readInbox(resolver, inbox))) throw new IOException("Inbox changed in Obsidian. Refresh and try again.");
            overwrite(resolver, inbox, after);
            if (!java.util.Arrays.equals(after, readInbox(resolver, inbox))) {
                try { overwrite(resolver, inbox, before); } catch (Exception ignored) { }
                throw new IOException("Could not verify the edit. The previous inbox was restored.");
            }
            InboxWidget.refresh(context);
        }
    }
    private static byte[] readInbox(android.content.ContentResolver resolver, Uri inbox) throws Exception {
        try (InputStream input = resolver.openInputStream(inbox)) { return readAll(input); }
    }
    private static void overwrite(android.content.ContentResolver resolver, Uri inbox, byte[] bytes) throws Exception {
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(inbox, "rwt")) {
            if (descriptor == null) throw new IOException("Inbox unavailable");
            try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                output.write(bytes); output.flush(); output.getFD().sync();
            }
        }
    }
    /** Current inbox text for the home screen. */
    static String readText(Context context) throws Exception {
        String saved = context.getSharedPreferences("capture", 0).getString("inbox", "");
        if (saved.isEmpty()) throw new IOException("No inbox file chosen");
        return new String(readInbox(context.getContentResolver(), Uri.parse(saved)), StandardCharsets.UTF_8);
    }
    static File[] pending(Context context) {
        File[] jobs = new File(context.getFilesDir(), "pending").listFiles((dir, name) -> name.endsWith(".json"));
        return jobs == null ? new File[0] : jobs;
    }
    static JSONObject readJob(File file) throws Exception {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
    }
    private static void writeJob(File file, JSONObject job) throws Exception {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try { output.write(job.toString().getBytes(StandardCharsets.UTF_8)); atomic.finishWrite(output); }
        catch (Exception e) { atomic.failWrite(output); throw e; }
    }
    static void start(Context context, String retryJob) {
        Intent intent = new Intent(context, CaptureService.class);
        if (retryJob != null) intent.putExtra("retry", retryJob);
        context.startForegroundService(intent);
    }
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Source captures", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Saving progress and confirmation for links you share");
        channel.setSound(null, null);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private PendingIntent openApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private Notification notification(String title, String message, String shortText, int progress) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.notification_icon).setContentTitle(title).setContentText(message).setContentIntent(openApp())
            .setOngoing(true).setShowWhen(false).setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(true).setColor(0xFF1B7F5C).setProgress(100, Math.max(progress, 0), progress < 0);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (Build.VERSION.SDK_INT >= 36) {
            // Reflection: compileSdk is 35. Harmless no-op where the API is missing.
            try {
                Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(builder, true);
                Notification.Builder.class.getMethod("setShortCriticalText", String.class).invoke(builder, shortText);
            } catch (ReflectiveOperationException ignored) { }
        }
        return builder.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        Notification progress = notification("Sparkbox", "Catching your spark…", "Saving", 10);
        if (Build.VERSION.SDK_INT >= 34) startForeground(ACTIVE, progress, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        else startForeground(ACTIVE, progress);
        lastPost = SystemClock.uptimeMillis();
        String retry = intent == null ? null : intent.getStringExtra("retry");
        worker.execute(() -> {
            for (File file : pending(this)) {
                if (file.getName().equals(retry)) reopen(file);
                save(file);
            }
            main.postDelayed(() -> {
                if (startId == lastStart) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); }
            }, 3500);
        });
        return START_NOT_STICKY;
    }
    /** Clear a failed state so save() tries again. Safe: save() skips entries already in the inbox. */
    private void reopen(File file) {
        try { JSONObject job = readJob(file); job.put("phase", job.optString("entry").isEmpty() ? "queued" : "prepared").remove("error"); writeJob(file, job); }
        catch (Exception ignored) { }
    }
    private void save(File file) {
        synchronized (LOCK) { saveLocked(file); }
    }
    private void saveLocked(File file) {
        JSONObject job = null;
        try {
            job = readJob(file);
            String phase = job.optString("phase");
            if ("failed".equals(phase)) return; // waits for an explicit Retry or Discard
            boolean link = "link".equals(job.optString("kind", "link"));
            String url = job.optString("url"), entry = job.optString("entry"), time = job.getString("time");
            if (entry.isEmpty()) {
                String label;
                if (link) {
                    label = job.optString("title");
                    if (Capture.isGeneric(label, url)) {
                        stage("Sparkbox", "Reading the page…", "Reading", -1);
                        String fetched;
                        try { fetched = Html.fromHtml(PageTitle.fetchTitle(url), Html.FROM_HTML_MODE_LEGACY).toString(); }
                        catch (Exception e) { fetched = ""; }
                        label = Capture.chooseTitle(label, "", fetched, url);
                    }
                    entry = Capture.entry(time, label, url);
                } else {
                    entry = Capture.textEntry(time, job.optString("text"));
                    label = Capture.clean(job.optString("text"));
                    if (label.length() > 80) label = label.substring(0, 80) + "…";
                }
                job.put("entry", entry).put("label", label).put("phase", "prepared");
                writeJob(file, job);
            }
            String label = job.optString("label"), name = inboxName(this);
            String saved = getSharedPreferences("capture", 0).getString("inbox", "");
            if (saved.isEmpty()) throw new IOException("No inbox file chosen");
            stage("Sparkbox", label.isEmpty() ? "Got it" : label, "Found", 55);
            stage("Sparkbox", "Filing into " + name + "…", "Filing", 85);
            Uri inbox = Uri.parse(saved);
            byte[] before;
            try (InputStream input = getContentResolver().openInputStream(inbox)) { before = readAll(input); }
            String beforeText = new String(before, StandardCharsets.UTF_8);
            // Also makes retrying an interrupted write safe: if it landed, it is found here.
            boolean duplicate = link ? Capture.alreadyListed(beforeText, url) : beforeText.contains(entry);
            if (!duplicate) {
                byte[] addition = (Capture.separator(before) + entry + "\n").getBytes(StandardCharsets.UTF_8);
                // Append only. Never open a truncating write mode.
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(inbox, "wa")) {
                    if (descriptor == null) throw new IOException("Inbox unavailable");
                    job.put("phase", "writing"); writeJob(file, job);
                    try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                        output.write(addition); output.flush(); output.getFD().sync();
                    }
                }
                byte[] after;
                try (InputStream input = getContentResolver().openInputStream(inbox)) { after = readAll(input); }
                if (!AppendCheck.matches(before, addition, after)) throw new IOException("Inbox changed during save");
            }
            new AtomicFile(file).delete();
            InboxWidget.refresh(this);
            if (duplicate) stage("Already in inbox", label.isEmpty() ? "This is already in " + name : label, "Seen", 100);
            else stage("Source added", label.isEmpty() ? "Saved to " + name : label + " → " + name, "Added", 100);
        } catch (Exception e) {
            fail(file, job, e);
        }
    }
    /**
     * One step of the island animation: real progress through real stages. Steps are held at least
     * 260 ms apart so the system's notification rate limit cannot drop the final "Added".
     */
    private void stage(String title, String message, String shortText, int progress) {
        long wait = lastPost + 260 - SystemClock.uptimeMillis();
        if (wait > 0) try { Thread.sleep(wait); } catch (InterruptedException ignored) { }
        getSystemService(NotificationManager.class).notify(ACTIVE, notification(title, message, shortText, progress));
        lastPost = SystemClock.uptimeMillis();
    }
    private void fail(File file, JSONObject job, Exception e) {
        String reason = e instanceof SecurityException || e instanceof FileNotFoundException
            ? "Inbox file moved, renamed or permission revoked. Choose the inbox file again."
            : String.valueOf(e.getMessage());
        try { if (job != null) writeJob(file, job.put("phase", "failed").put("error", reason)); } catch (Exception ignored) { }
        PendingIntent retry = PendingIntent.getForegroundService(this, file.getName().hashCode(),
            new Intent(this, CaptureService.class).putExtra("retry", file.getName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification error = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon)
            .setContentTitle("Source needs attention").setContentText(reason)
            .setStyle(new Notification.BigTextStyle().bigText(reason)).setContentIntent(openApp()).setAutoCancel(true)
            .addAction(new Notification.Action.Builder(null, "Retry", retry).build()).build();
        getSystemService(NotificationManager.class).notify(file.getName().hashCode(), error);
    }
    static byte[] readAll(InputStream input) throws Exception {
        if (input == null) throw new IOException("Inbox unavailable");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 32 * 1024 * 1024) throw new IOException("Inbox exceeds safe read limit");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
