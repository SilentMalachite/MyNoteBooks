package app.data

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.db.NotesDb
import app.nav.liftChildren
import kotlin.uuid.Uuid

suspend fun ensureFirstPage(db: NotesDb, now: Long) {
    if (db.notesQueries.countPages().awaitAsOne() != 0L) return
    val id = newId()
    db.transaction {
        if (db.notesQueries.countPages().awaitAsOne() != 0L) return@transaction
        db.notesQueries.insertPage(id, null, "Untitled", 0L, now)
        db.notesQueries.insertBlock(newId(), id, "text", "", 0L, 0L)
    }
}

suspend fun newChild(db: NotesDb, parentId: String, now: Long, open: (String) -> Unit): String {
    val id = newId()
    val blockId = newId()
    db.transaction {
        val position = db.notesQueries.nextPosition(parentId).awaitAsOne()
        db.notesQueries.insertPage(id, parentId, "Untitled", position, now)
        db.notesQueries.insertBlock(blockId, id, "text", "", 0L, 0L)
    }
    open(id)
    return id
}

fun exitStatements(page: Page, blocks: List<Block>, now: Long): List<ExitStatement> {
    val statements = mutableListOf(
        ExitStatement(
            "UPDATE page SET title = ?, updated_at = ? WHERE id = ?",
            listOf(ExitParam.Text(page.title), ExitParam.Number(now), ExitParam.Text(page.id)),
        ),
    )
    blocks.forEach { block ->
        statements += ExitStatement(
            "UPDATE block SET body = ?, checked = ? WHERE id = ?",
            listOf(
                ExitParam.Text(block.body),
                ExitParam.Number(block.checked),
                ExitParam.Text(block.id),
            ),
        )
    }
    return statements
}

suspend fun savePage(db: NotesDb, page: Page, blocks: List<Block>, now: Long) {
    db.transaction {
        db.notesQueries.updatePage(page.title, now, page.id)
        // Only insertBlockAfter and removeBlock move blocks. A draft can hold old positions.
        blocks.forEach { block ->
            db.notesQueries.updateBlock(block.body, block.checked, block.id)
        }
    }
}

// The page row and its blocks stay in the file with deleted_at set.
suspend fun removePage(db: NotesDb, id: String, now: Long) {
    db.transaction {
        val moved = liftChildren(listPages(db), id)
        db.notesQueries.markDeleted(now, id)
        moved.forEach { db.notesQueries.movePage(it.parentId, it.position, it.id) }
    }
}

suspend fun listPages(db: NotesDb): List<Page> {
    return db.notesQueries.selectPages().awaitAsList().map { row ->
        Page(row.id, row.parent_id, row.title, row.position, row.updated_at)
    }
}

suspend fun listBlocks(db: NotesDb, pageId: String): List<Block> {
    return db.notesQueries.selectBlocks(pageId).awaitAsList().map { row ->
        Block(row.id, row.page_id, row.kind, row.body, row.checked, row.position)
    }
}

internal fun newId(): String = Uuid.random().toString()
