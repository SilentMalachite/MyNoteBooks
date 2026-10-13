package app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Notebook",
        state = rememberWindowState(width = 1100.dp, height = 720.dp),
    ) {
        App()
    }
}
