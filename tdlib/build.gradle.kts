import java.io.File
import java.net.URI
import java.util.zip.ZipFile

// Telegram's TDLib (Java interface + libtdjni.so for arm64-v8a).
// Prebuilt by .github/workflows/tdlib.yml from the commit in tdlib/VERSION and published as the
// "tdlib-<short commit>" release; this module downloads it and unpacks the Java sources into src/main/java and the
// native library into src/main/jniLibs (both gitignored), the default source locations.

plugins {
    alias(libs.plugins.android.library)
}

val tdCommit = file("VERSION").readText().trim()
val mainDir: File = file("src/main")
val zipUrl = "https://github.com/nikhil914/Android-Link-Resolver/releases/download/tdlib-${tdCommit.take(7)}/tdlib.zip"

val fetchTdlib = tasks.register("fetchTdlib") {
    description = "Downloads the prebuilt TDLib for this commit"
    // Plain locals so the action doesn't capture the build script (configuration cache).
    val tdCommit = tdCommit
    val mainDir = mainDir
    val zipUrl = zipUrl
    // The marker file below decides whether to download; always run the (cheap) check.
    outputs.upToDateWhen { false }
    doLast {
        val marker = File(mainDir, ".tdlib-commit")
        if (marker.exists() && marker.readText().trim() == tdCommit) return@doLast
        val libFile = File(mainDir, "jniLibs/arm64-v8a/libtdjni.so")
        mainDir.mkdirs()
        val zip = File(mainDir, "tdlib.zip")
        URI(zipUrl).toURL().openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        File(mainDir, "java").deleteRecursively()
        File(mainDir, "jniLibs").deleteRecursively()
        ZipFile(zip).use { zf ->
            for (e in zf.entries()) {
                // tdlib/java/... -> java/..., tdlib/libs/... -> jniLibs/... (javadoc is skipped)
                val rel = when {
                    e.name.startsWith("tdlib/java/") -> "java/" + e.name.removePrefix("tdlib/java/")
                    e.name.startsWith("tdlib/libs/") -> "jniLibs/" + e.name.removePrefix("tdlib/libs/")
                    else -> null
                } ?: continue
                val out = File(mainDir, rel)
                if (e.isDirectory) out.mkdirs() else {
                    out.parentFile.mkdirs()
                    zf.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
                }
            }
        }
        zip.delete()
        check(libFile.exists()) { "tdlib.zip has no ${libFile.name}" }
        marker.writeText(tdCommit)
    }
}

android {
    namespace = "org.drinkless.tdlib"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        // 160k lines of generated API code; nothing useful to lint here.
        abortOnError = false
        checkReleaseBuilds = false
    }
}

tasks.named("preBuild") { dependsOn(fetchTdlib) }

dependencies {
    implementation(libs.androidx.annotation)
}
