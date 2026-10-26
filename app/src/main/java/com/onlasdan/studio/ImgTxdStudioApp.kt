package com.onlasdan.studio

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import com.onlasdan.studio.worker.repo.WorkerProcessRepositoryImpl

class ImgTxdStudioApp : Application() {

    companion object {
        private const val TAG = "ImgTxdStudioApp"
    }

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Inisialisasi ImgTxdStudioApp")

        // Lewati inisialisasi lifecycle UI jika berjalan di proses background :worker
        val processName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            ""
        }
        if (processName.endsWith(":worker")) {
            Log.d(TAG, "Running in :worker process, skipping UI lifecycle tracking")
            return
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
                if (startedActivityCount == 1) {
                    Log.d(TAG, "App entered FOREGROUND (all CPU cores active)")
                    WorkerProcessRepositoryImpl.getInstance(applicationContext).setAppForegroundState(true)
                }
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    Log.d(TAG, "App entered BACKGROUND (limiting CPU cores per settings)")
                    WorkerProcessRepositoryImpl.getInstance(applicationContext).setAppForegroundState(false)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}
