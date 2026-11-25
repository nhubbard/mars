package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsLosslessLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/lossless-video-0.1.0-student.zip")!!.use {
          Files.copy(it, path)
        }
      }

  @Test
  fun `actual lossless package retains all bounds cases and distinguishes history cost count and area`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("034-lossless-video", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(24, plan.steps.size)
      assertEquals(12, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(6, debug.size)
      assertEquals(18, debug.sumOf { it.stops.size })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val stages = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(44, 66, 44, 28, 182), stages.map { it.functionCases.size })
      assertEquals(8, stages.flatMap { it.caseProfiles }.distinct().size)
      assertTrue(stages.all { it.source == "independent/video-change-bounds.asm" })
      assertTrue(stages.all { it.functionCaseBatches.flatten() == it.functionCases })
      assertEquals(
          stages.dropLast(1).flatMap { it.functionCases }.toSet(),
          stages.last().functionCases.toSet(),
      )
      assertEquals(192, stages.last().functionCases.sumOf { it.calls.size })
      assertEquals(91, stages.last().functionCases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
      assertEquals(
          91,
          stages.last().functionCases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN },
      )
      assertTrue(
          stages.last().functionCases.all {
            it.instructionLimit == 1_000_000L && it.stackAlignment == 16
          },
      )
      val xor = debug.single { it.id == "observe-xor" }.stops
      assertEquals(0x0000000080000000L, xor.first().vectorRegisters.getValue($$"$w2").low)
      assertEquals(
          0xffffffff00000010uL.toLong(),
          xor.first().vectorRegisters.getValue($$"$w2").high,
      )
      assertEquals(listOf(3L, 4L, 0L), xor.map { it.registers.getValue($$"$v1") })
      assertEquals(
          listOf(40L, 20L, 0L),
          debug.single { it.id == "observe-encoding" }.stops.map { it.registers.getValue($$"$v1") },
      )
      val seek = debug.single { it.id == "observe-seek" }.stops[1]
      assertEquals(
          listOf(6L, 4L, 3L),
          listOf($$"$s0", $$"$s1", $$"$s2").map(seek.registers::getValue),
      )
      val geometry = debug.single { it.id == "observe-coordinates" }.stops
      assertEquals(6L, geometry[1].registers.getValue($$"$s6"))
      assertEquals(2L, geometry[1].registers.getValue($$"$s7"))
      assertEquals(0x100000001L, geometry.last().registers.getValue($$"$s0"))
    }
  }

  @Test
  fun `every lossless prediction and helper boundary resolves through native PSI in the imported source`() =
      timeoutRunBlocking {
        withContext(Dispatchers.IO) {
          MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            for (activity in content.plan.steps) {
              val source = content.source(activity)
              assertTrue(Files.isRegularFile(source))
              val anchors =
                  if (activity is MarsNativeDebugStep) activity.stops.map { it.anchor }
                  else listOfNotNull(activity.anchor)
              if (anchors.isEmpty()) continue
              val text = Files.readString(source)
              withContext(Dispatchers.EDT) {
                fixture.configureByText(source.fileName.toString(), text)
                for (anchor in anchors) {
                  val range =
                      requireNotNull(MarsLessonAnchors.range(fixture.editor, anchor)) {
                        "$source: $anchor"
                      }
                  assertEquals(
                      anchor.mnemonic,
                      text
                          .substring(range.startOffset, range.endOffset)
                          .substringBefore(' ')
                          .substringBefore('\t'),
                  )
                }
              }
            }
            val independent =
                Files.readString(installed.directory.resolve("independent/video-change-bounds.asm"))
            assertEquals(1, independent.lines().count { it.trim() == "break" })
            assertTrue(independent.endsWith("video_change_bounds:\n    break\n"))
          }
        }
      }
}
