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
class MarsChachaLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { archive ->
        javaClass.getResourceAsStream("/learning/chacha-0.1.0-student.zip")!!.use {
          Files.copy(it, archive)
        }
      }

  @Test
  fun `actual ChaCha20 archive retains scalar vector tail and complete interface observations`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("026-chacha20", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(14, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(10, debug.size)
      assertEquals(46, debug.sumOf { it.stops.size })
      assertTrue(debug.all { it.fileRead == null })
      assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
      val groups = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(12, 4, 4, 37, 11, 5), groups.map { it.functionCases.size })
      assertEquals(9, groups.sumOf { it.caseProfiles.size })
      assertEquals(95, groups.sumOf { it.functionCases.sumOf { case -> case.calls.size } })
      assertTrue(groups.all { it.source == "independent/chacha20.asm" })
      assertTrue(groups.all { it.functionCaseBatches.flatten() == it.functionCases })
      val msa = debug.single { it.id == "follow-msa-rows" }
      assertEquals(8, msa.stops.first().vectorRegisters.size)
      assertEquals(0x3320646e61707865L, msa.stops.first().vectorRegisters.getValue("\$w0").low)
      assertEquals(0x6b20657479622d32L, msa.stops.first().vectorRegisters.getValue("\$w0").high)
      assertEquals(0x15593bd1e4e7f110L, msa.stops[5].vectorRegisters.getValue("\$w0").low)
      val visits =
          debug
              .single { it.id == "walk-two-fragments" }
              .stops
              .filter { it.anchor.label == "after_stream_bytes" }
      assertEquals(listOf(65L, 1L), visits.map { it.registers.getValue("\$s2") })
      assertEquals(listOf(64L, 1L), visits.map { it.registers.getValue("\$s3") })
    }
  }

  @Test
  fun `every delivered ChaCha20 prediction pointer and Debug boundary resolves through native PSI`() =
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
          assertTrue(
              Files.readString(installed.directory.resolve("independent/chacha20.asm"))
                  .contains("quarter_development:"),
          )
        }
      }
}
