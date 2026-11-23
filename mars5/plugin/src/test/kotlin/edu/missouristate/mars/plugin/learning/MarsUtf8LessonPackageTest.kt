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
class MarsUtf8LessonPackageTest {
    @TempDir lateinit var directory: Path
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)
    private fun archive(): Path = directory.resolve("student.zip").also { target ->
        javaClass.getResourceAsStream("/learning/utf8-streams-0.1.0-student.zip")!!.use { Files.copy(it, target) }
    }

    @Test
    fun `actual UTF-8 archive retains every public trial and continuous caller observation`() {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            assertEquals("015-utf8-streams", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(16, plan.steps.size)
            assertEquals(8, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(listOf(3,3,4), debug.map { it.stops.size })
            assertEquals(listOf(0L,1L,2L,3L), debug.last().stops.map { it.registers.getValue("\$s3") })
            val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf(426,12), assessments.take(2).map { it.functionCases.size })
            assertEquals(List(8) { 50 } + 26, assessments.first().functionCaseBatches.map { it.size })
            assertEquals(listOf(12), assessments[1].functionCaseBatches.map { it.size })
            assertTrue(assessments.take(2).flatMap { it.functionCases }.all { it.instructionLimit == 50000L && it.exit!!.accessPolicies.isNotEmpty() })
            assertEquals((0..4).flatMap { count -> List(3) { count } }, assessments.last().cases.map { it.publication!!.snapshots.size })
            assertTrue(assessments.last().cases.all { it.instructionLimit == 50000L })
            assertEquals(List(3) { "independent/stream.asm" }, assessments.map { it.source })
        }
    }

    @Test
    fun `native UTF-8 sources resolve all twenty PSI pointers while the scanner remains unfinished`() = timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            var count = 0
            for (step in content.plan.steps) {
                val source = content.source(step)
                val anchors = if (step is MarsNativeDebugStep) step.stops.map { it.anchor } else listOfNotNull(step.anchor)
                if (anchors.isEmpty()) continue
                val text = Files.readString(source)
                withContext(Dispatchers.EDT) {
                    fixture.configureByText(source.fileName.toString(), text)
                    for (anchor in anchors) {
                        val range = requireNotNull(MarsLessonAnchors.range(fixture.editor, anchor)) { "$source: $anchor" }
                        assertEquals(anchor.mnemonic, text.substring(range.startOffset,range.endOffset).substringBefore(' ').substringBefore('\t'))
                        count++
                    }
                }
            }
            assertEquals(20, count)
            val independent = Files.readString(installed.directory.resolve("independent/stream.asm"))
            assertEquals(1, independent.lines().count { it.trim() == "break" })
        }
    }
}
