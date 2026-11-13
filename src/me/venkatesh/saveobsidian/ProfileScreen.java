package me.venkatesh.saveobsidian;

import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** What is in the vault, how Sparkbox is set up, and how much each feature is used (counts stay on the phone). */
final class ProfileScreen extends Screen {
    @Override View build(Ui ui) {
        VaultStore.Snap snap = VaultStore.cached(ui.c);
        int sources = 0, topics = 0, people = 0, entities = 0, cards = 0;
        if (snap != null) for (Vault.Note n : snap.graph.notes) {
            if (n.catalog) continue;
            if (Layout.inSources(n.path)) sources++;
            else if (n.path.startsWith("Knowledge/Topics/")) topics++;
            else if (n.path.startsWith("Knowledge/People/")) people++;
            else if (n.path.startsWith("Knowledge/Entities/")) entities++;
            if (n.cardsText != null && !n.cardsText.isEmpty()) cards++;
        }
        List<Block> b = new ArrayList<>();
        b.add(new Hero("S", "Your Sparkbox", VaultStore.ready(ui.c) ? VaultStore.label(ui.c) : "No vault chosen yet"));
        b.add(new Heading("Knowledge base"));
        List<Nav> base = new ArrayList<>();
        base.add(new Nav("Notes", "", snap == null ? "—" : String.format(java.util.Locale.US, "%,d", snap.graph.notes.size())));
        base.add(new Nav("Sources", "", String.valueOf(sources)));
        base.add(new Nav("Topics", "", String.valueOf(topics)));
        base.add(new Nav("People", "", String.valueOf(people)));
        base.add(new Nav("Entities", "", String.valueOf(entities)));
        base.add(new Nav("Notes with recall cards", "", String.valueOf(cards)));
        b.add(new Group(base));
        b.add(new Heading("Account"));
        List<Nav> account = new ArrayList<>();
        account.add(new Nav("OpenRouter key", "", Model.key(ui.c).isEmpty() ? "Not set" : "Saved"));
        account.add(new Nav("Process captures into notes", "", Ingest.on(ui.c) ? "On" : "Off"));
        account.add(new Nav("Ingest model", Ingest.model(ui.c), ""));
        b.add(new Group(account));
        b.add(new Heading("Activity"));
        List<Nav> use = new ArrayList<>();
        for (String[] f : Usage.FEATURES) use.add(new Nav(f[1], "", String.valueOf(ui.c.getSharedPreferences("usage", 0).getInt(f[0], 0))));
        b.add(new Group(use));
        b.add(new Body("Counts stay on this phone.", true, false));
        PageState page = new PageState();
        page.publish(b);
        return PageView.create(ui.c, ui, "Profile", page, () -> host.onBackPressed());
    }
}
