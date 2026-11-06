import me.venkatesh.saveobsidian.Capture;
import me.venkatesh.saveobsidian.AppendCheck;
import me.venkatesh.saveobsidian.InboxFile;
import me.venkatesh.saveobsidian.Enrich;
import me.venkatesh.saveobsidian.Index;
import me.venkatesh.saveobsidian.Vault;
import java.util.List;
import me.venkatesh.saveobsidian.VerifiedEdit;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class CaptureTest {
    static int count;
    public static void main(String[] args) throws Exception {
        check(Capture.firstUrl("A caption\nhttps://example.com/post?a=1&b=two+three\nRead it").equals("https://example.com/post?a=1&b=two+three"));
        check(Capture.firstUrl("https://youtu.be/Abc?t=42").equals("https://youtu.be/Abc?t=42"));
        check(Capture.pageTitle("<HTML><TITLE class='x'>You don&#x27;t\nneed long context</TITLE></HTML>").equals("You don&#x27;t\nneed long context"));
        check(Capture.pageTitle("<html>Login</html>").isEmpty());
        check(Capture.pageTitle("<title>Plain</title><meta property=\"og:title\" content=\"Rich\">").equals("Rich"));
        check(Capture.pageTitle("<meta content='Rev' name='twitter:title'><title>Plain</title>").equals("Rev"));
        check(Capture.pageTitle("<meta property=\"og:title\" content=\"\"><title>Plain</title>").equals("Plain"));
        check(Capture.entry("2026-11-04 21:55", "  A\ntitle  ", "https://example.com").equals("- 🔗 A title\n  https://example.com\n  Captured: 2026-11-04 21:55 · via Sparkbox\n"));
        check(Capture.entry("2026-11-04 21:55", "", "https://example.com").startsWith("- 🔗 https://example.com\n"));
        check(Capture.timestamp().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
        try { Capture.firstUrl("no link"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        try { Capture.firstUrl("https://name:password@example.com"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }

        check(Capture.cleanUrl("https://a.com/p?utm_source=x&id=7&fbclid=z#top").equals("https://a.com/p?id=7#top"));
        check(Capture.cleanUrl("https://youtube.com/post/Ugk?si=abc").equals("https://youtube.com/post/Ugk"));
        check(Capture.cleanUrl("https://youtu.be/Abc?t=42&si=q").equals("https://youtu.be/Abc?t=42"));
        check(Capture.cleanUrl("https://a.com/p").equals("https://a.com/p"));

        String u = "https://a.com/x";
        check(Capture.isGeneric("YouTube", u) && Capture.isGeneric("", u) && Capture.isGeneric(u, u) && !Capture.isGeneric("Real title", u));
        check(Capture.chooseTitle("YouTube", "", "Fetched", u).equals("Fetched"));
        check(Capture.chooseTitle("Subject", "Lead", "Fetched", u).equals("Subject"));
        check(Capture.chooseTitle("", "Lead", "Fetched", u).equals("Lead"));
        check(Capture.chooseTitle("YouTube", "", "LinkedIn", u).equals(u));
        check(Capture.leadingText("Great read https://a.com/x", u).equals("Great read"));
        check(Capture.leadingText("https://a.com/x", u).isEmpty());

        check(Capture.textEntry("T", "Just a thought").equals("- 📝 Just a thought\n  Captured: T · via Sparkbox\n"));
        check(InboxFile.parse(Capture.textEntry("T", "One\n\nTwo\r\nThree")).get(0).body.equals("One\n\nTwo\nThree"));
        String longLine = "x".repeat(100);
        check(InboxFile.parse(Capture.textEntry("T", longLine)).get(0).body.equals(longLine));
        check(InboxFile.parse(Capture.textEntry("T", "y".repeat(2500))).get(0).body.equals("y".repeat(2500)));

        check(Capture.separator(new byte[0]).isEmpty());
        check(Capture.separator("a".getBytes()).equals("\n\n"));
        check(Capture.separator("a\n".getBytes()).equals("\n"));
        check(Capture.separator("a\n\n".getBytes()).isEmpty());
        check(Capture.separator("a\n\n\n".getBytes()).isEmpty());

        String inbox = "- 🔗 T\n  https://a.com/x/\n\n- 2026 — T\n  <https://b.com/y>\n";
        check(Capture.alreadyListed(inbox, "https://a.com/x"));
        check(Capture.alreadyListed(inbox, "https://b.com/y"));
        check(!Capture.alreadyListed(inbox, "https://a.com/xy"));
        check(!Capture.alreadyListed(inbox, "https://a.com"));

        inboxFileChecks();
        regressionChecks();
        editFailureChecks();
        vaultChecks();
        check(AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,9,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3,3}));
        System.out.println("Capture and append checks passed (" + count + ").");
    }
    static void vaultChecks() {
        String topic = "---\ntype: topic\nlast-updated: 2026-11-06\nrelated-topics: ['[[AI Agent Architecture]]', '[[RAG]]']\ndescription: LLM Memory Systems\n---\n\n## Core Mechanism\nPlain.\n"
            + "| Claim | Source |\n|---|---|\n| Memory | [[Sources/AI Systems/2026/2026-04-12 - Harness\\|Harness]] |\n";
        Vault.Note t = Vault.parse("Knowledge/Topics/LLM Memory Systems.md", 5, "\uFEFF" + topic.replace("\n", "\r\n"));
        check(t.title.equals("LLM Memory Systems") && t.type.equals("topic") && t.updated.equals("2026-11-06") && t.description.equals("LLM Memory Systems"));
        check(t.topics.equals(Arrays.asList("AI Agent Architecture", "RAG")));
        check(t.links.equals(Arrays.asList("AI Agent Architecture", "RAG", "Sources/AI Systems/2026/2026-04-12 - Harness")));
        check(t.cite().equals("[[Knowledge/Topics/LLM Memory Systems|LLM Memory Systems]]") && t.folder().equals("Knowledge/Topics") && !t.catalog);
        Vault.Note src = Vault.parse("Sources/AI Systems/2026/2026-04-12 - Harness.md", 1,
            "---\ntype: \"source\"\ntopics: [\"[[LLM Memory Systems]]\"]\ncaptured: \"06/10/26, 8:20 AM\"\n---\n\n# Harness\n\n## Raw Content\n\nOriginal capture: https://example.org/a-long-link-here-for-sure\n\n### Key claims\n\n- Memory is a harness job, not the model's.\n- Second.\n![[pic.png]] [[Knowledge/Topics/Missing#Heading|alias]]\n");
        check(src.title.equals("Harness") && src.description.equals("Memory is a harness job, not the model's.") && src.updated.equals("06/10/26, 8:20 AM"));
        check(src.links.equals(Arrays.asList("LLM Memory Systems", "Knowledge/Topics/Missing")) && src.topics.equals(Arrays.asList("LLM Memory Systems")));
        check(Vault.parse("Sources/X/2026/2026-06-02 - Plain Name.md", 1, "text").title.equals("Plain Name"));
        StringBuilder hostile = new StringBuilder();
        for (int i = 0; i < 100000; i++) hostile.append("[[a ");
        long began = System.nanoTime();
        Vault.Note flood = Vault.parse("Sources/Flood.md", 1, "# Flood\n" + hostile + "\n[[Real]] next");
        check((System.nanoTime() - began) < 2_000_000_000L && flood.links.contains("Real") && Vault.clipLines("a\nb").equals("a\nb")); // one 400 KB line must not stall the scan
        check(Vault.parse("K/n.md", 1, "*one* and **two** and `three`").digest.contains("one"));
        Vault.Note bare = Vault.parse("Knowledge/People/Ada.md", 1, "no frontmatter\n---\nnot yaml\n");
        check(bare.title.equals("Ada") && bare.type.isEmpty() && bare.digest.startsWith("Ada."));
        check(Vault.parse("x.md", 1, "---\ntype: broken\nnever closed\n").type.isEmpty()); // unclosed frontmatter is body
        Vault.Note index = Vault.parse("Knowledge/index.md", 1, "# Knowledge Index\n");
        Vault.Note dup1 = Vault.parse("A/Same.md", 1, "x"), dup2 = Vault.parse("B/Same.md", 1, "x");
        Vault.Note a = Vault.parse("A/Linker.md", 1, "[[Same]] [[LLM Memory Systems]] [[Linker]] [[Knowledge/Topics/llm memory systems.md#h]] [[Nothing]]");
        Vault.Graph g = Vault.build(Arrays.asList(t, src, bare, index, dup1, dup2, a));
        check(index.catalog && g.resolve("llm memory systems") == t && g.resolve("Knowledge/Topics/LLM Memory Systems.md") == t);
        check(g.resolve("Same") == null && g.resolve("A/Same") == dup1 && g.resolve("Nothing") == null); // ambiguous name is never guessed
        check(g.out(a).equals(Arrays.asList(t)) && g.in(t).contains(a) && g.in(t).contains(src) && !g.in(a).contains(a));
        check(g.out(t).contains(src) && g.in(src).contains(t));
        check(g.ambiguous == 1 && g.unresolved >= 3 && g.orphans().contains(bare) && !g.orphans().contains(index));
        check(Vault.target("X\\").equals("X") && Vault.target("a/b.md#h").equals("a/b") && Vault.label("a/b|Shown").equals("Shown") && Vault.label("a/b").equals("b"));
        // search: every word first, rarer and title words weigh more; fusion favours notes both lists like
        Vault.Note mem = Vault.parse("Knowledge/Topics/LLM Memory Systems.md", 1, "# LLM Memory Systems\nHow an agent keeps memory between sessions in files.");
        List<Vault.Note> pile = new java.util.ArrayList<>(Arrays.asList(mem));
        for (int i = 0; i < 8; i++) pile.add(Vault.parse("Sources/A/2026/Agent " + i + ".md", 10 + i, "# Agent " + i + "\nAn agent does tool calls and planning " + i));
        pile.add(Vault.parse("Knowledge/Topics/Memory Palace.md", 3, "# Memory Palace\nA mnemonic technique for people."));
        List<Vault.Note> found = Vault.search(pile, "agent memory", 5);
        check(found.get(0) == mem); // the only note with both words leads the many that mention agents
        check(Vault.search(pile, "palace", 5).size() == 1 && Vault.search(pile, "zebra", 5).isEmpty() && Vault.search(pile, "  ", 5).isEmpty());
        check(Vault.search(pile, "memory", 5).get(0).title.equals("Memory Palace") || Vault.search(pile, "memory", 5).get(0) == mem); // title word beats body word
        check(Vault.search(Arrays.asList(index), "knowledge", 5).isEmpty()); // index and log files are never ranked
        List<Vault.Note> fused = Vault.fuse(Arrays.asList(pile.get(1), mem), Arrays.asList(mem, pile.get(2)), 1, 3);
        check(fused.get(0) == mem && fused.size() == 3);
        // a short query trusts the word match and meaning only adds what it missed; a question fuses both
        List<Vault.Note> words = new java.util.ArrayList<>(Arrays.asList(mem));
        words.addAll(pile.subList(1, 6));
        List<Vault.Note> shortQuery = Vault.blend(Arrays.asList(pile.get(8), pile.get(1)), words, "agent memory", 20);
        check(shortQuery.get(0) == mem && shortQuery.contains(pile.get(8)) && shortQuery.size() == 7);
        check(Vault.blend(Arrays.asList(pile.get(8), pile.get(1)), words, "how do agents keep memory between sessions", 20).get(0) == pile.get(1)); // in both lists
        check(t.contains("memory core") && !t.contains("memory zebra") && src.contains("harness job"));
    }
    static void inboxFileChecks() {
        String head = "---\ntype: inbox\n---\n# Sources Inbox\n\n*Landing pad.*\n\n";
        String telegram = "- 🔗 Telegram title\n  https://t.example/a\n  Captured: 18/10/26, 7:36 AM\n";
        String legacy = "- 2026-11-04 22:24 — Legacy\n  <https://l.example/b>\n";
        String mine = "- 🔗 Mine\n  https://m.example/c\n  Captured: 2026-11-04 23:13 · via Sparkbox\n";
        String note = "- 📝 A thought\n  Captured: 2026-11-04 23:14 · via Sparkbox\n";
        String file = head + telegram + "\n" + legacy + "\n" + mine + "\n" + note;
        List<InboxFile.Entry> all = InboxFile.parse(file);
        check(all.size() == 4);
        check(all.get(0).title.equals("Telegram title") && all.get(0).url.equals("https://t.example/a") && all.get(0).time.equals("18/10/26, 7:36 AM"));
        check(all.get(1).url.equals("https://l.example/b") && all.get(1).title.equals("Legacy") && all.get(1).time.equals("2026-11-04 22:24"));
        check(all.get(2).title.equals("Mine") && !all.get(2).text && all.get(3).text && all.get(3).url.isEmpty());

        String second = all.get(2).raw;
        String withNotes = InboxFile.setNotes(file, second, "Because X", "Idea one\n\nIdea two");
        List<InboxFile.Entry> after = InboxFile.parse(withNotes);
        check(after.size() == 4 && after.get(2).why.equals("Because X") && after.get(2).ideas.equals("Idea one\n\nIdea two"));
        check(withNotes.contains("  Why: Because X\n  Ideas: Idea one\n    \n    Idea two\n\n- 📝"));
        check(after.get(2).url.equals("https://m.example/c") && after.get(2).time.startsWith("2026-11-04 23:13"));
        String edited = InboxFile.setNotes(withNotes, after.get(2).raw, "Changed", "");
        check(InboxFile.parse(edited).get(2).why.equals("Changed") && InboxFile.parse(edited).get(2).ideas.isEmpty() && !edited.contains("Idea one"));
        check(InboxFile.setNotes(edited, InboxFile.parse(edited).get(2).raw, "", "").equals(file));

        check(InboxFile.delete(file, second).equals(head + telegram + "\n" + legacy + "\n" + note));
        check(InboxFile.delete(file, all.get(3).raw).equals(head + telegram + "\n" + legacy + "\n" + mine + "\n"));
        check(InboxFile.parse(InboxFile.delete(InboxFile.delete(InboxFile.delete(InboxFile.delete(file, all.get(0).raw), all.get(1).raw), all.get(2).raw), all.get(3).raw)).isEmpty());
        check(InboxFile.delete(head + mine, mine.trim()).equals(head));
        try { InboxFile.delete(file, "- 🔗 gone\n  https://x.example"); throw new AssertionError(); } catch (IllegalStateException expected) { }
        try { InboxFile.setNotes(file, "- 🔗 Mine", "w", ""); throw new AssertionError(); } catch (IllegalStateException expected) { }
        check(InboxFile.day("2026-11-04 23:13 · via Sparkbox").equals(java.time.LocalDate.of(2026, 11, 4)));
        check(InboxFile.day("18/10/26, 7:36 AM").equals(java.time.LocalDate.of(2026, 10, 18)));
        check(InboxFile.day("") == null && InboxFile.day("2026-13-45 10:00") == null && InboxFile.day("soon") == null);
        check(InboxFile.parse("no entries here\n\n# Heading\n").isEmpty());
    }
    static void regressionChecks() {
        String text = "  Indent\n\nWhy: body text, not metadata\n    ```\n- 📝 Not another capture\n  Captured: also body\n\n" + "z".repeat(5000) + "\n";
        String rendered = Capture.textEntry("2026-11-05 12:00", text);
        List<InboxFile.Entry> parsed = InboxFile.parse(rendered);
        check(parsed.size() == 1 && parsed.get(0).body.equals(text));
        check(parsed.get(0).why.isEmpty());
        String withNotes = InboxFile.setNotes(rendered, parsed.get(0), "Why now", "My idea");
        check(InboxFile.parse(withNotes).get(0).body.equals(text));
        check(InboxFile.parse(withNotes).get(0).why.equals("Why now"));
        String annotation = "\n  Indented\n\nend\n";
        check(InboxFile.parse(InboxFile.setNotes(rendered, parsed.get(0), annotation, annotation)).get(0).why.equals(annotation));
        check(InboxFile.parse(InboxFile.setNotes(rendered, parsed.get(0), annotation, annotation)).get(0).ideas.equals(annotation));
        String front = "---\ntags:\n- research\n- 📝 YAML value\n---\n# Inbox\n\n";
        String fenced = "```md\n- 📝 Example\n  Captured: not real\n```\n";
        check(InboxFile.parse(front + fenced + rendered).size() == 1);
        check(InboxFile.delete(front + fenced + rendered, InboxFile.parse(front + fenced + rendered).get(0)).equals(front + fenced));
        check(InboxFile.parse("- ordinary list\n  leave alone\n").isEmpty());
        String entry = Capture.entry("2026-11-05 12:00", "Same", "https://example.com");
        String twins = entry + "\n" + entry;
        String edited = InboxFile.setNotes(twins, InboxFile.parse(twins).get(1), "Second", "");
        check(InboxFile.parse(edited).get(0).why.isEmpty() && InboxFile.parse(edited).get(1).why.equals("Second"));
        check(InboxFile.delete(twins, InboxFile.parse(twins).get(1)).equals(entry + "\n"));
        try { InboxFile.setNotes(twins, InboxFile.parse(twins).get(1).raw, "", ""); throw new AssertionError(); }
        catch (IllegalStateException expected) { check(true); }
        check(InboxFile.containsEntry(entry, entry));
        check(!InboxFile.containsEntry(entry.substring(0, entry.indexOf("  Captured:")), entry));
        check(!InboxFile.containsEntry("```\n" + entry + "```", entry));
        String link = entry + Capture.block("Shared text", "Caption\nhttps://example.com?si=keep\nhttps://second.example\n");
        check(InboxFile.parse(link).get(0).sharedText.equals("Caption\nhttps://example.com?si=keep\nhttps://second.example\n"));
        check(Capture.cleanUrl("https://example.com/?si=required&id=7").equals("https://example.com/?si=required&id=7"));
        check(Capture.firstUrl("See https://example.com/post). More").equals("https://example.com/post"));
        check(Capture.firstUrl("See https://example.com/Function_(math).").equals("https://example.com/Function_(math)"));
        check(Capture.firstUrl("https://example.com/?x=1&y=2").endsWith("?x=1&y=2"));
    }
    static byte[] bytes(String s) { return s.getBytes(StandardCharsets.UTF_8); }
    static void editFailureChecks() throws Exception {
        byte[] original = bytes("original"), changed = bytes("changed"), external = bytes("external");
        byte[][] disk = {original}, backup = {null}, attempted = {null};
        int[] writes = {0};
        VerifiedEdit.apply(original, changed, () -> disk[0], b -> { writes[0]++; disk[0] = b; }, (b, a) -> { backup[0] = b; attempted[0] = a; });
        check(writes[0] == 1 && Arrays.equals(disk[0], changed) && Arrays.equals(backup[0], original) && Arrays.equals(attempted[0], changed));
        writes[0] = 0; disk[0] = external; backup[0] = null;
        try { VerifiedEdit.apply(original, changed, () -> disk[0], b -> writes[0]++, (b, a) -> backup[0] = b); throw new AssertionError(); }
        catch (IOException expected) { check(writes[0] == 0 && backup[0] == null && Arrays.equals(disk[0], external)); }
        disk[0] = original;
        try { VerifiedEdit.apply(original, changed, () -> disk[0], b -> writes[0]++, (b, a) -> { backup[0] = b; disk[0] = external; }); throw new AssertionError(); }
        catch (IOException expected) { check(writes[0] == 0 && Arrays.equals(disk[0], external) && Arrays.equals(backup[0], original)); }
        disk[0] = original;
        try { VerifiedEdit.apply(original, changed, () -> disk[0], b -> { writes[0]++; disk[0] = bytes("partial"); throw new IOException("Disk full"); }, (b, a) -> { backup[0] = b; attempted[0] = a; }); throw new AssertionError(); }
        catch (IOException expected) { check(writes[0] == 1 && Arrays.equals(disk[0], bytes("partial")) && Arrays.equals(backup[0], original) && expected.getMessage().contains("No automatic restore")); }
        disk[0] = original; writes[0] = 0;
        try { VerifiedEdit.apply(original, changed, () -> disk[0], b -> { writes[0]++; disk[0] = external; }, (b, a) -> backup[0] = b); throw new AssertionError(); }
        catch (IOException expected) { check(writes[0] == 1 && Arrays.equals(disk[0], external)); }
        disk[0] = original; writes[0] = 0;
        try { VerifiedEdit.apply(original, changed, () -> disk[0], b -> writes[0]++, (b, a) -> { throw new IOException("Backup unavailable"); }); throw new AssertionError(); }
        catch (IOException expected) { check(writes[0] == 0 && Arrays.equals(disk[0], original)); }
        VerifiedEdit.apply(original, original, () -> disk[0], b -> { throw new AssertionError(); }, (b, a) -> { throw new AssertionError(); });
        check(Arrays.equals(disk[0], original));
        enrichChecks();
        indexChecks();
        captureKindChecks();
    }
    private static void captureKindChecks() {
        // voice notes are ordinary notes that say where they came from
        String voice = Capture.textEntry("2026-11-05 14:32", "Idea: transcribe voice", "Sparkbox voice");
        check(voice.equals("- 📝 Idea: transcribe voice\n  Captured: 2026-11-05 14:32 · via Sparkbox voice\n"));
        check(InboxFile.parse(voice).get(0).text && InboxFile.parse(voice).get(0).time.equals("2026-11-05 14:32 · via Sparkbox voice"));
        // file entries: embed on its own line, parsed back without becoming a link or note
        String photo = Capture.fileEntry("2026-11-05 14:32", "Whiteboard", "20261105 1432 IMG-ab12.jpg", true);
        check(photo.equals("- 🖼 Whiteboard\n  ![[20261105 1432 IMG-ab12.jpg]]\n  Captured: 2026-11-05 14:32 · via Sparkbox\n"));
        InboxFile.Entry e = InboxFile.parse(photo).get(0);
        check(e.file.equals("20261105 1432 IMG-ab12.jpg") && !e.text && e.url.isEmpty() && e.title.equals("Whiteboard"));
        check(InboxFile.parse(Capture.fileEntry("T", "", "a.pdf", false)).get(0).title.equals("a.pdf"));
        check(Capture.fileEntry("T", "Doc", "a.pdf", false).startsWith("- 📎 Doc"));
        // enrichment lines go on a file entry and the embed survives
        String tagged = InboxFile.setEnrich(photo, photo.trim(), "image", "whiteboard, plan", "A whiteboard sketch.");
        InboxFile.Entry t = InboxFile.parse(tagged).get(0);
        check(t.file.equals(e.file) && t.type.equals("image") && t.tldr.equals("A whiteboard sketch.") && t.contains("whiteboard"));
        // a file entry between two links keeps the neighbours intact
        String mixed = Capture.entry("T", "A", "https://a.example") + "\n" + photo + "\n" + Capture.textEntry("T", "note");
        check(InboxFile.parse(mixed).size() == 3 && InboxFile.parse(mixed).get(1).file.length() > 0);
        // names are safe for Obsidian links and unique per share
        String name = Capture.fileName("2026-11-05 14:32", "Q3 [draft] #1 | plan^2.PDF", "ab12");
        check(name.equals("2026-11-05 1432 Q3 draft 1 plan 2-ab12.PDF"));
        check(Capture.fileName("T 1:2", "../../etc/passwd", "x").indexOf('/') < 0);
        check(Capture.fileName("T", "", "x").startsWith("T file-x"));
        // model labels now cover notes and pictures
        check(Enrich.clean("question", java.util.Arrays.asList("a"), "x")[0].equals("question") && Enrich.clean("image", java.util.Arrays.asList("a"), "x")[0].equals("image"));
        check(Enrich.AUDIO.get("opus").equals("ogg") && Enrich.AUDIO.get("m4a").equals("m4a") && Enrich.AUDIO.get("amr") == null);
    }
    private static void enrichChecks() {
        String inbox = "- 🔗 SQLite internals\n  https://a.com/x\n  Captured: 2026-11-05 10:00 · via Sparkbox\n  Why: for the db talk\n\n- 🔗 Other\n  https://b.com\n  Captured: 2026-11-05 10:01 · via Sparkbox\n";
        String raw = InboxFile.parse(inbox).get(0).raw;
        String tagged = InboxFile.setEnrich(inbox, raw, "article", "sqlite, db", "How SQLite stores pages.");
        InboxFile.Entry e = InboxFile.parse(tagged).get(0);
        check(e.type.equals("article") && e.tags.equals("sqlite, db") && e.tldr.equals("How SQLite stores pages.") && e.why.equals("for the db talk"));
        check(InboxFile.parse(tagged).get(1).type.isEmpty() && InboxFile.parse(tagged).size() == 2);
        String titled = InboxFile.setEnrich(inbox, raw, "article", "db", "x", "Inside SQLite pages");
        InboxFile.Entry g = InboxFile.parse(titled).get(0);
        check(g.heading.equals("Inside SQLite pages") && g.title.equals("SQLite internals") && g.shownTitle().equals("Inside SQLite pages") && InboxFile.parse(inbox).get(0).shownTitle().equals("SQLite internals") && g.why.equals("for the db talk"));
        check(InboxFile.parse(InboxFile.setEnrich(titled, g.raw, "article", "db", "x", "Second")).get(0).heading.equals("Second") && !InboxFile.setEnrich(titled, g.raw, "article", "db", "x", "Second").contains("Inside SQLite"));
        String again = InboxFile.setEnrich(tagged, e.raw, "tool", "", "");
        InboxFile.Entry f = InboxFile.parse(again).get(0);
        check(f.type.equals("tool") && f.tags.isEmpty() && f.tldr.isEmpty() && f.why.equals("for the db talk") && !again.contains("sqlite, db"));
        String notes = InboxFile.setNotes(tagged, InboxFile.parse(tagged).get(0), "new why", "");
        check(InboxFile.parse(notes).get(0).tags.equals("sqlite, db") && InboxFile.parse(notes).get(0).why.equals("new why"));
        check(e.hasTag("db") && !e.hasTag("d") && e.contains("") && e.contains("SQLITE stores") && !e.contains("postgres"));
        String[] c = Enrich.clean(" ARTICLE ", Arrays.asList("#SQL Lite!", "sql-lite", "", "a", "b", "c", "d", "e"), "line one\nline two");
        check(c[0].equals("article") && c[1].equals("sql-lite, a, b, c, d") && c[2].equals("line one line two"));
        String longLine = Enrich.clean("article", Arrays.asList(), "word ".repeat(60))[2];
        check(longLine.length() <= 200 && longLine.endsWith("word…"));
        check(Enrich.clean("hack\n- 🔗 x", Arrays.asList(), "x")[0].equals("other"));
        check(Enrich.pageText("<style>p{}</style><p>Hi&nbsp;<b>there</b></p><script>evil()</script>").equals("Hi&nbsp; there"));
    }
    private static float[] vec(float... lead) { float[] v = new float[Index.DIMS]; System.arraycopy(lead, 0, v, 0, lead.length); return v; }
    private static void indexChecks() throws Exception {
        String inbox = "- 🔗 Caching\n  https://a.com\n  Captured: 2026-11-05 10:00 · via Sparkbox\n  TL;DR: Cut token costs.\n\n- 🔗 Scraper\n  https://b.com\n  Captured: 2026-11-05 10:01 · via Sparkbox\n\n- 🔗 Moats\n  https://c.com\n  Captured: 2026-11-05 10:02 · via Sparkbox\n";
        List<InboxFile.Entry> es = InboxFile.parse(inbox);
        Index index = new Index();
        check(index.missing(es).size() == 3 && Index.text(es.get(0)).contains("Cut token costs"));
        index.put(Index.text(es.get(0)), vec(1, 0));
        index.put(Index.text(es.get(1)), vec(0, 1));
        check(index.missing(es).size() == 1 && index.missing(es).get(0).equals(Index.text(es.get(2)))); // Moats has no vector yet
        index.put(Index.text(es.get(2)), vec(5, 5));                                                     // scaled: ranking must use unit vectors
        List<InboxFile.Entry> hits = index.rank(vec(1, 0.1f), es, 5, 0.28f, 0);
        check(hits.size() == 2 && hits.get(0).title.equals("Caching") && hits.get(1).title.equals("Moats")); // Scraper is orthogonal: cut by min
        check(index.rank(vec(1, 0), es, 1, 0f, 0).size() == 1);
        check(index.related(es.get(0), es, 3).size() == 0 || index.related(es.get(0), es, 3).get(0) != es.get(0)); // never itself
        java.io.File file = java.io.File.createTempFile("index", ".bin");
        index.save(file);
        Index again = Index.load(file);
        check(again.size() == 3 && again.missing(es).isEmpty() && again.rank(vec(1, 0.1f), es, 5, 0.28f, 0).get(0).title.equals("Caching"));
        again.retain(es.subList(0, 1));
        check(again.size() == 1);
        java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3}); // damaged file: start empty, never crash
        check(Index.load(file).size() == 0 && Index.load(new java.io.File(file.getPath() + ".none")).size() == 0);
        file.delete();
    }
    private static void check(boolean ok) { count++; if (!ok) throw new AssertionError("check #" + count); }
}
