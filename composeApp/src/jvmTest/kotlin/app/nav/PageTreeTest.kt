package app.nav

import app.data.Page
import kotlin.test.Test
import kotlin.test.assertEquals

class PageTreeTest {
    @Test
    fun childrenSitUnderTheParentInPositionOrder() {
        val notes = page("notes", null, "Notes", 0)
        val ideas = page("ideas", null, "Ideas", 1)
        val meeting = page("meeting", "notes", "Meeting", 1)
        val week = page("week", "notes", "Week plan", 0)

        val tree = pageTree(listOf(meeting, ideas, week, notes))

        assertEquals(listOf("Notes", "Ideas"), tree.map { it.page.title })
        assertEquals(listOf("Week plan", "Meeting"), tree.first().children.map { it.page.title })
        assertEquals(emptyList(), tree[1].children)
    }

    private fun page(id: String, parentId: String?, title: String, position: Long) = Page(
        id = id,
        parentId = parentId,
        title = title,
        position = position,
        updatedAt = 0,
    )
}
