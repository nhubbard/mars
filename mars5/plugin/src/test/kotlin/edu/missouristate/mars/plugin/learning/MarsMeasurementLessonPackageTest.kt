package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsMeasurementLessonPackageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual measurement archive binds five observation sources and complete native matvec policies`() {
        val archive = directory.resolve("student.zip")
        javaClass.getResourceAsStream("/learning/measuring-programs-0.1.0-student.zip")!!.use { Files.copy(it, archive) }
        MarsLessonPackages.prepare(archive).use { prepared ->
            assertEquals("022-measuring-programs", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(9, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(listOf(5, 3, 5, 3, 3), debug.map { it.stops.size })
            assertEquals(5, debug.map { it.source }.distinct().size)
            val runs = plan.steps.filterIsInstance<MarsNativeRunStep>()
            assertEquals(5, runs.size)
            assertEquals(debug.map { it.source }.toSet(), runs.map { it.source }.toSet())
            val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf("matrix-vector", "caller-exit"), assessments.map { it.id })
            assertEquals(1, assessments.map { it.source }.distinct().size)
            val cases = assessments.first().functionCases
            assertEquals(11, cases.size)
            assertEquals(21, cases.sumOf { it.calls.size })
            for (case in cases.filter { it.calls.first().entry == "matvec" }) {
                assertEquals(listOf("function_return", "matvec_done"), case.calls.map { it.returnTo })
                assertEquals(9, case.preservedRegisters.size)
                val output = case.initializedOutput.single()
                val policy = case.accessPolicies.single()
                assertEquals("matrix_guard", policy.from.label)
                assertEquals("completed", policy.until.label)
                assertEquals(4, policy.until.offset)
                assertEquals(4, policy.readable.size)
                assertEquals(output.at, policy.writable.single().at)
                assertEquals(output.byteLength, policy.writable.single().byteLength)
                assertEquals(case.calls.first().expectedRegisters.getValue("v0") * 8, output.byteLength.toLong())
            }
            val wide = cases.single { it.name == "positive 2^31" }.calls.first()
            assertTrue(wide.memory.single { it.label == "output" }.bytes.startsWith("0000008000000000"))
            val caller = cases.single { it.calls.singleOrNull()?.entry == "main" }
            assertEquals("done", caller.calls.single().returnTo)
            assertEquals(listOf("output", "completed"), caller.accessPolicies.single().writable.map { it.at.label })
            assertEquals("02000000", caller.calls.single().memory.single { it.label == "completed" }.bytes)
            assertTrue(assessments.last().functionCases.isEmpty())
            assertEquals("", assessments.last().cases.single().expectedOutput)
        }
    }
}
