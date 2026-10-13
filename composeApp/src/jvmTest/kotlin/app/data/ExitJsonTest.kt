package app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ExitJsonTest {
    @Test
    fun commitJsonEscapesControlCharacters() {
        val statements = listOf(
            ExitStatement(
                "UPDATE page SET title = ? WHERE id = ?",
                listOf(ExitParam.Text("a\tb\u0000c\"d\\e\nf"), ExitParam.Number(9)),
            ),
        )

        assertEquals(
            """[{"sql":"UPDATE page SET title = ? WHERE id = ?","params":["a\tb\u0000c\"d\\e\nf", 9]}]""",
            statements.toCommitJson(),
        )
    }
}
