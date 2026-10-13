package app

import kotlin.test.Test
import kotlin.test.assertEquals

class ExitFlushTest {
    @Test
    fun hidingPostsTheDraftBeforeTheFlushWhenNothingIsSaving() {
        val order = mutableListOf<String>()

        saveOnHide(
            saving = false,
            postBatch = { order += "batch" },
            scheduleFlush = { order += "flush" },
        )

        assertEquals(listOf("batch", "flush"), order)
    }

    @Test
    fun hidingDoesNotInsertABatchIntoAnOpenSave() {
        val order = mutableListOf<String>()

        saveOnHide(
            saving = true,
            postBatch = { order += "batch" },
            scheduleFlush = { order += "flush" },
        )

        assertEquals(listOf("flush"), order)
    }

    @Test
    fun aVisiblePageDoesNotFlush() {
        assertEquals(true, shouldFlushForVisibility("hidden"))
        assertEquals(false, shouldFlushForVisibility("visible"))
        assertEquals(false, shouldFlushForVisibility("prerender"))
    }
}