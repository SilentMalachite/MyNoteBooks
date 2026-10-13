package app.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.data.TitleHit
import app.db.NotesDb
import app.theme.Ink
import app.theme.Mute

suspend fun titleSearch(db: NotesDb, query: String): List<TitleHit> {
    if (query.isBlank()) return emptyList()
    return db.notesQueries.searchByTitle(likePattern(query)).awaitAsList().map { row ->
        TitleHit(row.id, row.parent_id, row.title)
    }
}

internal fun likePattern(raw: String): String {
    return raw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}

@Composable
fun TitleSearchField(
    query: String,
    onQuery: (String) -> Unit,
) {
    BasicTextField(
        value = query,
        onValueChange = onQuery,
        singleLine = true,
        textStyle = TextStyle(color = Ink, fontSize = 13.sp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        decorationBox = { inner ->
            Box {
                if (query.isEmpty()) {
                    Text("Search", color = Mute, fontSize = 13.sp)
                }
                inner()
            }
        },
    )
}

@Composable
fun TitleHits(
    hits: List<TitleHit>,
    parentTitle: (String?) -> String,
    onOpen: (String) -> Unit,
) {
    hits.forEach { hit ->
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onOpen(hit.id) }
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(hit.title, color = Ink, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            val parent = parentTitle(hit.parentId)
            if (parent.isNotEmpty()) {
                Text(parent, color = Mute, fontSize = 12.sp)
            }
        }
    }
}
