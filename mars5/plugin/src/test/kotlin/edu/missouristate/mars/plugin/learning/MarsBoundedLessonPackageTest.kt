package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsBoundedLessonPackageTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual bounded archive retains all input families full pointers and both continuation paths`() {
    val archive = directory.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/bounded-interfaces-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("013-bounded-interfaces", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(7, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(listOf(8, 2, 2), debug.map { it.stops.size })
      assertEquals(
          listOf(null, "worked/packet-short.asm", "worked/packet-checksum.asm"),
          debug.map { it.source },
      )
      assertEquals(3, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf("line-bounds", "line-bytes", "caller-exit"), assessments.map { it.id })
      assertEquals(1, assessments.map { it.source }.distinct().size)
      assertEquals(listOf(139, 256), assessments.take(2).map { it.functionCases.size })
      val cases = assessments.take(2).flatMap { it.functionCases }
      assertEquals(789, cases.sumOf { it.calls.size })
      for (case in cases.filter { it.calls.first().entry == "read_line" }) {
        assertEquals(listOf("line_function_return", "line_done"), case.calls.map { it.returnTo })
        assertEquals("line_function_return", case.noExecution.single().from)
        assertEquals("line_done", case.noExecution.single().until)
        assertEquals(1, case.storeBounds.size)
        assertEquals(264, case.accessBounds.single().byteLength)
        assertTrue(case.protectCallerStack)
        assertEquals(10, case.preservedRegisters.size)
        for (call in case.calls) {
          if (call.expectedRegisters.getValue("v0") > 0) {
            assertEquals(
                call.argumentAddresses.getValue("a0"),
                call.expectedAddresses.getValue("v1"),
            )
            assertEquals(8, case.storeBounds.single().permittedByteLength)
          } else {
            assertEquals(0L, call.expectedRegisters.getValue("v1"))
            assertEquals(0, case.storeBounds.single().permittedByteLength)
          }
        }
      }
      val byteCases = assessments[1].functionCases
      assertEquals(
          (0..255).toSet(),
          byteCases.map { it.name.removePrefix("one byte ").toInt() }.toSet(),
      )
      val caller = cases.single { it.calls.singleOrNull()?.entry == "main" }.calls.single()
      assertEquals("line_done", caller.returnTo)
      assertEquals("payload_address", caller.addressMemory.single().at.label)
      assertEquals("input", caller.addressMemory.single().value.label)
      assertEquals(caller.expectedAddresses.getValue("v1"), caller.addressMemory.single().value)
      assertTrue(assessments.last().functionCases.isEmpty())
      assertEquals("", assessments.last().cases.single().expectedOutput)
    }
  }
}
