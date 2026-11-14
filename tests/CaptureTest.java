import me.venkatesh.saveobsidian.Capture;
import me.venkatesh.saveobsidian.AppendCheck;
import me.venkatesh.saveobsidian.InboxFile;
import me.venkatesh.saveobsidian.Enrich;
import me.venkatesh.saveobsidian.Index;
import me.venkatesh.saveobsidian.Vault;
import me.venkatesh.saveobsidian.Layout;
import me.venkatesh.saveobsidian.Lane;
import me.venkatesh.saveobsidian.Feed;
import me.venkatesh.saveobsidian.NoteEdit;
import me.venkatesh.saveobsidian.Cites;
import me.venkatesh.saveobsidian.Cards;
import me.venkatesh.saveobsidian.Sched;
import me.venkatesh.saveobsidian.Spark;
import me.venkatesh.saveobsidian.CardPrompt;
import me.venkatesh.saveobsidian.Extract;
import me.venkatesh.saveobsidian.Transcript;
import me.venkatesh.saveobsidian.JsonFix;
import me.venkatesh.saveobsidian.Net;
import java.util.ArrayList;
import me.venkatesh.saveobsidian.IngestText;
import java.util.List;
import me.venkatesh.saveobsidian.VerifiedEdit;
import me.venkatesh.saveobsidian.VaultLock;
import me.venkatesh.saveobsidian.Cancel;
import me.venkatesh.saveobsidian.RunGate;
import me.venkatesh.saveobsidian.Limits;
import me.venkatesh.saveobsidian.Failures;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class CaptureTest {
    static int count;
    public static void main(String[] args) throws Exception {
        concurrency();
        limits();
        AgentTest.run();
        SessionUsageTest.run();
        VaultToolsTest.run();
        McpTest.run();
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
        feedChecks();
        noteEditChecks();
        citesChecks();
        entityChecks();
        cardChecks();
        readingChecks();
        check(AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,9,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3,3}));
        System.out.println("Capture and append checks passed (" + count + ").");
    }
    static void vaultChecks() {
        Layout.detect(Arrays.asList("Sources/A/2026/x.md", "Knowledge/Topics/T.md"));
        check(Layout.sources().equals("Sources") && Layout.inbox().equals("Sources/Inbox")); // only the old layout has notes
        Layout.detect(Arrays.asList("Knowledge/Sources/A/2026/x.md", "Sources/A/2026/y.md"));
        check(Layout.sources().equals("Knowledge/Sources")); // both present: the new one wins
        Layout.detect(new java.util.ArrayList<String>());
        check(Layout.sources().equals("Knowledge/Sources") && Layout.other("Sources/X").equals("Knowledge/Sources/X") && Layout.other("Knowledge/Sources/X").equals("Sources/X") && Layout.other("Knowledge/Topics/X") == null);
        check(Layout.conflict("a.sync-conflict-20261112-ABC.md") && !Layout.conflict("a.md") && Layout.isInbox("Sources/Inbox") && Layout.isInbox("Knowledge/Sources/Inbox"));
        Vault.Graph moved = Vault.build(Arrays.asList(Vault.parse("Knowledge/Sources/A/2026/2026-01-01 - One.md", 1, "# One\ntext"), Vault.parse("Sources/B/2026/2026-01-02 - Two.md", 1, "# Two\ntext")));
        check(moved.resolve("Sources/A/2026/2026-01-01 - One") != null && moved.resolve("Knowledge/Sources/B/2026/2026-01-02 - Two") != null); // links from either side of the move
        check(IngestText.sourcesIndex("- [[Knowledge/Sources/AI Systems/index|AI Systems]] — 120 notes\n*297 source records under", "AI Systems").contains("121 notes"));
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
    static void feedChecks() {
        Layout.detect(new java.util.ArrayList<String>());
        String note = "---\ntype: source\nsource: \"https://www.example.com/a?utm_source=1\"\ncaptured: 2026-11-11\nvia: Sparkbox\nprocessed: true\ncuriosity: \"What changes?\"\nmade-topics-new: [\"[[T1]]\"]\nmade-topics-updated: [\"[[T2]]\"]\nmade-people-updated: [\"[[P]]\"]\n---\n\n# Stop coding\n\n## Why I captured this\nIdea to test.\n\n## Raw Content\none two three four\n\n## Claude's Extraction\n- x\n";
        Vault.Note src = Vault.parse("Knowledge/Sources/AI Systems/2026/2026-11-11 - Stop coding.md", 1000, note);
        check(src.processed() && src.meta("curiosity").equals("What changes?") && src.why.equals("Idea to test.") && src.words == 4);
        check(Vault.bullets("## Claude's Extraction\n### Key claims\n- One (high)\n- Two (low)\n### Topics identified\n- [[X]]\n", "Key claims").equals(Arrays.asList("One (high)", "Two (low)")));
        check(Feed.made(src).equals("Made 1 note, 2 topics (1 new), 1 person"));
        check(Lane.of(src.path, src.meta).equals("Craft") && Lane.of("Knowledge/Sources/Health & Biology/2026/x.md", new java.util.HashMap<String, String>()).equals("Life") && Lane.of("Knowledge/Topics/X.md", null).equals(""));
        java.util.Map<String, String> override = new java.util.HashMap<>(); override.put("lane", "life");
        check(Lane.of("Knowledge/Sources/AI Systems/2026/x.md", override).equals("Life"));
        Vault.Note raw = Vault.parse("Knowledge/Sources/Developer Tools/2026/2026-11-10 - Raw.md", 900, "---\ntype: source\nsource: \"https://b.org/x\"\ncaptured: 2026-11-10\nprocessed: false\n---\n\n# Raw\ntext");
        Vault.Note t1 = Vault.parse("Knowledge/Topics/T1.md", 800, "---\ntype: topic\n---\n# T1\n");
        Vault.Note p1 = Vault.parse("Knowledge/People/P.md", 700, "---\ntype: person\n---\n# P\n");
        Vault.Note s2 = Vault.parse("Knowledge/Sources/AI Systems/2026/2026-11-09 - Second.md", 950, "---\ntype: source\ncaptured: 2026-11-09\n---\n# Second\n[[Knowledge/Topics/T1]]\n");
        Vault.Note s3 = Vault.parse("Knowledge/Sources/AI Systems/2026/2026-11-11 - Third.md", 960, "---\ntype: source\ncaptured: 2026-11-11\n---\n# Third\n[[Knowledge/Topics/T1]] [[Knowledge/People/P]]\n");
        List<Vault.Note> all = Arrays.asList(src, raw, t1, p1, s2, s3);
        InboxFile.Entry inboxed = InboxFile.parse("- \uD83D\uDD17 Fresh\n  https://c.org/y\n  Captured: 2026-11-12 08:14 \u00B7 via Sparkbox\n").get(0);
        InboxFile.Entry dup = InboxFile.parse("- \uD83D\uDD17 Same\n  https://example.com/a\n  Captured: 2026-11-11 08:00 \u00B7 via Sparkbox\n").get(0);
        java.time.LocalDateTime now = java.time.LocalDateTime.of(2026, 11, 12, 11, 14);
        List<Feed.Item> items = Feed.build(all, Vault.build(all), Arrays.asList(inboxed, dup), now);
        check(items.get(0).status.equals("Captured") && items.get(0).age.equals("3h ago") && items.get(0).sourceName.equals("c.org")); // newest first
        int sources = 0, topics = 0, people = 0;
        for (Feed.Item it : items) { if (it.kind.equals("source")) sources++; if (it.kind.equals("topic")) topics++; if (it.kind.equals("person")) people++; }
        check(sources == 5 && topics == 1 && people == 1); // 4 notes + 1 capture; the duplicate capture is hidden; T1 has two fresh sources
        check(Feed.filter(items, "All").size() == 6 && Feed.filter(items, "People").size() == 1 && Feed.filter(items, "Topics").size() == 1 && Feed.filter(items, "Craft").size() == 5);
        check(Feed.filter(items, "Sources").size() == 5 && Feed.filter(items, "Life").isEmpty());
        check(Feed.snippet("A long text about designing deterministic test harnesses before generating code in factories", "harnesses", 40).contains("harnesses") && Feed.snippet("short", "zzz", 40).isEmpty() && Feed.snippet("x harness y", "harness", 40).equals("x harness y"));
        check(Feed.age(java.time.LocalDateTime.of(2026, 11, 9, 0, 0), false, now).equals("3d ago") && Feed.age(java.time.LocalDateTime.of(2026, 11, 12, 0, 0), false, now).equals("Today"));
    }

    static void entityChecks() {
        String link = "[[Knowledge/Sources/AI Systems/2026/2026-11-11 - A]]", link2 = "[[Knowledge/Sources/AI Systems/2026/2026-11-12 - B]]";
        check(IngestText.entityKind("Tool").equals("tool") && IngestText.entityKind("gadget").equals("other") && IngestText.entityKind(null).equals("other"));
        String page = IngestText.newEntity("Qwen Coder", "model", "A family of open coding models.", link, "2026-11-11");
        check(page.startsWith("---\ntype: entity\nkind: model\nappears-in: ['" + link + "']\nlast-updated: 2026-11-11\n---\n\n# Qwen Coder\n\n## What it is\nA family of open coding models.\n\n## Where it appears\n- " + link + "\n"));
        String two = IngestText.updateEntity(page, link2, "2026-11-12");
        check(two.contains("appears-in: ['" + link + "', '" + link2 + "']") && two.contains("last-updated: 2026-11-12") && two.contains("- " + link + "\n- " + link2) && IngestText.updateEntity(two, link2, "2026-11-13").equals(two));
        check(IngestText.newEntity("X", "tool", "", link, "d").contains("A tool named in the captured sources."));
        String idx = IngestText.entityIndex("# Knowledge Index\n\nText\n", "[[Knowledge/Entities/Qwen Coder|Qwen Coder]]", "model", "Open coding models");
        check(idx.endsWith("## Entities\n- [[Knowledge/Entities/Qwen Coder|Qwen Coder]] (model) — Open coding models\n") && IngestText.entityIndex(idx, "[[Knowledge/Entities/Qwen Coder|Qwen Coder]]", "model", "x").equals(idx));
        String idx2 = IngestText.entityIndex(idx, "[[Knowledge/Entities/Rex|Rex]]", "tool", "");
        check(idx2.contains("(model) — Open coding models\n- [[Knowledge/Entities/Rex|Rex]] (tool)"));
    }

    static void citesChecks() {
        Cites.Result r = Cites.number("A [[Knowledge/Topics/X|X]] and B [[Knowledge/Sources/S|Source S]], again [[Knowledge/Topics/X|X]]. Table \\| [[Knowledge/Topics/Y\\|Y]] ![[img.png]] [[ ]]");
        check(r.text.startsWith("A [1] and B [2], again [1]. Table \\| [3] ![[img.png]]"));
        check(r.sources.size() == 3 && r.sources.get(0)[1].equals("Knowledge/Topics/X") && r.sources.get(1)[2].equals("Source S") && r.sources.get(2)[1].equals("Knowledge/Topics/Y"));
        check(Cites.number("No citations here.").sources.isEmpty() && Cites.number("No citations here.").text.equals("No citations here."));
        String seen = "Knowledge/Topics/X.md\n- [[Knowledge/Sources/S|Source S]]\nAgents.md\nhttps://example.com/a?x=1";
        check(Cites.unseen("A [[Knowledge/Topics/X|X]], B [[Knowledge/Sources/S|S]], C [[Knowledge/Topics/Agents|Agents]]. See https://example.com/a?x=1.", seen).isEmpty());
        check(Cites.unseen("A [[Knowledge/Topics/Ghost|G]] and [[Knowledge/Topics/Ghost|G]] and https://made.up/page, https://example.com/a?x=1/", seen).toString().equals("[Knowledge/Topics/Ghost, https://made.up/page]"));
        check(Cites.unseen("No citations ![[img.png]]", seen).isEmpty());
        java.util.List<String> gone = Cites.unseen("Real [[Knowledge/Topics/X|X]], fake [[Knowledge/Topics/Ghost|The Ghost]] and [site](https://made.up/page) and (https://made.up/b) or https://made.up/c. Kept https://example.com/a?x=1.", seen);
        check(Cites.strip("Real [[Knowledge/Topics/X|X]], fake [[Knowledge/Topics/Ghost|The Ghost]] and [site](https://made.up/page) and (https://made.up/b) or https://made.up/c. Kept https://example.com/a?x=1.", gone)
            .equals("Real [[Knowledge/Topics/X|X]], fake The Ghost and site and or. Kept https://example.com/a?x=1."));
        check(Cites.strip("Nothing wrong [[A|A]]", new java.util.ArrayList<String>()).equals("Nothing wrong [[A|A]]"));
        check(Cites.notInKnowledge("Not in your knowledge base. Closest: [[A|A]]") && Cites.notInKnowledge("  **Not in your knowledge base**") && !Cites.notInKnowledge("It is not in your knowledge base maybe"));
    }

    static final long MIN = 60000L, DAYMS = 86400000L;

    static void cardChecks() {
        // ---- Cards: ids, times, state ----
        check(Cards.id("p", "What  is X?").equals(Cards.id("p", "what is x?")) && Cards.id("p", "a").startsWith("c_") && Cards.id("p", "a").length() == 10 && !Cards.id("p", "a").equals(Cards.id("q", "a")));
        long t0 = Cards.parseTime("2026-11-12T07:41Z");
        check(Cards.time(t0).equals("2026-11-12T07:41Z") && Cards.parseTime("junk") == 0);
        Cards.State st = new Cards.State(t0, 3, 2.5, 2, 0);
        Cards.State back = Cards.parseState(Cards.stateText(st));
        check(back != null && back.due == t0 && back.interval == 3 && back.ease == 2.5 && back.reps == 2 && back.lapses == 0 && Cards.parseState("due x") == null);
        String log = Cards.logWith("", t0, "good", 9);
        log = Cards.logWith(log, t0 - DAYMS, "hard", 14);
        check(log.equals("2026-11-11T07:41Z hard 14s; 2026-11-12T07:41Z good 9s") && Cards.logTimes(log).size() == 2);
        String big = "";
        for (int i = 0; i < 60; i++) big = Cards.logWith(big, t0 + i * MIN, "good", 1);
        check(big.split(";").length == 50);

        // ---- Cards: parse and surgical edits ----
        String note = "---\ntype: source\n---\n\n# T\n\n## Raw Content\ntext\n\n## Cards\n\n### c_1\nQ: Why?\nA: Because.\n  Second line.\nBasis: claim (high)\nState: due 2026-11-15T07:41Z interval 3 ease 2.50 reps 2 lapses 0\n\n### c_2\nQ: Odd\n\n### c_3\nQ: Next?\nA: Yes.\nFlag: yes\n\n## After\nkept\n";
        List<Cards.Card> cs = Cards.parse("n.md", note);
        check(cs.size() == 2 && cs.get(0).a.equals("Because.\nSecond line.") && cs.get(0).state.interval == 3 && cs.get(1).flag && cs.get(0).basis.equals("claim (high)")); // c_2 has no answer: skipped
        java.util.Map<String, String> ch = new java.util.LinkedHashMap<>();
        ch.put("Take", "my words"); ch.put("Flag", null);
        String edited = Cards.setKeys(note, "c_1", ch);
        check(edited.contains("### c_1\nQ: Why?\nA: Because.\n  Second line.\nBasis: claim (high)\nState: due 2026-11-15T07:41Z interval 3 ease 2.50 reps 2 lapses 0\nTake: my words\n\n### c_2"));
        check(Cards.setKeys(edited, "c_1", ch).equals(edited)); // applying twice changes nothing
        java.util.Map<String, String> gone = new java.util.LinkedHashMap<>(); gone.put("Flag", null);
        String unflagged = Cards.setKeys(note, "c_3", gone);
        check(!unflagged.contains("Flag: yes") && unflagged.contains("### c_3\nQ: Next?\nA: Yes.\n\n## After") && unflagged.contains("## After\nkept\n")); // only that block changed
        java.util.Map<String, String> multi = new java.util.LinkedHashMap<>(); multi.put("A", "New\nanswer");
        check(Cards.setKeys(note, "c_1", multi).contains("A: New\n  answer\nBasis: claim (high)") && Cards.setKeys(note, "c_zzz", multi).equals(note));
        Cards.Card mine = new Cards.Card(); mine.id = "c_m"; mine.q = "Mine?"; mine.a = "Yes."; mine.origin = "mine"; mine.take = "t";
        String added = Cards.add(note, java.util.Collections.singletonList(mine));
        check(added.contains("Flag: yes\n\n### c_m\nQ: Mine?\nA: Yes.\nTake: t\nOrigin: mine\n\n## After") && Cards.add(added, java.util.Collections.singletonList(mine)).equals(added));
        String created = Cards.add("# T\n\ntext\n", java.util.Collections.singletonList(mine));
        check(created.equals("# T\n\ntext\n\n## Cards\n\n### c_m\nQ: Mine?\nA: Yes.\nTake: t\nOrigin: mine\n\n"));
        check(!Cards.remove(note, "c_3").contains("c_3") && Cards.remove(note, "c_3").contains("### c_1") && Cards.remove(note, "c_nope").equals(note));
        check(Cards.parse("x", Cards.add(created, java.util.Collections.<Cards.Card>emptyList())).size() == 1 && Cards.setKeys(note.replace("\n", "\r\n"), "c_1", ch).contains("Take: my words\r\n"));

        // ---- NoteEdit.setKey ----
        String fm = "---\ntype: source\nquality: partial\n---\n# T\n";
        check(NoteEdit.setKey(fm, "quality", "full").equals("---\ntype: source\nquality: full\n---\n# T\n") && NoteEdit.setKey(fm, "kept", "true").equals("---\ntype: source\nquality: partial\nkept: true\n---\n# T\n"));
        check(NoteEdit.setKey(fm, "quality", null).equals("---\ntype: source\n---\n# T\n") && NoteEdit.setKey("# no frontmatter\n", "kept", "true").equals("# no frontmatter\n") && NoteEdit.setKey(NoteEdit.setKey(fm, "kept", "true"), "kept", "true").equals(NoteEdit.setKey(fm, "kept", "true")));

        // ---- Sched ----
        long n = t0;
        Cards.State g1 = Sched.next(null, "good", n);
        check(g1.interval == 1 && g1.reps == 1 && g1.due == n + DAYMS && g1.ease == 2.5);
        Cards.State g2 = Sched.next(g1, "good", n), g3 = Sched.next(g2, "good", n);
        check(g2.interval == 3 && g3.interval == 8 && g3.reps == 3); // 3 * 2.5 = 7.5, rounded
        check(Sched.next(Sched.next(null, "easy", n), "good", n).interval >= 4); // Easy then Good never shrinks the interval
        check(Sched.next(null, "easy", n).interval == 4 && Sched.next(null, "hard", n).interval == 1 && Sched.next(g3, "easy", n).interval == 26 && Sched.next(g3, "easy", n).ease == 2.65);
        Cards.State hard = Sched.next(g3, "hard", n);
        check(hard.interval == 10 && Math.abs(hard.ease - 2.35) < 1e-9);
        Cards.State lapse = Sched.next(g3, "again", n);
        check(lapse.reps == 0 && lapse.lapses == 1 && lapse.due == n + 10 * MIN && Math.abs(lapse.ease - 2.3) < 1e-9 && Sched.next(lapse, "good", n).interval == 1);
        check(Sched.next(null, "again", n).lapses == 0 && Sched.next(null, "again", n).ease == 2.5);
        Cards.State low = new Cards.State(n, 5, 1.35, 4, 0);
        check(Sched.next(low, "again", n).ease == 1.3 && Sched.next(new Cards.State(n, 5, 1.3, 4, 0), "hard", n).ease == 1.3);
        check(Sched.label(null, "again", n).equals("later today") && Sched.label(g3, "good", n).equals("2w") && Sched.label(g1, "good", n).equals("3d") && Sched.label(g3, "easy", n).equals("3w") && Sched.label(new Cards.State(n, 100, 2.5, 5, 0), "good", n).equals("8mo"));

        // ---- Spark ----
        java.time.ZoneId utc = java.time.ZoneId.of("UTC");
        java.util.List<Cards.Card> deck = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) { Cards.Card c = new Cards.Card(); c.id = "c" + i; c.path = i < 6 ? "a.md" : "b.md"; c.q = "q"; c.a = "a"; deck.add(c); }
        java.util.List<String> newest = java.util.Arrays.asList("b.md", "a.md");
        Spark.Plan p = Spark.plan(deck, new java.util.HashSet<String>(), n, utc, 7, newest);
        check(p.state.equals("question") && p.queue.size() == 7 && p.queue.get(0).path.equals("b.md") && p.minutes() == 4 && p.reviewedToday == 0); // newest note first, capped at 7
        java.util.Set<String> kept = new java.util.HashSet<>(); kept.add("a.md");
        check(Spark.plan(deck, kept, n, utc, 7, newest).queue.get(0).path.equals("a.md")); // kept notes first
        deck.get(0).flag = true; deck.get(1).parked = n + DAYMS; deck.get(2).state = new Cards.State(n - DAYMS, 3, 2.5, 2, 0); deck.get(3).state = new Cards.State(n + 5 * DAYMS, 3, 2.5, 2, 0);
        Spark.Plan q = Spark.plan(deck, kept, n, utc, 7, newest);
        check(q.queue.get(0).id.equals("c2")); // due cards before new ones
        boolean none = true;
        for (Cards.Card c : q.queue) none &= !c.id.equals("c0") && !c.id.equals("c1") && !c.id.equals("c3"); // flagged, parked and not-yet-due stay out
        check(none && q.nextDue == n + 5 * DAYMS);
        deck.get(4).log = Cards.logWith("", n - 60 * MIN, "good", 5); deck.get(5).log = Cards.logWith(Cards.logWith("", n - 3 * 60 * MIN, "again", 5), n - 2 * 60 * MIN, "good", 5);
        check(Spark.reviewedToday(deck, n, utc) == 2 && Spark.plan(deck, kept, n, utc, 7, newest).queue.size() == 5); // each card counts once; cap 7 minus 2
        check(Spark.plan(deck, kept, n, utc, 2, newest).state.equals("cap_reached") && Spark.plan(new java.util.ArrayList<Cards.Card>(), kept, n, utc, 7, newest).state.equals("no_cards"));
        java.util.List<Cards.Card> one = new java.util.ArrayList<>(); Cards.Card done = new Cards.Card(); done.id = "d"; done.path = "a.md"; done.state = new Cards.State(n + DAYMS, 1, 2.5, 1, 0); done.log = Cards.logWith("", n - MIN, "good", 4); one.add(done);
        check(Spark.plan(one, kept, n, utc, 7, newest).state.equals("caught_up") && Spark.plan(one, kept, n, utc, 1, newest).state.equals("caught_up") && Spark.clampCap(1) == 3 && Spark.clampCap(99) == 20);

        // ---- CardPrompt: note input and quality ----
        String src = "---\ntype: source\n---\n\n# Title\n\n## Why I captured this\nTo try.\n\n## Raw Content\n" + "word ".repeat(400) + "\n\n## Claude's Extraction\n\n### Key claims\n- One (high)\n- Two (low)\n";
        check(CardPrompt.quality(src).equals("full") && CardPrompt.input(src).startsWith("Title: Title\nKey claims") && CardPrompt.input(src).contains("Why the owner saved it: To try.") && CardPrompt.input(src).contains("Page text:\nword word"));
        String thinNote = "---\ntype: source\n---\n# T\n\n## Raw Content\nshort\n";
        check(CardPrompt.quality(thinNote).equals("title_only") && CardPrompt.input("# Nothing\n").isEmpty());
        String claimsOnly = "# T\n\n## Claude's Extraction\n\n### Key claims\n" + "- A claim that is long enough to count as text for the model to work from. ".repeat(30);
        check(CardPrompt.quality(claimsOnly).equals("partial")); // claims alone never reach full
        check(CardPrompt.input("# T\n\n## Raw Content\n" + "x".repeat(20000) + "\n").length() < 12100);

        // ---- CardPrompt ----
        java.util.List<String[]> raw = new java.util.ArrayList<>();
        raw.add(new String[]{"Why is X?", "Because Y.", "Y is true", "high"});
        raw.add(new String[]{"Low one?", "No.", "z", "low"});
        raw.add(new String[]{"Link?", "See [[X]]", "b", "high"});
        raw.add(new String[]{"Same?", "Same?", "b", "high"});
        raw.add(new String[]{"  why   is x?", "Dup.", "b", "medium"});
        raw.add(new String[]{"Url?", "Go to https://x.org", "b", "high"});
        for (int i = 0; i < 6; i++) raw.add(new String[]{"Extra " + i + "?", "Answer " + i, "b", "medium"});
        java.util.List<Cards.Card> ok = CardPrompt.validate("n.md", raw);
        check(ok.size() == 4 && ok.get(0).basis.equals("Y is true (high)") && ok.get(1).q.equals("Extra 0?")); // low, link, same, duplicate, url dropped; capped at 4
        check(CardPrompt.curiosity("What changes?").equals("What changes?") && CardPrompt.curiosity("A statement.").isEmpty() && CardPrompt.curiosity("See [[X]]?").isEmpty());
        check(CardPrompt.quality(100).equals("title_only") && CardPrompt.quality(300).equals("partial") && CardPrompt.quality(1500).equals("full"));
        String made = CardPrompt.apply("---\ntype: source\n---\n# T\n", "What changes?", "full", ok);
        check(made.contains("curiosity: \"What changes?\"") && made.contains("quality: full") && made.contains("## Cards") && Cards.parse("n.md", made).size() == 4 && CardPrompt.apply(made, "What changes?", "full", ok).equals(made));
        String thin = CardPrompt.apply("---\ntype: source\n---\n# T\n", "What?", "title_only", ok);
        check(thin.contains("quality: title_only") && !thin.contains("curiosity") && !thin.contains("## Cards"));
    }

    static void noteEditChecks() {
        String note = "---\ntype: source\n---\n\n# T\n\n## Why I captured this\nBecause.\n\n## Raw Content\n```\n## not a heading\n```\ntext\n\n## Claude's Extraction\n- x\n";
        String a = NoteEdit.setSection(note, "My ideas", "Mine\nline two", "Why I captured this");
        check(a.contains("## Why I captured this\nBecause.\n\n## My ideas\nMine\nline two\n\n## Raw Content")); // added after Why, other sections untouched
        check(NoteEdit.setSection(a, "My ideas", "Mine\nline two", "Why I captured this").equals(a)); // applying twice changes nothing
        String b = NoteEdit.setSection(a, "My ideas", "Changed", "Why I captured this");
        check(b.contains("## My ideas\nChanged\n\n## Raw Content") && !b.contains("line two") && b.contains("## not a heading"));
        check(NoteEdit.setSection(b, "My ideas", "  ", "Why I captured this").equals(note)); // an empty body removes the section
        check(NoteEdit.setSection(note, "My ideas", "Z").contains("- x\n\n## My ideas\nZ\n") && NoteEdit.setSection(note, "My ideas", "Z").endsWith("Z\n")); // no anchor: at the end
        String crlf = NoteEdit.setSection(note.replace("\n", "\r\n"), "My ideas", "Q", "Why I captured this");
        check(crlf.contains("## My ideas\r\nQ\r\n") && !crlf.replace("\r\n", "").contains("\n"));
        String raw = NoteEdit.setSection("# T\n\n## Raw Content\n```\n## My ideas\n```\n", "My ideas", "Real");
        check(raw.contains("```\n## My ideas\n```") && raw.endsWith("## My ideas\nReal\n")); // a heading inside a fence is not a section
        String topic = NoteEdit.setSection("# T\n\n## Core Mechanism\nPlain.\n\n## Key Claims\n| a |\n", "My take", NoteEdit.takeBody("I think so.", "2026-11-12"), "Core Mechanism");
        check(topic.contains("Plain.\n\n## My take\nI think so.\n\nEdited: 2026-11-12\n\n## Key Claims"));
        String sec = "I think so.\n\nEdited: 2026-11-12";
        check(NoteEdit.takeText(sec).equals("I think so.") && NoteEdit.takeEdited(sec).equals("2026-11-12") && NoteEdit.takeBody(" ", "2026-11-12").isEmpty());
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
        check(Enrich.notes("To try", "").equals("\nWhy saved: To try") && Enrich.notes(" ", null).isEmpty() && Enrich.notes("a", "b").endsWith("\nIdeas: b")); // annotations reach the prompt only when present
        check(Enrich.noteName("2026-11-10 19:31", "A/B: what? [x] #y").equals("2026-11-10 - A B what x y.md") && Enrich.noteName("", "").equals("undated - Capture.md")); // file names the provider and Obsidian accept
                // Ingest text edits follow the vault's formats and only add
        java.util.List<String> noRows = new java.util.ArrayList<>(), rows = java.util.Collections.singletonList(IngestText.claimRow("A | B claim", "[[Sources/AI Systems/2026/S]]", "2026", "low"));
        String topic = "---\ntype: topic\nlast-updated: 2026-01-01\nsource-count: 4\n---\n\n## Core Mechanism\nx\n\n## Key Claims\n| Claim | Source | Year | Confidence |\n|---|---|---|---|\n| old | [[S0]] | 2025 | high |\n\n## Sources that built this page\n- [[S0]]\n";
        String updated = IngestText.updateTopic(topic, rows, java.util.Collections.singletonList("Open?"), "[[Sources/AI Systems/2026/S]]", "2026-11-10");
        check(updated.contains("last-updated: 2026-11-10") && updated.contains("source-count: 5") && updated.contains("| old | [[S0]] | 2025 | high |\n| A \\| B claim | [[Sources/AI Systems/2026/S]] | 2026 | low |")
            && updated.contains("## Contradictions / Open Questions\n- Open?") && updated.contains("- [[S0]]\n- [[Sources/AI Systems/2026/S]]\n")); // rows after the table, counters bumped, nothing removed
        check(IngestText.updateTopic(updated, noRows, noRows, "[[Sources/AI Systems/2026/S]]", "2026-11-10").equals(updated.replace("source-count: 5", "source-count: 6"))); // a source link is never listed twice
        check(IngestText.newTopic("T", "Core.", rows, java.util.Collections.singletonList("R"), noRows, "[[S]]", "2026-11-10").contains("related-topics: ['[[R]]']\nreferenced-in: []\n---\n\n## Core Mechanism\nCore.")
            && IngestText.updatePerson(IngestText.newPerson("N", "", "[[S]]"), "[[S2]]").endsWith("- [[S]]\n- [[S2]]\n"));
        String kidx = "*Last updated: 2026-11-09 | 27 topic pages | 104 people pages | 297 source records*\n| T | C | S | U |\n| [[Knowledge/Topics/RAG\\|RAG]] | core | 20 | 2026-11-06 |\n| [[Knowledge/Topics/LLM\\|LLM]] | core | 30 | 2026-11-06 |\n\n| P | R | N | S |\n| [[Knowledge/People/Z\\|Z]] | r | n | [[S]] |\n";
        String k2 = IngestText.knowledgeIndex(kidx, java.util.Collections.singletonList("| [[Knowledge/Topics/New\\|New]] | c | 1 | 2026-11-10 |"), java.util.Collections.singletonList("| [[Knowledge/People/Q\\|Q]] | r | n | [[S]] |"), java.util.Collections.singletonList("RAG"), 1, "2026-11-10");
        check(k2.contains("28 topic pages | 105 people pages | 298 source records") && k2.contains("| [[Knowledge/Topics/RAG\\|RAG]] | core | 21 | 2026-11-10 |") && k2.contains("| [[Knowledge/Topics/LLM\\|LLM]] | core | 30 | 2026-11-06 |\n| [[Knowledge/Topics/New")
            && k2.indexOf("Knowledge/People/Q") > k2.indexOf("Knowledge/People/Z"));
        check(IngestText.sourcesIndex("- [[Sources/AI Systems/index|AI Systems]] — 120 notes\n*297 source records under", "AI Systems").equals("- [[Sources/AI Systems/index|AI Systems]] — 121 notes\n*298 source records under")
            && IngestText.category("people & society").equals("People & Society") && IngestText.category("nonsense").equals("AI Systems") && IngestText.safe("A/B: x?").equals("A B x"));
String note = Enrich.sourceNote("T", "https://e.org/a", "2026-11-10 19:31", "ai, rag", "Sum.", "To try", "", "Body");
        check(note.startsWith("---\ntype: source\nsource: \"https://e.org/a\"\ncaptured: 2026-11-10\nvia: Sparkbox\ntopics: [ai, rag]\nprocessed: false\n---\n\n# T\n")
            && note.contains("## Summary\nSum.") && note.contains("## Why I captured this\nTo try") && !note.contains("## My ideas") && note.endsWith("## Raw Content\nBody\n")); // the vault's Source format, empty sections left out
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
    /** Page extraction, YouTube parsing, model-reply repair, lane choice. */
    private static void readingChecks() throws Exception {
        // an article page: navigation, a sidebar of links and a footer around the real text
        String para = "The cache keeps recent results close to the reader, which saves a long trip to the database, and it is cleared when the data changes. ";
        String html = "<html><head><title>Fast caches | Example Blog</title><meta property='og:site_name' content='Example Blog'><meta name='author' content='Ada Lovelace'>"
            + "<meta property='og:image' content='/img/cover.png'></head><body><nav><a href='/'>Home</a><a href='/about'>About</a><a href='/x'>Archive</a></nav>"
            + "<div class='sidebar'><a href='/a'>Popular one</a><a href='/b'>Popular two</a><a href='/c'>Popular three</a></div>"
            + "<div class='post-content'><h1>Fast caches</h1><p>" + para + para + "</p><p>" + para + para + "</p><h2>Why it works</h2><p>" + para + para + "</p><ul><li>Keep it small and fast to read from memory</li></ul></div>"
            + "<footer>Copyright Example Blog. All rights reserved. Privacy. Terms.</footer></body></html>";
        Extract.Page page = Extract.parse(html, "https://blog.example.com/fast-caches");
        check(page.title.equals("Fast caches"));
        check(page.byline.equals("Ada Lovelace"));
        check(page.image.equals("https://blog.example.com/img/cover.png"));
        check(page.text.contains("saves a long trip to the database"));
        check(page.text.contains("### Why it works"));
        check(!page.text.contains("Popular two") && !page.text.contains("Copyright") && !page.text.contains("About"));
        check(!page.blocked);
        // structured data wins when the visible page is a thin app shell
        String shell = "<html><head><title>Post</title><script type='application/ld+json'>{\"@context\":\"https://schema.org\",\"@graph\":[{\"@type\":\"BlogPosting\",\"headline\":\"From JSON\",\"author\":{\"name\":\"Grace\"},\"articleBody\":\"" + para.repeat(4).trim() + "\"}]}</script></head><body><div id='root'></div></body></html>";
        Extract.Page ld = Extract.parse(shell, "https://x.example/p");
        check(ld.title.equals("From JSON") && ld.byline.equals("Grace") && ld.text.contains("saves a long trip"));
        // a bot check is reported as blocked, not as the article
        Extract.Page wall = Extract.parse("<html><head><title>Just a moment...</title></head><body><h1>Just a moment...</h1><p>Enable JavaScript and cookies to continue</p></body></html>", "https://x.example/");
        check(wall.blocked && wall.text.isEmpty());
        check(Extract.parse("", "https://x.example/").text.isEmpty());

        // YouTube links
        check(Transcript.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s").equals("dQw4w9WgXcQ"));
        check(Transcript.videoId("https://youtu.be/dQw4w9WgXcQ?si=abc").equals("dQw4w9WgXcQ"));
        check(Transcript.videoId("https://m.youtube.com/shorts/dQw4w9WgXcQ").equals("dQw4w9WgXcQ"));
        check(Transcript.videoId("https://www.youtube.com/live/dQw4w9WgXcQ?feature=share").equals("dQw4w9WgXcQ"));
        check(Transcript.videoId("https://music.youtube.com/watch?list=x&v=dQw4w9WgXcQ").equals("dQw4w9WgXcQ"));
        check(Transcript.videoId("https://www.youtube.com/channel/UC1234567890").isEmpty());
        check(Transcript.videoId("https://example.com/watch?v=dQw4w9WgXcQ").isEmpty());
        check(Transcript.videoId("not a url").isEmpty());
        // caption tracks: written English beats automatic English beats other languages
        org.json.JSONArray tracks = new org.json.JSONArray("[{\"baseUrl\":\"a\",\"languageCode\":\"de\"},{\"baseUrl\":\"b\",\"languageCode\":\"en\",\"kind\":\"asr\"},{\"baseUrl\":\"c\",\"languageCode\":\"en\"}]");
        check(Transcript.pick(tracks).getString("baseUrl").equals("c"));
        check(Transcript.pick(new org.json.JSONArray("[{\"baseUrl\":\"a\",\"languageCode\":\"de\"},{\"baseUrl\":\"b\",\"languageCode\":\"en\",\"kind\":\"asr\"}]")).getString("baseUrl").equals("b"));
        check(Transcript.pick(new org.json.JSONArray("[]")) == null && Transcript.pick(null) == null);
        // caption XML, both formats; a pause starts a paragraph; entities are decoded
        String f3 = "<?xml version=\"1.0\"?><timedtext format=\"3\"><body><p t=\"1000\" d=\"2000\">We&#39;re no strangers</p><p t=\"3000\" d=\"2000\">to love &amp; war</p><p t=\"9000\" d=\"1000\">A new idea</p></body></timedtext>";
        check(Transcript.fromXml(f3).equals("We're no strangers to love & war\n\nA new idea"));
        String srv1 = "<transcript><text start=\"0\" dur=\"2\">Don&amp;#39;t panic</text><text start=\"2\" dur=\"2\">it works</text></transcript>";
        check(Transcript.fromXml(srv1).equals("Don't panic it works"));
        check(Transcript.fromXml("<timedtext><body><p t=\"0\" d=\"1\">[Music]</p><p t=\"1000\" d=\"1\">Hello</p></body></timedtext>").equals("Hello"));
        check(Transcript.fromXml("").isEmpty() && Transcript.fromXml("<timedtext><body></body></timedtext>").isEmpty());

        // model replies that are almost JSON
        check(JsonFix.object("Here you go:\n```json\n{\"a\": 1, \"b\": [1, 2,],}\n```").getInt("a") == 1);
        check(JsonFix.object("{\"a\": \"line one\nline two\"}").getString("a").equals("line one\nline two"));
        org.json.JSONObject cut = JsonFix.object("{\"topics\": [{\"title\": \"One\"}, {\"title\": \"Two\", \"claims\": [{\"claim\": \"cut o");
        check(cut != null && cut.getJSONArray("topics").length() == 2 && cut.getJSONArray("topics").getJSONObject(0).getString("title").equals("One"));
        check(JsonFix.object("no json here") == null && JsonFix.object("") == null && JsonFix.object(null) == null);
        check(JsonFix.object("{\"a\":{\"b\":2}}").getJSONObject("a").getInt("b") == 2);

        // two videos are two links; tracking noise and fragments are not part of a link's identity
        check(Net.offline(new java.net.UnknownHostException("Unable to resolve host \"openrouter.ai\": No address associated with hostname")));
        check(Net.offline(new java.io.IOException("wrapped", new java.net.ConnectException("failed to connect"))));
        check(Net.offline(new Net.Offline("Smart tags paused: no connection")));
        check(!Net.offline(new java.io.IOException("OpenRouter refused this request (400)")) && !Net.offline(new java.net.SocketTimeoutException("Read timed out")));
        check(!Capture.identity("https://www.youtube.com/watch?v=aaaaaaaaaaa").equals(Capture.identity("https://www.youtube.com/watch?v=bbbbbbbbbbb")));
        check(Capture.identity("https://a.com/p?id=7&utm_source=x#top").equals("https://a.com/p?id=7") && Capture.identity("https://a.com/p#x").equals("https://a.com/p"));
        // lane and folder from the classifier
        check(Lane.pick("craft", "Developer Tools")[0].equals("Craft") && Lane.pick("life", "developer tools")[0].equals("Craft")); // a real folder decides its own lane
        check(Lane.pick("life", "Health & Biology")[1].equals("Health & Biology") && Lane.pick("craft", "Health & Biology")[0].equals("Life"));
        check(Lane.pick("life", "nonsense")[1].equals("People & Society") && Lane.pick("craft", "nonsense") == null && Lane.pick("", "") == null);
    }

    private static void check(boolean ok) { count++; if (!ok) throw new AssertionError("check #" + count); }

    static void concurrency() throws Exception {
        // cancel isolation: stopping Ask's token leaves background work running, and the reverse
        Cancel ask = new Cancel(), background = new Cancel();
        ask.stop();
        check(ask.stopped() && !background.stopped());
        Cancel ask2 = new Cancel(); background.stop();
        check(background.stopped() && !ask2.stopped());
        check(!Cancel.current().stopped()); // a thread with no run is never stopped
        Cancel.current().halt();
        check(!Cancel.current().stopped()); // and cannot be stopped by accident

        // halt isolation: halting one run stops that run only; a new run gets a fresh switch, children inherit their run's
        RunGate gate = new RunGate();
        final Cancel[] first = new Cancel[1];
        final boolean[] childSaw = new boolean[1];
        java.util.concurrent.CountDownLatch in = new java.util.concurrent.CountDownLatch(1);
        Thread a = new Thread(() -> {
            try {
                first[0] = gate.enter(60000, 1000);
                in.countDown();
                while (!Cancel.current().stopped()) Thread.sleep(5);
                Thread child = new Thread(() -> childSaw[0] = Cancel.current().stopped());
                child.start(); child.join();
                gate.exit();
            } catch (InterruptedException e) { }
        });
        a.start(); in.await();
        check(gate.enter(60000, 1000) == null && gate.again); // busy, not hung: no second run
        gate.halt();
        a.join(2000);
        check(first[0].stopped() && childSaw[0] && !a.isAlive());
        Cancel second = gate.enter(60000, 1000);
        check(second != null && second != first[0] && !second.stopped());
        gate.exit();

        // the hung-run escape stops the old run and waits; it never runs two at once
        RunGate g2 = new RunGate();
        java.util.concurrent.CountDownLatch held = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean overlap = new java.util.concurrent.atomic.AtomicBoolean(), inside = new java.util.concurrent.atomic.AtomicBoolean();
        Thread stuck = new Thread(() -> {
            try { g2.enter(0, 0); inside.set(true); held.countDown(); while (!Cancel.current().stopped()) Thread.sleep(5); Thread.sleep(50); inside.set(false); g2.exit(); } catch (InterruptedException e) { }
        });
        stuck.start(); held.await();
        Cancel next = g2.enter(0, 2000);
        overlap.set(inside.get());
        check(next != null && !overlap.get() && !stuck.isAlive());
        g2.exit();
        Thread deaf = new Thread(() -> { try { g2.enter(0, 0); Thread.sleep(400); g2.exit(); } catch (InterruptedException e) { } }); // ignores the stop
        deaf.start(); Thread.sleep(50);
        check(g2.enter(0, 50) == null); // still alive after the wait: skip, do not run beside it
        deaf.join();

        // lock serialization: two writers doing read, compute, write never lose an update
        final int[] file = {0};
        Runnable writer = () -> { for (int i = 0; i < 500; i++) { VaultLock.L.lock(); try { int read = file[0]; Thread.yield(); file[0] = read + 1; } finally { VaultLock.L.unlock(); } } };
        Thread w1 = new Thread(writer), w2 = new Thread(writer);
        w1.start(); w2.start(); w1.join(); w2.join();
        check(file[0] == 1000);
        // VerifiedEdit holds the same lock: another writer cannot slip in between its compare and its write
        final byte[][] disk = {"a".getBytes(StandardCharsets.UTF_8)};
        VaultLock.L.lock();
        Thread edit = new Thread(() -> { try { VerifiedEdit.apply(disk[0], "b".getBytes(StandardCharsets.UTF_8), () -> disk[0], b -> disk[0] = b, (x, y) -> { }); } catch (Exception e) { } });
        edit.start(); Thread.sleep(50);
        check(edit.isAlive() && Arrays.equals(disk[0], "a".getBytes(StandardCharsets.UTF_8)));
        VaultLock.L.unlock(); edit.join();
        check(Arrays.equals(disk[0], "b".getBytes(StandardCharsets.UTF_8)));

        // input hash: a result for older Why/Ideas is discarded; legacy plans without a hash are kept
        check(IngestText.samePlanInputs("abc", "abc"));
        check(!IngestText.samePlanInputs("abc", "def"));
        check(IngestText.samePlanInputs("", "def") && IngestText.samePlanInputs(null, "def"));
    }

    static void limits() throws Exception {
        // background model calls: never more than two at once, however many ask
        final java.util.concurrent.atomic.AtomicInteger now = new java.util.concurrent.atomic.AtomicInteger(), most = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> ts = new ArrayList<>();
        for (int i = 0; i < 6; i++) ts.add(new Thread(() -> { try { Limits.with(Limits.AI, () -> { most.accumulateAndGet(now.incrementAndGet(), Math::max); Thread.sleep(40); now.decrementAndGet(); return null; }); } catch (Exception e) { } }));
        for (Thread t : ts) t.start();
        for (Thread t : ts) t.join();
        check(most.get() == 2 && Limits.AI.availablePermits() == 2);
        // a permit comes back even when the work throws
        try { Limits.with(Limits.FETCH, () -> { throw new java.io.IOException("x"); }); } catch (java.io.IOException expected) { }
        check(Limits.FETCH.availablePermits() == 3);
        // interactive is per thread
        Limits.interactive(true);
        final boolean[] other = new boolean[1];
        Thread t = new Thread(() -> other[0] = Limits.interactive());
        t.start(); t.join();
        check(Limits.interactive() && !other[0]);
        Limits.interactive(false);
        check(!Limits.interactive());

        // failure kinds and what follows from them
        check(Failures.kind("Offline: waiting for a connection").equals(Failures.OFFLINE));
        check(Failures.kind("Smart tags paused: OpenRouter answered 401 (check the key)").equals(Failures.KEY));
        check(Failures.kind("Smart tags paused: OpenRouter answered 402 (out of credit)").equals(Failures.KEY));
        check(Failures.kind("Smart tags paused: OpenRouter answered 429: slow down").equals(Failures.RATE));
        check(Failures.kind("The model found no topic for this capture").equals(Failures.MALFORMED));
        check(Failures.kind("Knowledge/index.md changed while it was being edited. It will be tried again.").equals(Failures.CONFLICT));
        check(Failures.kind("Why or Ideas changed while processing: it will be processed again").equals(Failures.CONFLICT));
        check(Failures.kind("Something odd").equals(Failures.OTHER));
        check(!Failures.retries(Failures.KEY) && Failures.retries(Failures.RATE) && Failures.retries(Failures.CONFLICT));
        check(Failures.minWaitMs(Failures.RATE) >= 600000 && Failures.minWaitMs(Failures.OTHER) == 0);
        check(Failures.recovery(Failures.CONFLICT, "").contains("without a new model call"));
    }
}
