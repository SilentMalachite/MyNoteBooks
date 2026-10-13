package app.search

import app.data.ensureFirstPage
import app.data.listBlocks
import app.data.listPages
import app.data.newChild
import app.data.savePage
import app.newNotesDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class TitleSearchTest {
    @Test
    fun searchMatchesTheTitleAndSkipsTheBlockText() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val notes = listPages(db).single()
        val block = listBlocks(db, notes.id).single().copy(body = "plan inside the block")
        savePage(db, notes.copy(title = "Notes"), listOf(block), 2)
        val child = newChild(db, notes.id, 3) { }
        savePage(db, listPages(db).first { it.id == child }.copy(title = "Week plan"), listBlocks(db, child), 4)

        assertEquals(listOf("Week plan"), titleSearch(db, "plan").map { it.title })
        assertEquals(notes.id, titleSearch(db, "plan").single().parentId)
    }

    @Test
    fun newerTitleComesFirst() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val first = listPages(db).single()
        savePage(db, first.copy(title = "Notes"), listBlocks(db, first.id), 2)
        val child = newChild(db, first.id, 3) { }
        savePage(db, listPages(db).first { it.id == child }.copy(title = "Field notes"), listBlocks(db, child), 8)

        assertEquals(listOf("Field notes", "Notes"), titleSearch(db, "note").map { it.title })
    }

    @Test
    fun percentSignIsALiteral() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val first = listPages(db).single()
        savePage(db, first.copy(title = "100% done"), listBlocks(db, first.id), 2)
        val child = newChild(db, first.id, 3) { }
        savePage(db, listPages(db).first { it.id == child }.copy(title = "Other"), listBlocks(db, child), 4)

        assertEquals(listOf("100% done"), titleSearch(db, "100%").map { it.title })
    }

    @Test
    fun blankQueryMatchesNothing() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        assertEquals(emptyList(), titleSearch(db, "  "))
    }
}
