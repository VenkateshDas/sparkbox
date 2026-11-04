import me.venkatesh.saveobsidian.Capture;
import me.venkatesh.saveobsidian.AppendCheck;
import me.venkatesh.saveobsidian.InboxFile;
import java.util.List;

public class CaptureTest {
    static int count;
    public static void main(String[] args) {
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
        check(Capture.textEntry("T", "One\n\nTwo\r\nThree").equals("- 📝 One\n  One\n  Two\n  Three\n  Captured: T · via Sparkbox\n"));
        String longLine = "x".repeat(100);
        check(Capture.textEntry("T", longLine).startsWith("- 📝 " + "x".repeat(80) + "…\n  " + longLine + "\n"));
        check(Capture.textEntry("T", "y".repeat(2500)).contains("…\n  Captured"));

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
        check(after.size() == 4 && after.get(2).why.equals("Because X") && after.get(2).ideas.equals("Idea one\nIdea two"));
        check(withNotes.contains("  Why: Because X\n  Ideas: Idea one\n    Idea two\n\n- 📝"));
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
    private static void check(boolean ok) { count++; if (!ok) throw new AssertionError("check #" + count); }
}
