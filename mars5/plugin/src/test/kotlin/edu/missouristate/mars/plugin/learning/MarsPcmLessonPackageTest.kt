package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsPcmLessonPackageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual PCM archive binds signed mask and alias observations with all public subtraction cases`() {
        val archive = directory.resolve("student.zip")
        javaClass.getResourceAsStream("/learning/pcm-mixing-0.1.0-student.zip")!!.use { Files.copy(it, archive) }
        MarsLessonPackages.prepare(archive).use { prepared ->
            assertEquals("020-pcm-mixing", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(8, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(listOf(3, 7, 4, 2, 2, 2, 2), debug.map { it.stops.size })
            assertEquals(7, debug.map { it.source }.distinct().size)
            val mask = debug.single { it.id == "trace-mask-nine" }
            val lanes = mask.stops.single { it.id == "mask" }.vectorRegisters.getValue("\$w4")
            assertEquals(listOf(0L, 0L, 65535L, 0L, 65535L, 65535L, 65535L, 0L),
                (0..7).map { lanes.unsignedLane(it, 16) })
            assertEquals(VectorRegister(16, 48), mask.stops.single { it.id == "population" }.vectorRegisters.getValue("\$w5"))
            assertEquals(4L, mask.stops.single { it.id == "tail" }.registers.getValue("\$v1"))
            assertEquals(5L, mask.stops.last().registers.getValue("\$v1"))
            val signed = debug.single { it.id == "trace-signed-one" }.stops.first()
            assertEquals(-30000L, signed.registers.getValue("\$t0"))
            assertEquals(-10000L, signed.registers.getValue("\$t1"))
            val runs = plan.steps.filterIsInstance<MarsNativeRunStep>()
            assertEquals(debug.map { it.source }, runs.map { it.source })
            assertTrue(runs.all { it.stdout.isEmpty() && it.integerInputs.isEmpty() })
            val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(6, assessments.size)
            assertEquals(setOf("independent/pcm-subtract.asm"), assessments.map { it.source }.toSet())
            assertEquals(listOf(73, 65, 65, 65, 65), assessments.dropLast(1).map { it.functionCases.size })
            val cases = assessments.flatMap { it.functionCases }
            assertEquals(333, cases.size)
            assertEquals(337, cases.sumOf { it.calls.size })
            for (case in cases) {
                assertEquals(9, case.preservedRegisters.size)
                assertTrue(case.initializedOutput.isEmpty())
                assertEquals("pcm_a_guard", case.accessPolicies.single().from.label)
                assertEquals("caller_end", case.accessPolicies.single().until.label)
                assertEquals(4, case.accessPolicies.single().until.offset)
            }
            val reuse = cases.single { it.calls.size == 4 }
            assertEquals(listOf(8L, 1L, 8L, 0L), reuse.calls.map { it.arguments.getValue("a3") })
            assertEquals(listOf(6L, 0L, 6L, 0L), reuse.calls.map { it.expectedRegisters.getValue("v1") })
            val caller = cases.single { it.calls.first().entry == "main" }
            assertEquals(listOf("subtract_ready", "results_ready"), caller.calls.map { it.returnTo })
            assertEquals(listOf("63000000", "05000000"), caller.calls.map { call -> call.memory.single { it.label == "clipped" }.bytes })
            assertTrue(assessments.last().functionCases.isEmpty())
            assertEquals("", assessments.last().cases.single().expectedOutput)
        }
    }
}
