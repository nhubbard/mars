package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsCrcFileFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual CRC file profiles preserve short read errors budgets descriptor ownership and padding rules`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/crc-file-function-profiles.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
            }.toList()
        }
        assertEquals(7, profiles.size)
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000 })
        val cases = profiles.flatten()
        assertEquals(27, cases.size)
        assertEquals(28, cases.sumOf { it.calls.size })
        assertEquals(24, cases.count { it.instructionLimit == 2_000_000L })
        assertEquals(2, cases.count { it.instructionLimit == 100_000L })
        assertEquals(1, cases.count { it.instructionLimit == 3_000_000L })
        for (case in cases) {
            val fixture = case.fileRead!!
            assertEquals(19, fixture.firstDescriptor)
            assertTrue(fixture.maxRequest in setOf(1, 7, 256))
            assertTrue(case.calls.all { it.readFile })
            assertEquals(16, case.accessPolicies.size)
            assertFalse(case.accessPolicies.first().checkReads)
            assertTrue(case.accessPolicies.first().readable.isEmpty())
            assertTrue(case.accessPolicies.drop(1).all { it.checkReads })
            assertTrue(case.accessPolicies.all { it.maxAccesses == 100_000 })
        }
        assertEquals(2, cases.count { it.fileRead!!.missing })
        assertEquals(listOf(0, 1, 9, 0, 1, 9), cases.mapNotNull { it.fileRead!!.failAfterBytes })
        val repeated = cases.single { it.calls.size == 2 }
        assertEquals(listOf(0L, 1L), repeated.calls.map { it.arguments.getValue("a3") })
        assertEquals(listOf(0xcbf43926L, 0xcbf43926L), repeated.calls.map { it.expectedRegisters.getValue("v0") })
    }
}
