package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsCrcLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { archive ->
        javaClass.getResourceAsStream("/learning/crc32-0.1.0-student.zip")!!.use {
          Files.copy(it, archive)
        }
      }

  @Test
  fun `actual CRC archive retains raw continuation read failure and complete independent contracts`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("024-crc32", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(13, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(8, debug.size)
      assertEquals(22, debug.sumOf { it.stops.size })
      assertEquals(6, debug.map { it.source }.distinct().size)
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      assertEquals(4, debug.count { it.fileRead != null })
      val error = debug.single { it.id == "trace-file-error" }
      assertEquals(5, error.fileRead!!.failAfterBytes)
      assertEquals(3, error.fileRead.maxReadSize)
      assertEquals(19, error.fileRead.firstDescriptor)
      assertEquals(listOf(3L, 2L, -1L, 0L), error.stops.map { it.registers.getValue("\$v0") })
      assertEquals(2L, error.stops.last().registers.getValue("\$v1"))
      assertTrue(debug.single { it.id == "trace-file-missing" }.fileRead!!.missing)
      val prefix = debug.single { it.id == "trace-stream" }
      assertEquals(0x641c1f5cL, prefix.stops[1].registers.getValue("\$a2"))
      assertEquals(5L, prefix.stops[1].registers.getValue("\$a1"))
      assertEquals(0x340bc6d9L, prefix.stops.last().registers.getValue("\$v0"))
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(80, 32, 23, 2, 27, 0), assessments.map { it.functionCases.size })
      assertEquals(listOf(8, 4, 3, 1, 7, 1), assessments.map { it.caseProfiles.size })
      assertTrue(assessments.all { it.source == "independent/crc32-chunk.asm" })
      assertEquals(164, assessments.sumOf { it.functionCases.size })
      assertEquals(170, assessments.sumOf { it.functionCases.sumOf { case -> case.calls.size } })
      for (assessment in assessments.dropLast(1)) {
        assertEquals(assessment.functionCases, assessment.functionCaseBatches.flatten())
        assertTrue(
            assessment.functionCaseBatches.all {
              it.sumOf { case -> case.instructionLimit } <= 10_000_000
            },
        )
      }
      val fileCases = assessments[4].functionCases
      assertEquals(28, fileCases.sumOf { it.calls.size })
      assertTrue(
          fileCases.all {
            it.fileRead!!.firstDescriptor == 19 && it.calls.all { call -> call.readFile }
          },
      )
      val exit = assessments.last().cases.single()
      assertEquals(100_000L, exit.instructionLimit)
      assertEquals("", exit.expectedOutput)
    }
  }

  @Test
  fun `every delivered source pointer and Debug stop resolves in the actual source through native PSI`() =
      timeoutRunBlocking {
        val archive = archive()
        val plan = MarsLessonPackages.prepare(archive).use { it.nativeLesson!! }
        ZipFile(archive.toFile()).use { zip ->
          for (activity in plan.steps) {
            val relative = (activity as? MarsNativeDebugStep)?.source ?: "crc32.asm"
            val anchors =
                if (activity is MarsNativeDebugStep) activity.stops.map { it.anchor }
                else listOfNotNull(activity.anchor)
            if (anchors.isEmpty()) continue
            val text =
                zip.getInputStream(zip.getEntry("024-crc32/$relative")).use {
                  it.readBytes().toString(Charsets.UTF_8)
                }
            withContext(Dispatchers.EDT) {
              fixture.configureByText(relative.substringAfterLast('/'), text)
              for (anchor in anchors) {
                val range =
                    requireNotNull(MarsLessonAnchors.range(fixture.editor, anchor)) {
                      "$relative: $anchor"
                    }
                assertTrue(
                    text.substring(range.startOffset, range.endOffset).startsWith(anchor.mnemonic),
                    "$relative: $anchor",
                )
              }
            }
          }
          val input =
              zip.getInputStream(zip.getEntry("024-crc32/worked/fixture.bin")).use {
                it.readBytes()
              }
          assertArrayEquals("123456789".toByteArray(), input)
          val starter =
              zip.getInputStream(zip.getEntry("024-crc32/independent/crc32-chunk.asm")).use {
                it.readBytes().toString(Charsets.UTF_8)
              }
          assertTrue(
              starter.contains("crc32_check_chunk:\n    break"),
              "The student archive must retain the unfinished checker",
          )
        }
      }
}
