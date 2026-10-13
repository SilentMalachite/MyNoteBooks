package app.data

import app.cash.sqldelight.db.SqlDriver

expect suspend fun createDriver(): SqlDriver

// False when the web app runs on an in-memory database that is lost with the tab.
expect suspend fun keepsData(driver: SqlDriver): Boolean
