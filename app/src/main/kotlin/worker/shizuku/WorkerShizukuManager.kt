package com.onlasdan.studio.worker.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.DeadObjectException
import android.util.Log
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku Manager dedicated for the secondary :worker process.
 *
 * Handles Shizuku binder synchronization from the main process ShizukuProvider,
 * lifecycle listeners, readiness checks, and remote privileged process creation.
 */
object WorkerShizukuManager {
    private const val TAG = "WorkerShizukuManager"

    private val isInitialized = AtomicBoolean(false)
    private val isListenersRegistered = AtomicBoolean(false)

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Log.i(TAG, "Shizuku binder received in worker process")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died in worker process")
    }

    private val newProcessMethod: Method? by lazy {
        try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            method
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to resolve Shizuku.newProcess via reflection", e)
            null
        }
    }

    /**
     * Initializes Shizuku binder synchronization for the :worker process.
     * Communicates with ShizukuProvider in the main process to retrieve the Shizuku binder token.
     */
    fun init(context: Context) {
        registerListeners()
        try {
            ShizukuProvider.requestBinderForNonProviderProcess(context.applicationContext)
            isInitialized.set(true)
            Log.d(TAG, "Shizuku binder requested for worker process (bound=${isShizukuBound()})")
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to request Shizuku binder for worker process: ${e.message}")
        }
    }

    /**
     * Registers Shizuku binder received and dead listeners.
     */
    fun registerListeners() {
        if (isListenersRegistered.compareAndSet(false, true)) {
            try {
                Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
                Shizuku.addBinderDeadListener(binderDeadListener)
                Log.d(TAG, "Shizuku binder listeners registered in worker process")
            } catch (e: Throwable) {
                isListenersRegistered.set(false)
                Log.w(TAG, "Error registering Shizuku listeners: ${e.message}")
            }
        }
    }

    /**
     * Unregisters Shizuku binder listeners.
     */
    fun unregisterListeners() {
        if (isListenersRegistered.compareAndSet(true, false)) {
            try {
                Shizuku.removeBinderReceivedListener(binderReceivedListener)
                Shizuku.removeBinderDeadListener(binderDeadListener)
                Log.d(TAG, "Shizuku binder listeners unregistered")
            } catch (e: Throwable) {
                Log.w(TAG, "Error unregistering Shizuku listeners: ${e.message}")
            }
        }
    }

    /**
     * Ensures Shizuku binder is received in this process.
     * If not yet bound, attempts to request it using the provided Context or reflection.
     */
    fun ensureBinder(context: Context? = null): Boolean {
        if (isShizukuBound()) return true
        registerListeners()
        val targetContext = context?.applicationContext ?: getApplicationContextSafely()
        if (targetContext != null) {
            try {
                ShizukuProvider.requestBinderForNonProviderProcess(targetContext)
            } catch (e: Throwable) {
                Log.w(TAG, "ensureBinder requestBinderForNonProviderProcess failed: ${e.message}")
            }
        }
        return isShizukuBound()
    }

    /**
     * Checks whether Shizuku service is installed and binder is alive.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if Shizuku binder is currently received and responsive.
     */
    fun isShizukuBound(): Boolean {
        return try {
            Shizuku.getBinder() != null && Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Checks if the app has permission granted to use Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Returns true if Shizuku is installed, service is alive, and permission has been granted.
     */
    fun isShizukuReady(): Boolean {
        return try {
            if (!isShizukuAvailable()) return false
            val version = Shizuku.getVersion()
            version > 0 && hasPermission()
        } catch (e: Throwable) {
            Log.e(TAG, "Error checking if Shizuku is ready", e)
            false
        }
    }

    /**
     * Creates a new remote process via Shizuku shell (UID 2000).
     */
    @Throws(Exception::class)
    fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val method = newProcessMethod ?: throw IllegalStateException("Shizuku newProcess method not found")
        return try {
            method.invoke(null, cmd, env, dir) as Process
        } catch (e: InvocationTargetException) {
            throw unwrapException(e)
        }
    }

    /**
     * Executes a shell command synchronously using Shizuku privileged shell (UID 2000).
     */
    fun exec(command: String): Pair<Int, String> {
        return try {
            val process = newProcess(arrayOf("sh", "-c", command))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            val exitCode = process.waitFor()
            Pair(exitCode, output.toString().trim())
        } catch (e: Throwable) {
            val unwrapped = unwrapException(e)
            if (isShizukuDisconnectError(unwrapped)) {
                throw ShizukuDisconnectedException(
                    "Shizuku service disconnected mid-process. File restored to its previous state.",
                    unwrapped
                )
            }
            Pair(-1, unwrapped.message ?: "Error executing Shizuku command")
        }
    }

    /**
     * Checks if a path is restricted (e.g. Android/data or Android/obb on Android 11+ / API 30+).
     */
    fun isRestrictedPath(path: String): Boolean {
        val normalized = path.replace("//", "/")
        return (normalized.contains("/Android/data", ignoreCase = true) ||
                normalized.contains("/Android/obb", ignoreCase = true)) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    /**
     * Detects whether a throwable was caused by Shizuku disconnect, binder death, or permission revocation.
     */
    fun isShizukuDisconnectError(e: Throwable): Boolean {
        var curr: Throwable? = e
        while (curr != null) {
            if (curr is DeadObjectException || curr is SecurityException) {
                return true
            }
            val msg = curr.message?.lowercase() ?: ""
            if (msg.contains("deadobject") || (msg.contains("binder") && msg.contains("died"))) {
                return true
            }
            curr = curr.cause
        }
        return !isShizukuAvailable()
    }

    /**
     * Unwraps reflection InvocationTargetException to find the root cause.
     */
    fun unwrapException(e: Throwable): Throwable {
        var curr = e
        while (curr is InvocationTargetException && curr.targetException != null) {
            curr = curr.targetException
        }
        return curr
    }

    /**
     * Obtains the application Context in the current process using reflection if not passed explicitly.
     */
    private fun getApplicationContextSafely(): Context? {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentAppMethod = activityThreadClass.getMethod("currentApplication")
            currentAppMethod.invoke(null) as? Context
        } catch (_: Throwable) {
            null
        }
    }
}
