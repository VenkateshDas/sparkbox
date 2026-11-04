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
 * Sparkbox look: graphite surfaces, system sans at two weights, one green accent that only ever
 * means "your own words are attached". Sizes: 12 / 14 / 16 / 20 / 48. Spacing: multiples of 4.
 */
final class Ui {
    final Context c;
    final int bg, surface, raised, ink, sub, muted, line, accent;
    static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    Ui(Context c) {
        this.c = c;
        boolean dark = (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        bg      = dark ? 0xFF141415 : 0xFFFAFAFA;
        surface = dark ? 0xFF19191B : 0xFFFFFFFF;
        raised  = dark ? 0xFF232325 : 0xFFF4F4F5;
        ink     = dark ? 0xFFEDEDED : 0xFF171717;
        sub     = dark ? 0xFFA1A1A6 : 0xFF525252;
        muted   = dark ? 0xFF85858B : 0xFF737373;
        line    = (ink & 0xFFFFFF) | 0x14000000; // ink at 8%
        accent  = dark ? 0xFF4CC38A : 0xFF1B7F5C;
    }

    int dp(float v) { return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f); }

    LinearLayout.LayoutParams lp(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        return p;
    }

    TextView text(String value, float sp, Typeface face, int color) {
        TextView view = new TextView(c);
        view.setText(value);
        view.setTextSize(sp);
        view.setTypeface(face);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        view.setLineSpacing(0, 1.2f);
        view.setFontFeatureSettings("tnum");
        return view;
    }

    /** Section label: 14 medium, secondary. */
    TextView label(String value) { return text(value, 14, MEDIUM, sub); }

    GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(1), stroke);
        return shape;
    }

    Drawable ripple(Drawable content) {
        return new RippleDrawable(ColorStateList.valueOf((ink & 0xFFFFFF) | 0x1F000000), content, content == null ? new ColorDrawable(Color.BLACK) : null);
    }

    /** Primary: ink fill with page-coloured text. Secondary: raised surface with a hairline. */
    TextView button(String label, boolean primary, View.OnClickListener click) {
        TextView view = text(label, 14, MEDIUM, primary ? bg : ink);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(44));
        view.setPadding(dp(16), 0, dp(16), 0);
        view.setBackground(ripple(round(primary ? ink : raised, primary ? 0 : line, 10)));
        view.setOnClickListener(click);
        return view;
    }

    LinearLayout card(String heading) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(round(surface, line, 16));
        if (heading != null) {
            LinearLayout.LayoutParams p = lp(0);
            p.bottomMargin = dp(16);
            card.addView(label(heading), p);
        }
        return card;
    }

    /** Small status dot: filled accent when done, hollow hairline when not. */
    View dot(boolean done) {
        View v = new View(c);
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(done ? accent : 0);
        shape.setStroke(dp(1), done ? accent : muted);
        v.setBackground(shape);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(8), dp(8)));
        return v;
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
        if (n <= 0) return "Today";
        if (n == 1) return "Yesterday";
        if (n < 7) return n + " days ago";
        return d.getDayOfMonth() + " " + d.getMonth().getDisplayName(java.time.format.TextStyle.SHORT, Locale.getDefault())
            + (d.getYear() == LocalDate.now().getYear() ? "" : " " + d.getYear());
    }
}
