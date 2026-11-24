package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsMsaLessonPackageTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual MSA archive binds complete vector observations and independent RGB555 interfaces`() {
    val archive = directory.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/working-with-msa-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("019-working-with-msa", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(9, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(listOf(8, 5, 4, 5, 4, 5), debug.map { it.stops.size })
      assertEquals(
          listOf(
              "rgb-formats.asm",
              "worked/count-17.asm",
              "worked/count-0.asm",
              "worked/count-7.asm",
              "worked/count-8.asm",
              "worked/count-9.asm",
          ),
          debug.map { it.source },
      )
      val gathered = debug.first().stops.single { it.id == "gathered" }
      assertEquals(
          VectorRegister(0x008000ff0000007bL, 0x001f00f800080007L),
          gathered.vectorRegisters.getValue("\$w4"),
      )
      val fields = debug.first().stops.single { it.id == "fields" }
      assertEquals(0x7968L, fields.vectorRegisters.getValue("\$w4").low and 0xffffL)
      assertEquals(
          24L,
          debug.first().stops.single { it.id == "second-loaded" }.registers.getValue("\$a2"),
      )
      for ((step, count) in debug.zip(listOf(32, 17, 0, 7, 8, 9))) {
        assertEquals(count.toLong(), step.stops.last().registers.getValue("\$v0"))
        if (count != 32)
            assertEquals((count % 8).toLong(), step.stops.first().registers.getValue("\$a2"))
      }
      val runs = plan.steps.filterIsInstance<MarsNativeRunStep>()
      assertEquals(debug.map { it.source }, runs.map { it.source })
      assertTrue(runs.all { it.stdout.isEmpty() && it.integerInputs.isEmpty() })
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf("rgb555", "caller-exit"), assessments.map { it.id })
      assertEquals(setOf("independent/rgb555.asm"), assessments.map { it.source }.toSet())
      val cases = assessments.first().functionCases
      assertEquals(34, cases.size)
      assertEquals(67, cases.sumOf { it.calls.size })
      for ((case, count) in cases.take(33).zip(0..32)) {
        assertEquals(listOf("function_return", "pack555_done"), case.calls.map { it.returnTo })
        assertEquals(count.toLong(), case.calls.first().expectedRegisters.getValue("v0"))
        assertEquals(9, case.preservedRegisters.size)
        assertTrue(case.initializedOutput.isEmpty())
        val policy = case.accessPolicies.single()
        assertEquals("rgb_guard", policy.from.label)
        assertEquals("completed", policy.until.label)
        assertEquals(4, policy.until.offset)
        assertEquals(listOf(count * 3, count * 2), policy.readable.map { it.byteLength })
        assertEquals(listOf(count * 2), policy.writable.map { it.byteLength })
      }
      val caller = cases.last()
      assertEquals("main", caller.calls.single().entry)
      assertEquals("done", caller.calls.single().returnTo)
      assertEquals(
          listOf("packed", "completed"),
          caller.accessPolicies.single().writable.map { it.at.label },
      )
      assertEquals(
          "08000000",
          caller.calls.single().memory.single { it.label == "completed" }.bytes,
      )
      assertTrue(assessments.last().functionCases.isEmpty())
      assertEquals("", assessments.last().cases.single().expectedOutput)
    }
  }
}
