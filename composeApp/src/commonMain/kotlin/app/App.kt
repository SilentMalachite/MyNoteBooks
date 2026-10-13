package app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.editor.PageEditor
import app.nav.PageTree
import app.search.TitleHits
import app.search.TitleSearchField
import app.theme.Bad
import app.theme.Ink
import app.theme.Line
import app.theme.Mute
import app.theme.Paper
import app.theme.Warn
import app.theme.WarnInk
import app.theme.WarnLine

@Composable
fun App() {
    val screen = rememberWorkspace()
    WorkspaceScreen(screen)
}

@Composable
fun rememberWorkspace(): Workspace {
    val scope = rememberCoroutineScope()
    val screen = remember { Workspace(scope) }
    LaunchedEffect(screen) { screen.start() }
    DisposableEffect(screen) {
        onDispose { runBlockingSave { screen.flush() } }
    }
    return screen
}

@Composable
fun WorkspaceScreen(screen: Workspace) {
    when {
        screen.opening -> Status("Opening the notebook")
        screen.error != null && screen.openPage == null -> Status(screen.error ?: "The notebook could not open.")
        else -> Column(Modifier.fillMaxSize().background(Paper)) {
            if (!screen.keepsData) StorageBand()
            Row(Modifier.weight(1f)) {
                PageRail(screen, Modifier.width(260.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(Line))
                PageEditor(screen, Modifier.weight(1f).fillMaxHeight())
            }
        }
    }
}

@Composable
private fun StorageBand() {
    Text(
        "This browser cannot keep your notes. They go away when you close or reload the tab.",
        color = WarnInk,
        fontSize = 13.sp,
        modifier = Modifier.fillMaxWidth().background(Warn).padding(horizontal = 14.dp, vertical = 8.dp),
    )
    Box(Modifier.fillMaxWidth().height(1.dp).background(WarnLine))
}

@Composable
private fun PageRail(screen: Workspace, modifier: Modifier = Modifier) {
    Column(modifier.background(Paper)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pages", color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(
                "New",
                color = Mute,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClick = screen::newPage),
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
        TitleSearchField(
            query = screen.query,
            onQuery = screen::editQuery,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            TitleHits(
                hits = screen.hits,
                parentTitle = { parentId -> screen.pages.firstOrNull { it.id == parentId }?.title.orEmpty() },
                onOpen = screen::openLater,
            )
            PageTree(
                pages = screen.pages,
                openId = screen.openPage?.id,
                onOpen = screen::openLater,
            )
        }
        screen.error?.let { message ->
            Text(message, color = Bad, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Status(message: String) {
    Box(Modifier.fillMaxSize().background(Paper), contentAlignment = Alignment.Center) {
        Text(message, color = Mute)
    }
}
