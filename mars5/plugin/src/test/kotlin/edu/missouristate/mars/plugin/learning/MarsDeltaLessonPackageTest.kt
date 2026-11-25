package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsDeltaLessonPackageTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual scanner archive retains complete original budgets bounded batches and per-call read-only state`() {
    val archive = directory.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/delta-varint-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("023-delta-varint", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(10, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(11, debug.size)
      assertEquals(25, debug.sumOf { it.stops.size })
      assertEquals(11, debug.map { it.source }.distinct().size)
      val rejected = debug.single { it.id == "trace-redundant-zero" }.stops.first().registers
      assertEquals(3L, rejected.getValue($$"$t0"))
      assertEquals(0L, rejected.getValue($$"$t1"))
      for (name in listOf($$"$s4", $$"$s5", $$"$s6")) assertEquals(1L, rejected.getValue(name))
      val range = debug.single { it.id == "trace-range-failure" }.stops.first().registers
      assertEquals(8589934591L, range.getValue($$"$t1"))
      assertEquals(-4294967296L, range.getValue($$"$t6"))
      assertEquals(3, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(4, assessments.size)
      assertEquals(listOf(27, 4, 1), assessments.dropLast(1).map { it.caseProfiles.size })
      assertEquals(listOf(264, 32, 2), assessments.dropLast(1).map { it.functionCases.size })
      for (step in assessments.dropLast(1)) {
        assertEquals(step.functionCases, step.functionCaseBatches.flatten())
        assertTrue(
            step.functionCaseBatches.all {
              it.size <= 10 && it.sumOf { case -> case.instructionLimit } <= 10_000_000
            },
        )
        assertTrue(step.functionCases.all { it.instructionLimit == 1_000_000L })
      }
      val cases = assessments.flatMap { it.functionCases }
      assertEquals(298, cases.size)
      assertEquals(303, cases.sumOf { it.calls.size })
      val repeated = cases.single { it.calls.size == 5 }
      assertEquals(
          listOf(1L, 5L, 0L, 2L, 0L),
          repeated.calls.map { it.expectedRegisters.getValue("v1") },
      )
      assertEquals(
          listOf(3, 8, 0, 2, 0),
          repeated.calls.map { it.accessPolicies.single().readable.single().byteLength },
      )
      for (case in cases.filter { it.calls.first().entry != "main" }) {
        for (call in case.calls) {
          val policy = call.accessPolicies.single()
          assertTrue(policy.writable.isEmpty())
          assertEquals(call.argumentAddresses.getValue("a0"), policy.readable.single().at)
          assertEquals(case.initialMemory, call.memory)
        }
        assertEquals(9, case.preservedRegisters.size)
      }
      assertTrue(assessments.last().functionCases.isEmpty())
      assertEquals("", assessments.last().cases.single().expectedOutput)
    }
  }
}
