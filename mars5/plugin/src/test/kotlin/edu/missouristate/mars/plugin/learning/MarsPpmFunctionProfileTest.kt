package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsPpmFunctionProfileTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual PPM profiles retain all published allowances and complete caller access regions`() {
        val archive = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/ppm-negative-function-profiles.zip")!!.use { Files.copy(it, archive) }
        val profiles = ZipFile(archive.toFile()).use { zip ->
            zip.entries().asSequence().map { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                zip.getInputStream(entry).use { MarsFunctionCaseData.parse(it.readBytes()) }
            }.toList()
        }
        assertEquals(57, profiles.size)
        assertTrue(profiles.all { it.sumOf { case -> case.instructionLimit } <= 10_000_000 })
        val cases = profiles.flatten()
        assertEquals(167, cases.size)
        assertEquals(167, cases.map { it.name }.distinct().size)
        assertEquals(168, cases.sumOf { it.calls.size })
        assertEquals(161, cases.count { it.instructionLimit == 3_000_000L })
        assertEquals(6, cases.count { it.instructionLimit == 100_000L })
        for (case in cases) {
            assertEquals("workspace", case.initialRegisterAddresses.getValue("sp").label)
            assertEquals(4096, case.initialRegisterAddresses.getValue("sp").offset)
            assertEquals(9, case.preservedRegisters.size)
            assertEquals(13, case.accessPolicies.size)
            assertTrue(case.accessPolicies.none { it.from.label == "workspace" })
            assertEquals("function_return", case.noExecution.single().from)
            assertEquals("ppm_parse", case.noExecution.single().until)
        }
        val maximum = cases.single { it.name == "negative 64 by 64 capacity-kind 3" }.calls.single()
        assertEquals(Long.MIN_VALUE, maximum.arguments.getValue("a3"))
        assertEquals(12301L, maximum.expectedRegisters.getValue("v1"))
        assertTrue(maximum.accessPolicies.all { policy ->
            policy.readable.map { it.byteLength } == listOf(16384, 12301) &&
                policy.writable.single().byteLength == 12301
        })
        val caller = cases.single { it.calls.size == 2 }
        assertEquals(listOf("negative_return", "function_return"), caller.calls.map { it.returnTo })
        assertEquals(0, caller.callerStackBytes)
    }
}
