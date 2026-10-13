package app

import app.data.ExitStatement
import app.data.toCommitJson
import kotlin.JsFun
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.w3c.dom.Worker
import org.w3c.dom.events.Event

internal object WebExit {
    var worker: Worker? = null
}

@OptIn(DelicateCoroutinesApi::class)
actual fun runBlockingSave(block: suspend () -> Unit) {
    // The main thread has to keep reading worker replies, so this cannot wait inline.
    GlobalScope.launch(Dispatchers.Main) { block() }
}

actual fun installExitFlush(onHide: () -> Unit, onPageHide: () -> Unit) {
    document.addEventListener("visibilitychange", { _: Event ->
        if (shouldFlushForVisibility(pageVisibility())) onHide()
    })
    window.addEventListener("pagehide", { _: Event -> onPageHide() })
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun("() => document.visibilityState")
private external fun pageVisibility(): String

actual fun postExitBatch(statements: List<ExitStatement>) {
    val worker = WebExit.worker ?: return
    if (statements.isEmpty()) return
    postCommitBatch(worker, statements.toCommitJson())
}

@OptIn(ExperimentalWasmJsInterop::class)
@JsFun(
    "(worker, json) => { worker.postMessage({ id: -1, action: 'commit_batch', statements: JSON.parse(json) }); }",
)
private external fun postCommitBatch(worker: Worker, json: String)


