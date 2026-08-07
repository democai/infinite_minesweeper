import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Sideload-friendly versioning: the system package installer rejects updates unless
// versionCode strictly increases. Use wall-clock seconds since a fixed epoch so every
// `just apk` can install over the previous build without a git commit. versionName
// still reflects git so the human-readable string tracks the tree.
val versionEpochSeconds = 1_720_000_000L // ~2024-07-05 UTC

fun gitStdout(vararg args: String): String {
    val process =
        ProcessBuilder("git", *args)
            .directory(rootProject.projectDir)
            .redirectErrorStream(true)
            .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    check(process.waitFor() == 0 && output.isNotEmpty()) {
        "git ${args.joinToString(" ")} failed: $output"
    }
    return output
}

val apkVersionCode =
    ((System.currentTimeMillis() / 1000L) - versionEpochSeconds).toInt().also {
        check(it > 0) { "Clock before version epoch; cannot compute versionCode ($it)" }
    }
val apkVersionName = "0.1.${gitStdout("rev-list", "--count", "HEAD")}+${gitStdout("rev-parse", "--short", "HEAD")}"
val releaseSigning = Properties().apply {
    file("/cursor-agent/home/.android-signing/democ-release.properties").inputStream().use(::load)
}

android {
    namespace = "com.infinite.minesweeper"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.infinite.minesweeper"
        minSdk = 35
        targetSdk = 35
        versionCode = apkVersionCode
        versionName = apkVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            storeFile = file(releaseSigning.getProperty("storeFile"))
            storePassword = releaseSigning.getProperty("storePassword")
            keyAlias = releaseSigning.getProperty("keyAlias")
            keyPassword = releaseSigning.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

tasks.register("printApkVersion") {
    group = "help"
    description = "Print the versionCode / versionName used for this build"
    doLast {
        println("✅ versionCode=$apkVersionCode versionName=$apkVersionName")
    }
}
