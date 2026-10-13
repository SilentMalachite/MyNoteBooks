package app.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.db.NotesDb
import app.newNotesDb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class PageStoreTest {
    @Test
    fun emptyNotebookGetsOnePageAndOneTextBlock() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 10)
        ensureFirstPage(db, 11)

        val pages = listPages(db)
        assertEquals(1, pages.size)
        assertEquals("Untitled", pages.single().title)
        assertEquals(null, pages.single().parentId)
        val blocks = listBlocks(db, pages.single().id)
        assertEquals(1, blocks.size)
        assertEquals("text", blocks.single().kind)
        assertEquals("", blocks.single().body)
        assertEquals(0L, blocks.single().position)
    }

    @Test
    fun newPageIsAChildOfTheOpenPage() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val parent = listPages(db).single()
        var opened = ""
        val child = newChild(db, parent.id, 2) { opened = it }
        val again = newChild(db, parent.id, 3) { }

        val pages = listPages(db)
        val created = pages.first { it.id == child }
        assertEquals(parent.id, created.parentId)
        assertEquals("Untitled", created.title)
        assertEquals(child, opened)
        assertEquals(0L, created.position)
        assertEquals(1L, pages.first { it.id == again }.position)
        assertEquals("text", listBlocks(db, child).single().kind)
    }

    @Test
    fun saveWritesThePageAndTheBlocksTogether() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        val block = listBlocks(db, page.id).single()
        savePage(
            db,
            page.copy(title = "Week plan"),
            listOf(block.copy(body = "Write the notes", checked = 1)),
            9,
        )

        assertEquals("Week plan", listPages(db).single().title)
        assertEquals(9L, listPages(db).single().updatedAt)
        val saved = listBlocks(db, page.id).single()
        assertEquals("Write the notes", saved.body)
        assertEquals(1L, saved.checked)
    }

    @Test
    fun aDraftFromBeforeAnInsertKeepsTheNewOrder() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val page = listPages(db).single()
        db.notesQueries.insertBlock("b", page.id, "text", "b", 0L, 1L)
        val stale = listBlocks(db, page.id)
        val inserted = app.editor.insertBlockAfter(db, page.id, stale.first().position)

        savePage(db, page, stale.map { it.copy(body = it.body + "!") }, 2)

        val blocks = listBlocks(db, page.id)
        assertEquals(listOf(0L, 1L, 2L), blocks.map { it.position })
        assertEquals(listOf(stale[0].id, inserted, "b"), blocks.map { it.id })
        assertEquals("b!", blocks.last().body)
    }

    @Test
    fun failedWriteChangesNoRow() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDb.Schema.awaitCreate(driver)
        val db = NotesDb(driver)
        ensureFirstPage(db, 1)
        driver.await(
            null,
            """
            CREATE TRIGGER block_fail BEFORE UPDATE ON block
            BEGIN
              SELECT RAISE(ROLLBACK, 'no');
            END;
            """.trimIndent(),
            0,
        )
        val page = listPages(db).single().copy(title = "Changed")
        val result = runCatching { savePage(db, page, listBlocks(db, page.id), 2) }

        assertTrue(result.isFailure)
        assertEquals("Untitled", listPages(db).single().title)
    }

    @Test
    fun deletedPageStaysOutOfTheList() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        NotesDb.Schema.awaitCreate(driver)
        val db = NotesDb(driver)
        ensureFirstPage(db, 1)
        val id = listPages(db).single().id
        driver.await(null, "UPDATE page SET deleted_at = 1 WHERE id = ?", 1) {
            bindString(0, id)
        }

        assertEquals(0, listPages(db).size)
        assertEquals(0L, db.notesQueries.countPages().awaitAsOne())
    }

    @Test
    fun removePageMarksItAndLiftsItsChildren() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val notes = listPages(db).single().id
        db.notesQueries.insertPage("ideas", null, "Ideas", 1L, 1)
        val week = newChild(db, notes, 2) { }
        val meeting = newChild(db, notes, 3) { }

        removePage(db, notes, 9)

        val pages = listPages(db).sortedBy { it.position }
        assertEquals(listOf(week, meeting, "ideas"), pages.map { it.id })
        assertEquals(listOf(0L, 1L, 2L), pages.map { it.position })
        assertEquals(listOf(null, null, null), pages.map { it.parentId })
        // The row and its blocks stay in the file.
        assertEquals(1, listBlocks(db, notes).size)
    }

    @Test
    fun removeOnADeletedPageChangesNothing() = runTest {
        val db = newNotesDb()
        ensureFirstPage(db, 1)
        val notes = listPages(db).single().id
        db.notesQueries.insertPage("ideas", null, "Ideas", 1L, 1)
        removePage(db, notes, 9)

        removePage(db, notes, 10)

        assertEquals(listOf("ideas"), listPages(db).map { it.id })
        assertEquals(0L, listPages(db).single().position)
    }

    @Test
    fun exitBatchWritesTheTitleAndTheBlocks() {
        val page = Page("p", null, "Week plan", 0, 9)
        val block = Block("b", "p", "text", "Write", 1, 0)
        val statements = exitStatements(page, listOf(block), 9)

        assertEquals("UPDATE page SET title = ?, updated_at = ? WHERE id = ?", statements[0].sql)
        assertEquals(ExitParam.Text("Week plan"), statements[0].params[0])
        assertEquals(ExitParam.Number(9), statements[0].params[1])
        assertEquals("UPDATE block SET body = ?, checked = ? WHERE id = ?", statements[1].sql)
        assertEquals(3, statements[1].params.size)
        assertIs<ExitParam.Text>(statements[1].params[0])
        assertEquals("Write", (statements[1].params[0] as ExitParam.Text).value)
    }
}
