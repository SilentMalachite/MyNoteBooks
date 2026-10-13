package app

import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.db.NotesDb

suspend fun memoryDriver(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    NotesDb.Schema.awaitCreate(driver)
    return driver
}

suspend fun newNotesDb(): NotesDb = NotesDb(memoryDriver())
