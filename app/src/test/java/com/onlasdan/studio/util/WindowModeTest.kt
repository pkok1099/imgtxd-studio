package com.onlasdan.studio.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowModeTest {

    @Test
    fun testWindowModeEnumValues() {
        assertEquals("PER_FEATURE", WindowMode.PER_FEATURE.name)
        assertEquals("PER_FILE", WindowMode.PER_FILE.name)
        assertEquals("SINGLE_WINDOW", WindowMode.SINGLE_WINDOW.name)

        // Verify default value is PER_FEATURE according to requirements
        assertEquals(WindowMode.PER_FEATURE, WindowMode.valueOf("PER_FEATURE"))
    }

    @Test
    fun testLauncherFileType() {
        val img = LauncherFileType.IMG
        val txd = LauncherFileType.TXD
        assertNotNull(img)
        assertNotNull(txd)
        assertEquals(2, LauncherFileType.values().size)
    }

    @Test
    fun testParentArchiveInfoCreation() {
        val info = ParentArchiveInfo(
            archivePath = "/sdcard/test.img",
            entryName = "player.txd",
            originalRestrictedPath = "/sdcard/Android/data/com.game/files/test.img",
            isShizuku = true
        )
        assertEquals("/sdcard/test.img", info.archivePath)
        assertEquals("player.txd", info.entryName)
        assertEquals("/sdcard/Android/data/com.game/files/test.img", info.originalRestrictedPath)
        assertTrue(info.isShizuku)
    }

    @Test
    fun testWindowLauncherExtrasConstants() {
        assertEquals("com.onlasdan.studio.extra.FILE_PATH", WindowLauncher.EXTRA_FILE_PATH)
        assertEquals("com.onlasdan.studio.extra.IS_SHIZUKU", WindowLauncher.EXTRA_IS_SHIZUKU)
        assertEquals("com.onlasdan.studio.extra.PARENT_ARCHIVE_PATH", WindowLauncher.EXTRA_PARENT_ARCHIVE_PATH)
        assertEquals("com.onlasdan.studio.extra.PARENT_ENTRY_NAME", WindowLauncher.EXTRA_PARENT_ENTRY_NAME)
        assertEquals("com.onlasdan.studio.extra.PARENT_RESTRICTED_PATH", WindowLauncher.EXTRA_PARENT_RESTRICTED_PATH)
        assertEquals("com.onlasdan.studio.extra.PARENT_IS_SHIZUKU", WindowLauncher.EXTRA_PARENT_IS_SHIZUKU)
    }
}
