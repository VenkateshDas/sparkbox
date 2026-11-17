package me.venkatesh.saveobsidian;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.widget.Toast;

/** The capture sheet: write a thought or speak one. Opens from the widget, the Quick Settings tiles and the launcher shortcuts. Drawn by QuickView.kt. */
public class QuickActivity extends androidx.activity.ComponentActivity {
    static final String VOICE = "me.venkatesh.saveobsidian.VOICE";
    private static final int MIC = 7;
    private QuickState view;
    private boolean closing, wasRecording;
    private org.json.JSONObject destination = new org.json.JSONObject();
    private long asked;
    private final Handler loop = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { @Override public void run() { update(); loop.postDelayed(this, 60); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false); // the app switcher must not show inbox contents
        Ui ui = new Ui(this);
        view = new QuickState(getSharedPreferences("capture", 0).getString("draft", ""), CaptureService.inboxName(this),
            Model.key(this).isEmpty() ? "Add an OpenRouter key in Settings to turn recordings into text. Until then they wait on this phone." : "Sent to OpenRouter for a transcript, then the audio is deleted");
        try { destination = new org.json.JSONObject(getSharedPreferences("capture", 0).getString(RecordService.active ? "recordDestination" : "quickDestination", "{}")); } catch (Exception ignored) { }
        view.setDestination(DestinationPicker.label(destination));
        QuickViewHost.set(this, ui, view, actions);
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) { @Override public void handleOnBackPressed() { close(); } });
        boolean voice = VOICE.equals(getIntent().getAction()) || RecordService.active;
        show(voice && state == null || RecordService.active);
        if (voice && state == null) startSpeaking();
    }

    private final QuickActions actions = new QuickActions() {
        @Override public void write() { if (!RecordService.active) show(false); }
        @Override public void speak() { startSpeaking(); }
        @Override public void save(String text) { saveText(text); }
        @Override public void discard() { startService(new Intent(QuickActivity.this, RecordService.class).setAction(RecordService.CANCEL)); close(); }
        @Override public void stop() { getSharedPreferences("capture", 0).edit().remove("quickDestination").apply(); getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); startService(new Intent(QuickActivity.this, RecordService.class).setAction(RecordService.STOP)); close(); }
        @Override public void dismiss() { close(); }
        @Override public void draft(String text) { getSharedPreferences("capture", 0).edit().putString("draft", text).apply(); }
        @Override public void destination() { DestinationPicker.show(QuickActivity.this, destination, d -> {
            try {
                if (RecordService.active) RecordService.destination(QuickActivity.this, d);
                if (!getSharedPreferences("capture", 0).edit().putString("quickDestination", d.toString()).commit()) throw new IllegalStateException();
                destination = d; view.setDestination(DestinationPicker.label(d));
            } catch (Exception e) { Toast.makeText(QuickActivity.this, "Destination could not be saved. Check the selected destination before saving.", Toast.LENGTH_LONG).show(); }
        }); }
    };

    private void show(boolean speak) {
        view.setSpeaking(speak);
        loop.removeCallbacks(tick);
        if (speak) loop.post(tick);
    }

    private void startSpeaking() {
        if (RecordService.active) { show(true); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC); return; }
        asked = System.currentTimeMillis();
        wasRecording = false;
        view.clear();
        startForegroundService(new Intent(this, RecordService.class).setAction(RecordService.START).putExtra("destination", destination.toString()));
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        show(true);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code != MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) { InboxWidget.refresh(this); startSpeaking(); }
        else { Toast.makeText(this, "Allow the microphone to record", Toast.LENGTH_LONG).show(); show(false); }
    }

    /** Every 60 ms while speaking: timer, waveform, and noticing a recording that was stopped from the widget or the notification. */
    private void update() {
        if (!view.getSpeaking() || closing) return;
        if (RecordService.active) {
            wasRecording = true;
            long s = (System.currentTimeMillis() - RecordService.startedMs) / 1000;
            view.setClock(s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60);
            view.push(RecordService.level());
        } else if (wasRecording) close();
        else if (System.currentTimeMillis() - asked > 3000) { Toast.makeText(this, "Could not start recording", Toast.LENGTH_LONG).show(); show(false); }
    }

    private void saveText(String raw) {
        String text = raw.trim();
        if (text.isEmpty()) return;
        try {
            if (DirectCapture.direct(destination)) CaptureService.enqueue(this, new org.json.JSONObject(destination.toString()).put("kind", "text").put("text", raw));
            else {
                String url = null; try { url = Capture.firstUrl(text); } catch (IllegalArgumentException ignored) { }
                CaptureService.enqueue(this, url == null ? "text" : "link", url == null ? "" : Capture.cleanUrl(url), "", raw);
            }
            getSharedPreferences("capture", 0).edit().remove("draft").remove("quickDestination").apply();
            if (DirectCapture.direct(destination)) BackgroundJob.schedule(this); else ShareActivity.dispatch(this);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            closing = true;
            close();
        } catch (Exception e) { closing = false; Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    /** The sheet slides away, then the activity ends. An unsaved thought is kept as the draft. */
    private void close() {
        boolean saved = closing;
        closing = true;
        loop.removeCallbacks(tick);
        if (!saved && !view.getSpeaking()) getSharedPreferences("capture", 0).edit().putString("draft", view.getText()).apply();
        view.getShown().setTargetState(false);
        loop.postDelayed(this::finish, 220);
    }

    /** Tapping Speak on the widget while the sheet is already open switches it to recording. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (VOICE.equals(intent.getAction()) || RecordService.active) startSpeaking();
    }

    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    @Override protected void onPause() {
        super.onPause();
        loop.removeCallbacks(tick);
        if (!closing && !view.getSpeaking()) getSharedPreferences("capture", 0).edit().putString("draft", view.getText()).apply();
    }

    @Override protected void onResume() { super.onResume(); if (view.getSpeaking()) { loop.removeCallbacks(tick); loop.post(tick); } }
}
