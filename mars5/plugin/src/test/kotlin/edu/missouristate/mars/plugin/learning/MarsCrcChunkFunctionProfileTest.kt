package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsCrcChunkFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual CRC chunk data retains all original inputs allowances and scoped retry histories`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/crc-chunk-function-profiles.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(17, entries.size)
            val exitEntry = entries.single { it.name == "caller-exit.cases.json" }
            val exit = zip.getInputStream(exitEntry).use { MarsConsoleCaseData.parse(it.readBytes()) }.single()
            assertEquals(100_000L, exit.instructionLimit)
            assertEquals("", exit.expectedOutput)
            entries.filter { it != exitEntry }.map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
            }
        }
        assertEquals(16, profiles.size)
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000 })
        val cases = profiles.flatten()
        assertEquals(137, cases.size)
        assertEquals(142, cases.sumOf { it.calls.size })
        assertEquals(113, cases.count { it.instructionLimit == 1_000_000L })
        assertEquals(24, cases.count { it.instructionLimit == 100_000L })
        for (case in cases) {
            assertEquals(0x7fff0000L, case.initialRegisters.getValue("sp"))
            assertEquals(9, case.preservedRegisters.size)
            assertEquals(200_000, case.accessPolicies.single().maxAccesses)
            assertEquals("data", case.accessPolicies.single().from.label)
            assertEquals("file_status", case.accessPolicies.single().until.label)
            assertEquals(4, case.accessPolicies.single().until.offset)
        }
        val retry = cases.single { it.calls.size == 5 }
        assertEquals(listOf(2L, 0L, 2L, 1L, 0L), retry.calls.map { it.expectedRegisters.getValue("v1") })
        assertEquals(listOf(emptyList(), listOf(12, 1024), listOf(4), listOf(15, 1024), listOf(15, 1024)),
            retry.calls.map { it.accessPolicies.single().readable.map { window -> window.byteLength } })
        assertTrue(retry.calls.all { it.accessPolicies.single().maxAccesses == 200_000 })
        assertEquals(0xae426082L, retry.calls[1].expectedRegisters.getValue("v0"))
    }
}
