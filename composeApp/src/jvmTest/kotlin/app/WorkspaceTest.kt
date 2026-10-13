@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app

import app.data.ensureFirstPage
import app.data.listPages
import app.data.newChild
import app.data.savePage
import app.editor.SaveState
import app.nav.pageTree
import app.cash.sqldelight.async.coroutines.await
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
        assertEquals(screen.blocks[1].id, screen.focus?.id)
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

    @Test
    fun deleteBlockRemovesItAndFocusesTheBlockAbove() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.editBody(screen.blocks.single().id, "Monday")
        screen.enter(screen.blocks.single())
        advanceUntilIdle()
        screen.enter(screen.blocks[1])
        advanceUntilIdle()
        val first = screen.blocks[0]
        screen.editBody(screen.blocks[2].id, "Wednesday")

        screen.deleteBlock(screen.blocks[1])
        advanceUntilIdle()

        assertEquals(listOf("Monday", "Wednesday"), screen.blocks.map { it.body })
        assertEquals(listOf(0L, 1L), screen.blocks.map { it.position })
        assertEquals(first.id, screen.focus?.id)
        val seq = screen.focus!!.seq

        screen.deleteBlock(screen.blocks[1])
        advanceUntilIdle()

        assertEquals(listOf("Monday"), screen.blocks.map { it.body })
        assertEquals(first.id, screen.focus?.id)
        assertEquals(seq + 1, screen.focus?.seq)
    }

    @Test
    fun deletingTheFirstBlockFocusesTheNewFirst() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.enter(screen.blocks.single())
        advanceUntilIdle()
        val second = screen.blocks[1]

        screen.deleteBlock(screen.blocks[0])
        advanceUntilIdle()

        assertEquals(listOf(second.id), screen.blocks.map { it.id })
        assertEquals(second.id, screen.focus?.id)
    }

    @Test
    fun theLastBlockOfAPageCannotBeDeleted() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val block = screen.blocks.single()

        screen.deleteBlock(block)
        advanceUntilIdle()

        assertEquals(listOf(block.id), screen.blocks.map { it.id })
        assertEquals(null, screen.error)
    }

    @Test
    fun deletePageOpensTheParentAndLiftsTheSubpages() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val root = screen.openPage!!.id
        screen.newChildAndOpen()
        val middle = screen.openPage!!.id
        screen.newChildAndOpen()
        val leaf = screen.openPage!!.id
        screen.open(middle)

        screen.deletePage()

        assertEquals(root, screen.openPage?.id)
        assertEquals(setOf(root, leaf), screen.pages.map { it.id }.toSet())
        assertEquals(root, screen.pages.first { it.id == leaf }.parentId)
    }

    @Test
    fun deletingATopLevelPageOpensTheFirstTopLevelPage() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val root = screen.openPage!!.id
        screen.newChildAndOpen()
        val child = screen.openPage!!.id
        screen.open(root)

        screen.deletePage()

        assertEquals(child, screen.openPage?.id)
        assertEquals(null, screen.openPage?.parentId)
        assertEquals(listOf(child), screen.pages.map { it.id })
    }

    @Test
    fun deletingTheLastPageMakesANewUntitledPage() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        val only = screen.openPage!!.id
        screen.editTitle("Week plan")

        screen.deletePage()

        assertEquals(1, screen.pages.size)
        assertEquals("Untitled", screen.openPage?.title)
        assertEquals(false, screen.openPage?.id == only)
        assertEquals(listOf("text"), screen.blocks.map { it.kind })
        assertEquals(SaveState.Clean, screen.saveState)
    }

    @Test
    fun deletePageDropsADraftThatCouldNotBeSaved() = runTest {
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
        val root = screen.openPage!!.id
        screen.newChildAndOpen()
        failWrites = true
        screen.editTitle("Lost")

        screen.deletePage()

        assertEquals(root, screen.openPage?.id)
        assertEquals(SaveState.Clean, screen.saveState)
        advanceUntilIdle()
        assertEquals(SaveState.Clean, screen.saveState)
    }

    @Test
    fun deletePageRunsTheSearchAgain() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.newChildAndOpen()
        screen.editTitle("Week plan")
        screen.flush()
        screen.editQuery("plan")
        advanceUntilIdle()
        assertEquals(listOf("Week plan"), screen.hits.map { it.title })

        screen.deletePage()
        advanceUntilIdle()

        assertEquals(emptyList(), screen.hits)
    }

    @Test
    fun keepsDataComesFromTheDriver() = runTest {
        val kept = Workspace(this, openDriver = { memoryDriver() })
        kept.start()
        assertEquals(true, kept.keepsData)

        val lost = Workspace(this, openDriver = { memoryDriver() }, readKeepsData = { false })
        lost.start()
        assertEquals(false, lost.keepsData)
        assertEquals("Untitled", lost.openPage?.title)
    }

    @Test
    fun aDeletedBlockLeavesTheScreenBeforeTheFileChanges() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.enter(screen.blocks.single())
        advanceUntilIdle()
        val (first, second) = screen.blocks

        screen.deleteBlock(second)

        // No coroutine has run yet. Keys typed now go to the block above.
        assertEquals(listOf(first.id), screen.blocks.map { it.id })
        assertEquals(first.id, screen.focus?.id)
        advanceUntilIdle()
        assertEquals(listOf(first.id), screen.blocks.map { it.id })
        assertEquals(listOf(0L), screen.blocks.map { it.position })
    }

    @Test
    fun aFailedBlockDeleteBringsTheBlockBack() = runTest {
        val screen = Workspace(this, openDriver = { failingDriver("BEFORE DELETE ON block") }, clock = { 4 })
        screen.start()
        screen.enter(screen.blocks.single())
        advanceUntilIdle()
        val ids = screen.blocks.map { it.id }

        screen.deleteBlock(screen.blocks[1])
        advanceUntilIdle()

        assertEquals(ids, screen.blocks.map { it.id })
        assertEquals(false, screen.error.isNullOrEmpty())
    }

    @Test
    fun focusSkipsAPageLinkAbove() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.enter(screen.blocks.single())
        advanceUntilIdle()
        screen.enter(screen.blocks[1])
        advanceUntilIdle()
        val (first, link, last) = screen.blocks
        screen.changeKind(link.id, "page")
        advanceUntilIdle()

        screen.deleteBlock(last)
        advanceUntilIdle()
        assertEquals(first.id, screen.focus?.id)

        screen.deleteBlock(first)
        advanceUntilIdle()
        // Only the page link is left. It has no field to focus.
        assertEquals(listOf(link.id), screen.blocks.map { it.id })
        assertEquals(null, screen.focus?.id)
    }

    @Test
    fun enterAfterADeleteUsesThePositionInTheFile() = runTest {
        val screen = Workspace(this, openDriver = { memoryDriver() }, clock = { 4 })
        screen.start()
        screen.enter(screen.blocks.single())
        advanceUntilIdle()
        screen.enter(screen.blocks[1])
        advanceUntilIdle()
        val (a, b, c) = screen.blocks

        screen.deleteBlock(a)
        screen.enter(b)
        advanceUntilIdle()

        val ids = screen.blocks.map { it.id }
        assertEquals(3, ids.size)
        assertEquals(b.id, ids[0])
        assertEquals(c.id, ids[2])
        assertEquals(listOf(0L, 1L, 2L), screen.blocks.map { it.position })
    }

    @Test
    fun aFailedPageDeleteKeepsTheUnsavedDraft() = runTest {
        var failWrites = false
        val screen = Workspace(
            this,
            openDriver = { failingDriver("BEFORE UPDATE OF deleted_at ON page") },
            clock = { 4 },
            writePage = { db, page, blocks, now ->
                if (failWrites) error("disk")
                savePage(db, page, blocks, now)
            },
        )
        screen.start()
        failWrites = true
        screen.editTitle("Kept")

        assertFails { screen.deletePage() }

        assertEquals("Kept", screen.openPage?.title)
        assertEquals(SaveState.Failed, screen.saveState)
        failWrites = false
        screen.retrySave()
        advanceUntilIdle()
        assertEquals(SaveState.Saved, screen.saveState)
    }

    @Test
    fun newDuringADeleteMakesNoPageUnderTheDeletedPage() = runTest {
        val release = CompletableDeferred<Unit>()
        val screen = Workspace(
            this,
            openDriver = { memoryDriver() },
            clock = { 4 },
            writePage = { db, page, blocks, now ->
                release.await()
                savePage(db, page, blocks, now)
            },
        )
        screen.start()
        val root = screen.openPage!!.id
        screen.newChildAndOpen()
        val doomed = screen.openPage!!.id
        screen.editTitle("Doomed")

        launch { screen.deletePage() }
        runCurrent()
        launch { screen.newChildAndOpen() }
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        val live = screen.pages.map { it.id }.toSet()
        assertTrue(doomed !in live)
        assertTrue(screen.pages.all { it.parentId == null || it.parentId in live })
        assertEquals(root, screen.openPage?.id)
    }
}

private suspend fun failingDriver(trigger: String) = memoryDriver().also { driver ->
    driver.await(null, "CREATE TRIGGER fail $trigger BEGIN SELECT RAISE(ABORT, 'disk'); END", 0)
}
