package me.venkatesh.saveobsidian;

import android.Manifest;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** The capture sheet: write a thought or speak one. Opens from the widget, the Quick Settings tiles and the launcher shortcuts. */
public class QuickActivity extends Activity {
    static final String VOICE = "me.venkatesh.saveobsidian.VOICE";
    private static final int MIC = 7;
    private Ui ui;
    private FrameLayout root;
    private View scrim;
    private LinearLayout sheet, writePane, speakPane;
    private TextView tabWrite, tabSpeak, clock, hint, save;
    private EditText input;
    private Wave wave;
    private boolean speaking, closing, wasRecording;
    private long asked;
    private final Handler loop = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() { @Override public void run() { update(); loop.postDelayed(this, 60); } };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false); // the app switcher must not show inbox contents
        ui = new Ui(this);
        root = new FrameLayout(this);
        scrim = new View(this);
        scrim.setBackgroundColor(0xA6000000);
        scrim.setOnClickListener(v -> close());
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(20));
        GradientDrawable top = ui.round(ui.surface, ui.line, 0);
        top.setCornerRadii(new float[]{ui.dp(28), ui.dp(28), ui.dp(28), ui.dp(28), 0, 0, 0, 0});
        sheet.setBackground(top);
        sheet.setClickable(true);
        root.addView(sheet, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        Ui.insets(root);
        setContentView(root);
        Ui.back(this, this::onBackPressed);

        View handle = new View(this);
        handle.setBackground(ui.round(ui.line, 0, 2));
        LinearLayout.LayoutParams h = new LinearLayout.LayoutParams(ui.dp(36), ui.dp(4));
        h.gravity = Gravity.CENTER_HORIZONTAL;
        sheet.addView(handle, h);
        sheet.addView(header(), ui.lp(16));
        FrameLayout panes = new FrameLayout(this);
        panes.addView(writePane());
        panes.addView(speakPane());
        sheet.addView(panes, ui.lp(8));

        boolean voice = VOICE.equals(getIntent().getAction()) || RecordService.active;
        show(voice && state == null || RecordService.active);
        if (voice && state == null) startSpeaking();
        root.post(this::enter);
    }

    // ---- layout ----

    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout segments = new LinearLayout(this);
        segments.setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3));
        segments.setBackground(ui.round(ui.raised, ui.line, 14));
        tabWrite = segment("Write", v -> { if (!RecordService.active) show(false); });
        tabSpeak = segment("Speak", v -> startSpeaking());
        segments.addView(tabWrite);
        segments.addView(tabSpeak);
        row.addView(segments);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        TextView where = ui.text(CaptureService.inboxName(this), 12, Typeface.DEFAULT, ui.muted);
        where.setGravity(Gravity.END);
        where.setMaxLines(1);
        where.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        row.addView(where, new LinearLayout.LayoutParams(ui.dp(140), -2));
        return row;
    }

    private TextView segment(String label, View.OnClickListener click) {
        TextView view = ui.text(label, 14, Ui.MEDIUM, ui.sub);
        view.setGravity(Gravity.CENTER);
        view.setMinWidth(ui.dp(88));
        view.setMinHeight(ui.dp(36));
        view.setOnClickListener(click);
        Ui.buttonRole(view);
        return view;
    }

    private LinearLayout writePane() {
        writePane = new LinearLayout(this);
        writePane.setOrientation(LinearLayout.VERTICAL);
        input = new EditText(this);
        input.setHint("Write a thought");
        input.setHintTextColor(ui.muted);
        input.setTextColor(ui.ink);
        input.setTextSize(18);
        input.setLineSpacing(0, 1.25f);
        input.setBackground(null);
        input.setPadding(0, ui.dp(8), 0, ui.dp(8));
        input.setMinLines(4);
        input.setMaxLines(9);
        input.setGravity(Gravity.TOP);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setText(getSharedPreferences("capture", 0).getString("draft", ""));
        input.setSelection(input.getText().length());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { ready(); }
        });
        writePane.addView(input, new LinearLayout.LayoutParams(-1, -2));
        save = ui.button("Save", true, v -> saveText());
        writePane.addView(save, ui.lp(12));
        ready();
        return writePane;
    }

    /** Save is quiet until there is something to save, then becomes the one filled control. */
    private void ready() {
        boolean on = !input.getText().toString().trim().isEmpty();
        save.setBackground(ui.ripple(ui.round(on ? ui.ink : ui.raised, on ? 0 : ui.line, 10)));
        save.setTextColor(on ? ui.bg : ui.muted);
        save.setEnabled(on);
    }

    private LinearLayout speakPane() {
        speakPane = new LinearLayout(this);
        speakPane.setOrientation(LinearLayout.VERTICAL);
        speakPane.setGravity(Gravity.CENTER_HORIZONTAL);
        speakPane.setVisibility(View.GONE);
        clock = ui.text("0:00", 52, Ui.MEDIUM, ui.ink);
        speakPane.addView(clock, new LinearLayout.LayoutParams(-2, -2));
        wave = new Wave(this, ui);
        speakPane.addView(wave, new LinearLayout.LayoutParams(-1, ui.dp(64)));
        ((LinearLayout.LayoutParams) wave.getLayoutParams()).topMargin = ui.dp(20);
        hint = ui.text(Model.key(this).isEmpty() ? "Add an OpenRouter key in Settings to turn recordings into text. Until then they wait on this phone." : "Sent to OpenRouter for a transcript, then the audio is deleted", 13, Typeface.DEFAULT, ui.sub);
        hint.setGravity(Gravity.CENTER);
        speakPane.addView(hint, ui.lp(16));
        LinearLayout row = new LinearLayout(this);
        row.addView(ui.button("Discard", false, v -> { startService(new Intent(this, RecordService.class).setAction(RecordService.CANCEL)); close(); }));
        TextView stop = ui.button("Stop and save", true, v -> { v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); startService(new Intent(this, RecordService.class).setAction(RecordService.STOP)); close(); });
        LinearLayout.LayoutParams grow = new LinearLayout.LayoutParams(0, -2, 1);
        grow.leftMargin = ui.dp(8);
        row.addView(stop, grow);
        speakPane.addView(row, ui.lp(24));
        return speakPane;
    }

    // ---- behaviour ----

    private void show(boolean speak) {
        speaking = speak;
        writePane.setVisibility(speak ? View.GONE : View.VISIBLE);
        speakPane.setVisibility(speak ? View.VISIBLE : View.GONE);
        tabWrite.setBackground(speak ? null : ui.round(ui.surface, ui.line, 11));
        tabWrite.setTextColor(speak ? ui.sub : ui.ink);
        tabSpeak.setBackground(speak ? ui.round(ui.surface, ui.line, 11) : null);
        tabSpeak.setTextColor(speak ? ui.ink : ui.sub);
        tabWrite.setSelected(!speak); tabSpeak.setSelected(speak);
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (speak) keyboard.hideSoftInputFromWindow(root.getWindowToken(), 0);
        else { input.requestFocus(); getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE); keyboard.showSoftInput(input, 0); }
        loop.removeCallbacks(tick);
        if (speak) loop.post(tick);
    }

    private void startSpeaking() {
        if (RecordService.active) { show(true); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MIC); return; }
        asked = System.currentTimeMillis();
        wasRecording = false;
        wave.clear();
        startForegroundService(new Intent(this, RecordService.class).setAction(RecordService.START));
        sheet.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        show(true);
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != MIC) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) { InboxWidget.refresh(this); startSpeaking(); }
        else { Toast.makeText(this, "Allow the microphone to record", Toast.LENGTH_LONG).show(); show(false); }
    }

    /** Every 60 ms while speaking: timer, waveform, and noticing a recording that was stopped from the widget or the notification. */
    private void update() {
        if (!speaking || closing) return;
        if (RecordService.active) {
            wasRecording = true;
            long s = (System.currentTimeMillis() - RecordService.startedMs) / 1000;
            clock.setText(s / 60 + ":" + (s % 60 < 10 ? "0" : "") + s % 60);
            wave.push(RecordService.level());
        } else if (wasRecording) close();
        else if (System.currentTimeMillis() - asked > 3000) { Toast.makeText(this, "Could not start recording", Toast.LENGTH_LONG).show(); show(false); }
    }

    private void saveText() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        try {
            CaptureService.enqueue(this, "text", "", "", text);
            getSharedPreferences("capture", 0).edit().remove("draft").apply();
            ShareActivity.dispatch(this);
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
            closing = true;
            close();
        } catch (Exception e) { closing = false; Toast.makeText(this, "Could not save: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }

    // ---- motion: the sheet rises and settles, then leaves the way it came ----

    private void enter() {
        if (!ValueAnimator.areAnimatorsEnabled()) return;
        sheet.setTranslationY(sheet.getHeight());
        scrim.setAlpha(0);
        sheet.animate().translationY(0).setDuration(240).setInterpolator(new DecelerateInterpolator(2)).start();
        scrim.animate().alpha(1).setDuration(200).start();
    }

    private void close() {
        boolean saved = closing;
        closing = true;
        loop.removeCallbacks(tick);
        if (!saved && !speaking) getSharedPreferences("capture", 0).edit().putString("draft", input.getText().toString()).apply();
        getSystemService(InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return; }
        scrim.animate().alpha(0).setDuration(160).start();
        sheet.animate().translationY(sheet.getHeight()).setDuration(180).setInterpolator(new AccelerateInterpolator(1.6f)).withEndAction(this::finish).start();
    }

    /** Tapping Speak on the widget while the sheet is already open switches it to recording. */
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (VOICE.equals(intent.getAction()) || RecordService.active) startSpeaking();
    }

    @Override public void onBackPressed() { close(); }

    @Override public void finish() { super.finish(); overridePendingTransition(0, 0); }

    @Override protected void onPause() {
        super.onPause();
        loop.removeCallbacks(tick);
        if (!closing && !speaking) getSharedPreferences("capture", 0).edit().putString("draft", input.getText().toString()).apply();
    }

    @Override protected void onResume() { super.onResume(); if (speaking) { loop.removeCallbacks(tick); loop.post(tick); } }

    /** Scrolling loudness bars: the newest on the right in the accent, older ones fading out to the left. */
    static final class Wave extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float[] levels = new float[160];
        private final float dp;
        private final int color;
        private float smooth;

        Wave(Context c, Ui ui) { super(c); dp = ui.dp(1); color = ui.accent; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }

        void clear() { java.util.Arrays.fill(levels, 0); smooth = 0; invalidate(); }

        void push(float raw) {
            float target = (float) Math.pow(Math.min(1f, raw * 3f), 0.7);
            smooth += (target - smooth) * 0.6f;
            System.arraycopy(levels, 1, levels, 0, levels.length - 1);
            levels[levels.length - 1] = smooth;
            invalidate();
        }

        @Override protected void onDraw(Canvas canvas) {
            float bar = 3 * dp, gap = 4 * dp, min = 4 * dp, mid = getHeight() / 2f;
            int count = Math.min(levels.length, (int) (getWidth() / (bar + gap)));
            for (int i = 0; i < count; i++) {
                float v = levels[levels.length - 1 - i];
                float half = Math.max(min, v * getHeight()) / 2f, right = getWidth() - i * (bar + gap);
                paint.setColor(color);
                paint.setAlpha((int) (255 * Math.max(0.12f, 1f - i / (float) count)));
                canvas.drawRoundRect(new RectF(right - bar, mid - half, right, mid + half), bar / 2, bar / 2, paint);
            }
        }
    }
}
