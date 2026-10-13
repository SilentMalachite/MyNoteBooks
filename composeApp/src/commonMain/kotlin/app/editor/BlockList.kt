package app.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import app.data.Block
import app.data.Page
import app.data.newId
import app.db.NotesDb
import app.theme.Ink
import app.theme.Line
import app.theme.Mute
import app.theme.Paper

val blockKinds = setOf("text", "heading", "todo", "bullet", "page")

suspend fun insertBlockAfter(db: NotesDb, pageId: String, after: Long): String {
    val id = newId()
    val pos = after + 1
    db.transaction {
        db.notesQueries.shiftPositions(pageId, pos)
        db.notesQueries.insertBlock(id, pageId, "text", "", 0L, pos)
    }
    return id
}

suspend fun onEnter(db: NotesDb, block: Block) {
    if (block.kind == "page") return
    insertBlockAfter(db, block.pageId, block.position)
}

suspend fun setBlockKind(db: NotesDb, id: String, kind: String) {
    require(kind in blockKinds)
    db.notesQueries.setKind(kind, id)
}

@Composable
fun BlockList(
    blocks: List<Block>,
    pages: List<Page>,
    focusId: String?,
    onBody: (String, String) -> Unit,
    onToggle: (String) -> Unit,
    onEnter: (Block) -> Unit,
    onKind: (String, String) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 28.dp)) {
        blocks.forEach { block ->
            // Text field state follows the block, not its slot, when a block is inserted above.
            key(block.id) {
                BlockRow(
                    block = block,
                    pages = pages,
                    focused = block.id == focusId,
                    onBody = { onBody(block.id, it) },
                    onToggle = { onToggle(block.id) },
                    onEnter = { onEnter(block) },
                    onKind = { onKind(block.id, it) },
                    onOpenPage = onOpenPage,
                )
            }
        }
    }
}

@Composable
private fun BlockRow(
    block: Block,
    pages: List<Page>,
    focused: Boolean,
    onBody: (String) -> Unit,
    onToggle: () -> Unit,
    onEnter: () -> Unit,
    onKind: (String) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        KindMenu(block.kind, onKind)
        when (block.kind) {
            "todo" -> TodoBox(block.checked != 0L, onToggle)
            "bullet" -> Text("•", color = Ink, modifier = Modifier.padding(end = 8.dp))
            "page" -> PageLink(block.body, pages, onBody, onOpenPage)
        }
        if (block.kind != "page") {
            BlockField(
                body = block.body,
                kind = block.kind,
                focused = focused,
                onBody = onBody,
                onEnter = onEnter,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun KindMenu(kind: String, onKind: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.width(72.dp)) {
        Text(
            kindLabel(kind),
            color = Mute,
            fontSize = 12.sp,
            maxLines = 1,
            modifier = Modifier.clickable { open = true }.padding(vertical = 4.dp),
        )
        if (open) {
            Popup(onDismissRequest = { open = false }) {
                Column(Modifier.background(Paper).border(1.dp, Line)) {
                    blockKinds.forEach { choice ->
                        Text(
                            kindLabel(choice),
                            color = Ink,
                            fontSize = 13.sp,
                            modifier = Modifier
                                .clickable {
                                    open = false
                                    if (choice != kind) onKind(choice)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .width(88.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TodoBox(checked: Boolean, onToggle: () -> Unit) {
    Box(
        Modifier
            .padding(end = 8.dp)
            .size(13.dp)
            .border(1.5.dp, Ink, RoundedCornerShape(3.dp))
            .clickable(onClick = onToggle),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Text("✓", color = Ink, fontSize = 10.sp)
    }
}

@Composable
private fun RowScope.PageLink(
    targetId: String,
    pages: List<Page>,
    onBody: (String) -> Unit,
    onOpenPage: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val target = pages.firstOrNull { it.id == targetId }
    val label = when {
        targetId.isBlank() -> "Choose a page"
        target == null -> "Missing page"
        else -> "↗ ${target.title}"
    }
    val color = if (target == null) Mute else Ink
    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = if (targetId.isBlank()) Mute else color,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(enabled = target != null) { onOpenPage(targetId) },
        )
        Text(
            "Choose",
            color = Mute,
            fontSize = 12.sp,
            modifier = Modifier.clickable { open = true }.padding(start = 8.dp),
        )
        if (open) {
            Popup(onDismissRequest = { open = false }) {
                Column(
                    Modifier
                        .background(Paper)
                        .border(1.dp, Line)
                        .width(220.dp)
                        .heightIn(max = 280.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    if (pages.isEmpty()) {
                        Text("No pages", color = Mute, modifier = Modifier.padding(12.dp))
                    }
                    pages.forEach { page ->
                        Text(
                            page.title,
                            color = Ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    open = false
                                    onBody(page.id)
                                }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockField(
    body: String,
    kind: String,
    focused: Boolean,
    onBody: (String) -> Unit,
    onEnter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val requester = remember { FocusRequester() }
    LaunchedEffect(focused) {
        if (focused) requester.requestFocus()
    }
    val style = when (kind) {
        "heading" -> TextStyle(color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        else -> TextStyle(color = Ink, fontSize = 15.sp)
    }
    BasicTextField(
        value = body,
        onValueChange = { onBody(it.replace("\n", "")) },
        singleLine = true,
        textStyle = style,
        modifier = modifier
            .focusRequester(requester)
            .onPreviewKeyEvent { event ->
                // The key up of the Enter that commits IME text arrives as a plain Enter on the web.
                if (event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                    onEnter()
                    true
                } else {
                    false
                }
            }
            .defaultMinSize(minHeight = 24.dp)
            .padding(vertical = 3.dp),
    )
}

private fun kindLabel(kind: String): String = when (kind) {
    "text" -> "Text"
    "heading" -> "Heading"
    "todo" -> "To-do"
    "bullet" -> "Bullet"
    "page" -> "Page"
    else -> kind
}
