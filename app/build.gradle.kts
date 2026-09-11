plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val releaseStoreFile = providers.environmentVariable("DSH_ANDROID_KEYSTORE_FILE")
val releaseStorePassword = providers.environmentVariable("DSH_ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = providers.environmentVariable("DSH_ANDROID_KEY_ALIAS")
val releaseKeyPassword = providers.environmentVariable("DSH_ANDROID_KEY_PASSWORD")
val releaseSigningConfigured = listOf(
    releaseStoreFile,
    releaseStorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { it.isPresent }

/**
 * The version this checkout is, derived from its own tags.
 *
 * Shelling out to `tools/version.sh` rather than reimplementing it: the
 * Gradle-free build already calls that script, and two derivations would drift.
 * CI checks out tags by default, so `git describe` sees them.
 */
fun tagVersion(): Pair<String, Int> {
    val output = providers.exec {
        commandLine("bash", rootProject.file("tools/version.sh").absolutePath)
    }.standardOutput.asText.get()
    var name = "0.0.0-dev"
    var code = 1
    output.lineSequence().forEach { line ->
        when {
            line.startsWith("VERSION_NAME=") -> name = line.substringAfter('=').trim().trim('"')
            line.startsWith("VERSION_CODE=") -> code = line.substringAfter('=').trim().toIntOrNull() ?: 1
        }
    }
    return name to code
}

private val tagVersionPair = tagVersion()
private val versionNameFromTag = tagVersionPair.first
private val versionCodeFromTag = tagVersionPair.second

android {
    namespace = "io.github.longislandicetea.dshnative"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.longislandicetea.dshnative"
        minSdk = 29
        targetSdk = 36
        // From the tag, via the same script the Gradle-free build calls: a
        // hardcoded pair here meant every published APK claimed to be 0.1.0
        // (versionCode 1), so three releases were indistinguishable on a device.
        versionCode = versionCodeFromTag
        versionName = versionNameFromTag
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFile.get())
                storePassword = releaseStorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            if (releaseSigningConfigured) signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
    }

    // The same version the manifest carries, as a class: generated so the APK's
    // metadata and what the settings dialog shows cannot disagree.
    val buildInfoDir = layout.buildDirectory.dir("generated/buildinfo")
    val generateBuildInfo by tasks.registering {
        val dir = buildInfoDir
        val name = versionNameFromTag
        val code = versionCodeFromTag
        outputs.dir(dir)
        doLast {
            val file = dir.get().file("io/github/longislandicetea/dshnative/BuildInfo.kt").asFile
            file.parentFile.mkdirs()
            file.writeText(
                """
                package io.github.longislandicetea.dshnative

                /** The version this APK was built as. Generated; see tools/version.sh. */
                internal object BuildInfo {
                    const val VERSION_NAME = "$name"
                    const val VERSION_CODE = $code
                }
                """.trimIndent() + "\n",
            )
        }
    }
    sourceSets.getByName("main").kotlin.srcDir(buildInfoDir)
    tasks.named("preBuild") { dependsOn(generateBuildInfo) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // OkHttp rather than a hand-rolled HttpURLConnection client: it is the one
    // dependency worth its ~1 MB here, because the multiplexed stream socket
    // and HTTP calls share a connection pool and both need sane timeouts.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    // No syntax-highlighting library: every candidate was either absent from
    // Maven Central or drags in a WebView/grammar assets. CodeBlocks.kt is a
    // small tokenizer instead.
}
