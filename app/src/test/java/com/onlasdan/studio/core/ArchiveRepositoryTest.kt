package com.onlasdan.studio.core

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ArchiveRepositoryTest {

    @Test
    fun testMutexSharingForSamePath() {
        val path1 = "/storage/emulated/0/gta3.img"
        val path2 = "/storage/emulated/0/gta3.img"

        val mutex1 = ArchiveRepository.getMutex(path1)
        val mutex2 = ArchiveRepository.getMutex(path2)

        assertNotNull(mutex1)
        assertNotNull(mutex2)
        assertSame("Mutex for same path must be identical instance", mutex1, mutex2)
    }

    @Test
    fun testWithLockExecution() = runBlocking {
        var executed = false
        val result = ArchiveRepository.withLock("/dummy/path.img") {
            executed = true
            "success"
        }
        assertTrue(executed)
        assertEquals("success", result)
    }

    @Test
    fun testArchiveUpdateEventData() {
        val event = ArchiveUpdateEvent(
            archiveCanonicalPath = "/storage/emulated/0/gta3.img",
            entryName = "player.txd"
        )
        assertEquals("/storage/emulated/0/gta3.img", event.archiveCanonicalPath)
        assertEquals("player.txd", event.entryName)
        assertTrue(event.timestamp > 0)
    }
}
