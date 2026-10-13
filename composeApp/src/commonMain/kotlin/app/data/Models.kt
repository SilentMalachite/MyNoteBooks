package app.data

data class Page(
    val id: String,
    val parentId: String?,
    val title: String,
    val position: Long,
    val updatedAt: Long,
)

data class Block(
    val id: String,
    val pageId: String,
    val kind: String,
    val body: String,
    val checked: Long,
    val position: Long,
)

data class TitleHit(
    val id: String,
    val parentId: String?,
    val title: String,
)

data class ExitStatement(
    val sql: String,
    val params: List<ExitParam>,
)

sealed interface ExitParam {
    data class Text(val value: String) : ExitParam
    data class Number(val value: Long) : ExitParam
}
