package edu.missouristate.mars.plugin.learning

import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsPngFilterFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual PNG filter profiles retain both native orders all predictors and session reuse`() {
        val path = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/png-filter-profiles-v21.zip")!!.use { Files.copy(it, path) }
        val profiles = ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().toList().map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
            }
        }
        assertEquals(listOf(44, 46, 44, 44, 44, 44), profiles.map { it.size })
        val cases = profiles.flatten()
        assertEquals(266, cases.size)
        assertEquals(266, cases.map { it.name }.distinct().size)
        assertEquals(276, cases.sumOf { it.calls.size })
        assertEquals(133, cases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN })
        assertEquals(133, cases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
        assertTrue(cases.all { it.instructionLimit == 100_000L && it.stackAlignment == 8 && it.preservedRegisters.size == 9 })
        val repeated = cases.filter { it.name.startsWith("repeated filters ") }
        assertEquals(2, repeated.size)
        assertTrue(repeated.all { it.calls.size == 6 })
        assertEquals(8, cases.count { it.name.startsWith("Paeth tie ") })
        assertEquals(16, cases.count { it.name.startsWith("invalid ") })
        for (case in cases) for (call in case.calls) {
            assertEquals(6, call.callerAccess!!.objects.size)
            assertEquals(100_000, call.callerAccess!!.maxAccesses)
            assertEquals(listOf("current_rgb", "previous_rgb", "filter_settings"), call.setupMemory.map { it.label })
            assertEquals(listOf(1552, 1552, 32), call.setupMemory.map { it.byteLength })
            assertTrue(call.callerAccess!!.readable.none { it.at.label == "filtered_row" })
        }
    }
}
