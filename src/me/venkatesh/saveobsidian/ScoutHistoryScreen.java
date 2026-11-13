package me.venkatesh.saveobsidian;

import android.view.View;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/** The list of Scout chats, newest first, with what each is doing now: running, or waiting for your OK. Tapping one opens it in the Scout tab. */
final class ScoutHistoryScreen extends Screen {
    @Override View build(Ui ui) {
        PageState page = new PageState();
        List<Block> b = new ArrayList<>();
        List<Nav> navs = new ArrayList<>();
        for (JSONObject s : Session.list(ScoutRuns.dir(ui.c))) {
            if (s.optInt("messages") == 0) continue;
            final String file = new File(s.optString("file")).getName();
            String name = s.optString("name"), title = name.isEmpty() ? s.optString("preview") : name;
            String state = ScoutRuns.waitingForYou(file) ? "Needs your OK" : ScoutRuns.run(file) != null ? "Working…" : "";
            navs.add(new Nav(title.isEmpty() ? "Chat" : title, state, ago(s.optLong("modified")), false, () -> { host.onBackPressed(); host.openSession(file); }));
        }
        if (navs.isEmpty()) b.add(new Body("No chats yet. Start one in the Scout tab.", true, false));
        else b.add(new Group(navs));
        page.publish(b);
        return PageView.create(ui.c, ui, "Chats", page, () -> host.onBackPressed());
    }

    private static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        return m < 1 ? "now" : m < 60 ? m + " min" : m < 1440 ? m / 60 + " h" : m / 1440 + " d";
    }
}
