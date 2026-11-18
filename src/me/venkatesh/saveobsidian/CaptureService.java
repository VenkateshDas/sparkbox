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
    static final String CHANGED = "me.venkatesh.saveobsidian.INBOX_CHANGED";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile int lastStart;
    private volatile long deadline;
    private volatile boolean stopping;
    private long lastPost;
    /** One writer at a time: appends from shares and edits from the home screen. */
    private static final Object LOCK = new Object();

    /** Queue one capture privately before any work. kind is "link" or "text". */
    static void enqueue(Context context, String kind, String url, String title, String text) throws Exception {
        if (text.getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw new IOException("Shared text exceeds 1 MiB. Nothing was shortened; save it directly to your vault.");
        enqueue(context, new JSONObject().put("kind", kind).put("url", url).put("title", title).put("text", text));
    }
    /** Queue any job: link, text, file (copied into the vault) or voice (transcribed first by Tagger). */
    static void enqueue(Context context, JSONObject job) throws Exception {
        File directory = new File(context.getFilesDir(), "pending");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create capture queue");
        if (!job.has("time")) job.put("time", Capture.timestamp());
        if (!job.has("captureId")) job.put("captureId", UUID.randomUUID().toString());
        if (job.optString("text").getBytes(StandardCharsets.UTF_8).length > 1024 * 1024) throw new IOException("Capture exceeds 1 MiB; nothing was shortened.");
        writeJob(new File(directory, job.getString("captureId") + ".json"), job
            .put("created", System.currentTimeMillis()).put("writeAttempted", false).put("phase", "queued"));
        Tracking.accepted(context, job);
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

    static String inboxLocation(Context context) {
        String saved = context.getSharedPreferences("capture", 0).getString("inbox", "");
        try {
            String id = android.provider.DocumentsContract.getDocumentId(Uri.parse(saved));
            if (id.contains("/")) return id.substring(id.indexOf(':') + 1);
        } catch (Exception ignored) { }
        return inboxName(context);
    }

    /** Snapshot-checked edit. Unverified writes keep both versions; never auto-restore. */
    static void edit(Context context, String destination, String snapshot, java.util.function.Function<String, String> change) throws Exception {
        synchronized (LOCK) {
            String saved = context.getSharedPreferences("capture", 0).getString("inbox", "");
            if (saved.isEmpty() || !saved.equals(destination)) throw new IOException("Inbox destination changed. Close the editor and refresh. Your draft is kept.");
            if (hasRecovery(context, saved)) throw new IOException("Resolve Edit recovery before changing this inbox.");
            Uri inbox = Uri.parse(saved);
            android.content.ContentResolver resolver = context.getContentResolver();
            byte[] before = snapshot.getBytes(StandardCharsets.UTF_8);
            byte[] after = change.apply(snapshot).getBytes(StandardCharsets.UTF_8);
            if (after.length > 32 * 1024 * 1024) throw new IOException("Edited inbox exceeds 32 MiB. Your draft is kept.");
            File recovery = new File(context.getFilesDir(), "edit-recovery/" + UUID.randomUUID() + ".json");
            VerifiedEdit.apply(before, after, () -> readInbox(resolver, inbox), bytes -> overwrite(resolver, inbox, bytes), (original, attempted) -> {
                File directory = recovery.getParentFile();
                if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create edit recovery");
                // One atomic journal keeps destination and both versions together, including after process death.
                writeJob(recovery, new JSONObject().put("destination", saved).put("name", inboxName(context))
                    .put("time", Capture.timestamp()).put("encoding", "base64").put("before", android.util.Base64.encodeToString(original, android.util.Base64.NO_WRAP))
                    .put("after", android.util.Base64.encodeToString(attempted, android.util.Base64.NO_WRAP)));
            });
            new AtomicFile(recovery).delete();
            InboxWidget.refresh(context);
        }
    }
    /** Deletes private copies (shared files, recordings) that no queued or retained job points to and that are over a day old. */
    static void sweep(Context context) {
        try {
            java.util.Set<String> used = new java.util.HashSet<>();
            File[] queued = pending(context), kept = new File(context.getFilesDir(), "discarded").listFiles((dir, name) -> name.endsWith(".json"));
            java.util.List<File> jobs = new java.util.ArrayList<>(java.util.Arrays.asList(queued));
            if (kept != null) jobs.addAll(java.util.Arrays.asList(kept));
            for (File job : jobs) {
                try { JSONObject j = readJob(job); used.add(j.optString("path")); used.add(j.optString("audio")); }
                catch (Exception unreadable) { return; } // cannot tell what is in use, so delete nothing
            }
            for (String folder : new String[]{"files", "voice"}) {
                File[] stray = new File(context.getFilesDir(), folder).listFiles();
                if (stray != null) for (File f : stray) if (!used.contains(f.getPath()) && System.currentTimeMillis() - f.lastModified() > 86400000L) f.delete();
            }
        } catch (RuntimeException ignored) { }
    }
    static File[] recoveries(Context context) {
        File[] files = new File(context.getFilesDir(), "edit-recovery").listFiles((dir, name) -> name.endsWith(".json"));
        return files == null ? new File[0] : files;
    }
    private static boolean hasRecovery(Context context, String destination) {
        for (File file : recoveries(context)) {
            try { if (destination.equals(readJob(file).optString("destination"))) return true; }
            catch (Exception unreadable) { return true; } // retain/block until exported and inspected
        }
        return false;
    }
    private static boolean hasUncertainCapture(Context context, File current) {
        for (File file : pending(context)) {
            if (file.equals(current)) continue;
            try {
                JSONObject job = readJob(file);
                if (job.optBoolean("writeAttempted") || "writing".equals(job.optString("phase")) || "uncertain".equals(job.optString("phase")) ||
                    ("failed".equals(job.optString("phase")) && !job.has("writeAttempted"))) return true;
            } catch (Exception unreadable) { return true; }
        }
        return false;
    }
    /** Explicit dismissal only; serialized with writes. Exports never write to the selected inbox. */
    static void dismissRecovery(File file) { synchronized (LOCK) { new AtomicFile(file).delete(); } }
    static void discard(Context context, File file) throws Exception {
        synchronized (LOCK) {
            File trash = new File(context.getFilesDir(), "discarded");
            if (!trash.exists() && !trash.mkdirs()) throw new IOException("Cannot keep discarded capture");
            if (file.exists() && !file.renameTo(new File(trash, file.getName()))) throw new IOException("Capture could not be discarded");
        }
    }
    private static byte[] readInbox(android.content.ContentResolver resolver, Uri inbox) throws Exception {
        try (InputStream input = resolver.openInputStream(inbox)) {
            byte[] bytes = readAll(input);
            if (bytes.length > 32 * 1024 * 1024) throw new IOException("Inbox exceeds 32 MiB");
            return bytes;
        }
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
        if (jobs == null) return new File[0];
        java.util.Arrays.sort(jobs, java.util.Comparator.comparingLong(CaptureService::created).thenComparing(File::getName));
        return jobs;
    }
    private static long created(File file) {
        try { return readJob(file).optLong("created", file.lastModified()); }
        catch (Exception e) { return file.lastModified(); }
    }
    static JSONObject readJob(File file) throws Exception {
        try (InputStream input = new AtomicFile(file).openRead()) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
    }
    static void writeJob(File file, JSONObject job) throws Exception {
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
    /** The entry just saved: tapping the island opens its why/ideas editor instead of the bare inbox. */
    private volatile String openEntry;
    private PendingIntent openApp() {
        Intent open = new Intent(this, MainActivity.class).putExtra(MainActivity.TAB, 0).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
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
        if (deadline == 0) {
            deadline = lastPost + 120000;
            main.postDelayed(() -> onTimeout(lastStart), 140000);
        } // leave headroom within shortService's three minutes
        String retry = intent == null ? null : intent.getStringExtra("retry");
        worker.execute(() -> {
            for (File file : pending(this)) {
                if (stopping || SystemClock.uptimeMillis() > deadline) break;
                if (file.getName().equals(retry)) reopen(file);
                save(file);
            }
            main.postDelayed(() -> {
                if (startId == lastStart) { stopping = true; stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); }
            }, 3500);
        });
        return START_NOT_STICKY;
    }
    /** Retry failed pre-write work; interrupted writes remain check-only. */
    private void reopen(File file) {
        try { JSONObject job = readJob(file);
            if ("failed".equals(job.optString("phase")) && !job.has("writeAttempted")) job.put("phase", "uncertain");
            if (!job.optBoolean("writeAttempted") && !"writing".equals(job.optString("phase")) && !"uncertain".equals(job.optString("phase")))
                job.put("phase", job.optString("entry").isEmpty() ? "queued" : "prepared");
            job.remove("error"); writeJob(file, job); }
        catch (Exception ignored) { }
    }
    private void save(File file) {
        synchronized (LOCK) { saveLocked(file); }
    }
    private void saveLocked(File file) {
        JSONObject job = null;
        try {
            job = readJob(file);
            if (DirectCapture.direct(job)) { BackgroundJob.schedule(this); return; }
            if ("voice".equals(job.optString("kind"))) return; // Tagger transcribes it, then files it itself
            String phase = job.optString("phase");
            if ("failed".equals(phase)) return; // waits for an explicit Retry or Discard
            boolean link = "link".equals(job.optString("kind", "link")), attachment = "file".equals(job.optString("kind"));
            String url = job.optString("url"), entry = job.optString("entry"), time = job.getString("time");
            boolean uncertain = job.optBoolean("writeAttempted") || "writing".equals(phase) || "uncertain".equals(phase);
            if (uncertain && entry.isEmpty()) throw new IOException("Capture state is uncertain. Export it and inspect the inbox.");
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
                    if (!job.optString("text").isEmpty()) entry += Capture.block("Shared text", job.optString("text"));
                } else if (attachment) {
                    stage("Sparkbox", "Copying to your vault…", "Copying", 40);
                    String stored = Attach.store(this, new File(job.getString("path")), job.optString("stored", job.getString("name")), job.optString("mime"));
                    job.put("stored", stored);
                    label = Capture.clean(job.optString("title"));
                    if (label.isEmpty()) label = Capture.clean(job.optString("original"));
                    entry = Capture.fileEntry(time, label, stored, job.optString("mime").startsWith("image/"));
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
            stage("Sparkbox", "Filing into " + name + "…", "Filing", 85);
            if (hasRecovery(this, saved)) throw new IOException("Resolve Edit recovery before saving to this inbox.");
            if (!job.optString("destination", saved).equals(saved)) throw new IOException("This capture belongs to a different inbox. Export it before changing destinations.");
            Uri inbox = Uri.parse(saved);
            byte[] before;
            before = readInbox(getContentResolver(), inbox);
            String beforeText = new String(before, StandardCharsets.UTF_8);
            // URL-only presence cannot resolve an interrupted write; require the entire entry.
            boolean complete = InboxFile.containsEntry(beforeText, entry);
            if (uncertain && !complete) {
                job.put("phase", "uncertain");
                throw new IOException("Save is uncertain. Check the inbox and export this capture. Retry only checks for the complete entry; it will not append again.");
            }
            if (!uncertain && hasUncertainCapture(this, file)) throw new IOException("Resolve the uncertain or unreadable queued capture before appending more. This capture is kept.");
            boolean duplicate = complete || (!uncertain && link && completeLink(beforeText, url));
            if (!duplicate) {
                byte[] addition = (Capture.separator(before) + entry + "\n").getBytes(StandardCharsets.UTF_8);
                if (before.length + addition.length > 32 * 1024 * 1024) throw new IOException("Inbox would exceed 32 MiB. Capture remains in recovery.");
                if (stopping || SystemClock.uptimeMillis() > deadline) return;
                // Append only. Never open a truncating write mode.
                try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(inbox, "wa")) {
                    if (descriptor == null) throw new IOException("Inbox unavailable");
                    job.put("phase", "writing").put("writeAttempted", true).put("destination", saved); writeJob(file, job);
                    try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                        output.write(addition); output.flush(); output.getFD().sync();
                    }
                }
                byte[] after;
                after = readInbox(getContentResolver(), inbox);
                if (!AppendCheck.matches(before, addition, after)) throw new IOException("Inbox changed during save");
            }
            if (duplicate && !complete && !job.optString("text").isEmpty()) discard(this, file);
            new AtomicFile(file).delete();
            if (attachment) new File(job.optString("path")).delete(); // the vault copy is verified; drop the private one
            getSystemService(NotificationManager.class).cancel(file.getName().hashCode());
            InboxWidget.refresh(this);
            sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
            if (!duplicate) BackgroundJob.now(this);
            openEntry = duplicate ? null : entry;
            if (!duplicate) { Tracking.linked(this, job, entry); Usage.hit(this, "capture"); }
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
        if (!stopping) getSystemService(NotificationManager.class).notify(ACTIVE, notification(title, message, shortText, progress));
        lastPost = SystemClock.uptimeMillis();
    }
    private void fail(File file, JSONObject job, Exception e) {
        String reason = e instanceof SecurityException || e instanceof FileNotFoundException
            ? "Inbox file moved, renamed or permission revoked. Choose the inbox file again."
            : String.valueOf(e.getMessage());
        try { if (job != null) writeJob(file, job.put("phase", job.optBoolean("writeAttempted") || "uncertain".equals(job.optString("phase")) || "writing".equals(job.optString("phase")) ? "uncertain" : "failed").put("error", reason)); } catch (Exception ignored) { }
        PendingIntent retry = PendingIntent.getForegroundService(this, file.getName().hashCode(),
            new Intent(this, CaptureService.class).putExtra("retry", file.getName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification error = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setContentTitle("Source needs attention").setContentText(reason)
            .setStyle(new Notification.BigTextStyle().bigText(reason)).setContentIntent(openApp()).setAutoCancel(true)
            .addAction(new Notification.Action.Builder(null, "Retry", retry).build()).build();
        getSystemService(NotificationManager.class).notify(file.getName().hashCode(), error);
        sendBroadcast(new Intent(CHANGED).setPackage(getPackageName()));
    }
    private static boolean completeLink(String text, String url) {
        for (InboxFile.Entry e : InboxFile.parse(text))
            if (e.url.replaceAll("/+$", "").equals(url.replaceAll("/+$", "")) && !e.time.isEmpty()) return true;
        return false;
    }
    @Override public void onTimeout(int startId) {
        stopping = true;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf(); // interrupted jobs retain their atomic journal and write-attempt flag
        worker.shutdownNow();
    }
    static byte[] readAll(InputStream input) throws Exception {
        if (input == null) throw new IOException("Inbox unavailable");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() + count > 96 * 1024 * 1024) throw new IOException("Inbox exceeds safe read limit");
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onDestroy() { stopping = true; main.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy(); }
}
