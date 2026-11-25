package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsCborLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { archive ->
        javaClass.getResourceAsStream("/learning/cbor-0.1.0-student.zip")!!.use {
          Files.copy(it, archive)
        }
      }

  @Test
  fun `actual CBOR archive retains recursive observations exact source contracts and all sequence cases`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("032-cbor-telemetry", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(12, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(11, debug.size)
      assertEquals(31, debug.sumOf { it.stops.size })
      assertTrue(debug.all { it.fileRead == null })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val groups = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(55, 12, 8, 112, 16, 1, 1), groups.map { it.functionCases.size })
      assertEquals(104, groups.sumOf { it.caseProfiles.size })
      assertEquals(208, groups.sumOf { it.functionCases.sumOf { case -> case.calls.size } })
      assertTrue(groups.all { it.source == "independent/cbor-sequence.asm" })
      assertTrue(groups.all { it.functionCaseBatches.flatten() == it.functionCases })
      val nested =
          debug
              .single { it.id == "follow-parent-and-child" }
              .stops
              .filter { it.anchor.label == "after_k_child" }
      assertEquals(listOf(4L, 4L, 5L), nested.map { it.registers.getValue($$"$s6") })
      assertEquals(listOf(2L, 2L, 1L), nested.map { it.registers.getValue($$"$s2") })
      assertEquals(1L, nested[1].registers.getValue($$"$a2"))
      assertEquals(
          listOf(52L, 37L, 0L),
          debug
              .single { it.id == "capacity-before-output" }
              .stops
              .map { it.registers.getValue($$"$v1") },
      )
    }
  }

  @Test
  fun `every delivered CBOR prediction pointer and Debug boundary resolves through native PSI`() =
      timeoutRunBlocking {
        withContext(Dispatchers.IO) {
          MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            for (activity in content.plan.steps) {
              val source = content.source(activity)
              assertTrue(Files.isRegularFile(source))
              if (activity is MarsNativeLessonStep)
                  assertEquals(installed.directory.resolve(activity.source!!), source)
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
            assertTrue(
                Files.readString(installed.directory.resolve("independent/cbor-sequence.asm"))
                    .contains("cbor_sequence_count:\n    break"),
            )
          }
        }
      }
}
