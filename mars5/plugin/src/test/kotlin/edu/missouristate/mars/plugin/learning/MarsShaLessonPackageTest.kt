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
class MarsShaLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { archive ->
        javaClass.getResourceAsStream("/learning/sha-0.2.0-student.zip")!!.use {
          Files.copy(it, archive)
        }
      }

  @Test
  fun `actual SHA archive retains limited experiments staged checks and deterministic file failures`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("025-sha256", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(35, plan.steps.size)
      assertEquals(16, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(9, debug.size)
      assertEquals(32, debug.sumOf { it.stops.size })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val groups = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(16, 12, 3, 17, 23, 40, 12, 22, 128), groups.map { it.functionCases.size })
      assertEquals(42, groups.flatMap { it.caseProfiles }.distinct().size)
      assertEquals(655, groups.sumOf { it.functionCases.sumOf { case -> case.calls.size } })
      assertTrue(groups.all { it.source == "independent/sha256.asm" })
      assertTrue(groups.all { it.functionCaseBatches.flatten() == it.functionCases })
      val update = groups.single { it.id == "complete-update" }.functionCases
      assertTrue(update.all { it.name.startsWith("update prefix: ") })
      assertTrue(update.flatMap { it.calls }.none { it.entry == "sha256_final" })
      val pending = debug.single { it.id == "fill-before-compressing" }.stops.last()
      assertEquals(1L, pending.registers.getValue($$"$s1"))
      assertEquals(64L, pending.registers.getValue($$"$s2"))
      val high = debug.single { it.id == "encode-high-length" }.stops.last()
      assertEquals(0x8000001f8L, high.registers.getValue($$"$t3"))
      val success = debug.single { it.id == "observe-short-file" }
      val failure = debug.single { it.id == "observe-read-failure" }
      val input = requireNotNull(success.fileRead)
      assertEquals("616263", input.bytes)
      assertEquals(2, input.maxReadSize)
      assertEquals(19, input.firstDescriptor)
      assertEquals(input.copy(failAfterBytes = 2), failure.fileRead)
      assertEquals(2L, failure.stops.last().registers.getValue($$"$v0"))
      assertTrue(debug.all { requireNotNull(it.source).startsWith("worked/") })
      assertEquals("worked/read-prefixes.asm", success.source)
      assertEquals(success.source, failure.source)
      val final = groups.last()
      assertEquals("recheck-completed-source", final.id)
      assertEquals(36, final.caseProfiles.size)
      assertTrue(final.functionCases.none { it.name.startsWith("update prefix: ") })
    }
  }

  @Test
  fun `every delivered SHA prediction and Debug boundary resolves in the student source through native PSI`() =
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
            assertEquals(
                6,
                Files.readString(installed.directory.resolve("independent/sha256.asm"))
                    .lines()
                    .count { it.trim() == "break" },
            )
          }
        }
      }
}
