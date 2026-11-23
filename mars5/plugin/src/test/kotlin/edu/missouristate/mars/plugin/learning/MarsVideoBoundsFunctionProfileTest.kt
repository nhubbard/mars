package edu.missouristate.mars.plugin.learning

import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsVideoBoundsFunctionProfileTest {
    @TempDir lateinit var directory: Path

    private fun profiles(): Map<String, ByteArray> {
        val path = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/video-bounds-profiles-v21.zip")!!.use { Files.copy(it, path) }
        return ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().toList().associate { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            }
        }
    }

    @Test
    fun `actual video bounds profiles preserve native orders full guards repeated calls and access ownership`() {
        val profiles = profiles().values.map(MarsFunctionCaseData::parse)
        assertEquals(listOf(22, 28, 22, 22, 22, 22, 22, 22), profiles.map { it.size })
        val cases = profiles.flatten()
        assertEquals(182, cases.size)
        assertEquals(182, cases.map { it.name }.distinct().size)
        assertEquals(192, cases.sumOf { it.calls.size })
        assertEquals(91, cases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN })
        assertEquals(91, cases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
        assertTrue(cases.all { it.instructionLimit == 1_000_000L && it.stackAlignment == 16 && it.preservedRegisters.size == 9 })
        assertEquals(2, cases.count { it.calls.size == 6 })
        assertEquals(16, cases.count { it.name.startsWith("invalid dimensions ") })
        assertEquals(8, cases.count { it.name.startsWith("misaligned ") })
        for (case in cases) for (call in case.calls) {
            val access = requireNotNull(call.callerAccess)
            assertEquals(6, access.objects.size)
            assertEquals(100_000, access.maxAccesses)
            for ((label, length) in mapOf("bounds_previous" to 16416, "bounds_current" to 16416, "bounds_settings" to 32)) {
                assertEquals(length, call.setupMemory.filter { it.label == label }.sumOf { it.byteLength })
            }
            assertTrue(access.readable.none { it.at.label == "bounds_result" })
            assertTrue(access.writable.all { it.at.label == "bounds_result" && it.byteLength == 32 })
            if (case.name.startsWith("misaligned settings ")) assertTrue(access.readable.isEmpty())
        }
    }

    @Test
    fun `one final assessment combines all eight bounded profiles without reducing the original budgets`() {
        val profiles = profiles()
        val paths = profiles.keys.joinToString(",") { "\"$it\"" }
        val lesson = """{"formatVersion":11,"introduction":"Compare changed pixels.","conclusion":"Explain minimal bounds.","steps":[{
            "kind":"assessment","id":"all-bounds","explanation":"Check the complete source.","source":"independent/bounds.asm",
            "caseProfiles":[$paths],"checkKind":"function","success":"All public contracts pass."}]}"""
        val plan = MarsNativeLessonPlan.parse(lesson.toByteArray()) { profiles.getValue(it) }
        val step = plan.steps.single() as MarsNativeAssessmentStep
        assertEquals(8, step.functionCaseBatches.size)
        assertEquals(182, step.functionCases.size)
        assertEquals(192, step.functionCases.sumOf { it.calls.size })
        assertEquals(step.functionCases, step.functionCaseBatches.flatten())
        assertEquals(182_000_000L, step.functionCases.sumOf { it.instructionLimit })
    }
}
