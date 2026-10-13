@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.editor

import app.data.Page
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class SaveControllerTest {
    private val page = Page("p", null, "Week plan", 0, 0)

    @Test
    fun pauseOf400msStartsOneWrite() = runTest {
        var writes = 0
        val controller = SaveController(this) { _, _ -> writes += 1 }
        controller.onEdit(page, emptyList())

        advanceTimeBy(399)
        runCurrent()
        assertEquals(SaveState.Dirty, controller.state)
        assertEquals(0, writes)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(SaveState.Saved, controller.state)
    }

    @Test
    fun aNewEditWaits400msAgain() = runTest {
        var writes = 0
        val controller = SaveController(this) { _, _ -> writes += 1 }
        controller.onEdit(page, emptyList())
        advanceTimeBy(300)
        runCurrent()
        controller.onEdit(page.copy(title = "Week"), emptyList())
        advanceTimeBy(300)
        runCurrent()

        assertEquals(0, writes)
        advanceTimeBy(100)
        runCurrent()
        assertEquals(1, writes)
        assertEquals(SaveState.Saved, controller.state)
    }

    @Test
    fun aFailedWriteCanBeRetried() = runTest {
        var fail = true
        var writes = 0
        val controller = SaveController(this) { _, _ ->
            writes += 1
            if (fail) error("disk")
        }
        controller.onEdit(page, emptyList())
        advanceTimeBy(400)
        runCurrent()
        assertEquals(SaveState.Failed, controller.state)
        assertEquals(1, writes)

        fail = false
        controller.retry()
        advanceUntilIdle()
        assertEquals(2, writes)
        assertEquals(SaveState.Saved, controller.state)
    }

    @Test
    fun flushWritesThePendingDraftWithoutWaitingForThePause() = runTest {
        var writes = 0
        val controller = SaveController(this) { _, _ -> writes += 1 }
        controller.onEdit(page, emptyList())

        controller.flush()

        assertEquals(1, writes)
        assertEquals(SaveState.Saved, controller.state)
        advanceUntilIdle()
        assertEquals(1, writes)
    }

    @Test
    fun aSlowWriteDoesNotFailWhenANewerEditCancelsIt() = runTest {
        val inside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>()
        val controller = SaveController(this, pauseMillis = 0) { current, _ ->
            saved += current.title
            if (saved.size == 1) {
                inside.complete(Unit)
                release.await()
            }
        }
        controller.onEdit(page, emptyList())
        advanceUntilIdle()
        assertTrue(inside.isCompleted)

        controller.onEdit(page.copy(title = "Next"), emptyList())
        runCurrent()
        assertNotEquals(SaveState.Failed, controller.state)

        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(SaveState.Saved, controller.state)
        assertEquals("Next", saved.last())
    }
}
