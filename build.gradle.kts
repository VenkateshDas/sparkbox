// Single-module build. Sources stay where build.py expects them (src/, res/, AndroidManifest.xml).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

layout.buildDirectory = file("build/gradle") // build/ itself belongs to build.py

val signingDir = file("${System.getProperty("user.home")}/.local/share/save-to-obsidian-signing")

android {
    namespace = "me.venkatesh.saveobsidian"
    compileSdk = 37 // androidx needs 36+; targetSdk stays 35, so runtime behaviour is unchanged
    defaultConfig {
        applicationId = "me.venkatesh.saveobsidian"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.9.0"
    }
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.directories.add("src")
        kotlin.directories.add("src")
        res.directories.add("res")
    }
    signingConfigs {
        create("release") { // the same key as build.py, so either APK installs over the other
            storeFile = signingDir.resolve("release.jks")
            storePassword = signingDir.resolve("password").takeIf { it.exists() }?.readText()?.trim()
            keyAlias = "release"
            keyPassword = storePassword
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs["release"]
        }
        debug { signingConfig = signingConfigs["release"] } // debug builds still upgrade the installed app in place
    }
    lint { baseline = file("lint-baseline.xml") } // findings that predate Gradle; new ones fail check
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.jsoup)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
}

// The JVM checks (tests/CaptureTest.java): plain classes only, no Android, same list as build.py.
val jvmCheck by configurations.creating
dependencies { jvmCheck(libs.jsoup); jvmCheck(libs.json) }
val pure = listOf("Extract", "Transcript", "JsonFix", "Net", "Capture", "AppendCheck", "InboxFile", "VerifiedEdit", "VaultLock", "Cancel", "RunGate", "Limits", "Failures",
    "Enrich", "Index", "Vault", "Layout", "Lane", "Feed", "NoteEdit", "Cites", "Cards", "Sched", "Spark", "CardPrompt", "IngestText",
    "Truncate", "Validate", "Tool", "Agent", "Session", "Compact", "OpenRouter", "VaultTools", "Skills")
val compileCaptureTest by tasks.registering(JavaCompile::class) {
    source = files(pure.map { "src/me/venkatesh/saveobsidian/$it.java" } + "tests/CaptureTest.java" + "tests/AgentTest.java" + "tests/VaultToolsTest.java").asFileTree
    classpath = jvmCheck
    destinationDirectory = layout.buildDirectory.dir("captureTest")
    options.encoding = "UTF-8"
}
val captureTest by tasks.registering(JavaExec::class) {
    group = "verification"
    description = "Runs the JVM checks in tests/CaptureTest.java"
    dependsOn(compileCaptureTest)
    classpath = files(compileCaptureTest.map { it.destinationDirectory }) + jvmCheck
    mainClass = "CaptureTest"
}
tasks.named("check") { dependsOn(captureTest) }
tasks.named("preBuild") { dependsOn(captureTest) } // like build.py: no APK from failing checks
