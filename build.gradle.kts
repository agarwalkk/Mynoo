// Top-level build configuration for all sub-projects.
plugins {
    id("com.android.application")       version "8.13.2" apply false
    id("org.jetbrains.kotlin.android")  version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("com.google.gms.google-services") version "4.4.2" apply false
    id("com.google.dagger.hilt.android") version "2.51.1" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.26" apply false
    id("com.google.firebase.appdistribution") version "5.2.1" apply false
}
