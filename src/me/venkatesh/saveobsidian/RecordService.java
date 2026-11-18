package me.venkatesh.saveobsidian;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.service.quicksettings.TileService;
import android.widget.Toast;
import java.io.File;
import java.util.UUID;
import org.json.JSONObject;

/** Owns the microphone while a voice note is recorded, so the sheet, the widget, the tile and the notification all show and control one recording. */
public class RecordService extends Service {
    static final String START = "me.venkatesh.saveobsidian.START", STOP = "me.venkatesh.saveobsidian.STOP", CANCEL = "me.venkatesh.saveobsidian.CANCEL";
    private static final String CHANNEL = "recording";
    private static final int ID = 200, LIMIT_MS = 5 * 60 * 1000; // five minutes keeps one upload inside the provider's time limit
    static volatile boolean active;
    static volatile long startedMs;
    private static volatile RecordService instance;
    private MediaRecorder recorder;
    private File audio;
    private String startedAt;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Loudness since the last call, 0 to 1, for the waveform. */
    static float level() {
        RecordService s = instance;
        if (s == null || s.recorder == null) return 0;
        try { return Math.min(1f, s.recorder.getMaxAmplitude() / 32767f); } catch (RuntimeException gone) { return 0; }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        if (START.equals(action) && !active) {
            try { destination(this, new JSONObject(intent.getStringExtra("destination") == null ? "{}" : intent.getStringExtra("destination"))); begin(); }
            catch (Exception e) { toast("Could not save recording destination"); stopSelf(); }
        }
        else if (STOP.equals(action) || CANCEL.equals(action)) end(STOP.equals(action));
        else if (!active) stopSelf(); // a stale start that cannot record still has to leave foreground cleanly
        return START_NOT_STICKY;
    }

    static void destination(android.content.Context c, JSONObject value) {
        if (!c.getSharedPreferences("capture", 0).edit().putString("recordDestination", value.toString()).commit()) throw new IllegalStateException("Recording destination could not be saved");
    }

    private Notification notification() {
        PendingIntent stop = PendingIntent.getService(this, 3, new Intent(this, RecordService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(this, 4, new Intent(this, QuickActivity.class).setAction(QuickActivity.VOICE), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.notification_icon).setContentTitle("Recording a voice note")
            .setContentText("Tap to open, or stop and save").setUsesChronometer(true).setWhen(startedMs == 0 ? System.currentTimeMillis() : startedMs)
            .setShowWhen(true).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE).setColor(0xFF1B7F5C).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "Stop and save", stop).build()).build();
    }

    private void begin() {
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "Voice recording", NotificationManager.IMPORTANCE_LOW));
        boolean allowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, notification(), allowed ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE);
            else startForeground(ID, notification());
        } catch (RuntimeException refused) { stopSelf(); return; }
        if (!allowed) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return; }
        try {
            File directory = new File(getFilesDir(), "voice");
            if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("no space for the recording");
            audio = new File(directory, UUID.randomUUID() + ".m4a");
            recorder = Build.VERSION.SDK_INT >= 31 ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(32000); // speech only: about 240 KB a minute
            recorder.setMaxDuration(LIMIT_MS);
            recorder.setOnInfoListener((r, what, extra) -> { if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) main.post(() -> end(true)); });
            recorder.setOutputFile(audio.getPath());
            recorder.prepare();
            recorder.start();
        } catch (Exception e) {
            release();
            if (audio != null) audio.delete();
            toast("Cannot record: " + e.getMessage());
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            return;
        }
        instance = this;
        startedAt = Capture.timestamp(); startedMs = System.currentTimeMillis();
        getSystemService(NotificationManager.class).notify(ID, notification()); // now with the real start time
        active = true;
        changed();
    }

    private void release() {
        if (recorder != null) { try { recorder.release(); } catch (RuntimeException ignored) { } recorder = null; }
    }

    /** Stops the clip and queues it, or deletes it. A clip that is too short to hold audio is dropped. */
    private void end(boolean save) {
        if (recorder == null) { active = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); changed(); return; }
        boolean kept = true;
        try { recorder.stop(); } catch (RuntimeException tooShort) { kept = false; }
        release();
        active = false;
        if (save && kept) {
            try {
                CaptureService.enqueue(this, new JSONObject(getSharedPreferences("capture", 0).getString("recordDestination", "{}")).put("kind", "voice").put("audio", audio.getPath()).put("format", "m4a").put("label", "Voice note").put("time", startedAt));
                getSharedPreferences("capture", 0).edit().remove("quickDestination").apply();
                BackgroundJob.schedule(this);
                toast(Model.key(this).isEmpty() ? "Saved. Add a key in Settings to transcribe." : "Saved. Transcribing…");
            } catch (Exception e) { toast("Recording kept on this phone but not queued: " + e.getMessage()); }
        } else {
            audio.delete();
            if (save) toast("Too short to keep");
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        changed();
    }

    private void changed() {
        InboxWidget.refresh(this);
        TileService.requestListeningState(this, new ComponentName(this, QuickTile.Voice.class));
    }

    private void toast(String message) { main.post(() -> Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show()); }

    @Override public void onDestroy() {
        if (recorder != null) end(true); // never lose a clip when the system takes the service
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
