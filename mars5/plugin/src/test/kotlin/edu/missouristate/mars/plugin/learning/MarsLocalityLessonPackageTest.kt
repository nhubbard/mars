package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsLocalityLessonPackageTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual locality archive binds both execution sources and complete repeated transfer contracts`() {
    val archive = directory.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/memory-locality-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("011-memory-locality", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      val quizzes = plan.steps.filterIsInstance<MarsNativeLessonStep>()
      assertEquals(6, quizzes.size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(listOf(4, 4), debug.map { it.stops.size })
      assertEquals(null, debug.first().source)
      assertEquals("worked/matrix-tiled.asm", debug.last().source)
      val runs = plan.steps.filterIsInstance<MarsNativeRunStep>()
      assertEquals(2, runs.size)
      assertEquals("worked/matrix-tiled.asm", runs.last().source)
      assertTrue(runs.all { it.stdout.isEmpty() && it.integerInputs.isEmpty() })
      val checks = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf("rotate-clockwise", "caller-exit"), checks.map { it.id })
      assertEquals(listOf("independent/matrix-rotate.asm"), checks.map { it.source }.distinct())
      val cases = checks.first().functionCases
      assertEquals(22, cases.size)
      assertEquals(43, cases.sumOf { it.calls.size })
      for (case in cases.dropLast(1)) {
        assertEquals(listOf("rotate_done", "rotate_exit"), case.calls.map { it.returnTo })
        val call = case.calls.first()
        val rows = call.arguments.getValue("a2").toInt()
        val columns = call.arguments.getValue("a3").toInt()
        assertTrue(rows in 0..16 && columns in 0..16)
        val transfer = case.transfers.single()
        assertEquals(4, transfer.elementBytes)
        assertEquals(
            (0 until rows).flatMap { i -> (0 until columns).map { j -> j * rows + rows - 1 - i } },
            transfer.destinationIndices,
        )
        assertEquals(call.argumentAddresses.getValue("a0"), transfer.source)
        assertEquals(call.argumentAddresses.getValue("a1"), transfer.destination)
        assertEquals(2, case.accessBounds.size)
        assertTrue(case.accessBounds.all { it.permittedByteLength == 4 * rows * columns })
        assertTrue(case.protectCallerStack)
        assertEquals(10, case.preservedRegisters.size)
        assertEquals(1024, call.memory.single { it.label == "output" }.byteLength)
      }
      val caller = cases.last().calls.single()
      assertEquals("main", caller.entry)
      assertEquals("rotate_exit", caller.returnTo)
      assertEquals(15L, caller.expectedRegisters.getValue("v0"))
      assertEquals(15, cases.last().transfers.single().destinationIndices.size)
      assertTrue(cases.last().noStores.none { it.label == "copied" })
      assertTrue(checks.last().functionCases.isEmpty())
      assertEquals("", checks.last().cases.single().expectedOutput)
    }
  }
}
