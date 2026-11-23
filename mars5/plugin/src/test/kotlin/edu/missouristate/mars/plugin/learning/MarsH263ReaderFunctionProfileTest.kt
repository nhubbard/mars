package edu.missouristate.mars.plugin.learning

import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsH263ReaderFunctionProfileTest {
    @TempDir lateinit var directory: Path
    private fun profiles(): Map<String, ByteArray> {
        val path = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/h263-reader-profiles-v22.zip")!!.use { Files.copy(it, path) }
        return ZipFile(path.toFile()).use { zip -> zip.entries().asSequence().associate { entry ->
            assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
            entry.name to zip.getInputStream(entry).use { it.readBytes() }
        } }
    }

    @Test
    fun `actual reader profiles retain all widths phases full metadata and repeated cursor calls`() {
        val cases = profiles().values.flatMap(MarsFunctionCaseData::parse)
        assertEquals(592, cases.size)
        assertEquals(592, cases.map { it.name }.distinct().size)
        assertEquals(598, cases.sumOf { it.calls.size })
        assertEquals(296, cases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN })
        assertEquals(296, cases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
        assertTrue(cases.all { it.instructionLimit == 100_000L && it.stackAlignment == 16 && it.preservedRegisters.size == 10 })
        for (case in cases) for (call in case.calls) {
            val access = requireNotNull(call.callerAccess)
            assertTrue(access.objects.any { it.at.label == "reader_bytes_tail" && it.byteLength == 64 })
            assertEquals(1, call.accessPolicies.size)
            assertEquals(1048576, call.accessPolicies.single().until.offset)
            assertEquals(access.readable, call.accessPolicies.single().readable)
            assertEquals(access.writable, call.accessPolicies.single().writable)
            if (call.expectedRegisters.getValue("v0") != 0L || call.arguments.getValue("a1") == 0L) {
                assertTrue(access.writable.isEmpty())
                assertTrue(call.memory.isEmpty())
            } else {
                assertEquals("reader_context", access.writable.single().at.label)
                assertEquals(24, access.writable.single().at.offset)
                assertEquals(8, access.writable.single().byteLength)
            }
        }
        for (case in cases.filter { it.calls.size == 4 }) {
            assertEquals(listOf(5L, 419L, 0L, 0L), case.calls.map { it.expectedRegisters.getValue("v1") })
            assertEquals(1, case.calls.first().setupAddressMemory.size)
            assertTrue(case.calls.drop(1).all { it.setupAddressMemory.isEmpty() && it.setupMemory.isEmpty() })
        }
    }

    @Test
    fun `two final bounded assessments cover all 592 original reader cases without weakening budgets`() {
        val profiles = profiles()
        val steps = listOf("big", "little").joinToString(",") { order ->
            val paths = profiles.keys.filter { !it.startsWith("fields-") || it.startsWith("fields-$order-") }.joinToString(",") { "\"$it\"" }
            """{"kind":"assessment","id":"all-$order","explanation":"Check the retained reader source.","source":"independent/reader.asm",
                "caseProfiles":[$paths],"checkKind":"function","success":"All selected contracts pass."}"""
        }
        val lesson = """{"formatVersion":11,"introduction":"Inspect MSB-first fields.","conclusion":"Explain cursor publication.","steps":[$steps]}"""
        val plan = MarsNativeLessonPlan.parse(lesson.toByteArray()) { profiles.getValue(it) }
        val final = plan.steps.map { it as MarsNativeAssessmentStep }
        assertEquals(listOf(328, 328), final.map { it.functionCases.size })
        assertEquals(listOf(334, 334), final.map { it.functionCases.sumOf { c -> c.calls.size } })
        assertEquals(592, final.flatMap { it.functionCases }.map { it.name }.distinct().size)
        for (step in final) {
            assertEquals(6, step.functionCaseBatches.size)
            assertEquals(step.functionCases, step.functionCaseBatches.flatten())
            assertEquals(32_800_000L, step.functionCases.sumOf { it.instructionLimit })
        }
    }
}
