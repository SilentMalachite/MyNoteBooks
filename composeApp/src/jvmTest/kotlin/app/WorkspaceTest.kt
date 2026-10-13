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
}
