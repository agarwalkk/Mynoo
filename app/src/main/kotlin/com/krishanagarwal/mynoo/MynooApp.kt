package com.krishanagarwal.mynoo

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MynooApp : Application() {

    override fun onCreate() {
        super.onCreate()
        configureFirestore()
    }

    /**
     * Enable 100 MB persistent disk cache — mirrors the original app's behaviour.
     * Data survives restarts and the device can work fully offline; writes queue
     * and sync automatically when connectivity is restored.
     */
    private fun configureFirestore() {
        val cacheSettings = PersistentCacheSettings.newBuilder()
            .setSizeBytes(100L * 1024 * 1024) // 100 MB
            .build()

        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(cacheSettings)
            .build()

        FirebaseFirestore.getInstance().firestoreSettings = settings
    }
}
