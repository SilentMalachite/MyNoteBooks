package app.editor

import app.data.ensureFirstPage
import app.data.listBlocks
import app.data.listPages
import app.newNotesDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

class BlockListTest {
    @Test
    fun insertMovesLaterBlocksDownByOne() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        db.notesQueries.insertBlock("a", page.id, "text", "a", 0L, 1L)
        db.notesQueries.insertBlock("b", page.id, "text", "b", 0L, 2L)
        val first = listBlocks(db, page.id).first()

        insertBlockAfter(db, page.id, first.position)

        val blocks = listBlocks(db, page.id)
        assertEquals(listOf(0L, 1L, 2L, 3L), blocks.map { it.position })
        assertEquals(listOf("", "", "a", "b"), blocks.map { it.body })
        assertEquals("text", blocks[1].kind)
    }

    @Test
    fun enterOnAPageLinkDoesNotAddABlock() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        val block = listBlocks(db, page.id).single()
        setBlockKind(db, block.id, "page")

        onEnter(db, block.copy(kind = "page"))

        assertEquals(1, listBlocks(db, page.id).size)
        assertEquals("page", listBlocks(db, page.id).single().kind)
    }

    @Test
    fun enterAddsATextBlockBelowTheCursor() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        val block = listBlocks(db, page.id).single()

        onEnter(db, block)

        assertEquals(2, listBlocks(db, page.id).size)
        assertEquals("", listBlocks(db, page.id)[1].body)
    }

    @Test
    fun kindMustBeOneOfTheFive() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val block = listBlocks(db, listPages(db).single().id).single()

        assertFailsWith<IllegalArgumentException> {
            setBlockKind(db, block.id, "code")
        }
        setBlockKind(db, block.id, "todo")
        assertEquals("todo", listBlocks(db, block.pageId).single().kind)
    }

    @Test
    fun removeClosesTheGapInPositions() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        db.notesQueries.insertBlock("a", page.id, "text", "a", 0L, 1L)
        db.notesQueries.insertBlock("b", page.id, "text", "b", 0L, 2L)

        val removed = removeBlock(db, listBlocks(db, page.id)[1])

        assertEquals(true, removed)
        val blocks = listBlocks(db, page.id)
        assertEquals(listOf("", "b"), blocks.map { it.body })
        assertEquals(listOf(0L, 1L), blocks.map { it.position })
    }

    @Test
    fun theLastBlockOfAPageStays() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        val block = listBlocks(db, page.id).single()

        assertEquals(false, removeBlock(db, block))
        assertEquals(listOf(block.id), listBlocks(db, page.id).map { it.id })
    }

    @Test
    fun removingABlockTwiceMovesNoOtherBlock() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        db.notesQueries.insertBlock("a", page.id, "text", "a", 0L, 1L)
        db.notesQueries.insertBlock("b", page.id, "text", "b", 0L, 2L)
        val a = listBlocks(db, page.id)[1]
        removeBlock(db, a)

        assertEquals(false, removeBlock(db, a))
        assertEquals(listOf(0L, 1L), listBlocks(db, page.id).map { it.position })
    }
}
