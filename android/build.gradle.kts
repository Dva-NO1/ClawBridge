// Top-level build file — plugin versions declared here, applied in :app
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

val fastBuildRoot = file("F:/AndroidToolchain/build-out")
if (fastBuildRoot.parentFile.exists()) {
    layout.buildDirectory.set(File(fastBuildRoot, "root"))
    subprojects {
        layout.buildDirectory.set(File(fastBuildRoot, project.name))
    }
}
