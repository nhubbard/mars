package edu.missouristate.mars.plugin.learning

import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsH263ReconstructFunctionProfileTest {
    @TempDir lateinit var directory: Path

    private fun profiles(): Map<String, ByteArray> {
        val path = directory.resolve("profiles.zip")
        javaClass.getResourceAsStream("/learning/h263-reconstruct-profiles-v21.zip")!!.use { Files.copy(it, path) }
        return ZipFile(path.toFile()).use { zip ->
            zip.entries().asSequence().toList().associate { entry ->
                assertTrue(entry.size in 1..MarsFunctionCaseData.MAX_BYTES.toLong())
                entry.name to zip.getInputStream(entry).use { it.readBytes() }
            }
        }
    }

    @Test
    fun `actual H263 reconstruction profiles preserve signed sample rejection row ownership and both orders`() {
        val profiles = profiles().values.map(MarsFunctionCaseData::parse)
        assertEquals(listOf(24, 36, 16, 2, 60), profiles.map { it.size })
        val cases = profiles.flatten()
        assertEquals(138, cases.size)
        assertEquals(138, cases.map { it.name }.distinct().size)
        assertEquals(146, cases.sumOf { it.calls.size })
        assertEquals(69, cases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN })
        assertEquals(69, cases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
        assertTrue(cases.all { it.instructionLimit == 100_000L && it.stackAlignment == 16 && it.preservedRegisters.size == 10 })
        assertTrue(cases.all { "gp" in it.preservedRegisters && "fp" in it.preservedRegisters })
        assertEquals(2, cases.count { it.calls.size == 5 })
        val lateAliases = cases.filter { it.name.startsWith("invalid reconstruction retains aliased prediction ") }
        assertEquals(8, lateAliases.size)
        for (case in cases) for (call in case.calls) {
            val access = requireNotNull(call.callerAccess)
            assertEquals(6, access.objects.size)
            assertEquals(10_000, access.maxAccesses)
            for ((label, size) in mapOf("recon_prediction" to 28720, "recon_inverse" to 28752, "recon_settings" to 40)) {
                assertEquals(size, call.setupMemory.filter { it.label == label }.sumOf { it.byteLength })
            }
            assertTrue(access.readable.none { it.at.label == "recon_output" })
            when (call.expectedRegisters.getValue("v0")) {
                0L -> {
                    assertEquals(17, access.readable.size)
                    assertEquals(8, access.writable.size)
                    assertTrue(access.writable.all { it.byteLength == 8 })
                    assertEquals(64, call.memory.sumOf { it.byteLength })
                }
                1L -> {
                    assertTrue(access.writable.isEmpty())
                    assertTrue(access.readable.all { it.at.label == "recon_settings" })
                }
                2L -> {
                    assertEquals(17, access.readable.size)
                    assertTrue(access.writable.isEmpty())
                    assertTrue(call.memory.isEmpty())
                }
                else -> error("Unexpected reconstruction status")
            }
        }
        assertEquals(-4L, cases.single { it.name == "reconstruct invalid prediction-wrap little=true" }
            .calls.single().arguments.getValue("a0"))
        for (case in lateAliases) {
            val call = case.calls.single()
            assertEquals(call.argumentAddresses.getValue("a0"), call.argumentAddresses.getValue("a2"))
            assertEquals(2L, call.expectedRegisters.getValue("v0"))
        }
    }

    @Test
    fun `final reconstruction assessment combines every original case and repeated call without weaker budgets`() {
        val profiles = profiles()
        val paths = profiles.keys.joinToString(",") { "\"$it\"" }
        val lesson = """{"formatVersion":11,"introduction":"Inspect signed reconstruction.","conclusion":"Explain publication.","steps":[{
            "kind":"assessment","id":"all-reconstruction","explanation":"Check this completed source.","source":"independent/reconstruct.asm",
            "caseProfiles":[$paths],"checkKind":"function","success":"All public contracts pass."}]}"""
        val plan = MarsNativeLessonPlan.parse(lesson.toByteArray()) { profiles.getValue(it) }
        val step = plan.steps.single() as MarsNativeAssessmentStep
        assertEquals(5, step.functionCaseBatches.size)
        assertEquals(138, step.functionCases.size)
        assertEquals(146, step.functionCases.sumOf { it.calls.size })
        assertEquals(step.functionCases, step.functionCaseBatches.flatten())
        assertEquals(13_800_000L, step.functionCases.sumOf { it.instructionLimit })
    }
}
