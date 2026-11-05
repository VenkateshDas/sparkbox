import me.venkatesh.saveobsidian.Capture;
import me.venkatesh.saveobsidian.AppendCheck;
import me.venkatesh.saveobsidian.InboxFile;
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
        check(AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,9,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3,3}));
        System.out.println("Capture and append checks passed (" + count + ").");
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
    }
    private static void check(boolean ok) { count++; if (!ok) throw new AssertionError("check #" + count); }
}
