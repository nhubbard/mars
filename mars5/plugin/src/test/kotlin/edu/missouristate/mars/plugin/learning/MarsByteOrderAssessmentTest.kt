package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.cli.grading.FunctionCallCheck
import edu.missouristate.mars.cli.grading.FunctionCheckCase
import edu.missouristate.mars.cli.grading.PreparedFunctionChecks
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MipsAssemblyContextListener
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.learning.MarsDownloadedAssessmentCheckpoint.Feedback
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsByteOrderAssessmentTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)
    private val source = $$"""
        .data
        constant: .word 0x12345678
        .text
        main: li $v0,10
            syscall
        returned: nop
        inspect:
            dla $t0,constant
            lwu $v0,0($t0)
            lbu $v1,0($t0)
            jrc $ra
    """.trimIndent()
    private val cases = listOf(null, ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN).map { order ->
        FunctionCheckCase("native $order", listOf(FunctionCallCheck("inspect", "returned", emptyMap(),
            mapOf("v0" to 0x12345678L, "v1" to if (order == ByteOrder.BIG_ENDIAN) 0x12L else 0x78L))), byteOrder = order)
    }
    private val step = MarsNativeAssessmentStep("native", "Inspect native data.", "native.asm", listOf("cases.json"),
        emptyList(), "Explain native byte order.", emptyList(), functionCases = cases,
        functionCaseBatches = cases.chunked(2))

    @Test
    fun `assessment assembles each requested order from current unsaved source and consumes one attempt`() = timeoutRunBlocking {
        val project = projects.get()
        val lifetime = Disposer.newDisposable()
        val identity = MarsLessonIdentity("tests", "native-order", "0.1.0", "a".repeat(64))
        try {
            val document = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("native.asm", source).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                fixture.editor.document
            }
            val path = withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().getFile(document)!!.toNioPath() }
            val checkpoint = MarsDownloadedAssessmentCheckpoint(project, lifetime, path, document, identity, step, { true })
            val result = assertInstanceOf(Feedback.Checked::class.java, checkpoint.check())
            assertEquals(cases.map { it.name }, result.results.map { it.name })
            assertTrue(result.results.all { it.passed }); assertEquals(1, result.completed)
            assertTrue(checkpoint.passed())
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { document.setText(source.replace("0x12345678", "0x76543210")) }
            }
            assertFalse(checkpoint.passed())
            val edited = assertInstanceOf(Feedback.Checked::class.java, checkpoint.check())
            assertTrue(edited.results.none { it.passed }); assertEquals(2, edited.completed)
        } finally { withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) } }
    }

    @Test
    fun `build cache keeps endian images distinct and reuses each exact source revision`() = timeoutRunBlocking {
        val project = projects.get()
        val entry = withContext(Dispatchers.EDT) { fixture.addFileToProject("cache.asm", source).virtualFile.toNioPath() }
        val builds = project.service<MarsBuildService>()
        val little = builds.prepare(MarsSourceSet(entry), MarsLearningSource.options, false)!!
        val big = builds.prepare(MarsSourceSet(entry), MarsLearningSource.options, false, ByteOrder.BIG_ENDIAN)!!
        assertEquals(ByteOrder.LITTLE_ENDIAN, little.image.byteOrder)
        assertEquals(ByteOrder.BIG_ENDIAN, big.image.byteOrder)
        assertNotSame(little.image, big.image)
        assertSame(little.image, builds.prepare(MarsSourceSet(entry), MarsLearningSource.options, false)!!.image)
        assertSame(big.image, builds.prepare(MarsSourceSet(entry), MarsLearningSource.options, false, ByteOrder.BIG_ENDIAN)!!.image)
        for (image in listOf(little.image, big.image)) {
            val results = PreparedFunctionChecks.evaluate(image,
                cases.filter { it.byteOrder == image.byteOrder },
                { MarsSessionService.createMarsComponent(backstepLimit = 1).context() })
            assertTrue(results.all { it.passed })
        }
    }

    @Test
    fun `policy withdrawn during alternate assembly discards the assessment before any case runs`() = timeoutRunBlocking {
        val project = projects.get()
        val lifetime = Disposer.newDisposable()
        val policy = project.service<MarsLearningPolicy>()
        try {
            val document = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("policy.asm", source).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                fixture.editor.document
            }
            val path = withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().getFile(document)!!.toNioPath() }
            var assemblies = 0
            project.messageBus.connect(lifetime).subscribe(MipsAssemblyContextListener.TOPIC, MipsAssemblyContextListener {
                if (++assemblies == 2) policy.update(allowFreeWriting = false)
            })
            val identity = MarsLessonIdentity("tests", "revoked-order", "0.1.0", "b".repeat(64))
            val checkpoint = MarsDownloadedAssessmentCheckpoint(project, lifetime, path, document, identity, step, { true },
                { error("Revoked assessment must not acquire execution machines") })
            assertEquals(Feedback.STALE, checkpoint.check())
            assertEquals(2, assemblies)
            assertFalse(checkpoint.passed())
            assertEquals(0, project.service<MarsLearningProgress>().snapshot(identity)?.completedAssessments ?: 0)
        } finally {
            policy.update(allowFreeWriting = true)
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }
}
