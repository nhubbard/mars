package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsShaFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual SHA profiles retain all original cases and repeated calls without fixing private context bytes`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/sha-function-profiles-v16.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(33, entries.size)
            entries.map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use {
                    val bytes = it.readBytes()
                    val original = MarsFunctionCaseData.parse(bytes)
                    val upgraded = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.apply {
                        addProperty("formatVersion", 17)
                    }
                    assertEquals(original, MarsFunctionCaseData.parse(upgraded.toString().toByteArray()))
                    original
                }
            }
        }
        val cases = profiles.flatten()
        assertEquals(128, cases.size)
        assertEquals(128, cases.map { it.name }.distinct().size)
        assertEquals(298, cases.sumOf { it.calls.size })
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000 })
        for (case in cases) {
            assertEquals(9, case.preservedRegisters.size)
            assertEquals(8, case.stackAlignment)
            assertEquals(0, case.callerStackBytes)
            assertTrue(case.instructionLimit in setOf(100_000L, 3_000_000L))
            assertEquals(0, case.exit!!.code)
            for (call in case.calls.filter { it.entry != "main" }) {
                assertEquals(13, call.accessPolicies.size)
                val wholeSpan = call.accessPolicies.last()
                assertEquals("data", wholeSpan.from.label)
                assertEquals("status", wholeSpan.until.label)
                assertEquals(4, wholeSpan.until.offset)
                assertFalse(wholeSpan.checkReads)
                assertTrue(wholeSpan.readable.isEmpty())
                assertEquals(200_000, call.accessHistory!!.maxAccesses)
                assertTrue(call.accessPolicies.all { it.maxAccesses == 200_000 })
                if (call.entry == "sha256_init") {
                    assertEquals("context", call.memory.single().label)
                    assertEquals(44, call.memory.single().byteLength)
                }
                if (call.entry == "sha256_final") assertEquals("digest", call.memory.single().label)
                if (call.entry == "sha256_compress") assertEquals("state", call.memory.single().label)
            }
        }
        val callers = cases.filter { it.calls.first().entry == "main" }
        assertEquals(12, callers.size)
        assertTrue(callers.all { it.calls.last().returnTo == "main_done" && it.exit!!.memory.single().label == "status" })
        val reused = cases.filter { it.name.startsWith("repeated files descriptor") }
        assertEquals(4, reused.size)
        for (case in reused) {
            assertEquals(listOf("repeat-one.bin", "repeat-two.bin", "repeat-one.bin", "missing.bin"),
                case.calls.map { it.fileReadInvocation!!.path })
            assertEquals(listOf(7, 64, 3, 1), case.calls.map { it.fileReadInvocation!!.maxRequest })
            assertEquals(setOf("repeat-two.bin"), case.fileRead!!.additionalFiles.keys)
            assertTrue(case.calls.all { it.setupMemory.single().label == "path" })
            if (case.fileRead!!.failAfterBytes == 3) {
                assertEquals(listOf(0L, 2L, 0L, 1L), case.calls.map { it.expectedRegisters.getValue("v0") })
                assertEquals(case.calls[0].memory.single(), case.calls[1].memory.single())
                assertEquals(case.calls[2].memory.single(), case.calls[3].memory.single())
            }
        }
    }
}
