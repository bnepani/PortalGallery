package com.example.portalgallery

import android.app.Application
import android.os.StrictMode

/**
 * Enables StrictMode in debug builds.
 *
 * This exists because of a specific, real failure: the original app spun for a minute
 * on the album screen and then died, and the leading suspects were all main-thread
 * violations — a blocking network call, and `errorBody().string()` invoked from a catch
 * block on the main thread. StrictMode surfaces that whole class of bug the moment it
 * happens, with a stack trace, instead of as an unexplained hang weeks later.
 *
 * penaltyLog rather than penaltyDeath: a wall-mounted photo frame should complain
 * loudly, not crash, and these builds do get left running.
 */
class PortalGalleryApp : Application() {

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .detectCustomSlowCalls()
                    .penaltyLog()
                    .build()
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build()
            )
        }
    }
}
