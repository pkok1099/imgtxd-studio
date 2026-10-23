package com.onlasdan.studio.loader

import com.onlasdan.studio.ui.ModelIdHint
import com.onlasdan.studio.ui.autoFillModelIdForName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoaderPayloadTest {

    private val index = IdeIndex(
        models = mapOf("infernus" to 411, "landstal" to 400),
        txdToIds = mapOf("infernus" to listOf(411), "shared" to listOf(100, 200)),
        rootPath = "/game/files",
        scannedAtMillis = 1L,
        ideFileCount = 3
    )

    @Test
    fun acceptsModelExtensionsAnyCase() {
        assertTrue(LoaderFileRules.isModelFileName("landstal.dff"))
        assertTrue(LoaderFileRules.isModelFileName("LANDSTAL.TXD"))
        assertTrue(LoaderFileRules.isModelFileName("x.Dff"))
        assertFalse(LoaderFileRules.isModelFileName("readme.txt"))
        assertFalse(LoaderFileRules.isModelFileName("dff"))
        assertFalse(LoaderFileRules.isModelFileName("model.dff.bak"))
    }

    @Test
    fun dffNameFillsFromModelMap() {
        val (text, hint) = autoFillModelIdForName("infernus.dff", index)
        assertEquals("411", text)
        assertEquals(ModelIdHint.FromIndex, hint)
    }

    @Test
    fun looseFileNameUsesSameLookupAsArchiveEntry() {
        // Uppercase loose file names resolve through the same lookup.
        val (text, hint) = autoFillModelIdForName("LANDSTAL.DFF", index)
        assertEquals("400", text)
        assertEquals(ModelIdHint.FromIndex, hint)
    }

    @Test
    fun dffMissClearsWithNotInIndex() {
        val (text, hint) = autoFillModelIdForName("unknown.dff", index)
        assertEquals("", text)
        assertEquals(ModelIdHint.NotInIndex, hint)
    }

    @Test
    fun txdUniqueCandidateFills() {
        val (text, hint) = autoFillModelIdForName("infernus.txd", index)
        assertEquals("411", text)
        assertEquals(ModelIdHint.FromIndex, hint)
    }

    @Test
    fun txdSharedNameLeavesFieldUntouched() {
        val (text, hint) = autoFillModelIdForName("shared.txd", index)
        assertNull(text)
        assertEquals(ModelIdHint.TxdCandidates(2), hint)
    }

    @Test
    fun withoutIndexNothingChanges() {
        val (text, hint) = autoFillModelIdForName("infernus.dff", null)
        assertNull(text)
        assertNull(hint)
    }
}
