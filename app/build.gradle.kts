import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

/*
 * A default Gmail OAuth client id, and entirely optional.
 *
 * The id can also be pasted or scanned into the app at runtime (Settings -> Client ID),
 * which is what makes a plain release APK usable by anyone — there is no secret to bake
 * in, because an installed-app client doesn't have one and the redirect scheme is fixed
 * by the package name rather than the id.
 *
 * Set it here only to skip that step on your own device: local.properties on a
 * workstation, or a GMAIL_CLIENT_ID secret in CI. A build with neither still works.
 */
val clientId: String = run {
    val fromEnv = System.getenv("GMAIL_CLIENT_ID")?.trim().orEmpty()
    if (fromEnv.isNotEmpty()) return@run fromEnv
    val f = rootProject.file("local.properties")
    if (!f.exists()) return@run ""
    Properties().apply { f.inputStream().use { load(it) } }
        .getProperty("gmailClientId")?.trim().orEmpty()
}

/*
 * The OAuth redirect. Google validates an Android client by package name and signing
 * certificate rather than by redirect URI, and accepts a custom scheme matching the
 * package name — so this is a constant, and the manifest can declare it regardless of
 * which client id is in use.
 *
 * If a consent screen ever rejects it, the other accepted form is the reversed client id
 * (what Google documents for iOS clients):
 *
 *   "com.googleusercontent.apps." + clientId.removeSuffix(".apps.googleusercontent.com")
 *
 * Swapping to that couples the scheme to the id, and the id then has to be known at
 * build time for real rather than by convention.
 */
val redirectScheme = "com.gios.lightnews"

/**
 * The key shake-to-report posts issues with. Never in the repository: `local.properties` is
 * ignored by git, and CI hands it in from a repository secret. An empty string is a working
 * build — reports queue on the phone and go out from a later one that has the key.
 */
val reportToken: String = run {
    val local = rootProject.file("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("reportToken")
    } else {
        null
    }
    fromFile ?: System.getenv("REPORT_TOKEN") ?: ""
}

android {
    namespace = "com.gios.lightnews"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.gios.lightnews"
        minSdk = 29
        targetSdk = 35
        // CI overwrites both from the workflow run number; see .github/workflows/build.yml
        versionCode = 1
        versionName = "1.1.0"

        // The LPIII is arm64 only; shipping four ABIs tripled the APK for nothing.
        ndk { abiFilters += "arm64-v8a" }

        buildConfigField("String", "REPORT_TOKEN", "\"$reportToken\"")
        buildConfigField("String", "REPORT_REPO", "\"gi-os/light-reports\"")

        buildConfigField("String", "GMAIL_CLIENT_ID", "\"$clientId\"")
        buildConfigField("String", "OAUTH_REDIRECT", "\"$redirectScheme:/oauth2redirect\"")
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../keystore/lightnews.jks")
            storePassword = "lightnews"
            keyAlias = "lightnews"
            keyPassword = "lightnews"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Same committed key as debug, so either APK upgrades over the other, and
            // the SHA-1 registered on the OAuth client keeps matching both.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // Room — newsletter metadata. Bodies go to files, not rows.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background sync. WorkManager sits on JobScheduler, so it needs no Play Services.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Gmail REST over plain HTTP — the official client library drags in half of GAX.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR scanning, so the OAuth client id doesn't have to be typed on a 3.9" keyboard.
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // HTML: rewrite for the panel, and extract plain text when there is no WebView.
    implementation("org.jsoup:jsoup:1.18.3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // The shake gesture is plain arithmetic with no Android imports, so it runs here.
    testImplementation("junit:junit:4.13.2")
}
