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
class MarsRsaLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { archive ->
        javaClass.getResourceAsStream("/learning/rsa-0.1.0-student.zip")!!.use {
          Files.copy(it, archive)
        }
      }

  @Test
  fun `actual RSA archive preserves all stages and full width caller contracts`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("027-rsa-arithmetic", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(35, plan.steps.size)
      assertEquals(16, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(8, debug.size)
      assertEquals(26, debug.sumOf { it.stops.size })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val groups = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(
          listOf(8, 22, 13, 22, 17, 14, 11, 12, 4, 123),
          groups.map { it.functionCases.size },
      )
      assertEquals(18, groups.flatMap { it.caseProfiles }.distinct().size)
      assertEquals(284, groups.sumOf { it.functionCases.sumOf { case -> case.calls.size } })
      assertTrue(groups.all { it.source == "independent/rsa-arithmetic.asm" })
      assertTrue(groups.all { it.functionCaseBatches.flatten() == it.functionCases })
      assertEquals(groups.dropLast(1).flatMap { it.functionCases }, groups.last().functionCases)
      val repeated =
          groups.last().functionCases.single { it.name == "reused power with moving objects" }
      assertEquals(40_000_000L, repeated.instructionLimit)
      assertEquals(3, repeated.calls.size)
      assertTrue(debug.all { requireNotNull(it.source).startsWith("worked/") })
      val shift = debug.single { it.id == "observe-exponent-shift" }.stops[1]
      assertEquals(Long.MIN_VALUE, shift.registers.getValue("\$t2"))
      assertEquals(0x4000000000000000L, shift.registers.getValue("\$t3"))
      val validation = debug.single { it.id == "observe-validation-boundary" }.stops.last()
      assertEquals(1L, validation.registers.getValue("\$v0"))
      assertEquals(0x5a5a5a5aL, validation.registers.getValue("\$t1"))
    }
  }

  @Test
  fun `every delivered RSA prediction and Debug boundary resolves in the student source through native PSI`() =
      timeoutRunBlocking {
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
          assertEquals(
              7,
              Files.readString(installed.directory.resolve("independent/rsa-arithmetic.asm"))
                  .lines()
                  .count { it.trim() == "break" },
          )
        }
      }
}
