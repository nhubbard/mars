package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsFirLessonPackageTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `actual FIR archive retains exact wide sums all original cases and even phase split checks`() {
        val archive = directory.resolve("student.zip")
        javaClass.getResourceAsStream("/learning/fixed-point-fir-0.1.0-student.zip")!!.use { Files.copy(it, archive) }
        MarsLessonPackages.prepare(archive).use { prepared ->
            assertEquals("021-fixed-point-fir", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(9, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(listOf(3, 2, 3, 7, 3, 3, 3, 3, 2, 3), debug.map { it.stops.size })
            assertEquals(10, debug.map { it.source }.distinct().size)
            val wide = debug.single { it.id == "trace-wide-sum" }
            val words = wide.stops.first().vectorRegisters.getValue("\$w5")
            assertEquals(List(4) { 0xffff8000L }, (0..3).map { words.unsignedLane(it, 32) })
            assertEquals(VectorRegister(2147483648, 2147483648), wide.stops.single { it.id == "pairs" }.vectorRegisters.getValue("\$w7"))
            assertEquals(VectorRegister(4294967296, 4294967296), wide.stops.single { it.id == "combined" }.vectorRegisters.getValue("\$w7"))
            assertEquals(8589934592L, wide.stops.single { it.id == "numerator" }.registers.getValue("\$t0"))
            assertEquals(262144L, wide.stops.single { it.id == "quantized" }.registers.getValue("\$t0"))
            for ((id, value, clips) in listOf(Triple("negative-tie", -32768L, 0L), Triple("negative-below", -32769L, 1L), Triple("positive-tie", 32768L, 1L))) {
                val step = debug.single { it.id == "trace-$id" }
                assertEquals(value, step.stops.single { it.id == "quantized" }.registers.getValue("\$t0"))
                assertEquals(clips, step.stops.last().registers.getValue("\$v1"))
            }
            val runs = plan.steps.filterIsInstance<MarsNativeRunStep>()
            assertEquals(listOf("worked/scalar-impulse.asm", "fir-filter.asm", "worked/split-five.asm"), runs.map { it.source })
            assertTrue(runs.all { it.stdout.isEmpty() && it.integerInputs.isEmpty() })
            val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(5, assessments.size)
            assertEquals(setOf("independent/fir-decimate.asm"), assessments.map { it.source }.toSet())
            assertEquals(listOf(43, 36, 34, 33), assessments.dropLast(1).map { it.functionCases.size })
            val cases = assessments.flatMap { it.functionCases }
            assertEquals(146, cases.size)
            assertEquals(155, cases.sumOf { it.calls.size })
            for (case in cases) {
                assertEquals(9, case.preservedRegisters.size)
                assertTrue(case.initializedOutput.isEmpty())
                assertEquals("function_return", case.noExecution.single().from)
                assertEquals("done", case.noExecution.single().until)
                assertEquals("source_guard", case.accessPolicies.single().from.label)
                assertEquals("caller_end", case.accessPolicies.single().until.label)
            }
            val splits = cases.filter { it.calls.size == 3 }
            assertEquals(4, splits.size)
            for (case in splits) {
                val cut = case.calls[1].arguments.getValue("a3").toInt()
                assertEquals(listOf(32L, cut.toLong(), (32 - cut).toLong()), case.calls.map { it.arguments.getValue("a3") })
                assertEquals(13 + 4 * cut, case.calls.last().argumentAddresses.getValue("a0").offset)
                for (call in case.calls) {
                    val count = call.arguments.getValue("a3").toInt()
                    val policy = call.accessPolicies.single()
                    assertEquals(listOf(if (count == 0) 0 else 4 * count + 12, if (count == 0) 0 else 16, 2 * count), policy.readable.map { it.byteLength })
                    assertEquals(call.argumentAddresses.getValue("a0"), policy.readable.first().at)
                    assertEquals(call.argumentAddresses.getValue("a2"), policy.writable.single().at)
                    assertEquals(2 * count, policy.writable.single().byteLength)
                }
                val output = case.calls.last().memory.single { it.label == "output" }.bytes
                assertEquals(output.substring(6, 134), output.substring(198, 326))
            }
            val clipped = splits.single { it.name.endsWith("family-2") }
            assertEquals(listOf(32L, 5L, 27L), clipped.calls.map { it.expectedRegisters.getValue("v1") })
            val caller = cases.single { it.calls.first().entry == "main" }
            assertEquals(listOf("output"), caller.calls.first().accessPolicies.single().writable.map { it.at.label })
            assertTrue(caller.calls.last().accessPolicies.single().readable.isEmpty())
            assertEquals(listOf("processed", "clipped"), caller.calls.last().accessPolicies.single().writable.map { it.at.label })
            assertTrue(assessments.last().functionCases.isEmpty())
            assertEquals("", assessments.last().cases.single().expectedOutput)
        }
    }
}
