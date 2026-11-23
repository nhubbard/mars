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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MarsQoiLessonPackageTest {
    @TempDir lateinit var directory: Path
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    private fun archive(): Path = directory.resolve("student.zip").also { archive ->
        javaClass.getResourceAsStream("/learning/qoi-0.1.0-student.zip")!!.use { Files.copy(it, archive) }
    }

    @Test
    fun `actual QOI archive retains every original contract and the distinct worked observations`() {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            assertEquals("030-qoi", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(10, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(10, debug.size)
            assertEquals(29, debug.sumOf { it.stops.size })
            assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
            val groups = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf(150, 9, 32, 4, 7, 1, 2), groups.map { it.functionCases.size })
            assertEquals(188, groups.sumOf { it.caseProfiles.size })
            assertEquals(205, groups.sumOf { it.functionCases.sumOf { case -> case.calls.size } })
            assertTrue(groups.all { it.source == "independent/qoi-mirrored.asm" })
            assertTrue(groups.all { it.functionCaseBatches.flatten() == it.functionCases })
            val file = debug.single { it.fileRead != null }.fileRead!!
            assertEquals(7, file.maxReadSize)
            assertEquals(19, file.firstDescriptor)
            assertEquals(38, file.byteLength)
            val indexes = debug.single { it.id == "chunk-transitions" }.stops.filter { it.anchor.label == "after_w_index" }
            assertEquals(listOf(0xff000000L, 0xff010101L), indexes.map { it.registers.getValue("\$s3") })
            assertEquals(0L, debug.single { it.id == "counterexample-states" }.stops[1].registers.getValue("\$s3"))
            assertEquals(1L, debug.single { it.id == "counterexample-states" }.stops[1].registers.getValue("\$fp"))
        }
    }

    @Test
    fun `every prediction pointer and Debug boundary resolves in its selected installed source through native PSI`() = timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            for (activity in content.plan.steps) {
                val path = content.source(activity)
                assertTrue(Files.isRegularFile(path))
                if (activity is MarsNativeLessonStep) {
                    assertNotNull(activity.source)
                    assertEquals(installed.directory.resolve(activity.source!!), path)
                }
                val anchors = if (activity is MarsNativeDebugStep) activity.stops.map { it.anchor } else listOfNotNull(activity.anchor)
                if (anchors.isEmpty()) continue
                val text = Files.readString(path)
                withContext(Dispatchers.EDT) {
                    fixture.configureByText(path.fileName.toString(), text)
                    for (anchor in anchors) {
                        val range = requireNotNull(MarsLessonAnchors.range(fixture.editor, anchor)) { "$path: $anchor" }
                        assertEquals(anchor.mnemonic, text.substring(range.startOffset, range.endOffset).substringBefore(' ').substringBefore('\t'))
                    }
                }
            }
            assertTrue(Files.readString(content.installed.directory.resolve("independent/qoi-mirrored.asm")).contains("qoi_decode_mirrored:\n    break"))
        }
    }
}
