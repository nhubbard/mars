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
class MarsMarsBotLessonPackageTest {
    @TempDir lateinit var directory: Path
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    private fun archive(): Path = directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/marsbot-feedback-0.1.0-student.zip")!!.use { Files.copy(it, path) }
    }

    @Test
    fun `actual MarsBot student archive preserves device checks and independent writing`() {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            assertEquals("006-marsbot-feedback", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(12, plan.steps.size)
            assertEquals(7, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(listOf(3, 3), debug.map { it.stops.size })
            assertEquals(listOf(16L, 2L), debug.map { it.stops.first().registers.getValue("\$s1") })
            val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf(16, 3), assessments.map { it.cases.size })
            assertTrue(assessments.flatMap { it.cases }.all { it.marsBot != null && it.instructionLimit == 100_000L })
            assertEquals(listOf(16, 1, 63), assessments.last().cases.map { it.marsBot!!.trail[1].first })
            assertEquals("independent/square.asm", assessments.last().source)
        }
    }

    @Test
    fun `imported sources resolve every native PSI anchor and keep the independent program unfinished`() = timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            var count = 0
            for (step in content.plan.steps) {
                val source = content.source(step)
                assertTrue(Files.isRegularFile(source))
                val anchors = if (step is MarsNativeDebugStep) step.stops.map { it.anchor } else listOfNotNull(step.anchor)
                if (anchors.isEmpty()) continue
                val text = Files.readString(source)
                withContext(Dispatchers.EDT) {
                    fixture.configureByText(source.fileName.toString(), text)
                    for (anchor in anchors) {
                        val range = requireNotNull(MarsLessonAnchors.range(fixture.editor, anchor)) { "$source: $anchor" }
                        assertEquals(anchor.mnemonic, text.substring(range.startOffset, range.endOffset).substringBefore(' ').substringBefore('\t'))
                        count++
                    }
                }
            }
            assertEquals(14, count)
            val independent = Files.readString(installed.directory.resolve("independent/square.asm"))
            assertEquals(1, independent.substringAfter("main:").lines().count { it.trim() == "break" })
        }
    }
}
