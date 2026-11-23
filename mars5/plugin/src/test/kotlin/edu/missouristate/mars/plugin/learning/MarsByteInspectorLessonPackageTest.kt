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
class MarsByteInspectorLessonPackageTest {
    @TempDir lateinit var directory: Path
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)
    private fun archive(): Path = directory.resolve("student.zip").also { target ->
        javaClass.getResourceAsStream("/learning/byte-inspector-0.4.0-student.zip")!!.use { Files.copy(it, target) }
    }

    @Test
    fun `current byte inspector archive preserves five input observations and two independent programs`() {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            assertEquals("004-byte-inspector", prepared.metadata.id)
            assertEquals("0.4.0", prepared.metadata.version)
            val plan = prepared.nativeLesson!!
            assertEquals(12, plan.steps.size)
            assertEquals(4, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            assertEquals(listOf(150,128,0,127,255), plan.steps.filterIsInstance<MarsNativeRunStep>().map { it.integerInputs.single() })
            assertEquals(3, plan.steps.filterIsInstance<MarsNativeDebugStep>().single().stops.size)
            val assessments = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf("independent/plus-one.asm", "independent/wrapped-byte.asm"), assessments.map { it.source })
            assertEquals(listOf(7,7), assessments.map { it.cases.size })
            assertTrue(assessments.all { it.functionCases.isEmpty() })
        }
    }

    @Test
    fun `current byte inspector sources resolve every instruction pointer and retain both unfinished programs`() = timeoutRunBlocking {
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
            assertEquals(12, count)
            for (name in listOf("plus-one.asm", "wrapped-byte.asm")) {
                val source = Files.readString(installed.directory.resolve("independent/" + name))
                assertTrue(source.contains("syscall"))
            }
        }
    }
}
