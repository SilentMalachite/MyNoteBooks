package app

import app.data.ExitStatement

expect fun runBlockingSave(block: suspend () -> Unit)

expect fun installExitFlush(onHide: () -> Unit, onPageHide: () -> Unit)

expect fun postExitBatch(statements: List<ExitStatement>)

internal fun shouldFlushForVisibility(state: String): Boolean = state == "hidden"

internal fun saveOnHide(saving: Boolean, postBatch: () -> Unit, scheduleFlush: () -> Unit) {
    if (!saving) postBatch()
    scheduleFlush()
}
