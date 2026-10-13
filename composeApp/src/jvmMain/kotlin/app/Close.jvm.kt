package app

import app.data.ExitStatement
import kotlinx.coroutines.runBlocking

actual fun runBlockingSave(block: suspend () -> Unit) {
    // The save runs on this thread. Joining the UI dispatcher here would deadlock it.
    runBlocking { block() }
}

@Suppress("UNUSED_PARAMETER")
actual fun installExitFlush(onHide: () -> Unit, onPageHide: () -> Unit) = Unit

@Suppress("UNUSED_PARAMETER")
actual fun postExitBatch(statements: List<ExitStatement>) = Unit
