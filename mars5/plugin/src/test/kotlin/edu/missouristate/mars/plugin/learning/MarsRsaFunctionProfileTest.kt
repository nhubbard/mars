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

class MarsRsaFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual RSA profiles preserve all original interfaces aliases and cumulative reuse budgets`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/rsa-function-profiles-v18.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            val entries = zip.entries().asSequence().toList()
            assertEquals(18, entries.size)
            entries.map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use {
                    val bytes = it.readBytes()
                    val original = MarsFunctionCaseData.parse(bytes)
                    val upgraded = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject.apply { addProperty("formatVersion",20) }
                    assertEquals(original,MarsFunctionCaseData.parse(upgraded.toString().toByteArray()))
                    original
                }
            }
        }
        val cases = profiles.flatten()
        assertEquals(123, cases.size)
        assertEquals(123, cases.map { it.name }.distinct().size)
        assertEquals(142, cases.sumOf { it.calls.size })
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 40_000_000 })
        val repeatedPower = cases.single { it.name == "reused power with moving objects" }
        assertEquals(40_000_000L, repeatedPower.instructionLimit)
        assertEquals(3, repeatedPower.calls.size)
        for (case in cases) {
            assertEquals(9, case.preservedRegisters.size)
            assertEquals(8, case.stackAlignment)
            assertEquals(0, case.callerStackBytes)
            for (call in case.calls.filter { it.entry != "main" }) {
                val caller = call.callerAccess!!
                assertEquals(2_000_000, caller.maxAccesses)
                assertEquals(setOf("left", "right", "modulus", "exponent", "output", "mode", "status"), caller.objects.map { it.at.label }.toSet())
                assertFalse(caller.objects.any { it.at.label == "workspace" })
                assertTrue(call.accessPolicies.isEmpty())
            }
        }
        val aliases = cases.filter { it.name.startsWith("public modular addition ") }
        assertEquals(20, aliases.size)
        assertEquals(setOf("left", "right", "output"), aliases.flatMap { it.calls }.flatMap { it.memory }.map { it.label }.toSet())
        val callers = cases.filter { it.calls.first().entry == "main" }
        assertEquals(12, callers.size)
        assertTrue(callers.all { it.calls.size == 2 && it.exit!!.memory.single().label == "status" })
        val invalid = cases.filter { it.name.contains("invalid") }
        assertEquals(5, invalid.size)
        assertTrue(invalid.all { it.calls.single().callerAccess!!.writable.isEmpty() })
    }
}
