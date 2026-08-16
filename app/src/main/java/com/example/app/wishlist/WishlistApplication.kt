package com.example.app.wishlist

import android.app.Application
import android.content.pm.ApplicationInfo
import com.example.app.wishlist.data.db.ObjectBoxProvider
import timber.log.Timber

/**
 * Application entry point.
 *
 * Exists primarily because `ObjectBoxProvider.initialize()` previously had no callers
 * anywhere — the whole data layer was built but never opened. Every screen and the
 * notification listener depend on the store being up before they touch a Box.
 */
class WishlistApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Read off ApplicationInfo rather than BuildConfig: AGP 8+ does not generate
        // BuildConfig unless `buildFeatures { buildConfig = true }` is set, and enabling
        // it just for a log check is not worth the build surface.
        val debuggable = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (debuggable) {
            Timber.plant(Timber.DebugTree())
        }

        // Opening the store is fast (it maps a file; it does not read the data), so doing
        // it here rather than lazily keeps every later Box access free of an
        // initialisation check. The 110 MB NER model is a different matter and is loaded
        // off the main thread by whoever needs it first.
        runCatching { ObjectBoxProvider.initialize(this) }
            .onFailure { Timber.e(it, "ObjectBox failed to initialize") }
    }
}
