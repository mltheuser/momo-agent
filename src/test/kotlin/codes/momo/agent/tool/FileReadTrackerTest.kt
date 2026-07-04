package codes.momo.agent.tool

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileReadTrackerTest {

    @Test
    @DisplayName("A path is unread until marked")
    fun pathIsUnreadByDefault() {
        val tracker = FileReadTracker()

        assertFalse(tracker.wasRead("/workspace/notes.txt"))
    }

    @Test
    @DisplayName("A marked path reads back as read")
    fun markedPathIsRead() {
        val tracker = FileReadTracker()

        tracker.markRead("/workspace/notes.txt")

        assertTrue(tracker.wasRead("/workspace/notes.txt"))
    }

    @Test
    @DisplayName("Matching is by exact string — dot segments and doubled slashes are not normalized")
    fun matchingIsExactString() {
        val tracker = FileReadTracker()

        tracker.markRead("/workspace/notes.txt")

        assertFalse(tracker.wasRead("/workspace/./notes.txt"))
        assertFalse(tracker.wasRead("/workspace//notes.txt"))
        assertFalse(tracker.wasRead("/workspace/sub/../notes.txt"))
    }

    @Test
    @DisplayName("Independent instances share no state")
    fun independentInstancesShareNoState() {
        val marked = FileReadTracker()
        marked.markRead("/workspace/notes.txt")

        assertFalse(FileReadTracker().wasRead("/workspace/notes.txt"))
    }
}
