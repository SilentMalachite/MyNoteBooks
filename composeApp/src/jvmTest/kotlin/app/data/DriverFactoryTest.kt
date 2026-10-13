package app.data

import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.db.NotesDb
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class DriverFactoryTest {
    @Test
    fun databaseFileLivesUnderTheHomeNotebookDirectory() {
        val home = File("/tmp/home")
        assertEquals(File(home, ".notebook/notebook.db"), notebookDatabaseFile(home))
    }

    @Test
    fun openingAnExistingFileKeepsThePages() = runTest {
        val root = File(System.getProperty("java.io.tmpdir"), "notebook-${System.nanoTime()}")
        try {
            val file = notebookDatabaseFile(root)
            val first = openJvmDriver(file)
            ensureFirstPage(NotesDb(first), 1)
            first.close()

            val second = openJvmDriver(file)
            assertEquals(1L, NotesDb(second).notesQueries.countPages().awaitAsOne())
            second.close()
        } finally {
            root.deleteRecursively()
        }
    }
}
