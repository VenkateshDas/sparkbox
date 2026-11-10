package me.venkatesh.saveobsidian;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** How often each feature is used: counters in private storage, shown in Settings. Never leaves the phone. */
final class Usage {
    static final String[][] FEATURES = {
        {"capture", "Links and notes captured"}, {"island", "Island taps to annotate"}, {"annotate", "Why and ideas saved"}, {"delete", "Entries deleted"},
        {"related", "Related items opened"}, {"ask", "Questions asked"}, {"knowledge", "Knowledge opened"}, {"filed", "Notes filed to the vault"}, {"ingest", "Captures ingested into Knowledge"}};

    private static SharedPreferences prefs(Context c) { return c.getSharedPreferences("usage", 0); }

    static void hit(Context c, String feature) {
        SharedPreferences p = prefs(c);
        p.edit().putInt(feature, p.getInt(feature, 0) + 1).putLong(feature + ".last", System.currentTimeMillis()).apply();
    }

    /** One line per feature, most used first. */
    static List<String> lines(Context c) {
        SharedPreferences p = prefs(c);
        List<String[]> rows = new ArrayList<>();
        for (String[] f : FEATURES) rows.add(new String[]{f[1], String.valueOf(p.getInt(f[0], 0)), String.valueOf(p.getLong(f[0] + ".last", 0))});
        Collections.sort(rows, (a, b) -> Integer.parseInt(b[1]) - Integer.parseInt(a[1]));
        List<String> out = new ArrayList<>();
        for (String[] r : rows) {
            long last = Long.parseLong(r[2]);
            out.add(String.format(Locale.US, "%s: %s%s", r[0], r[1], last == 0 ? "" : " (last " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(new java.util.Date(last)) + ")"));
        }
        return out;
    }
}
