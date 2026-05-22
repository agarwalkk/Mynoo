import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.gms.google-services")
    id("com.google.dagger.hilt.android")
    kotlin("kapt")
}

// ── Read local.properties (API keys — never committed to git) ────────────────
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}
fun lp(key: String): String = localProps.getProperty(key, "")

android {
    namespace   = "com.krishanagarwal.mynoo"
    compileSdk  = 36

    defaultConfig {
        applicationId = "com.krishanagarwal.mynoo"
        minSdk        = 26
        targetSdk     = 36
        versionCode   = 1
        versionName   = "1.0.0"

        // API keys injected as BuildConfig constants (read from local.properties)
        buildConfigField("String", "GEMINI_API_KEY",         "\"${lp("GEMINI_API_KEY")}\"")
        buildConfigField("String", "ELEVENLABS_API_KEY",     "\"${lp("ELEVENLABS_API_KEY")}\"")
        buildConfigField("String", "XAI_API_KEY",            "\"${lp("XAI_API_KEY")}\"")
        buildConfigField("String", "SARVAM_API_KEY",         "\"${lp("SARVAM_API_KEY")}\"")
        buildConfigField("String", "OPENAI_API_KEY",         "\"${lp("OPENAI_API_KEY")}\"")
        buildConfigField("String", "GOOGLE_CLIENT_EMAIL",    "\"${lp("GOOGLE_CLIENT_EMAIL")}\"")
        buildConfigField("String", "GIST_API_KEY",           "\"${lp("GIST_API_KEY")}\"")
        buildConfigField("String", "GIST_ID",                "\"092b9fe05d4b1bb99d84571cf2f6b3bd\"")
        // Google private key lives in a raw resource file (too long for local.properties)
    }

    signingConfigs {
        create("release") {
            storeFile     = file(lp("KEYSTORE_PATH"))
            storePassword = lp("KEYSTORE_PASSWORD")
            keyAlias      = lp("KEY_ALIAS")
            keyPassword   = lp("KEY_PASSWORD")
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
        release {
            isMinifyEnabled   = true
            isShrinkResources = true
            signingConfig     = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose     = true
        buildConfig = true
    }
}

dependencies {
    // ── Firebase ──────────────────────────────────────────────────────────────
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-auth-ktx")

    // ── Jetpack Compose (BOM manages versions) ────────────────────────────────
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // ── Navigation ────────────────────────────────────────────────────────────
    implementation("androidx.navigation:navigation-compose:2.8.5")

    // ── Hilt DI ───────────────────────────────────────────────────────────────
    implementation("com.google.dagger:hilt-android:2.51.1")
    kapt("com.google.dagger:hilt-android-compiler:2.51.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // ── Networking (Retrofit + OkHttp for Gemini / TTS / STT REST) ───────────
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // ── Persistence ───────────────────────────────────────────────────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Image loading ─────────────────────────────────────────────────────────
    implementation("io.coil-kt:coil-compose:2.7.0")

    // ── Markdown rendering ────────────────────────────────────────────────────
    implementation("com.github.jeziellago:compose-markdown:0.5.7")

    // ── JSON ──────────────────────────────────────────────────────────────────
    implementation("com.google.code.gson:gson:2.11.0")

    // ── Material Components (needed for the XML launcher theme) ────────────────
    implementation("com.google.android.material:material:1.12.0")

    // ── Coroutines ────────────────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // ── Debug tools ───────────────────────────────────────────────────────────
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

kapt { correctErrorTypes = true }
