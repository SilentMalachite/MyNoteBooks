package app.data

internal fun List<ExitStatement>.toCommitJson(): String {
    return joinToString(prefix = "[", postfix = "]") { statement ->
        val params = statement.params.joinToString(prefix = "[", postfix = "]") { param ->
            when (param) {
                is ExitParam.Text -> param.value.toJsonString()
                is ExitParam.Number -> param.value.toString()
            }
        }
        """{"sql":${statement.sql.toJsonString()},"params":$params}"""
    }
}

private fun String.toJsonString(): String = buildString {
    append('"')
    for (ch in this@toJsonString) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> {
                if (ch.code < 0x20) {
                    append("\\u")
                    append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
    append('"')
}
