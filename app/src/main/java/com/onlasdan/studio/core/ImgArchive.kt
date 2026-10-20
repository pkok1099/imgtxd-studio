package com.onlasdan.studio.core

import com.onlasdan.studio.util.AppIntegrity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.Arrays

enum class ImgVersion {
    VER1,
    VER2
}

/**
 * High-performance GTA .img archive parser and builder for VER1 and VER2 formats.
 * Utilizes FileChannel and ByteBuffer with Little-Endian byte ordering.
 */
class ImgArchive private constructor(
    val imgFile: File,
    val dirFile: File?,
    val version: ImgVersion,
    private val entriesList: ArrayList<ImgEntry>
) {
    companion object {
        const val SECTOR_SIZE = 2048
        const val ENTRY_SIZE = 32
        const val NAME_SIZE = 24
        val VER2_MAGIC = byteArrayOf('V'.code.toByte(), 'E'.code.toByte(), 'R'.code.toByte(), '2'.code.toByte())
        val CHARSET = StandardCharsets.ISO_8859_1

        /**
         * Checks if the specified file starts with the "VER2" magic string.
         */
        fun isVer2(file: File): Boolean {
            if (!file.exists() || file.length() < 4) return false
            return try {
                FileInputStream(file).use { fis ->
                    val magic = ByteArray(4)
                    val read = fis.read(magic)
                    read == 4 && magic.contentEquals(VER2_MAGIC)
                }
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Parses a GTA .img archive. Auto-detects VER1 vs VER2.
         * For VER1 archives, finds the corresponding .dir file in the same directory if not provided.
         */
        @Throws(IOException::class)
        fun open(imgFile: File, dirFile: File? = null): ImgArchive {
            AppIntegrity.verifyOrThrow()
            if (!imgFile.exists()) {
                throw IOException("IMG archive file not found: ${imgFile.absolutePath}")
            }

            val isV2 = isVer2(imgFile)
            val resolvedDirFile = if (!isV2) {
                dirFile ?: run {
                    val candidate = File(imgFile.parentFile, imgFile.nameWithoutExtension + ".dir")
                    if (candidate.exists()) candidate else {
                        val candidateUpper = File(imgFile.parentFile, imgFile.nameWithoutExtension + ".DIR")
                        if (candidateUpper.exists()) candidateUpper else null
                    }
                } ?: throw IOException("VER1 archive requires corresponding .dir file: ${imgFile.nameWithoutExtension}.dir")
            } else {
                null
            }

            val entries = ArrayList<ImgEntry>()

            if (isV2) {
                RandomAccessFile(imgFile, "r").use { raf ->
                    val channel = raf.channel
                    val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    channel.read(headerBuf)
                    headerBuf.flip()

                    val magic = ByteArray(4)
                    headerBuf.get(magic)
                    if (!magic.contentEquals(VER2_MAGIC)) {
                        throw IOException("Invalid VER2 header magic in ${imgFile.name}")
                    }
                    val entryCount = headerBuf.int
                    if (entryCount < 0 || entryCount > 200_000) {
                        throw IOException("Invalid entry count in VER2 archive: $entryCount")
                    }

                    if (entryCount > 0) {
                        val entriesBuf = ByteBuffer.allocate(entryCount * ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                        channel.read(entriesBuf)
                        entriesBuf.flip()

                        val nameBuf = ByteArray(NAME_SIZE)
                        for (i in 0 until entryCount) {
                            val offset = entriesBuf.int
                            val size = entriesBuf.int
                            entriesBuf.get(nameBuf)
                            val nameStr = String(nameBuf, CHARSET)
                            entries.add(ImgEntry(offset, size, nameStr))
                        }
                    }
                }
            } else {
                val dir = resolvedDirFile!!
                val dirLen = dir.length()
                val entryCount = (dirLen / ENTRY_SIZE).toInt()

                RandomAccessFile(dir, "r").use { raf ->
                    val channel = raf.channel
                    if (entryCount > 0) {
                        val entriesBuf = ByteBuffer.allocate(entryCount * ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                        channel.read(entriesBuf)
                        entriesBuf.flip()

                        val nameBuf = ByteArray(NAME_SIZE)
                        for (i in 0 until entryCount) {
                            val offset = entriesBuf.int
                            val size = entriesBuf.int
                            entriesBuf.get(nameBuf)
                            val nameStr = String(nameBuf, CHARSET)
                            entries.add(ImgEntry(offset, size, nameStr))
                        }
                    }
                }
            }

            return ImgArchive(
                imgFile = imgFile,
                dirFile = resolvedDirFile,
                version = if (isV2) ImgVersion.VER2 else ImgVersion.VER1,
                entriesList = entries
            )
        }

        /**
         * Creates a fresh empty archive.
         */
        @Throws(IOException::class)
        fun createEmpty(imgFile: File, version: ImgVersion, dirFile: File? = null): ImgArchive {
            if (version == ImgVersion.VER2) {
                RandomAccessFile(imgFile, "rw").use { raf ->
                    raf.setLength(0)
                    val buf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                    buf.put(VER2_MAGIC)
                    buf.putInt(0)
                    buf.flip()
                    raf.channel.write(buf)
                }
                return ImgArchive(imgFile, null, ImgVersion.VER2, ArrayList())
            } else {
                val resolvedDir = dirFile ?: File(imgFile.parentFile, imgFile.nameWithoutExtension + ".dir")
                RandomAccessFile(imgFile, "rw").use { it.setLength(0) }
                RandomAccessFile(resolvedDir, "rw").use { it.setLength(0) }
                return ImgArchive(imgFile, resolvedDir, ImgVersion.VER1, ArrayList())
            }
        }
    }

    /**
     * Immutable snapshot of all current entries.
     */
    val entries: List<ImgEntry>
        @Synchronized get() = ArrayList(entriesList)

    val entryCount: Int
        @Synchronized get() = entriesList.size

    val archiveName: String
        get() = imgFile.name

    /**
     * Calculates the total uncompressed data size across all files in sectors * 2048.
     */
    @Synchronized
    fun calculateTotalDataSize(): Long {
        var total = entriesList.sumOf { it.sizeInBytes }
        if (version == ImgVersion.VER2) {
            total += getVer2HeaderSizeBytes()
        }
        return total
    }

    private fun getVer2HeaderSizeBytes(): Int = 8 + entriesList.size * ENTRY_SIZE

    private fun getFirstFileEntry(): ImgEntry? {
        return entriesList.minByOrNull { it.offsetInSectors }
    }

    /**
     * Writes the updated directory structure back to disk.
     */
    @Synchronized
    @Throws(IOException::class)
    fun writeDir() {
        if (version == ImgVersion.VER2) {
            val headerSize = getVer2HeaderSizeBytes()
            val first = getFirstFileEntry()

            // If header expanded and now overlaps with the first file entry, move that file to the end
            if (first != null && first.offsetInBytes < headerSize) {
                moveEntryToEnd(first)
            }

            RandomAccessFile(imgFile, "rw").use { raf ->
                val channel = raf.channel
                channel.position(0)

                val headerBuf = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                headerBuf.put(VER2_MAGIC)
                headerBuf.putInt(entriesList.size)
                headerBuf.flip()
                channel.write(headerBuf)

                val entriesBuf = ByteBuffer.allocate(entriesList.size * ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                for (entry in entriesList) {
                    entriesBuf.putInt(entry.offsetInSectors)
                    entriesBuf.putInt(entry.sizeInSectors)
                    val nameBytes = ByteArray(NAME_SIZE)
                    val srcBytes = entry.cleanName.toByteArray(CHARSET)
                    System.arraycopy(srcBytes, 0, nameBytes, 0, minOf(srcBytes.size, NAME_SIZE))
                    entriesBuf.put(nameBytes)
                }
                entriesBuf.flip()
                channel.write(entriesBuf)
            }
        } else {
            val dir = dirFile ?: throw IOException("Missing .dir file for VER1 archive")
            FileOutputStream(dir).use { fos ->
                val channel = fos.channel
                val entriesBuf = ByteBuffer.allocate(entriesList.size * ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                for (entry in entriesList) {
                    entriesBuf.putInt(entry.offsetInSectors)
                    entriesBuf.putInt(entry.sizeInSectors)
                    val nameBytes = ByteArray(NAME_SIZE)
                    val srcBytes = entry.cleanName.toByteArray(CHARSET)
                    System.arraycopy(srcBytes, 0, nameBytes, 0, minOf(srcBytes.size, NAME_SIZE))
                    entriesBuf.put(nameBytes)
                }
                entriesBuf.flip()
                channel.write(entriesBuf)
            }
        }
    }

    private fun moveEntryToEnd(entry: ImgEntry) {
        RandomAccessFile(imgFile, "rw").use { raf ->
            val channel = raf.channel
            val dataBuf = ByteBuffer.allocate(entry.sizeInBytes.toInt())
            channel.position(entry.offsetInBytes)
            channel.read(dataBuf)
            dataBuf.flip()

            val endOffsetBytes = ((imgFile.length() + SECTOR_SIZE - 1) / SECTOR_SIZE) * SECTOR_SIZE
            channel.position(endOffsetBytes)
            channel.write(dataBuf)

            entry.offsetInSectors = (endOffsetBytes / SECTOR_SIZE).toInt()
        }
    }

    /**
     * Extracts an entry to the given destination file.
     */
    @Synchronized
    @Throws(IOException::class)
    fun extract(entry: ImgEntry, destinationFile: File) {
        destinationFile.parentFile?.mkdirs()
        RandomAccessFile(imgFile, "r").use { raf ->
            val srcChannel = raf.channel
            srcChannel.position(entry.offsetInBytes)

            FileOutputStream(destinationFile).use { fos ->
                val destChannel = fos.channel
                var remaining = entry.sizeInBytes
                val buffer = ByteBuffer.allocate(256 * 1024)

                while (remaining > 0) {
                    buffer.clear()
                    if (remaining < buffer.capacity()) {
                        buffer.limit(remaining.toInt())
                    }
                    val read = srcChannel.read(buffer)
                    if (read <= 0) break
                    buffer.flip()
                    destChannel.write(buffer)
                    remaining -= read
                }
            }
        }
    }

    /**
     * Adds a new file into the archive.
     */
    @Synchronized
    @Throws(IOException::class)
    fun add(fileToAdd: File, entryName: String? = null) {
        AppIntegrity.verifyOrThrow()
        val targetName = entryName ?: fileToAdd.name
        val clean = if (targetName.indexOf('\u0000') >= 0) targetName.substringBefore('\u0000') else targetName
        if (entriesList.any { it.cleanName.equals(clean, ignoreCase = true) }) {
            throw IllegalArgumentException("Entry already exists in archive: $clean")
        }

        val fileLen = fileToAdd.length()
        val sectorsRequired = ((fileLen + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()

        val appendSector = ((imgFile.length() + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()
        val appendByteOffset = appendSector.toLong() * SECTOR_SIZE

        RandomAccessFile(imgFile, "rw").use { raf ->
            val channel = raf.channel
            channel.position(appendByteOffset)

            FileInputStream(fileToAdd).use { fis ->
                val sectorBuf = ByteBuffer.allocate(SECTOR_SIZE)
                var bytesReadTotal = 0L

                for (s in 0 until sectorsRequired) {
                    sectorBuf.clear()
                    val read = fis.read(sectorBuf.array(), 0, SECTOR_SIZE)
                    if (read > 0) {
                        if (read < SECTOR_SIZE) {
                            Arrays.fill(sectorBuf.array(), read, SECTOR_SIZE, 0.toByte())
                        }
                        sectorBuf.position(0)
                        sectorBuf.limit(SECTOR_SIZE)
                        channel.write(sectorBuf)
                        bytesReadTotal += read
                    } else {
                        Arrays.fill(sectorBuf.array(), 0.toByte())
                        sectorBuf.position(0)
                        sectorBuf.limit(SECTOR_SIZE)
                        channel.write(sectorBuf)
                    }
                }
            }
        }

        entriesList.add(ImgEntry(appendSector, sectorsRequired, clean))
        writeDir()
    }

    /**
     * Replaces an existing entry with the contents of replacementFile.
     * If the replacement fits into the existing allocated sectors, it is replaced in-place.
     * Otherwise, it is appended to the end of the archive.
     */
    @Synchronized
    @Throws(IOException::class)
    fun replace(entry: ImgEntry, replacementFile: File) {
        AppIntegrity.verifyOrThrow()
        val fileLen = replacementFile.length()
        val sectorsRequired = ((fileLen + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()

        val writeSector = if (sectorsRequired <= entry.sizeInSectors) {
            // Fits in-place!
            entry.offsetInSectors
        } else {
            // Must write to end of archive
            ((imgFile.length() + SECTOR_SIZE - 1) / SECTOR_SIZE).toInt()
        }

        RandomAccessFile(imgFile, "rw").use { raf ->
            val channel = raf.channel
            channel.position(writeSector.toLong() * SECTOR_SIZE)

            FileInputStream(replacementFile).use { fis ->
                val sectorBuf = ByteBuffer.allocate(SECTOR_SIZE)
                for (s in 0 until sectorsRequired) {
                    sectorBuf.clear()
                    val read = fis.read(sectorBuf.array(), 0, SECTOR_SIZE)
                    if (read > 0) {
                        if (read < SECTOR_SIZE) {
                            Arrays.fill(sectorBuf.array(), read, SECTOR_SIZE, 0.toByte())
                        }
                        sectorBuf.position(0)
                        sectorBuf.limit(SECTOR_SIZE)
                        channel.write(sectorBuf)
                    } else {
                        Arrays.fill(sectorBuf.array(), 0.toByte())
                        sectorBuf.position(0)
                        sectorBuf.limit(SECTOR_SIZE)
                        channel.write(sectorBuf)
                    }
                }
            }
        }

        entry.offsetInSectors = writeSector
        entry.sizeInSectors = sectorsRequired
        writeDir()
    }

    /**
     * Deletes the specified entry.
     */
    @Synchronized
    @Throws(IOException::class)
    fun delete(entry: ImgEntry) {
        delete(listOf(entry))
    }

    /**
     * Batch deletes entries.
     */
    @Synchronized
    @Throws(IOException::class)
    fun delete(entriesToDelete: List<ImgEntry>) {
        AppIntegrity.verifyOrThrow()
        entriesList.removeAll(entriesToDelete.toSet())
        writeDir()
    }

    /**
     * Renames an existing entry.
     */
    @Synchronized
    @Throws(IOException::class)
    fun rename(entry: ImgEntry, newName: String) {
        val clean = if (newName.indexOf('\u0000') >= 0) newName.substringBefore('\u0000') else newName
        entry.name = clean
        writeDir()
    }

    /**
     * Rebuilds the archive by repacking all active entries sequentially into a contiguous
     * layout without gaps, reclaiming all dead space from deleted or replaced entries.
     */
    @Synchronized
    @Throws(IOException::class)
    fun rebuild(progressListener: ((current: Int, total: Int) -> Unit)? = null) {
        AppIntegrity.verifyOrThrow()
        val tmpFile = File(imgFile.parentFile, imgFile.name + "_tmp")
        if (tmpFile.exists()) tmpFile.delete()

        val headerSize = getVer2HeaderSizeBytes()
        val headerSectors = if (version == ImgVersion.VER2) {
            ((headerSize + SECTOR_SIZE - 1) / SECTOR_SIZE)
        } else {
            0
        }

        var currentSector = headerSectors

        RandomAccessFile(imgFile, "r").use { srcRaf ->
            val srcChannel = srcRaf.channel

            RandomAccessFile(tmpFile, "rw").use { destRaf ->
                val destChannel = destRaf.channel

                if (version == ImgVersion.VER2) {
                    // Pre-allocate header space
                    destChannel.position(0)
                    val magicBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                    magicBuf.put(VER2_MAGIC)
                    magicBuf.flip()
                    destChannel.write(magicBuf)

                    val padBytes = (headerSectors * SECTOR_SIZE) - 4
                    if (padBytes > 0) {
                        val zeroChunk = ByteBuffer.allocate(minOf(padBytes, 64 * 1024))
                        var remainingPad = padBytes
                        while (remainingPad > 0) {
                            zeroChunk.clear()
                            val toWrite = minOf(remainingPad, zeroChunk.capacity())
                            zeroChunk.limit(toWrite)
                            destChannel.write(zeroChunk)
                            remainingPad -= toWrite
                        }
                    }
                }

                val transferBuffer = ByteBuffer.allocate(256 * 1024)
                for ((idx, entry) in entriesList.withIndex()) {
                    srcChannel.position(entry.offsetInBytes)
                    destChannel.position(currentSector.toLong() * SECTOR_SIZE)

                    var bytesRemaining = entry.sizeInBytes
                    while (bytesRemaining > 0) {
                        transferBuffer.clear()
                        if (bytesRemaining < transferBuffer.capacity()) {
                            transferBuffer.limit(bytesRemaining.toInt())
                        }
                        val read = srcChannel.read(transferBuffer)
                        if (read <= 0) break
                        transferBuffer.flip()
                        destChannel.write(transferBuffer)
                        bytesRemaining -= read
                    }

                    entry.offsetInSectors = currentSector
                    currentSector += entry.sizeInSectors
                    progressListener?.invoke(idx + 1, entriesList.size)
                }
            }
        }

        // Atomically replace original file with rebuilt file
        if (!imgFile.delete()) {
            tmpFile.delete()
            throw IOException("Cannot delete old archive file: ${imgFile.absolutePath}")
        }
        if (!tmpFile.renameTo(imgFile)) {
            throw IOException("Cannot rename temporary file to ${imgFile.absolutePath}")
        }

        writeDir()
    }
}
