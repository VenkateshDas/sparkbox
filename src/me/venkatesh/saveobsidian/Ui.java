package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Sparkbox look: slate surfaces, system sans at two weights, an emerald accent. Dark and light palettes from the approved design. Sizes: 12 / 14 / 16 / 20 / 48. Spacing: multiples of 4.
 */
public final class Ui {
    final Context c;
    final int bg, surface, raised, ink, sub, muted, line, accent, onAccent, container, onContainer, warn, warnBg, err, errBg;
    final boolean dark;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL), BOLD = Typeface.create("sans-serif", Typeface.BOLD);

    /** The Theme setting: "system" (default), "dark" or "light". Read here so every screen follows it without recreating the system configuration. */
    static boolean dark(Context c) {
        String theme = c.getSharedPreferences("capture", 0).getString("theme", "system");
        if (theme.equals("dark")) return true;
        if (theme.equals("light")) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Palette from the approved design: dark left, light right. Light primary is #047857 so white text keeps 4.5:1. */
    Ui(Context c) {
        this.c = c;
        dark = dark(c);
        bg          = dark ? 0xFF0F141B : 0xFFF8FAFC;
        surface     = dark ? 0xFF171C23 : 0xFFFFFFFF;
        raised      = dark ? 0xFF1E242D : 0xFFF1F5F9;
        ink         = dark ? 0xFFF1F5F9 : 0xFF0F172A;
        sub         = dark ? 0xFF94A3B8 : 0xFF475569;
        muted       = sub;
        line        = dark ? 0xFF28323F : 0xFFCBD5E1;
        accent      = dark ? 0xFF10B981 : 0xFF047857;
        onAccent    = dark ? 0xFF0F141B : 0xFFFFFFFF;
        container   = dark ? 0xFF064E3B : 0xFFD1FAE5;
        onContainer = dark ? 0xFF6EE7B7 : 0xFF065F46;
        warn        = dark ? 0xFFF59E0B : 0xFFB45309;
        warnBg      = dark ? 0xFF2A2015 : 0xFFFEF3C7;
        err         = dark ? 0xFFFB7185 : 0xFFBE123C;
        errBg       = dark ? 0xFF2A1219 : 0xFFFFE4E6;
    }

    /** Status and navigation bar icons that stay readable on this palette. */
    void bars(android.app.Activity a) {
        View decor = a.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (android.os.Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
        decor.setSystemUiVisibility(dark ? flags & ~light : flags | light);
    }

    int dp(float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    /** The type scale, tighter than the sizes written at call sites: body 1.5 steps down (15 to 13.5, never under 11), headings compressed (17 to 15, 20 to 17, 28 to 23). */
    static float type(float sp) { return sp <= 16 ? Math.max(11, sp - 1.5f) : 15 + (sp - 17) * 0.7f; }

    TextView text(String value, float sp, Typeface face, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(type(sp));
        view.setTypeface(face);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.2f);
        view.setFontFeatureSettings("tnum");
        return view;
    }



    GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }



    static void buttonRole(View view) {
        view.setFocusable(true);
        view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                info.setSelected(host.isSelected());
            }
        });
    }

    static void insets(View view) {
        view.setFitsSystemWindows(false);
        view.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        view.requestApplyInsets();
    }



    static String host(String url) {
        String h = Uri.parse(url).getHost();
        return h == null ? "" : h.replaceFirst("^www\\.", "");
    }

    /** "Today", "Yesterday", "3 days ago", "4 Nov"; falls back to the raw stamp. */
    static String when(InboxFile.Entry e) {
        LocalDate d = InboxFile.day(e.time);
        if (d == null) return e.time;
        long n = ChronoUnit.DAYS.between(d, LocalDate.now());
        if (n == 0) return "Today";
        if (n == 1) return "Yesterday";
        if (n < 7) return n + " days ago";
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            + (d.getYear() == LocalDate.now().getYear() ? "" : " " + d.getYear());
    }
}
