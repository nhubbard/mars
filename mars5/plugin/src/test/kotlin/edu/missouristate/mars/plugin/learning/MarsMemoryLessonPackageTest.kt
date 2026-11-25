package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The actual public draft archive, exercising the same validation used before import preview. */
class MarsMemoryLessonPackageTest {
  @TempDir lateinit var temporary: Path

  @Test
  fun `memory unit binds guided register boundaries and separate final memory exercises before import`() {
    val archive = temporary.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/memory-pixel-0.2.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("002-single-pixel", prepared.metadata.id)
      assertEquals("0.2.0", prepared.metadata.version)
      val plan = requireNotNull(prepared.nativeLesson)
      assertEquals(4, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>().single()
      assertTrue(debug.integerInputs.isEmpty())
      assertEquals(
          listOf("before-word-store", "before-byte-store", "after-byte-store", "after-reloads"),
          debug.stops.map { it.id },
      )
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(
          listOf("independent-red-component", "independent-copy"),
          assessments.map { it.id },
      )
      for ((index, assessment) in assessments.withIndex()) {
        assertEquals(6, assessment.cases.size)
        for (case in assessment.cases) {
          assertEquals("Enter an RGB integer: ", case.expectedOutput)
          assertEquals(0x10010000L, case.memory.single().address)
          assertEquals(if (index == 0) 8 else 16, case.memory.single().byteLength)
        }
      }
      val installed = prepared.install(Files.createDirectory(temporary.resolve("project")))
      assertTrue(Files.isRegularFile(installed.entry))
      for ((_, _, source, caseProfiles) in assessments) {
        assertTrue(Files.isRegularFile(installed.directory.resolve(source)))
        assertTrue(
            Files.isRegularFile(installed.directory.resolve(caseProfiles.single())),
        )
      }
    }
  }
}
