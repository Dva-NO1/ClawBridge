// Top-level build file — plugin versions declared here, applied in :app
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

// Optional custom build output directory (useful on Windows when repo path contains non-ASCII characters)
val customBuildRoot = System.getenv("CLAWBRIDGE_ANDROID_BUILD_DIR")?.takeIf { it.isNotBlank() }?.let { file(it) }
if (customBuildRoot != null) {
    layout.buildDirectory.set(File(customBuildRoot, "root"))
    subprojects {
        layout.buildDirectory.set(File(customBuildRoot, project.name))
    }
}
