import me.venkatesh.saveobsidian.Capture;
import me.venkatesh.saveobsidian.AppendCheck;

public class CaptureTest {
    public static void main(String[] args) {
        check(Capture.firstUrl("A caption\nhttps://example.com/post?a=1&b=two+three\nRead it").equals("https://example.com/post?a=1&b=two+three"));
        check(Capture.firstUrl("https://youtu.be/Abc?t=42").equals("https://youtu.be/Abc?t=42"));
        check(Capture.pageTitle("<HTML><TITLE class='x'>You don&#x27;t\nneed long context</TITLE></HTML>").equals("You don&#x27;t\nneed long context"));
        check(Capture.pageTitle("<html>Login</html>").isEmpty());
        check(Capture.entry("2026-11-04 21:55", "  A\ntitle  ", "https://example.com").equals("- 2026-11-04 21:55 — A title\n  <https://example.com>\n"));
        check(Capture.entry("2026-11-04 21:55", "", "https://example.com").contains("— https://example.com"));
        check(Capture.timestamp().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
        try { Capture.firstUrl("no link"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        try { Capture.firstUrl("https://name:password@example.com"); throw new AssertionError(); } catch (IllegalArgumentException expected) { }
        check(AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,9,3}));
        check(!AppendCheck.matches(new byte[]{1,2}, new byte[]{3}, new byte[]{1,2,3,3}));
        System.out.println("Capture and append checks passed (13).");
    }
    private static void check(boolean ok) { if (!ok) throw new AssertionError(); }
}
