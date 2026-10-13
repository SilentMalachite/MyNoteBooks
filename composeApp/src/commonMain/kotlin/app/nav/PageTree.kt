package app.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.data.Page
import app.theme.Ink
import app.theme.Select

data class PageNode(val page: Page, val children: List<PageNode>)

fun pageTree(pages: List<Page>): List<PageNode> {
    val byParent = pages.groupBy { it.parentId }
    val visiting = mutableSetOf<String>()
    fun nodes(parentId: String?): List<PageNode> {
        return byParent[parentId].orEmpty()
            .sortedBy { it.position }
            .mapNotNull { page ->
                if (!visiting.add(page.id)) return@mapNotNull null
                val node = PageNode(page, nodes(page.id))
                visiting.remove(page.id)
                node
            }
    }
    return nodes(null)
}

@Composable
fun PageTree(
    pages: List<Page>,
    openId: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        PageNodes(pageTree(pages), depth = 0, openId = openId, onOpen = onOpen)
    }
}

@Composable
private fun PageNodes(
    nodes: List<PageNode>,
    depth: Int,
    openId: String?,
    onOpen: (String) -> Unit,
) {
    nodes.forEach { node ->
        val selected = node.page.id == openId
        Text(
            node.page.title,
            color = Ink,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) Select else androidx.compose.ui.graphics.Color.Transparent)
                .clickable { onOpen(node.page.id) }
                .padding(start = (12 + depth * 14).dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        )
        PageNodes(node.children, depth + 1, openId, onOpen)
    }
}
