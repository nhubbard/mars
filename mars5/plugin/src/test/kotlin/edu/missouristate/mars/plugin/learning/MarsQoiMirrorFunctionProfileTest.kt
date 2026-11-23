package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsQoiMirrorFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual QOI profiles retain the original geometry budgets ownership and caller exit`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/qoi-mirror-function-profiles.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(188, entries.size)
            entries.map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
            }
        }
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000L })
        val cases = profiles.flatten()
        assertEquals(205, cases.size)
        assertEquals(205, cases.map { it.name }.distinct().size)
        assertEquals(205, cases.sumOf { it.calls.size })
        assertEquals(182, cases.count { it.instructionLimit == 6_000_000L })
        assertEquals(23, cases.count { it.instructionLimit == 100_000L })
        val watched = cases.filter { it.calls.single().accessHistory != null }
        assertEquals(197, watched.size)
        for (case in watched) {
            val call = case.calls.single()
            assertEquals(14, case.initialMemory.size)
            assertEquals(9, case.preservedRegisters.size)
            assertEquals(0, case.callerStackBytes)
            assertEquals(8192, case.initialRegisterAddresses.getValue("sp").offset)
            assertEquals(13, call.accessPolicies.size)
            val history = call.accessHistory!!
            assertEquals(200_000, history.maxAccesses)
            assertEquals(call.accessPolicies.map { it.from.label }.toSet(), history.regions.map { it.at.label }.toSet())
            assertEquals(13, history.regions.size)
            assertTrue(call.accessPolicies.all { it.maxAccesses == 200_000 && it.checkReads })
        }
        val invalidCounts = cases.filter { it.name.startsWith("rejected count ") }
        assertEquals(7, invalidCounts.size)
        for (case in invalidCounts) {
            val call = case.calls.single()
            assertEquals(1L, call.arguments.getValue("a0"))
            assertEquals(1L, call.arguments.getValue("a2"))
            assertTrue(call.accessPolicies.isEmpty())
            assertEquals(null, call.accessHistory)
        }
        val caller = cases.single { it.exit != null }
        assertEquals("ordinary mirrored caller", caller.name)
        assertTrue(caller.initialMemory.isEmpty())
        assertEquals("main", caller.calls.single().entry)
        assertEquals("mirrored_return", caller.calls.single().returnTo)
        assertEquals(0, caller.exit!!.code)
        assertEquals(mapOf("status" to "00000000", "count" to "0800000001000000"), caller.exit!!.memory.associate { it.label to it.bytes })
        assertFalse(cases.any { it.instructionLimit < 100_000L })
    }
}
