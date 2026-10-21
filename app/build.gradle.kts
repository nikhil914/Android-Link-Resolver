import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Build number lives in version.properties and is bumped with every commit.
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
val ciVersionCode = versionProps.getProperty("versionCode").trim().toInt()
val ciVersionName = versionProps.getProperty("versionName").trim()

// Release signing comes from env vars (decoded from GitHub secrets in CI).
// If any is missing, the release build falls back to the debug key so the build never fails for that reason.
val keystoreFile = System.getenv("KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
val keyAliasEnv = System.getenv("KEY_ALIAS")
val keyPasswordEnv = System.getenv("KEY_PASSWORD")
val hasReleaseKey = keystoreFile != null && !keystorePassword.isNullOrEmpty() &&
    !keyAliasEnv.isNullOrEmpty() && !keyPasswordEnv.isNullOrEmpty()

android {
    namespace = "com.nik.linkresolver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.nik.linkresolver"
        minSdk = 26
        targetSdk = 37
        versionCode = ciVersionCode
        versionName = ciVersionName
        // TDLib is only built for arm64 (every current phone).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // Debug key committed to the repo so every CI debug build has the same signature and installs as an update.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (hasReleaseKey) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }

    buildTypes {
        release {
            // Kept off in v1: no shrinking surprises with Jsoup/OkHttp, and the APK is small anyway.
            isMinifyEnabled = false
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Dependency-version nags shouldn't break CI; real errors still do.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
        abortOnError = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":tdlib"))
    implementation(libs.work.runtime)
    implementation(libs.biometric)
    implementation(libs.fragment.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
