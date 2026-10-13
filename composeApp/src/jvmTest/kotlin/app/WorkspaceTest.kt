@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app

import app.data.ensureFirstPage
import app.data.listPages
import app.data.newChild
import app.data.savePage
import app.editor.SaveState
import app.nav.pageTree
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class WorkspaceTest {
    @Test
    fun startOpensTheFirstPage() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() })
        screen.start()

        assertEquals(false, screen.opening)
        assertEquals("Untitled", screen.openPage?.title)
        assertEquals(null, screen.openPage?.parentId)
        assertEquals(listOf("text"), screen.blocks.map { it.kind })
    }

    @Test
    fun newPageOpensUnderTheCurrentPage() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val parent = screen.openPage!!.id

        screen.newChildAndOpen()

        assertEquals(parent, screen.openPage?.parentId)
        assertEquals("Untitled", screen.openPage?.title)
        assertEquals("text", screen.blocks.single().kind)
        val tree = pageTree(screen.pages)
        assertEquals(parent, tree.single().page.id)
        assertEquals(screen.openPage?.id, tree.single().children.single().page.id)
    }

    @Test
    fun titleSearchUsesTheSavedTitle() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.editTitle("Week plan")
        advanceTimeBy(400)
        runCurrent()
        screen.editQuery("plan")
        advanceUntilIdle()

        assertEquals(listOf("Week plan"), screen.hits.map { it.title })
    }

    @Test
    fun openStaysOnTheUnsavedPageWhenTheSaveFails() = runTest {
        var failWrites = false
        val screen = Workspace(
            this,
            openDriver = { memoryDriver() },
            clock = { 4 },
            writePage = { db, page, blocks, now ->
                if (failWrites) error("disk")
                savePage(db, page, blocks, now)
            },
        )
        screen.start()
        val originalId = screen.openPage!!.id
        screen.newChildAndOpen()
        val childId = screen.openPage!!.id
        screen.open(originalId)
        screen.editTitle("Kept")
        failWrites = true

        screen.open(childId)

        assertEquals(originalId, screen.openPage?.id)
        assertEquals("Kept", screen.openPage?.title)
        assertEquals(SaveState.Failed, screen.saveState)

        failWrites = false
        screen.retrySave()
        advanceUntilIdle()
        screen.open(childId)
        screen.open(originalId)
        assertEquals("Kept", screen.openPage?.title)
    }

    @Test
    fun aFailedChangeShowsAMessageAndKeepsThePageOpen() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val block = screen.blocks.single()

        screen.changeKind(block.id, "code")
        advanceUntilIdle()

        assertEquals("text", screen.blocks.single().kind)
        assertEquals(false, screen.error.isNullOrEmpty())
        assertEquals("Untitled", screen.openPage?.title)
    }

    @Test
    fun enterAddsABlockBelowAndFocusesIt() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val first = screen.blocks.single()
        screen.editBody(first.id, "Monday")

        screen.enter(first)
        advanceUntilIdle()

        assertEquals(listOf("Monday", ""), screen.blocks.map { it.body })
        assertEquals(listOf(0L, 1L), screen.blocks.map { it.position })
        assertEquals(screen.blocks[1].id, screen.focusId)
    }

    @Test
    fun twoChildrenOfTheSamePageGetDifferentPositions() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val parent = listPages(db).single().id

        launch { newChild(db, parent, 2) { } }
        launch { newChild(db, parent, 3) { } }
        advanceUntilIdle()

        val children = listPages(db).filter { it.parentId == parent }
        assertEquals(2, children.size)
        assertEquals(setOf(0L, 1L), children.map { it.position }.toSet())
    }
}
