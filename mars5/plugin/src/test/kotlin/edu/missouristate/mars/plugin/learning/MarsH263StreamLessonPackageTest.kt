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
class MarsH263StreamLessonPackageTest {
    @TempDir lateinit var directory: Path
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)

    private fun archive(): Path = directory.resolve("student.zip").also { path ->
        javaClass.getResourceAsStream("/learning/h263-stream-0.1.0-student.zip")!!.use { Files.copy(it, path) }
    }

    @Test
    fun `actual H263 stream archive preserves real input failures and every bounded reader case`() {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            assertEquals("035-h263-stream", prepared.metadata.id)
            val plan = prepared.nativeLesson!!
            assertEquals(28, plan.steps.size)
            assertEquals(12, plan.steps.filterIsInstance<MarsNativeLessonStep>().size)
            val debug = plan.steps.filterIsInstance<MarsNativeDebugStep>()
            assertEquals(6, debug.size)
            assertEquals(18, debug.sumOf { it.stops.size })
            val clip = debug.single { it.id == "observe-clip" }.stops
            assertEquals(listOf(0L, 3L, 3L), clip.map { it.registers.getValue("\$v0") })
            assertEquals(listOf(8L, 1L, 2L), clip.map { it.registers.getValue("\$v1") })
            assertEquals(listOf(2212L, 696L, 908L), clip.map { it.registers.getValue("\$s1") })
            val run = plan.steps.filterIsInstance<MarsNativeRunStep>().single()
            assertEquals("0/8\n3/1\n3/2\n", run.stdout)
            assertEquals("worked/clip-progress.asm", run.source)
            val stages = plan.steps.filterIsInstance<MarsNativeAssessmentStep>()
            assertEquals(listOf(128, 136, 264, 26, 30, 6, 2, 328, 328), stages.map { it.functionCases.size })
            assertEquals(8, stages.flatMap { it.caseProfiles }.distinct().size)
            assertTrue(stages.all { it.source == "independent/h263-read-bits.asm" })
            assertTrue(stages.all { it.functionCaseBatches.flatten() == it.functionCases })
            assertEquals(592, stages.takeLast(2).flatMap { it.functionCases }.map { it.name }.distinct().size)
            assertEquals(598, stages.dropLast(2).flatMap { it.functionCases }.sumOf { it.calls.size })
            assertTrue(stages.flatMap { it.functionCases }.all { it.instructionLimit == 100_000L })
        }
    }

    @Test
    fun `imported stream sources resolve thirty native PSI anchors and retain binary raw fixtures`() = timeoutRunBlocking {
        MarsLessonPackages.prepare(archive()).use { prepared ->
            val installed = prepared.install(Files.createDirectory(directory.resolve("project")))
            val content = MarsDownloadedLessonContent(installed, prepared.nativeLesson!!)
            var count = 0
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
                        assertEquals(anchor.mnemonic, text.substring(range.startOffset, range.endOffset).substringBefore(' ').substringBefore('\t'))
                        count++
                    }
                }
            }
            assertEquals(30, count)
            val raw = Files.readAllBytes(installed.directory.resolve("worked/clip.yuv"))
            assertEquals(304128, raw.size)
            assertTrue(raw.copyOfRange(0, 38032).contentEquals(Files.readAllBytes(installed.directory.resolve("worked/truncated.yuv"))))
            assertTrue((raw.copyOfRange(0, 76032) + byteArrayOf(0xa5.toByte())).contentEquals(Files.readAllBytes(installed.directory.resolve("worked/trailing.yuv"))))
            val independent = Files.readString(installed.directory.resolve("independent/h263-read-bits.asm"))
            assertEquals(1, independent.substringAfter("h263_read_bits:").lines().count { it.trim() == "break" })
        }
    }
}
