package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsBase64LessonPackageTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual Base64 archive keeps full vector values symbolic stacks and every original budget`() {
    val archive = directory.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/base64-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("028-base64", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(10, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(12, debug.size)
      assertEquals(25, debug.sumOf { it.stops.size })
      val encoder = debug.single { it.id == "trace-direct-encode12" }
      val sextets = encoder.stops[1].vectorRegisters.getValue("\$w1")
      assertEquals(
          List(4) { listOf(19L, 22L, 5L, 46L) }.flatten(),
          (0..15).map { sextets.unsignedLane(it, 8) },
      )
      val ascii = encoder.stops[2].vectorRegisters.getValue("\$w4")
      assertEquals(
          "TWFu".repeat(4).map { it.code.toLong() },
          (0..15).map { ascii.unsignedLane(it, 8) },
      )
      val decoded =
          debug
              .single { it.id == "trace-direct-decode16" }
              .stops[1]
              .vectorRegisters
              .getValue("\$w7")
      assertEquals(
          "Man".repeat(4).map { it.code.toLong() } + List(4) { 0L },
          (0..15).map { decoded.unsignedLane(it, 8) },
      )
      for (step in debug) for (stop in step.stops.filter { it.anchor.mnemonic == "syscall" }) {
        assertEquals(10L, stop.registers.getValue("\$v0"))
        assertEquals("main_done", stop.anchor.label)
      }
      assertEquals(3, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(5, assessments.size)
      assertEquals(listOf(16, 17, 26, 2), assessments.dropLast(1).map { it.caseProfiles.size })
      assertEquals(listOf(160, 165, 256, 14), assessments.dropLast(1).map { it.functionCases.size })
      val cases = assessments.flatMap { it.functionCases }
      assertEquals(595, cases.size)
      assertEquals(600, cases.sumOf { it.calls.size })
      for (case in cases) {
        assertEquals(1_000_000L, case.instructionLimit)
        assertEquals("workspace", case.initialRegisterAddresses.getValue("sp").label)
        assertEquals(256, case.initialRegisterAddresses.getValue("sp").offset)
        assertTrue("sp" !in case.initialRegisters)
        assertEquals(9, case.preservedRegisters.size)
      }
      val reuse = cases.single { it.calls.size == 5 }
      assertEquals(
          listOf(3L, 0L, 0L, 1L, 0L),
          reuse.calls.map { it.expectedRegisters.getValue("v0") },
      )
      assertEquals(
          listOf(0L, 6L, 0L, 0L, 2L),
          reuse.calls.map { it.expectedRegisters.getValue("v1") },
      )
      assertEquals(
          listOf(0, 6, 0, 0, 2),
          reuse.calls.map { it.accessPolicies.single().writable.single().byteLength },
      )
      val invalidPointers = cases.filter {
        it.name.startsWith("URL unsigned count") ||
            it.name == "URL empty without pointer access" ||
            it.name == "URL capacity before reading source"
      }
      assertEquals(5, invalidPointers.size)
      for (case in invalidPointers) {
        val call = case.calls.single()
        assertEquals(1L, call.arguments.getValue("a0"))
        assertEquals(1L, call.arguments.getValue("a2"))
        assertTrue(
            (call.accessPolicies.single().readable + call.accessPolicies.single().writable).all {
              it.byteLength == 0
            },
        )
      }
      assertEquals("", assessments.last().cases.single().expectedOutput)
    }
  }
}
