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
class MarsH263PredictionLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/h263-prediction-0.1.0-student.zip")!!.use {
          Files.copy(it, path)
        }
      }

  @Test
  fun `actual H263 numerical package retains reconstruction cases and distinct prediction representations`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("035-h263-prediction", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(25, plan.steps.size)
      assertEquals(12, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(6, debug.size)
      assertEquals(18, debug.sumOf { it.stops.size })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val stages = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(60, 24, 36, 16, 2, 138), stages.map { it.functionCases.size })
      assertEquals(5, stages.flatMap { it.caseProfiles }.distinct().size)
      assertTrue(stages.all { it.source == "independent/h263-reconstruct8.asm" })
      assertTrue(stages.all { it.functionCaseBatches.flatten() == it.functionCases })
      assertEquals(
          stages.dropLast(1).flatMap { it.functionCases }.toSet(),
          stages.last().functionCases.toSet(),
      )
      assertEquals(146, stages.last().functionCases.sumOf { it.calls.size })
      assertEquals(69, stages.last().functionCases.count { it.byteOrder == ByteOrder.BIG_ENDIAN })
      assertEquals(
          69,
          stages.last().functionCases.count { it.byteOrder == ByteOrder.LITTLE_ENDIAN },
      )
      assertTrue(
          stages.last().functionCases.all {
            it.instructionLimit == 100_000L && it.stackAlignment == 16
          },
      )
      val sad = debug.single { it.id == "observe-sad" }.stops
      assertEquals(0x1fe01fe01fe01fe0L, sad.first().vectorRegisters.getValue($$"$w4").low)
      assertEquals(0x1fe01fe01fe01fe0L, sad.first().vectorRegisters.getValue($$"$w4").high)
      assertEquals(
          listOf(25L, 9L, 15L),
          debug.single { it.id == "observe-motion" }.stops.map { it.registers.getValue($$"$v1") },
      )
      val chain = debug.single { it.id == "observe-chain" }.stops
      assertEquals(2L, chain[0].registers.getValue($$"$s0"))
      assertEquals(16L, chain[0].registers.getValue($$"$s1"))
      assertEquals(
          listOf(1L, 23L, 3L, 131L),
          listOf($$"$s0", $$"$s1", $$"$s2", $$"$s3").map(chain[1].registers::getValue),
      )
      assertEquals(
          listOf(0L, 0L, 0L, 131L),
          listOf($$"$s0", $$"$s1", $$"$s2", $$"$s3").map(chain[2].registers::getValue),
      )
      val origin = debug.single { it.id == "observe-coordinator" }.stops.first()
      assertEquals(-1L, origin.registers.getValue($$"$s1"))
      assertEquals(0L, origin.registers.getValue($$"$s2"))
      val boundary = debug.single { it.id == "observe-reconstruction" }.stops
      assertEquals(-129L, boundary.first().registers.getValue($$"$s0"))
      assertEquals(0x100000008L, boundary.last().registers.getValue($$"$s0"))
    }
  }

  @Test
  fun `every H263 numerical prediction and helper boundary resolves through native PSI in the imported source`() =
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
                Files.readString(installed.directory.resolve("independent/h263-reconstruct8.asm"))
            assertEquals(
                1,
                independent.substringAfter("h263_reconstruct8:").lines().count {
                  it.trim() == "break"
                },
            )
            assertTrue(independent.contains("recon_function_return:\n    break"))
          }
        }
      }
}
