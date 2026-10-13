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
import app.data.listBlocks
import app.data.listPages
import app.data.newChild
import app.data.savePage
import app.db.NotesDb
import app.editor.SaveController
import app.editor.SaveState
import app.editor.insertBlockAfter
import app.editor.setBlockKind
import app.search.titleSearch
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class Workspace(
    private val scope: CoroutineScope,
    private val openDriver: suspend () -> SqlDriver = ::createDriver,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val writePage: suspend (NotesDb, Page, List<Block>, Long) -> Unit = ::savePage,
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
    var focusId by mutableStateOf<String?>(null)
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
            val db = NotesDb(openDriver())
            database = db
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
            focusId = inserted.first
        }
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
        focusId = null
        error = null
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
