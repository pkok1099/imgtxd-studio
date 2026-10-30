package com.onlasdan.studio.util

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.security.MessageDigest
import kotlin.system.exitProcess

/**
 * Multi-layer anti-tampering, anti-repackaging, and cryptographic signature verification.
 * Protects the package name and identity of ImgTxd Studio against unauthorized cloning or re-signing.
 */
object AppIntegrity {

    private const val MASK: Byte = 0x5A

    // XOR-masked "com.onlasdan.studio"
    private val ENCODED_PKG = byteArrayOf(
        57, 53, 55, 116, 53, 52, 54, 59, 41, 62, 59, 52, 116, 41, 46, 47, 62, 51, 53
    )

    // SHA-256 hash of "com.onlasdan.studio"
    private const val EXPECTED_HASH = "c4033d2f142a223a8a586713ef00aef8f35318202e399c89deb969799dbe75c5"

    // XOR-masked SHA-256 fingerprint of the official release signing keystore
    // "e7adff483e1e4e20b264aab61d21ceedcab88480efb99b7df6ff5e086f7ec450"
    private val ENCODED_SIG = byteArrayOf(
        63, 109, 59, 62, 60, 60, 110, 98, 105, 63, 107, 63, 110, 63, 104, 106, 56, 104, 108, 110, 59, 59, 56, 108, 107, 62, 104, 107, 57, 63, 63, 62, 57, 59, 56, 98, 98, 110, 98, 106, 63, 60, 56, 99, 99, 56, 109, 62, 60, 108, 60, 60, 111, 63, 106, 98, 108, 60, 109, 63, 57, 110, 111, 106
    )

    @Volatile
    private var isVerified: Boolean? = null

    private fun getCanonicalName(): String {
        val chars = CharArray(ENCODED_PKG.size)
        for (i in ENCODED_PKG.indices) {
            chars[i] = (ENCODED_PKG[i].toInt() xor MASK.toInt()).toChar()
        }
        return String(chars)
    }

    private fun getExpectedSignature(): String {
        val chars = CharArray(ENCODED_SIG.size)
        for (i in ENCODED_SIG.indices) {
            chars[i] = (ENCODED_SIG[i].toInt() xor MASK.toInt()).toChar()
        }
        return String(chars)
    }

    private fun sha256(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun verifySignature(context: Context): Boolean {
        return try {
            val expectedSig = getExpectedSignature()
            val packageManager = context.packageManager
            val packageName = context.packageName

            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else {
                    null
                }
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = packageManager.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            if (signatures.isNullOrEmpty()) return false

            val md = MessageDigest.getInstance("SHA-256")
            signatures.any { sig ->
                val hash = md.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
                hash.equals(expectedSig, ignoreCase = true)
            }
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks multiple system and runtime package accessors as well as cryptographic signature.
     */
    fun checkIntegrity(context: Context): Boolean {
        if (isVerified != null) return isVerified == true

        val canonical = getCanonicalName()
        val currentPkg = context.packageName ?: ""
        val appInfoPkg = context.applicationInfo?.packageName ?: ""
        val appContextPkg = context.applicationContext?.packageName ?: ""

        // Cross-check through internal ActivityThread reflection
        val activityThreadPkg = try {
            val atClass = Class.forName("android.app.ActivityThread")
            val method = atClass.getDeclaredMethod("currentPackageName")
            method.isAccessible = true
            method.invoke(null) as? String ?: currentPkg
        } catch (e: Throwable) {
            currentPkg
        }

        val matchesDirect = (currentPkg == canonical)
        val matchesAppInfo = (appInfoPkg == canonical)
        val matchesContext = (appContextPkg == canonical)
        val matchesThread = (activityThreadPkg == canonical)
        val matchesHash = (sha256(currentPkg) == EXPECTED_HASH)
        val matchesSignature = verifySignature(context)

        val valid = matchesDirect && matchesAppInfo && matchesContext && matchesThread && matchesHash && matchesSignature
        isVerified = valid
        return valid
    }

    /**
     * Validates integrity or terminates process immediately.
     */
    fun verifyOrThrow(context: Context? = null) {
        if (isVerified == null) {
            val ctx = context ?: try {
                val atClass = Class.forName("android.app.ActivityThread")
                val method = atClass.getDeclaredMethod("currentApplication")
                method.isAccessible = true
                method.invoke(null) as? Context
            } catch (e: Throwable) {
                null
            }
            if (ctx != null) {
                checkIntegrity(ctx)
            }
        }
        if (isVerified == false) {
            terminate()
            throw SecurityException("Security violation: Application package integrity or signature check failed.")
        }
    }

    /**
     * Terminates process immediately upon confirmed tamper.
     */
    fun terminate() {
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } catch (ignored: Throwable) {}
        exitProcess(1)
    }
}
