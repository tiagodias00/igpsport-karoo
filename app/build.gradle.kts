plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release builds are tagged vX.Y.Z in CI (.github/workflows/build.yml); everything else is a dev build whose huge
// versionCode stops the Karoo updater from replacing it with a published release.
val releaseTag: String? = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }

fun semverToVersionCode(v: String): Int {
    val (major, minor, patch) = (v.substringBefore("-").split(".") + listOf("0", "0", "0"))
        .take(3).map { it.toIntOrNull() ?: 0 }
    return major * 10_000 + minor * 100 + patch
}

val githubRepo: String = providers.gradleProperty("githubRepo").getOrElse("OWNER/igpsport-karoo")

// Release builds are signed only when a keystore is supplied via env vars (CI).
val releaseKeystore: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.tiagodias.igpsportkaroo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tiagodias.igpsportkaroo"
        minSdk = 26
        targetSdk = 34
        versionCode = releaseTag?.let(::semverToVersionCode) ?: 2_000_000_000
        versionName = releaseTag ?: "0.1.0-dev"
        manifestPlaceholders["manifestUrl"] =
            "https://github.com/$githubRepo/releases/latest/download/manifest.json"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
    sourceSets["test"].java.srcDirs("src/test/kotlin")

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":protocol"))
    implementation("com.github.hammerheadnav:karoo-ext:1.1.9")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation(platform("androidx.compose:compose-bom:2024.09.02"))
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-unit")
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("com.jakewharton.timber:timber:5.0.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.0")
}
