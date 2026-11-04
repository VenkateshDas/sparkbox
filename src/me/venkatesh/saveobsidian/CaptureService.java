package me.venkatesh.saveobsidian;

import android.app.*;
import android.content.Context;
import android.content.Intent;
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
    static void enqueue(Context context, String url, String title) throws Exception {
        File directory = new File(context.getFilesDir(), "pending");
        if (!directory.exists() && !directory.mkdirs()) throw new IOException("Cannot create capture queue");
        File file = new File(directory, UUID.randomUUID().toString() + ".json");
        writeJob(file, new JSONObject().put("url", url).put("title", title).put("time", Capture.timestamp()).put("phase", "queued"));
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
    private Notification notification(String title, String message, boolean saving) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.notification_icon).setContentTitle(title).setContentText(message).setContentIntent(openApp())
            .setOngoing(saving).setShowWhen(false).setCategory(Notification.CATEGORY_PROGRESS)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setOnlyAlertOnce(saving);
        if (Build.VERSION.SDK_INT >= 31) builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        if (saving) builder.setProgress(100, "Source added".equals(title) ? 100 : 0, !"Source added".equals(title));
        else builder.setTimeoutAfter(6000);
        if (Build.VERSION.SDK_INT >= 36 && saving) {
            try {
                Notification.Builder.class.getMethod("setRequestPromotedOngoing", boolean.class).invoke(builder, true);
                Notification.Builder.class.getMethod("setShortCriticalText", String.class).invoke(builder, "Source added".equals(title) ? "Added" : "Saving");
            } catch (ReflectiveOperationException ignored) { }
        }
        return builder.build();
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        lastStart = startId;
        Notification progress = notification("Saving source…", "Adding to your local inbox", true);
        if (Build.VERSION.SDK_INT >= 34) startForeground(ACTIVE, progress, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
        else startForeground(ACTIVE, progress);
        worker.execute(() -> {
            File[] jobs = new File(getFilesDir(), "pending").listFiles((dir, name) -> name.endsWith(".json"));
            if (jobs != null) for (File file : jobs) save(file);
            main.postDelayed(() -> {
                if (startId == lastStart) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId); }
            }, 3500);
        });
        return START_NOT_STICKY;
    }
    private void save(File file) {
        try {
            JSONObject job = readJob(file);
            if ("writing".equals(job.optString("phase"))) throw new IOException("Previous write interrupted; check inbox before retrying");
            if ("saved".equals(job.optString("phase"))) { new AtomicFile(file).delete(); return; }
            String url = job.getString("url"), entry = job.optString("entry");
            if (entry.isEmpty()) {
                String title = Capture.clean(job.optString("title"));
                if (title.isEmpty() || title.equals(url)) {
                    try { title = PageTitle.fetchTitle(url); } catch (Exception ignored) { title = ""; }
                }
                title = Html.fromHtml(title, Html.FROM_HTML_MODE_LEGACY).toString();
                entry = Capture.entry(job.getString("time"), title, url);
                job.put("entry", entry).put("phase", "prepared");
                writeJob(file, job);
            }
            Uri inbox = Uri.parse(getSharedPreferences("capture", 0).getString("inbox", ""));
            byte[] before;
            try (InputStream input = getContentResolver().openInputStream(inbox)) { before = readAll(input); }
            byte[] addition = ("\n" + entry + "\n").getBytes(StandardCharsets.UTF_8);
            // Append only. Never retry an uncertain append or open a truncating write mode.
            try (ParcelFileDescriptor descriptor = getContentResolver().openFileDescriptor(inbox, "wa")) {
                if (descriptor == null) throw new IOException("Inbox unavailable");
                job.put("phase", "writing"); writeJob(file, job);
                try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                    output.write(addition); output.flush(); output.getFD().sync();
                }
            }
            byte[] after;
            try (InputStream input = getContentResolver().openInputStream(inbox)) { after = readAll(input); }
            if (!AppendCheck.matches(before, addition, after)) throw new IOException("Inbox changed during save; inspect before retrying");
            job.put("phase", "saved"); writeJob(file, job);
            new AtomicFile(file).delete();
            getSystemService(NotificationManager.class).notify(ACTIVE, notification("Source added", "Saved to Sources/Inbox.md", true));
        } catch (Exception e) {
            Notification error = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon)
                .setContentTitle("Source needs attention").setContentText("Capture kept privately. Tap to inspect; check inbox before retrying.")
                .setStyle(new Notification.BigTextStyle().bigText(e.getMessage())).setContentIntent(openApp()).setAutoCancel(true).build();
            getSystemService(NotificationManager.class).notify(file.getName().hashCode(), error);
        }
    }
    private static byte[] readAll(InputStream input) throws Exception {
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
