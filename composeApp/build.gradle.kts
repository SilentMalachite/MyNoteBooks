import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    jvm()

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "composeApp.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.compose.material3)
            implementation(libs.coroutines.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.sqldelight.async)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
            implementation(libs.sqldelight.jvm)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
        }
        wasmJsMain.dependencies {
            implementation(libs.sqldelight.web)
            implementation(npm("@sqlite.org/sqlite-wasm", "3.53.4-build2"))
            implementation(devNpm("copy-webpack-plugin", "11.0.0"))
        }
    }
}

compose.desktop {
    application {
        mainClass = "app.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Notebook"
            packageVersion = "1.0.0"
        }
    }
}

sqldelight {
    databases {
        create("NotesDb") {
            packageName.set("app.db")
            generateAsync.set(true)
        }
    }
}
