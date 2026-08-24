package ir.factory.entryexit

import android.app.Application
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.PersistentCacheSettings
import ir.factory.entryexit.util.AppPreferences

class FactoryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppPreferences.applyThemeMode(AppPreferences.getThemeMode(this))
        configureOfflineCache()
    }

    /**
     * Turns on Firestore's on-device persistent cache with NO size limit (the default is a
     * ~100MB LRU cache that starts evicting old data once full). This app is used by guards at
     * a factory/mine site where connectivity can drop for a while, so every check-in, item log
     * and weekly inspection made while offline must survive — sitting safely in local storage —
     * until the device is back online, at which point Firestore uploads it to the server on its
     * own. This must run before anything else touches Firestore (see Repository, CloudSync).
     */
    private fun configureOfflineCache() {
        val firestore = FirebaseFirestore.getInstance()
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(
                PersistentCacheSettings.newBuilder()
                    .setSizeBytes(FirebaseFirestoreSettings.CACHE_SIZE_UNLIMITED)
                    .build()
            )
            .build()
    }
}
