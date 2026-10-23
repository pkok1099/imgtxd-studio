package com.onlasdan.studio.loader

import com.onlasdan.studio.core.NativeCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdeIndexTest {

    @Test
    fun wireParsesHeaderModelsAndTxd() {
        val result = NativeCore.parseIdeScanWire(
            arrayOf(
                "OK\t2\t2\t3",
                "M\tinfernus\t411",
                "M\tlandstal\t400",
                "T\tinfernus\t411",
                "T\tshared\t100,200"
            )
        )
        assertEquals(411, result?.models?.get("infernus"))
        assertEquals(400, result?.models?.get("landstal"))
        assertEquals(listOf(100, 200), result?.txdToIds?.get("shared"))
        assertEquals(3, result?.filesRead)
    }

    @Test
    fun wireRejectsErrorAndGarbage() {
        assertNull(NativeCore.parseIdeScanWire(arrayOf("ERROR\tfolder tidak ditemukan")))
        assertNull(NativeCore.parseIdeScanWire(emptyArray()))
        assertNull(NativeCore.parseIdeScanWire(null))
    }

    @Test
    fun storeRoundTripPreservesIndex() {
        val index = IdeIndex(
            models = linkedMapOf("infernus" to 411, "male01" to 7),
            txdToIds = linkedMapOf("infernus" to listOf(411), "shared" to listOf(1, 2)),
            rootPath = "/storage/emulated/0/Android/data/com.rockstargames.gtasa/files",
            scannedAtMillis = 123456789L,
            ideFileCount = 58
        )
        val restored = IdeIndexStore.parse(IdeIndexStore.serialize(index))
        assertEquals(index, restored)
    }

    @Test
    fun storeParseRejectsEmptyText() {
        assertNull(IdeIndexStore.parse(""))
        assertNull(IdeIndexStore.parse("#root\t/x\n#files\t0\n"))
    }
}
