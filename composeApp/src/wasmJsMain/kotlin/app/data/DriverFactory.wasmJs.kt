package app.data

import app.cash.sqldelight.async.coroutines.await
import app.cash.sqldelight.async.coroutines.awaitCreate
import app.cash.sqldelight.async.coroutines.awaitMigrate
import app.cash.sqldelight.async.coroutines.awaitQuery
import app.cash.sqldelight.db.SqlDriver
import app.WebExit
import app.cash.sqldelight.driver.worker.WebWorkerDriver
import app.db.NotesDb
import kotlin.js.ExperimentalWasmJsInterop
import org.w3c.dom.Worker

actual suspend fun createDriver(): SqlDriver {
    val worker = sqliteWorker()
    WebExit.worker = worker
    val driver = WebWorkerDriver(worker)
    prepareNotes(driver)
    return driver
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun sqliteWorker(): Worker = js(
    """new Worker(new URL("/sqlite.worker" + ".js", self.location.origin), { type: "module" })""",
)

private suspend fun prepareNotes(driver: SqlDriver) {
    val schema = NotesDb.Schema
    val version = driver.awaitQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor ->
            if (cursor.next().await()) cursor.getLong(0) ?: 0L else 0L
        },
        parameters = 0,
    )
    if (version == 0L) {
        schema.awaitCreate(driver)
        driver.await(null, "PRAGMA user_version = ${schema.version}", 0)
    } else if (version < schema.version) {
        schema.awaitMigrate(driver, version, schema.version)
        driver.await(null, "PRAGMA user_version = ${schema.version}", 0)
    }
}
