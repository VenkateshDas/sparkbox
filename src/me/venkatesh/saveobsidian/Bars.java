package me.venkatesh.saveobsidian;

import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Header bar shared by detail screens: back arrow, title, 64 dp tall with a hairline under it. */
final class Bars {
    private Bars() { }

    static View header(Ui ui, String title, View.OnClickListener back) {
        LinearLayout box = new LinearLayout(ui.c);
        box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(ui.c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(ui.dp(4), 0, ui.dp(16), 0);
        TextView arrow = ui.text("‹", 28, Typeface.DEFAULT, ui.ink);
        arrow.setGravity(Gravity.CENTER);
        arrow.setContentDescription("Back");
        arrow.setOnClickListener(back);
        Ui.buttonRole(arrow);
        bar.addView(arrow, new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)));
        bar.addView(ui.text(title, 20, Ui.BOLD, ui.ink), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(63)));
        View rule = new View(ui.c);
        rule.setBackgroundColor(ui.line);
        box.addView(rule, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return box;
    }
}
