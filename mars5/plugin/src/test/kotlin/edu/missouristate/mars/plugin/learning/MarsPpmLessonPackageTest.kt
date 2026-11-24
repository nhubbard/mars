package edu.missouristate.mars.plugin.learning

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsPpmLessonPackageTest {
  @TempDir lateinit var directory: Path

  @Test
  fun `actual PPM archive retains worked boundaries real input and complete independent allowances`() {
    val archive = directory.resolve("student.zip")
    javaClass.getResourceAsStream("/learning/ppm-0.1.0-student.zip")!!.use {
      Files.copy(it, archive)
    }
    MarsLessonPackages.prepare(archive).use { prepared ->
      assertEquals("029-binary-ppm", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(10, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(12, debug.size)
      assertEquals(23, debug.sumOf { it.stops.size })
      assertEquals(3, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      assertEquals(12, debug.map { it.source }.distinct().size)
      val pipeline = debug.single { it.id == "trace-pipeline" }
      assertEquals(5, pipeline.stops.size)
      assertEquals(3L, pipeline.stops.first().registers.getValue("\$s3"))
      assertEquals(23L, pipeline.stops.last().registers.getValue("\$v1"))
      assertEquals("results_ready", pipeline.stops.last().anchor.label)
      for (step in debug) for (stop in step.stops.filter { it.anchor.mnemonic == "syscall" }) {
        assertEquals(10L, stop.registers.getValue("\$v0"))
      }
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(5, assessments.size)
      assertEquals(listOf(27, 27, 2, 1), assessments.dropLast(1).map { it.caseProfiles.size })
      assertEquals(listOf(80, 80, 6, 1), assessments.dropLast(1).map { it.functionCases.size })
      val cases = assessments.flatMap { it.functionCases }
      assertEquals(167, cases.size)
      assertEquals(168, cases.sumOf { it.calls.size })
      assertEquals(161, cases.count { it.instructionLimit == 3_000_000L })
      assertEquals(6, cases.count { it.instructionLimit == 100_000L })
      for (case in cases) {
        assertEquals(4096, case.initialRegisterAddresses.getValue("sp").offset)
        assertEquals(13, case.accessPolicies.size)
      }
      val exit = assessments.last().cases.single()
      assertEquals(3_000_000L, exit.instructionLimit)
      assertEquals("", exit.expectedOutput)
      assertTrue(assessments.last().functionCases.isEmpty())
    }
    ZipFile(archive.toFile()).use { zip ->
      val input =
          zip.getInputStream(zip.getEntry("029-binary-ppm/worked/input.ppm")).use { it.readBytes() }
      assertEquals(23, input.size)
      assertEquals("P6\n2 2\n255\n", input.take(11).toByteArray().toString(Charsets.US_ASCII))
      assertTrue(zip.entries().asSequence().none { it.name.endsWith("output.ppm") })
    }
  }
}
