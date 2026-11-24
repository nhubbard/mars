package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.cli.grading.ConsoleMazeCheck
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsMazeLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/maze-frontier-0.1.0-student.zip")!!.use {
          Files.copy(it, path)
        }
      }

  @Test
  fun `actual maze archive retains semantic search checks and independent renderer entry`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("010-maze-frontier", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(13, plan.steps.size)
      assertEquals(7, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(listOf(3, 3), debug.map { it.stops.size })
      assertEquals(listOf(7L, 9L), debug.map { it.stops.first().registers.getValue("\$a0") })
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(5, 8), assessments.map { it.cases.size })
      assertEquals(
          (0..4).toList(),
          assessments.first().cases.map { (it.maze as ConsoleMazeCheck.Renderer).fixture },
      )
      val searches = assessments.last().cases.map { it.maze as ConsoleMazeCheck.Search }
      assertEquals((0..7).toList(), searches.map { it.fixtureIndex })
      assertEquals(
          listOf(0 to 9, 63 to 63, 0 to 63, 63 to 0, 7 to 8, 0 to 9, 0 to 56, 27 to 63),
          searches.map { it.start to it.goal },
      )
      assertTrue(assessments.first().cases.all { it.instructionLimit == 10_000L })
      assertTrue(assessments.last().cases.all { it.instructionLimit == 250_000L })
      assertEquals(
          listOf("independent/maze.asm", "independent/maze.asm"),
          assessments.map { it.source },
      )
    }
  }

  @Test
  fun `imported sources resolve every native PSI anchor and keep the independent program unfinished`() =
      timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
          val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
          val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
          var count = 0
          for (step in content.plan.steps) {
            val source = content.source(step)
            assertTrue(Files.isRegularFile(source))
            val anchors =
                if (step is MarsNativeDebugStep) step.stops.map { it.anchor }
                else listOfNotNull(step.anchor)
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
                count++
              }
            }
          }
          assertEquals(15, count)
          val independent = Files.readString(installed.directory.resolve("independent/maze.asm"))
          assertEquals(
              2,
              independent.substringAfter("main:").lines().count { it.trim() == "break" },
          )
        }
      }
}
