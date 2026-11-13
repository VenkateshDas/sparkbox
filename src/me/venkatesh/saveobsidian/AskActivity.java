package me.venkatesh.saveobsidian;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** Old entry point for deep links and shortcuts: opens the Ask tab, with the question if one was given. */
public class AskActivity extends Activity {
    static final String QUESTION = "q";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Intent go = new Intent(this, MainActivity.class).putExtra(MainActivity.TAB, 3).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String q = getIntent().getStringExtra(QUESTION);
        if (q != null) go.putExtra(QUESTION, q);
        startActivity(go);
        finish();
    }
}
