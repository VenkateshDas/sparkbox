import java.util.ArrayList;
import java.util.List;
import me.venkatesh.saveobsidian.*;

/** Runs against the real Kotlin state store without Android or a provider. */
public final class AskStateTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        AskState state = new AskState();
        AskUiState initial = state.getState().getValue();
        check(initial.getWeb(), "New chats default to vault and web");
        check(!initial.getFullAccess(), "Unattended execution requires explicit selection");
        state.fullAccess(true);
        check(state.getFullAccess() && !initial.getFullAccess(), "Approval selection publishes an immutable snapshot");
        state.input("draft"); state.caret(99);
        check(state.getCaret() == 5, "Caret must stay inside the draft");
        state.input("x");
        check(state.getCaret() == 1, "Shortening a draft clamps its caret");
        check(initial.getInput().isEmpty(), "Published snapshots must not change");
        List<AskItem> items = new ArrayList<>(); items.add(new Question("first"));
        List<String> keys = new ArrayList<>(List.of("first"));
        state.publish(items, keys); items.clear(); keys.clear();
        check(state.getItems().size() == 1 && state.getKeys().size() == 1, "Publishing must detach controller lists");
        state.running(true); state.status("Reading"); state.live("Partial");
        AskUiState running = state.getState().getValue();
        state.running(false);
        check(!state.getRunning() && state.getStatus().isEmpty() && state.getLive().isEmpty(), "Stopping clears streaming state together");
        check(running.getRunning() && running.getLive().equals("Partial"), "Stopping must preserve old snapshots");
        state.drawer(true); state.query("match"); state.drawer(false);
        check(state.getQuery().isEmpty(), "Closing the drawer clears its filter");
        state.research(true); state.web(true); state.session("other");
        check(state.getResearch() && state.getWeb(), "Session publication must not discard composer options");
        state.toolNotice("Connection failed"); state.running(true); state.status("Thinking…"); state.running(false);
        check(state.getState().getValue().getToolNotice().equals("Connection failed"), "Connection failures must survive transient status and run completion");
        ChatScrollPosition scroll = new ChatScrollPosition(17, 42);
        scroll.scrollChanged(true, false, false);
        check(scroll.getFollowLatest(), "Programmatic scrolling must not disable following");
        scroll.scrollChanged(true, true, false);
        check(!scroll.getFollowLatest(), "A user drag must pause following");
        scroll.scrollChanged(false, false, false);
        check(!scroll.getFollowLatest(), "Content growth must not resume following while reading history");
        scroll.scrollChanged(true, true, true);
        scroll.scrollChanged(false, false, true);
        check(scroll.getFollowLatest(), "Reaching the bottom resumes following");
        check(scroll.shouldFollow(true, false, false), "Late Markdown growth must request another bottom alignment");
        check(!scroll.shouldFollow(true, true, false), "Never fight an ongoing scroll animation");
        scroll.scrollChanged(true, true, false); scroll.scrollChanged(false, false, false);
        scroll.scrollChanged(false, false, true);
        check(!scroll.getFollowLatest(), "A transient empty/restoring layout must not reset saved reading intent");
        check(scroll.getIndex() == 17 && scroll.getOffset() == 42, "Layout events preserve the saved position");
        AskUiState beforeChunk = state.getState().getValue().chrome();
        state.live("next chunk");
        check(beforeChunk.equals(state.getState().getValue().chrome()), "Streaming chunks must not invalidate history/composer rendering");
        state.status("New tool");
        check(!beforeChunk.equals(state.getState().getValue().chrome()), "Tool status still updates the screen");
        check(scroll.restoreOffset(17, 0) == null && scroll.restoreOffset(17, 21) == null, "Wait for Markdown measurement before restoring an offset");
        check(scroll.restoreOffset(18, 100) == null, "Never apply the saved offset to another row");
        check(scroll.restoreOffset(17, 100) == 42, "Restore the reading offset once the target row is measured");
        List<AskItem> history = List.of(new Question("saved"), new Work(10, List.of("Done")));
        state.publish(history, List.of("q", "work"));
        AskUiState sameHistory = state.getState().getValue();
        state.publish(List.of(new Question("saved"), new Work(10, List.of("Done"))), List.of("q", "work"));
        check(sameHistory == state.getState().getValue(), "Unchanged history must not emit another screen update");
        System.out.println("Ask state checks passed (26).");
    }
}
