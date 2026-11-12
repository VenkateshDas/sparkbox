package me.venkatesh.saveobsidian;

import android.view.View;

/** One screen of the main shell: a tab or a detail page. MainActivity owns navigation, theme and insets. */
abstract class Screen {
    MainActivity host;

    /** Builds the content view. Called again after a theme change. */
    abstract View build(Ui ui);

    /** The screen is now visible: refresh anything that may have changed. */
    void onShow() { }

    /** The screen is leaving: save drafts. */
    void onHide() { }

    /** True when the screen used the back press itself (closing a sheet, say). */
    boolean onBack() { return false; }
}
