package app.data

import app.cash.sqldelight.async.coroutines.synchronous
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.db.NotesDb
import java.io.File

actual suspend fun createDriver(): SqlDriver {
    return openJvmDriver(notebookDatabaseFile())
}

fun notebookDatabaseFile(home: File = File(System.getProperty("user.home"))): File {
    return File(home, ".notebook/notebook.db")
}

fun openJvmDriver(file: File): SqlDriver {
    file.parentFile?.mkdirs()
    return JdbcSqliteDriver(
        url = "jdbc:sqlite:${file.path}",
        schema = NotesDb.Schema.synchronous(),
    )
}
