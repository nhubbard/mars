package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsCborSequenceFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual CBOR sequence profiles retain every original budget snapshot and enclosing access history`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/cbor-sequence-function-profiles.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(104, entries.size)
            entries.map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
            }
        }
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000L })
        val cases = profiles.flatten()
        assertEquals(205, cases.size)
        assertEquals(205, cases.map { it.name }.distinct().size)
        assertEquals(208, cases.sumOf { it.calls.size })
        assertTrue(cases.all { it.instructionLimit == 4_000_000L })
        val caller = cases.single { it.exit != null }
        assertEquals("complete supplied sequence caller", caller.name)
        assertEquals("main", caller.calls.single().entry)
        assertEquals("sequence_return", caller.calls.single().returnTo)
        assertEquals(1L, caller.calls.single().expectedRegisters.getValue("v1"))
        assertEquals(mapOf("status" to "00000000", "count" to "0100000000000000"), caller.exit!!.memory.associate { it.label to it.bytes })
        assertTrue(caller.initialMemory.isEmpty())
        val original = cases.filter { it.exit == null && it.calls.size == 1 }
        assertEquals(203, original.size)
        val retry = cases.single { it.calls.size == 4 }
        assertEquals(listOf(1L, 0L, 0L, 1L), retry.calls.map { it.expectedRegisters.getValue("v0") })
        assertEquals(listOf(0L, 1L, 0L, 0L), retry.calls.map { it.expectedRegisters.getValue("v1") })
        for (case in original + retry) {
            assertEquals(4, case.initialMemory.size)
            assertEquals(9, case.preservedRegisters.size)
            assertEquals(0, case.callerStackBytes)
            assertEquals(8192, case.initialRegisterAddresses.getValue("sp").offset)
            for (call in case.calls) {
                assertEquals(setOf("source", "record", "destination"), call.memory.map { it.label }.toSet())
                assertEquals(listOf(1280, 256, 320), call.memory.map { it.byteLength })
                assertEquals(3, call.accessPolicies.size)
                assertTrue(call.accessPolicies.all { it.writable.isEmpty() && it.maxAccesses == 500_000 && it.checkReads })
                val history = call.accessHistory!!
                assertEquals(500_000, history.maxAccesses)
                assertEquals(listOf("source", "workspace"), history.regions.map { it.at.label })
                assertEquals(listOf(1280, 8192), history.regions.map { it.byteLength })
            }
        }
        val zero = original.single { it.name == "selected zero excludes real input" }.calls.single()
        assertEquals(0x123456780001L, zero.arguments.getValue("a0"))
        assertEquals(0L, zero.expectedRegisters.getValue("v0"))
        assertTrue(zero.accessPolicies.all { it.readable.isEmpty() })
        assertEquals(2L, original.single { it.name == "early count 1025 depth 9" }.calls.single().expectedRegisters.getValue("v0"))
        assertEquals(1L, original.single { it.name == "sibling keeps inherited depth after helper overwrites a2" }.calls.single().expectedRegisters.getValue("v0"))
    }
}
