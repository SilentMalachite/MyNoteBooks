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

    @Test
    fun liftedChildrenTakeThePlaceOfThePage() {
        val notes = page("notes", null, "Notes", 0)
        val ideas = page("ideas", null, "Ideas", 4)
        val week = page("week", "notes", "Week plan", 0)
        val meeting = page("meeting", "notes", "Meeting", 2)
        val inbox = page("inbox", null, "Inbox", 7)
        val agenda = page("agenda", "meeting", "Agenda", 0)

        val moved = liftChildren(listOf(inbox, meeting, ideas, agenda, week, notes), "notes")

        assertEquals(listOf("week", "meeting", "ideas", "inbox"), moved.map { it.id })
        assertEquals(listOf(0L, 1L, 2L, 3L), moved.map { it.position })
        assertEquals(listOf<String?>(null, null, null, null), moved.map { it.parentId })
    }

    @Test
    fun liftingAPageWithNoChildrenClosesItsGap() {
        val notes = page("notes", null, "Notes", 0)
        val week = page("week", "notes", "Week plan", 0)
        val meeting = page("meeting", "notes", "Meeting", 1)
        val agenda = page("agenda", "notes", "Agenda", 2)

        val moved = liftChildren(listOf(notes, week, meeting, agenda), "meeting")

        assertEquals(listOf("week", "agenda"), moved.map { it.id })
        assertEquals(listOf(0L, 1L), moved.map { it.position })
        assertEquals(listOf<String?>("notes", "notes"), moved.map { it.parentId })
    }

    @Test
    fun liftingAMissingPageMovesNothing() {
        assertEquals(emptyList(), liftChildren(listOf(page("notes", null, "Notes", 0)), "gone"))
    }

    private fun page(id: String, parentId: String?, title: String, position: Long) = Page(
        id = id,
        parentId = parentId,
        title = title,
        position = position,
        updatedAt = 0,
    )
}
