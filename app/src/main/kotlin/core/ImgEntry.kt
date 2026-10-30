package com.onlasdan.studio.core

/**
 * Represents a single file entry in a .img archive.
 *
 * @property offsetInSectors Sector offset in the .img archive (each sector is 2048 bytes).
 * @property sizeInSectors Size of the entry in 2048-byte sectors.
 * @property name Raw file name (up to 24 bytes in ISO-8859-1).
 */
class ImgEntry(
    var offsetInSectors: Int,
    var sizeInSectors: Int,
    name: String
) {
    var name: String = name
        set(value) {
            field = value
            cleanName = computeCleanName(value)
        }

    /**
     * Pre-calculated clean display name without null characters or padding.
     * Cached at object instantiation to avoid repeated substring allocations during list scrolling.
     */
    var cleanName: String = computeCleanName(name)
        private set

    val sizeInBytes: Long
        get() = sizeInSectors.toLong() * ImgArchive.SECTOR_SIZE

    val offsetInBytes: Long
        get() = offsetInSectors.toLong() * ImgArchive.SECTOR_SIZE

    companion object {
        fun computeCleanName(raw: String): String {
            val nullIdx = raw.indexOf('\u0000')
            return if (nullIdx >= 0) raw.substring(0, nullIdx) else raw
        }
    }

    override fun toString(): String = "FileInfo{offset=$offsetInSectors, size=$sizeInSectors, name='$cleanName'}"

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ImgEntry) return false
        return offsetInSectors == other.offsetInSectors &&
                sizeInSectors == other.sizeInSectors &&
                name == other.name
    }

    override fun hashCode(): Int {
        var result = offsetInSectors
        result = 31 * result + sizeInSectors
        result = 31 * result + name.hashCode()
        return result
    }
}
