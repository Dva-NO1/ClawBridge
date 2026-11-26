import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.clawbridge.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.clawbridge.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "2.0.1"
    }

    signingConfigs {
        create("release") {
            val ksProps = Properties()
            val ksPropsFile = File(rootProject.projectDir, "keystore.properties")
            if (ksPropsFile.exists()) {
                ksPropsFile.inputStream().use { ksProps.load(it) }
            }
            fun readOpt(key: String): String? =
                (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() }
                    ?: ksProps.getProperty(key)?.takeIf { it.isNotBlank() }
                    ?: System.getenv(key)?.takeIf { it.isNotBlank() }

            val storeFilePath = readOpt("AGM_RELEASE_STORE_FILE")
            val storePwd = readOpt("AGM_RELEASE_STORE_PASSWORD")
            val alias = readOpt("AGM_RELEASE_KEY_ALIAS")
            val keyPwd = readOpt("AGM_RELEASE_KEY_PASSWORD")
            val resolved = storeFilePath?.let {
                val f = File(it)
                if (f.isAbsolute) f else File(rootProject.projectDir, it)
            }

            if (resolved != null && resolved.exists() && storePwd != null && alias != null && keyPwd != null) {
                storeFile = resolved
                storePassword = storePwd
                keyAlias = alias
                keyPassword = keyPwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val rc = signingConfigs.getByName("release")
            if (rc.storeFile != null) signingConfig = rc
        }
        debug {
            // Uses standard Android debug keystore by default; optional release config if present
            val rc = signingConfigs.getByName("release")
            if (rc.storeFile != null) signingConfig = rc
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        disable += "Instantiatable"
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
