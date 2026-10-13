package app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.Workspace
import app.data.Block
import app.data.Page
import app.theme.Bad
import app.theme.Danger
import app.theme.Ink
import app.theme.Line
import app.theme.Mute
import app.theme.Ok
import app.theme.Paper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SaveState {
    Clean,
    Dirty,
    Saving,
    Saved,
    Failed,
}

fun SaveState.word(): String = when (this) {
    SaveState.Clean -> "Ready"
    SaveState.Dirty, SaveState.Failed -> "Not saved"
    SaveState.Saving -> "Saving"
    SaveState.Saved -> "Saved"
}

fun SaveState.color(): Color = when (this) {
    SaveState.Saved -> Ok
    SaveState.Failed -> Bad
    SaveState.Dirty -> Ink
    SaveState.Clean, SaveState.Saving -> Mute
}

class SaveController(
    private val scope: CoroutineScope,
    private val pauseMillis: Long = 400,
    private val save: suspend (Page, List<Block>) -> Unit,
) {
    var state by mutableStateOf(SaveState.Clean)
        private set

    private var job: Job? = null
    private var draft: Pair<Page, List<Block>>? = null
    private var editGeneration = 0
    private var savedGeneration = 0
    private val gate = Mutex()

    fun onEdit(page: Page, blocks: List<Block>) {
        draft = page to blocks
        state = SaveState.Dirty
        editGeneration += 1
        job?.cancel()
        job = scope.launch {
            delay(pauseMillis)
            persistLatest()
        }
    }

    fun retry() {
        if (state != SaveState.Failed) return
        if (draft == null) return
        job?.cancel()
        job = scope.launch { persistLatest() }
    }

    suspend fun flush() {
        job?.cancel()
        if (draft == null || editGeneration == savedGeneration) return
        persistLatest()
    }

    fun pendingDraft(): Pair<Page, List<Block>>? {
        if (editGeneration == savedGeneration) return null
        return draft
    }

    fun markClean() {
        if (editGeneration != savedGeneration) return
        job?.cancel()
        draft = null
        state = SaveState.Clean
    }

    // Drops the draft, saved or not. Used when its page is gone.
    fun discard() {
        job?.cancel()
        draft = null
        savedGeneration = editGeneration
        state = SaveState.Clean
    }

    private suspend fun persistLatest() {
        gate.withLock {
            while (editGeneration != savedGeneration) {
                val generation = editGeneration
                val current = draft ?: return
                state = SaveState.Saving
                try {
                    withContext(NonCancellable) {
                        save(current.first, current.second)
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    if (editGeneration == generation) state = SaveState.Failed
                    return
                }
                if (editGeneration != generation) {
                    state = SaveState.Dirty
                    continue
                }
                savedGeneration = generation
                state = SaveState.Saved
            }
        }
    }
}

@Composable
fun PageEditor(screen: Workspace, modifier: Modifier = Modifier) {
    val page = screen.openPage ?: return
    var confirming by remember(page.id) { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = page.title,
                onValueChange = screen::editTitle,
                singleLine = true,
                textStyle = TextStyle(color = Ink, fontSize = 22.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            Text(
                "Delete",
                color = Mute,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 12.dp).clickable { confirming = true },
            )
            val state = screen.saveState
            Text(
                state.word(),
                color = state.color(),
                fontSize = 13.sp,
                modifier = if (state == SaveState.Failed) {
                    Modifier.clickable(onClick = screen::retrySave)
                } else {
                    Modifier
                },
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        if (confirming) {
            DeleteConfirm(
                title = page.title,
                hasSubpages = screen.pages.any { it.parentId == page.id },
                onDelete = {
                    confirming = false
                    screen.deletePageLater()
                },
                onCancel = { confirming = false },
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            BlockList(
                blocks = screen.blocks,
                pages = screen.pages,
                focus = screen.focus,
                onBody = screen::editBody,
                onToggle = screen::toggleChecked,
                onEnter = screen::enter,
                onDelete = screen::deleteBlock,
                onKind = screen::changeKind,
                onOpenPage = screen::openLater,
            )
        }
    }
}

@Composable
private fun DeleteConfirm(title: String, hasSubpages: Boolean, onDelete: () -> Unit, onCancel: () -> Unit) {
    val question = "Delete “$title”?" + if (hasSubpages) " Its subpages move up." else ""
    Row(
        Modifier.fillMaxWidth().background(Danger).padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(question, color = Ink, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(end = 12.dp))
        Text(
            "Delete",
            color = Paper,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Bad)
                .clickable(onClick = onDelete)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Text(
            "Cancel",
            color = Ink,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .padding(start = 12.dp)
                .clip(RoundedCornerShape(6.dp))
                .border(1.dp, Line, RoundedCornerShape(6.dp))
                .background(Color.White)
                .clickable(onClick = onCancel)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}
