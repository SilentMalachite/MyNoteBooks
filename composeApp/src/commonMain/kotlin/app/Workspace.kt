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
    private var focusSeq = 0
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
                    // A batch must not cut into a write that holds the gate. Its ROLLBACK would undo half of it.
                    saveOnHide(
                        saving = saver.state == SaveState.Saving || gate.isLocked,
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
        val id = withDb { open ->
            // The page can be deleted while the save ran.
            if (listPages(open).none { it.id == parent.id }) return@withDb null
            newChild(open, parent.id, clock()) { }
        } ?: return
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
                // A delete above can move the block after the key press.
                val current = listBlocks(db, block.pageId).firstOrNull { it.id == block.id } ?: return@withDb null
                val id = insertBlockAfter(db, block.pageId, current.position)
                id to listBlocks(db, block.pageId)
            } ?: return@launchDb
            if (openPage?.id != block.pageId) return@launchDb
            keepLocalText(inserted.second)
            focusOn(inserted.first)
        }
    }

    fun deleteBlock(block: Block) {
        if (database == null || openPage?.id != block.pageId) return
        val index = blocks.indexOfFirst { it.id == block.id }
        if (index < 0 || blocks.size <= 1) return
        // The row leaves the screen now, so keys typed during the delete go to the block above.
        blocks = blocks.filterNot { it.id == block.id }
        focusNear(index - 1)
        launchDb {
            try {
                withDb { db -> removeBlock(db, block) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                // The transaction did not commit, so the file still has the block.
                if (openPage?.id == block.pageId && blocks.none { it.id == block.id }) {
                    blocks = blocks.toMutableList().apply { add(index.coerceAtMost(size), block) }
                }
                throw cause
            }
            val remaining = withDb { db -> listBlocks(db, block.pageId) }
            if (openPage?.id != block.pageId) return@launchDb
            // A block that the file kept comes back.
            keepLocalText(remaining)
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
        val next = withDb { db ->
            removePage(db, page.id, clock())
            ensureFirstPage(db, clock())
            val left = listPages(db)
            page.parentId?.takeIf { parent -> left.any { it.id == parent } }
                ?: left.first { it.parentId == null }.id
        }
        // A draft that could not be saved belongs to a page that is gone.
        // If the delete failed, the draft stays for retry and the exit batch.
        saver.discard()
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

    // Keep text typed while a write ran. The file has the new positions.
    private fun keepLocalText(fresh: List<Block>) {
        val local = blocks.associateBy { it.id }
        blocks = fresh.map { row ->
            local[row.id]?.let { row.copy(body = it.body, checked = it.checked) } ?: row
        }
    }

    private fun focusOn(id: String) {
        focusSeq += 1
        focus = FocusRequest(id, focusSeq)
    }

    // A page link has no field, so focus goes to the nearest text block above, then below.
    private fun focusNear(index: Int) {
        val target = blocks.take(index + 1).lastOrNull { it.kind != "page" }
            ?: blocks.drop(index + 1).firstOrNull { it.kind != "page" }
        if (target == null) focus = null else focusOn(target.id)
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
