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
class MarsWavLessonPackageTest {
    @TempDir lateinit var directory: Path
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    private fun archive(): Path = directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/wav-0.1.0-student.zip")!!.use { Files.copy(it, path) }
    }

    @Test
    fun `actual WAV student archive retains staged reversal cases and full pipeline file results`() {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            assertEquals("031-pcm-wav", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(20, plan.steps.size)
            assertEquals(10, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(5, debug.size)
            assertEquals(10, debug.sumOf { it.stops.size })
            assertEquals(1, plan.steps.filterIsInstance<MarsNativeRunStep>().size)
            val stages = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf(12, 12, 11, 35), stages.map { it.functionCases.size })
            assertEquals(7, stages.flatMap { it.caseProfiles }.distinct().size)
            assertEquals(stages.dropLast(1).flatMap { it.functionCases }, stages.last().functionCases)
            assertTrue(stages.all { it.source == "independent/wav-reverse-frames.asm" })
            assertTrue(stages.all { it.functionCaseBatches.flatten() == it.functionCases })
            assertEquals(40, stages.last().functionCases.sumOf { it.calls.size })
            val pipeline = stages.last().functionCases.last()
            assertEquals(19, pipeline.fileSystem!!.firstDescriptor)
            assertEquals(7, pipeline.fileSystem!!.maxReadSize)
            assertEquals(5, pipeline.fileSystem!!.maxWriteSize)
            assertEquals(60, pipeline.calls.single().expectedFiles!!.getValue("output.wav")!!.length / 2)
            val failure = debug.single { it.id == "observe-publication" }.stops.last()
            assertEquals(1L, failure.registers.getValue("\$v0"))
            assertEquals(-6510615555426900571L, failure.registers.getValue("\$s3"))
            val wide = debug.single { it.id == "observe-chunk-offsets" }.stops.last()
            assertEquals(0x100000000L, wide.registers.getValue("\$t2"))
        }
    }

    @Test
    fun `every WAV source pointer and Debug boundary resolves through native PSI`() = timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            for (activity in content.plan.steps) {
                val source = content.source(activity)
                assertTrue(Files.isRegularFile(source))
                val anchors = if (activity is MarsNativeDebugStep) activity.stops.map { it.anchor }
                    else listOfNotNull(activity.anchor)
                if (anchors.isEmpty()) continue
                val text = Files.readString(source)
                withContext(Dispatchers.EDT) {
                    fixture.configureByText(source.fileName.toString(), text)
                    for (anchor in anchors) {
                        val range = requireNotNull(MarsLessonAnchors.range(fixture.editor, anchor)) { "$source: $anchor" }
                        assertEquals(anchor.mnemonic, text.substring(range.startOffset, range.endOffset)
                            .substringBefore(' ').substringBefore('\t'))
                    }
                }
            }
            val independent = Files.readString(installed.directory.resolve("independent/wav-reverse-frames.asm"))
            assertEquals(1, independent.lines().count { it.trim() == "break" })
            assertTrue(independent.endsWith("wav_reverse_frames:\n    break\n"))
        }
    }
}
