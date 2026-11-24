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
class MarsBytecodeLessonPackageTest {
  @TempDir lateinit var directory: Path
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)

  private fun archive(): Path =
      directory.resolve("student.zip").also { target ->
        javaClass.getResourceAsStream("/learning/bytecode-machine-0.1.0-student.zip")!!.use {
          Files.copy(it, target)
        }
      }

  @Test
  fun `actual bytecode archive retains every public trial and continuous caller observation`() {
    MarsLessonPackages.prepare(archive()).use { prepared ->
      assertEquals("018-bytecode-machine", prepared.metadata.id)
      val plan = prepared.nativeLesson!!
      assertEquals(19, plan.steps.size)
      assertEquals(8, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
      val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
      assertEquals(listOf(3, 4, 3), debug.map { it.stops.size })
      assertEquals(
          listOf("vm_call", "vm_ready", "vm_stored"),
          debug.last().stops.map { it.anchor.label },
      )
      assertEquals(4L, debug[1].stops[2].registers.getValue("\$t3"))
      val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
      assertEquals(listOf(500, 18, 421, 308), assessments.take(4).map { it.functionCases.size })
      assertEquals(List(10) { 50 }, assessments.first().functionCaseBatches.map { it.size })
      assertEquals(listOf(18), assessments[1].functionCaseBatches.map { it.size })
      assertTrue(
          assessments
              .take(4)
              .flatMap { it.functionCases }
              .all { it.instructionLimit == 100000L && it.exit!!.accessPolicies.isNotEmpty() },
      )
      assertEquals(4, assessments[4].functionCases.size)
      assertTrue(assessments[4].functionCases.all { it.calls.size == 3 })
      assertEquals(List(23) { 3 }, assessments.last().cases.map { it.publication!!.snapshots.size })
      assertTrue(
          assessments.last().cases.all {
            it.publication!!.snapshots.last().label == "vm_stored" &&
                it.publication!!.snapshots.last().expectedRegisters.keys == setOf("v0", "v1")
          },
      )
      assertTrue(assessments.last().cases.all { it.instructionLimit == 100000L })
      assertEquals(List(6) { "independent/vm.asm" }, assessments.map { it.source })
    }
  }

  @Test
  fun `native bytecode sources resolve all twenty PSI pointers while the interpreter remains unfinished`() =
      timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
          val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
          val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
          var count = 0
          for (step in content.plan.steps) {
            val source = content.source(step)
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
          assertEquals(20, count)
          val independent = Files.readString(installed.directory.resolve("independent/vm.asm"))
          assertEquals(1, independent.lines().count { it.trim() == "break" })
        }
      }
}
