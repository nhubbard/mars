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
class MarsPngLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/png-0.1.0-student.zip")!!.use {
          Files.copy(it, path)
        }
      }

  @Test
  fun `actual PNG package retains all endian cases and distinguishes row extent from sample count`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("033-png", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(26, plan.steps.size)
      assertEquals(12, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(6, debug.size)
      assertEquals(18, debug.sumOf { it.stops.size })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val stages = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(44, 44, 44, 44, 44, 46, 266), stages.map { it.functionCases.size })
      assertEquals(6, stages.flatMap { it.caseProfiles }.distinct().size)
      assertTrue(stages.all { it.source == "independent/png-filter-scanline.asm" })
      assertTrue(stages.all { it.functionCaseBatches.flatten() == it.functionCases })
      assertEquals(stages.dropLast(1).flatMap { it.functionCases }, stages.last().functionCases)
      assertEquals(276, stages.last().functionCases.sumOf { it.calls.size })
      assertEquals(133, stages.last().functionCases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
      assertEquals(
          133,
          stages.last().functionCases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN },
      )
      val pipeline = debug.single { it.id == "observe-pipeline" }.stops
      assertEquals(listOf(46L, 35L, 35L, 40L, 40L), pipeline.map { it.registers.getValue("\$v1") })
      assertEquals(2L, pipeline.last().registers.getValue("\$s0"))
      assertEquals(5L, pipeline.last().registers.getValue("\$s1"))
      val wide = debug.single { it.id == "observe-native-settings" }.stops.first()
      assertEquals(0x100000001L, wide.registers.getValue("\$s0"))
      val packing = debug.single { it.id == "observe-vector-tail" }.stops
      assertEquals(0x0706050403020100L, packing.first().vectorRegisters.getValue("\$w0").low)
      assertEquals(0x0f0e0d0c0b0a0908L, packing.first().vectorRegisters.getValue("\$w0").high)
      assertEquals(0x0003040500000102L, packing[1].vectorRegisters.getValue("\$w3").low)
      assertEquals(0x002d2e2f002a2b2cL, packing[2].vectorRegisters.getValue("\$w3").high)
      val tail = packing.last()
      assertEquals(68L, tail.registers.getValue("\$v1"))
      assertEquals(0x00303132L, tail.registers.getValue("\$s2"))
    }
  }

  @Test
  fun `every PNG prediction pointer and decoder boundary resolves through native PSI`() =
      timeoutRunBlocking {
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
              Files.readString(installed.directory.resolve("independent/png-filter-scanline.asm"))
          assertEquals(1, independent.lines().count { it.trim() == "break" })
          assertTrue(independent.endsWith("png_filter_scanline:\n    break\n"))
        }
      }
}
