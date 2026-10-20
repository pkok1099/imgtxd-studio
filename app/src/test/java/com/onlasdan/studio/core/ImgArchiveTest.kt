package com.onlasdan.studio.core

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ImgArchiveTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testVer2CreateAndAddAndExtract() {
        val imgFile = tempFolder.newFile("gta3.img")
        val archive = ImgArchive.createEmpty(imgFile, ImgVersion.VER2)
        assertEquals(0, archive.entryCount)
        assertEquals(ImgVersion.VER2, archive.version)

        // Create sample file 1 (3000 bytes -> 2 sectors = 4096 bytes)
        val file1 = tempFolder.newFile("player.dff")
        val data1 = ByteArray(3000) { (it % 256).toByte() }
        file1.writeBytes(data1)

        archive.add(file1, "player.dff")
        assertEquals(1, archive.entryCount)
        val entry1 = archive.entries[0]
        assertEquals("player.dff", entry1.cleanName)
        assertEquals(2, entry1.sizeInSectors)

        // Create sample file 2 (1000 bytes -> 1 sector = 2048 bytes)
        val file2 = tempFolder.newFile("player.txd")
        val data2 = ByteArray(1000) { ((it + 50) % 256).toByte() }
        file2.writeBytes(data2)

        archive.add(file2, "player.txd")
        assertEquals(2, archive.entryCount)

        // Verify reopening the archive
        val reopened = ImgArchive.open(imgFile)
        assertEquals(2, reopened.entryCount)
        assertEquals("player.dff", reopened.entries[0].cleanName)
        assertEquals("player.txd", reopened.entries[1].cleanName)

        // Extract and verify file1
        val extracted1 = tempFolder.newFile("extracted_player.dff")
        reopened.extract(reopened.entries[0], extracted1)
        val extractedBytes1 = extracted1.readBytes()
        // The first 3000 bytes must match exactly data1
        for (i in 0 until 3000) {
            assertEquals(data1[i], extractedBytes1[i])
        }
        // Trailing bytes up to 4096 must be 0 (sector padding)
        for (i in 3000 until 4096) {
            assertEquals(0.toByte(), extractedBytes1[i])
        }
    }

    @Test
    fun testVer2ReplaceInPlaceAndExpand() {
        val imgFile = tempFolder.newFile("test_replace.img")
        val archive = ImgArchive.createEmpty(imgFile, ImgVersion.VER2)

        // File 1: 3 sectors (6144 bytes)
        val file1 = tempFolder.newFile("model.dff")
        file1.writeBytes(ByteArray(5000) { 1 })
        archive.add(file1, "model.dff")

        val originalOffset = archive.entries[0].offsetInSectors
        assertEquals(3, archive.entries[0].sizeInSectors)

        // Smaller replacement: 2 sectors (4096 bytes) -> must replace in-place!
        val fileSmall = tempFolder.newFile("model_small.dff")
        fileSmall.writeBytes(ByteArray(3000) { 2 })
        archive.replace(archive.entries[0], fileSmall)

        assertEquals(originalOffset, archive.entries[0].offsetInSectors)
        assertEquals(2, archive.entries[0].sizeInSectors)

        // Larger replacement: 5 sectors (10240 bytes) -> must append to end!
        val fileLarge = tempFolder.newFile("model_large.dff")
        fileLarge.writeBytes(ByteArray(9000) { 3 })
        archive.replace(archive.entries[0], fileLarge)

        assertTrue("Offset should move to end", archive.entries[0].offsetInSectors > originalOffset)
        assertEquals(5, archive.entries[0].sizeInSectors)
    }

    @Test
    fun testDeleteAndRebuild() {
        val imgFile = tempFolder.newFile("test_rebuild.img")
        val archive = ImgArchive.createEmpty(imgFile, ImgVersion.VER2)

        val f1 = tempFolder.newFile("a.dff").apply { writeBytes(ByteArray(1000) { 1 }) }
        val f2 = tempFolder.newFile("b.dff").apply { writeBytes(ByteArray(1000) { 2 }) }
        val f3 = tempFolder.newFile("c.dff").apply { writeBytes(ByteArray(1000) { 3 }) }

        archive.add(f1, "a.dff")
        archive.add(f2, "b.dff")
        archive.add(f3, "c.dff")
        assertEquals(3, archive.entryCount)

        // Delete "b.dff"
        val bEntry = archive.entries.first { it.cleanName == "b.dff" }
        archive.delete(bEntry)
        assertEquals(2, archive.entryCount)

        // Rebuild archive
        var progressCalled = false
        archive.rebuild { current, total ->
            progressCalled = true
            assertTrue(current in 1..total)
        }
        assertTrue(progressCalled)

        // Verify reopened archive
        val reopened = ImgArchive.open(imgFile)
        assertEquals(2, reopened.entryCount)
        assertEquals("a.dff", reopened.entries[0].cleanName)
        assertEquals("c.dff", reopened.entries[1].cleanName)

        // Rebuilt entries must be contiguous
        val headerSectors = ((8 + 2 * 32 + 2047) / 2048)
        assertEquals(headerSectors, reopened.entries[0].offsetInSectors)
        assertEquals(headerSectors + reopened.entries[0].sizeInSectors, reopened.entries[1].offsetInSectors)
    }

    @Test
    fun testVer1Archive() {
        val imgFile = tempFolder.newFile("gta3_v1.img")
        val dirFile = tempFolder.newFile("gta3_v1.dir")
        val archive = ImgArchive.createEmpty(imgFile, ImgVersion.VER1, dirFile)

        assertEquals(ImgVersion.VER1, archive.version)
        assertEquals(0, archive.entryCount)

        val f1 = tempFolder.newFile("car.dff").apply { writeBytes(ByteArray(2500) { 0xAA.toByte() }) }
        archive.add(f1, "car.dff")

        assertEquals(1, archive.entryCount)
        assertEquals(32L, dirFile.length())

        val reopened = ImgArchive.open(imgFile, dirFile)
        assertEquals(1, reopened.entryCount)
        assertEquals("car.dff", reopened.entries[0].cleanName)
        assertEquals(2, reopened.entries[0].sizeInSectors)
    }

    @Test
    fun testRename() {
        val imgFile = tempFolder.newFile("test_rename.img")
        val archive = ImgArchive.createEmpty(imgFile, ImgVersion.VER2)
        val f1 = tempFolder.newFile("old_name.dff").apply { writeBytes(ByteArray(500) { 1 }) }
        archive.add(f1, "old_name.dff")

        archive.rename(archive.entries[0], "new_name.dff")
        assertEquals("new_name.dff", archive.entries[0].cleanName)

        val reopened = ImgArchive.open(imgFile)
        assertEquals("new_name.dff", reopened.entries[0].cleanName)
    }
}
