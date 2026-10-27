package com.onlasdan.studio.util

import com.onlasdan.studio.Constants

import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.Method

data class ShizukuFileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: Long = 0
)

object ShizukuManager {

    const val SHIZUKU_REQUEST_CODE = Constants.RequestCode.SHIZUKU_PERMISSION

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
            e.printStackTrace()
            null
        }
    }

    /**
     * Creates a new remote process via Shizuku.
     */
    fun newProcess(cmd: Array<String>, env: Array<String>? = null, dir: String? = null): Process {
        val method = newProcessMethod ?: throw IllegalStateException("Shizuku newProcess method not found")
        return method.invoke(null, cmd, env, dir) as Process
    }

    /**
     * Checks whether Shizuku service is installed and binder is alive.
     */
    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Checks if our app has permission to use Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Requests Shizuku permission.
     */
    fun requestPermission(requestCode: Int = SHIZUKU_REQUEST_CODE) {
        if (isShizukuAvailable()) {
            try {
                Shizuku.requestPermission(requestCode)
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Checks if a path is in Android/data or Android/obb where normal File API is restricted.
     */
    fun isRestrictedPath(path: String): Boolean {
        val normalized = path.replace("//", "/")
        return (normalized.contains("/Android/data", ignoreCase = true) ||
                normalized.contains("/Android/obb", ignoreCase = true)) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    /**
     * Quotes a string for safe interpolation into a POSIX shell command.
     *
     * Wraps the value in single quotes and escapes any embedded single quote via
     * the standard `''\'''` idiom (close-quote, escaped-quote, reopen-quote).
     * This is the ONLY safe way to pass an attacker-controlled path into `sh -c`;
     * double-quote wrapping (`"$path"`) is NOT safe — backticks, `$(...)`, `\`, and
     * `"` can all break out and run arbitrary commands under Shizuku's UID 2000.
     *
     * Example: `shellQuote("a'b")` returns `'a'\''b'` which the shell expands to `a'b`.
     */
    fun shellQuote(s: String): String {
        val escaped = s.replace("'", "'\\''")
        return "'$escaped'"
    }

    /**
     * Executes a shell command using Shizuku privileged shell (UID 2000).
     */
    suspend fun exec(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        AppIntegrity.verifyOrThrow()
        if (!hasPermission()) {
            return@withContext Pair(-1, "Shizuku permission not granted")
        }
        try {
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
            Pair(-1, e.message ?: "Error executing Shizuku command")
        }
    }

    /**
     * Lists files in a restricted directory using a single batch Shizuku command (O(1) process calls).
     */
    suspend fun listFiles(dirPath: String): List<ShizukuFileItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<ShizukuFileItem>()
        if (!hasPermission()) return@withContext result

        val cleanPath = dirPath.trimEnd('/')
        // Use batch stat for instant retrieval of all file metadata in 1 single shell process.
        // shellQuote prevents command injection if dirPath comes from a malicious VIEW intent.
        val (statExit, statOutput) = exec("stat -c '%s|%F|%n' ${shellQuote(cleanPath)}/* 2>/dev/null")
        if (statExit == 0 && statOutput.isNotBlank()) {
            for (line in statOutput.lines()) {
                val parts = line.split('|')
                if (parts.size >= 3) {
                    val size = parts[0].trim().toLongOrNull() ?: 0L
                    val isDir = parts[1].contains("directory", ignoreCase = true)
                    val fullPath = parts[2].trim()
                    val name = File(fullPath).name
                    if (name.isNotEmpty() && name != "." && name != "..") {
                        result.add(
                            ShizukuFileItem(
                                name = name,
                                path = fullPath,
                                isDirectory = isDir,
                                size = if (isDir) 0L else size
                            )
                        )
                    }
                }
            }
        } else {
            // Fallback to ls if stat wildcard matched nothing or failed
            val (exitCode, output) = exec("ls -1pa ${shellQuote(cleanPath)}")
            if (exitCode == 0 && output.isNotBlank()) {
                for (line in output.lines()) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") continue
                    val isDir = trimmed.endsWith('/')
                    val name = if (isDir) trimmed.dropLast(1) else trimmed
                    val itemPath = "$cleanPath/$name"
                    result.add(
                        ShizukuFileItem(
                            name = name,
                            path = itemPath,
                            isDirectory = isDir,
                            size = 0L
                        )
                    )
                }
            }
        }
        result.sortedWith(compareBy<ShizukuFileItem> { !it.isDirectory }.thenBy { it.name.lowercase() })
    }

    /**
     * Copies a file from a Shizuku-restricted path to a local destination file using high-throughput 256KB buffer.
     *
     * The transfer is staged into a sibling temp file and renamed into place only
     * after `cat` exits cleanly, so a failed/interrupted copy can never leave a
     * truncated file at the destination that callers then consume as valid.
     */
    suspend fun copyToLocal(sourceRestrictedPath: String, destLocalFile: File): Boolean = withContext(Dispatchers.IO) {
        AppIntegrity.verifyOrThrow()
        if (!hasPermission()) return@withContext false
        val staging = File(destLocalFile.parentFile, destLocalFile.name + ".staging" + System.nanoTime())
        try {
            destLocalFile.parentFile?.mkdirs()
            // shellQuote prevents command injection via a crafted sourceRestrictedPath
            // (e.g. one coming from an exported VIEW intent).
            val process = newProcess(arrayOf("sh", "-c", "cat ${shellQuote(sourceRestrictedPath)}"))
            staging.outputStream().use { out ->
                process.inputStream.copyTo(out, bufferSize = 256 * 1024)
            }
            val exitCode = process.waitFor()
            val ok = exitCode == 0 && staging.exists() && staging.length() > 0
            if (!ok) {
                staging.delete()
                return@withContext false
            }
            if (destLocalFile.exists() && !destLocalFile.delete()) {
                staging.delete()
                return@withContext false
            }
            val moved = staging.renameTo(destLocalFile)
            if (!moved) staging.delete()
            moved
        } catch (e: Throwable) {
            e.printStackTrace()
            staging.delete()
            false
        }
    }

    /**
     * Copies a local file into a Shizuku-restricted path using high-throughput 256KB buffer.
     *
     * The remote side stages into `dest.staging<N>` and renames over the target
     * inside the same shell command: the original game file is only ever replaced
     * atomically by `mv`, never truncated in place by a `cat` that may die halfway
     * (Shizuku disconnect, process kill, full disk). A non-zero exit also cleans
     * the staging file and leaves the original untouched.
     */
    suspend fun copyFromLocal(sourceLocalFile: File, destRestrictedPath: String): Boolean = withContext(Dispatchers.IO) {
        AppIntegrity.verifyOrThrow()
        if (!hasPermission()) return@withContext false
        try {
            val parentDir = File(destRestrictedPath).parent ?: ""
            if (parentDir.isNotEmpty()) {
                exec("mkdir -p ${shellQuote(parentDir)}")
            }
            val stagingPath = destRestrictedPath + ".staging" + System.nanoTime()
            // shellQuote on every interpolated path — stagingPath and destRestrictedPath
            // can both contain shell metacharacters if the user picks a file whose name
            // includes $, `, ", etc.
            val command = "cat > ${shellQuote(stagingPath)} && mv -f ${shellQuote(stagingPath)} ${shellQuote(destRestrictedPath)} || { rm -f ${shellQuote(stagingPath)}; exit 1; }"
            val process = newProcess(arrayOf("sh", "-c", command))
            sourceLocalFile.inputStream().use { input ->
                process.outputStream.use { out ->
                    input.copyTo(out, bufferSize = 256 * 1024)
                    out.flush()
                }
            }
            val exitCode = process.waitFor()
            exitCode == 0
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Checks if file or directory exists via Shizuku.
     */
    suspend fun exists(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        val (exitCode, _) = exec("test -e ${shellQuote(path)}")
        exitCode == 0
    }

    /**
     * Creates directory via Shizuku.
     */
    suspend fun mkdirs(path: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext false
        val (exitCode, _) = exec("mkdir -p ${shellQuote(path)}")
        exitCode == 0
    }
}
