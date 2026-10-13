package app.data

import app.cash.sqldelight.db.SqlDriver

expect suspend fun createDriver(): SqlDriver
