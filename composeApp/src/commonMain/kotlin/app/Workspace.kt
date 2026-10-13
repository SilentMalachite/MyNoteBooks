package app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.cash.sqldelight.db.SqlDriver
import app.data.Block
import app.data.Page
import app.data.TitleHit
import app.data.ExitStatement
import app.data.createDriver
import app.data.ensureFirstPage
import app.data.exitStatements
import app.data.keepsData
import app.data.listBlocks
import app.data.listPages
import app.data.newChild
import app.data.removePage
import app.data.savePage
import app.db.NotesDb
import app.editor.SaveController
import app.editor.SaveState
import app.editor.insertBlockAfter
import app.editor.removeBlock
import app.editor.setBlockKind
import app.search.titleSearch
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// seq makes each request new, so focus can come back to a block that had it before.
data class FocusRequest(val id: String, val seq: Int)

class Workspace(
    private val scope: CoroutineScope,
    private val openDriver: suspend () -> SqlDriver = ::createDriver,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val writePage: suspend (NotesDb, Page, List<Block>, Long) -> Unit = ::savePage,
    private val readKeepsData: suspend (SqlDriver) -> Boolean = ::keepsData,
) {
    var opening by mutableStateOf(true)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var pages by mutableStateOf<List<Page>>(emptyList())
        private set
    var openPage by mutableStateOf<Page?>(null)
        private set
    var blocks by mutableStateOf<List<Block>>(emptyList())
        private set
    var query by mutableStateOf("")
        private set
    var hits by mutableStateOf<List<TitleHit>>(emptyList())
        private set
    var focus by mutableStateOf<FocusRequest?>(null)
        private set
    var keepsData by mutableStateOf(true)
        private set

    val saveState: SaveState get() = saver.state

    private var database: NotesDb? = null
    private var searchJob: Job? = null
    private val gate = Mutex()
    private val saver = SaveController(scope) { page, pageBlocks ->
        withDb { db -> writePage(db, page, pageBlocks, clock()) }
    }

    suspend fun start() {
        try {
            val driver = openDriver()
            val db = NotesDb(driver)
            database = db
            keepsData = readKeepsData(driver)
            installExitFlush(
                onHide = {
                    saveOnHide(
                        saving = saver.state == SaveState.Saving,
                        postBatch = { postExitBatch(pendingExitStatements()) },
                        scheduleFlush = { scope.launch { flush() } },
                    )
                },
                onPageHide = { postExitBatch(pendingExitStatements()) },
            )
            val first = withDb { open ->
                ensureFirstPage(open, clock())
                listPages(open).first()
            }
            show(first.id)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            error = cause.message ?: "The notebook could not open."
        } finally {
            opening = false
        }
    }

    fun openLater(id: String) {
        launchDb { open(id) }
    }

    suspend fun open(id: String) {
        if (id == openPage?.id) return
        saver.flush()
        if (saver.pendingDraft() != null) return
        show(id)
    }

    fun newPage() {
        launchDb { newChildAndOpen() }
    }

    suspend fun newChildAndOpen() {
        val parent = openPage ?: return
        if (database == null) return
        saver.flush()
        if (saver.state == SaveState.Failed) return
        val id = withDb { open -> newChild(open, parent.id, clock()) { } }
        show(id)
    }

    fun editTitle(title: String) {
        val page = openPage ?: return
        val updated = page.copy(title = title)
        openPage = updated
        pages = pages.map { if (it.id == page.id) it.copy(title = title) else it }
        saver.onEdit(updated, blocks)
    }

    fun editBody(id: String, body: String) {
        val page = openPage ?: return
        blocks = blocks.map { if (it.id == id) it.copy(body = body) else it }
        saver.onEdit(page, blocks)
    }

    fun toggleChecked(id: String) {
        val page = openPage ?: return
        blocks = blocks.map { block ->
            if (block.id == id) block.copy(checked = if (block.checked == 0L) 1L else 0L) else block
        }
        saver.onEdit(page, blocks)
    }

    fun enter(block: Block) {
        if (block.kind == "page") return
        launchDb {
            if (database == null) return@launchDb
            saver.flush()
            if (saver.state == SaveState.Failed) return@launchDb
            val inserted = withDb { db ->
                val id = insertBlockAfter(db, block.pageId, block.position)
                id to listBlocks(db, block.pageId)
            }
            if (openPage?.id != block.pageId) return@launchDb
            // Keep text typed while the insert ran. The file has the new positions.
            val local = blocks.associateBy { it.id }
            blocks = inserted.second.map { fresh ->
                local[fresh.id]?.let { fresh.copy(body = it.body, checked = it.checked) } ?: fresh
            }
            focusOn(inserted.first)
        }
    }

    fun deleteBlock(block: Block) {
        launchDb {
            if (database == null) return@launchDb
            saver.flush()
            if (saver.state == SaveState.Failed) return@launchDb
            val remaining = withDb { db ->
                if (!removeBlock(db, block)) return@withDb null
                listBlocks(db, block.pageId)
            } ?: return@launchDb
            if (openPage?.id != block.pageId) return@launchDb
            val local = blocks.associateBy { it.id }
            val above = blocks.indexOfFirst { it.id == block.id } - 1
            blocks = remaining.map { fresh ->
                local[fresh.id]?.let { fresh.copy(body = it.body, checked = it.checked) } ?: fresh
            }
            focusOn(blocks[above.coerceIn(0, blocks.lastIndex)].id)
        }
    }

    fun deletePageLater() {
        launchDb { deletePage() }
    }

    // Subpages move up to the parent. The app opens the parent, or the first top-level page.
    suspend fun deletePage() {
        val page = openPage ?: return
        if (database == null) return
        saver.flush()
        // A draft that could not be saved belongs to a page that is gone.
        saver.discard()
        val next = withDb { db ->
            removePage(db, page.id, clock())
            ensureFirstPage(db, clock())
            val left = listPages(db)
            page.parentId?.takeIf { parent -> left.any { it.id == parent } }
                ?: left.first { it.parentId == null }.id
        }
        show(next)
        editQuery(query)
    }

    fun changeKind(id: String, kind: String) {
        launchDb {
            if (database == null) return@launchDb
            saver.flush()
            if (saver.state == SaveState.Failed) return@launchDb
            withDb { db -> setBlockKind(db, id, kind) }
            blocks = blocks.map { if (it.id == id) it.copy(kind = kind) else it }
        }
    }

    fun editQuery(value: String) {
        query = value
        searchJob?.cancel()
        val db = database
        if (value.isBlank() || db == null) {
            hits = emptyList()
            return
        }
        searchJob = launchDb {
            val found = withDb { open -> titleSearch(open, value) }
            if (query == value) hits = found
        }
    }

    fun retrySave() {
        saver.retry()
    }

    suspend fun flush() {
        saver.flush()
    }

    private fun pendingExitStatements(): List<ExitStatement> {
        val pending = saver.pendingDraft() ?: return emptyList()
        return exitStatements(pending.first, pending.second, clock())
    }

    private suspend fun show(id: String) {
        val loaded = withDb { db ->
            val all = listPages(db)
            val page = all.firstOrNull { it.id == id } ?: return@withDb null
            page to all to listBlocks(db, id)
        } ?: return
        val (pageAndAll, loadedBlocks) = loaded
        val (page, all) = pageAndAll
        pages = all
        openPage = page
        blocks = loadedBlocks
        saver.markClean()
        focus = null
        error = null
    }

    private fun focusOn(id: String) {
        focus = FocusRequest(id, (focus?.seq ?: 0) + 1)
    }

    // A failed query shows a message. It must not end the app.
    private fun launchDb(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (cause: Throwable) {
            error = cause.message ?: "The notebook could not finish that change."
        }
    }

    private suspend fun <T> withDb(block: suspend (NotesDb) -> T): T {
        val db = database ?: error("The notebook is not open.")
        return gate.withLock { block(db) }
    }
}
