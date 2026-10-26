package com.onlasdan.studio.loader

/**
 * A loose .dff/.txd file picked from storage as a Modloader payload —
 * the alternative to picking an entry from the open IMG archive, so a
 * downloaded mod file can be substituted without importing it first.
 *
 * [path] is the read reference returned by the file picker; when
 * [isShizuku] (or the path is in restricted storage) the bytes are read
 * through the Shizuku bridge at send time, like other single-file
 * features (SA Audio Fixer, DFF inspector).
 */
data class LoaderLooseFile(
    val name: String,
    val path: String,
    val isShizuku: Boolean,
    val sizeBytes: Long
)

/** Name rules for files the Modloader plugin can consume. */
object LoaderFileRules {

    /** True when [name] ends in .dff or .txd (any case). */
    fun isModelFileName(name: String): Boolean =
        name.endsWith(".dff", ignoreCase = true) ||
            name.endsWith(".txd", ignoreCase = true)
}
