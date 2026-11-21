package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsSessionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsIndependentCheckpointTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val worked = MarsLearningWorkspace::class.java.getResourceAsStream(
        "/learnProjects/mars_mips/MARSLearningProject/first-program.asm")!!.use { it.readBytes().toString(Charsets.UTF_8) }
    private val correct = worked.replace("Twice your number", "Three times your number")
        .replace("    addiu \$t0, \$t0, 1", "    addu \$t0, \$t0, \$v0\n    addiu \$t0, \$t0, 1")

    private suspend fun exercise(source: String = correct, createSession: () -> MarsContext = {
        MarsSessionService.createMarsComponent(backstepLimit = 1).context()
    }, body: suspend (MarsIndependentCheckpoint, Disposable) -> Unit) {
        val lifetime = Disposer.newDisposable()
        try {
            val checkpoint = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("mars-labs/console/independent.asm", source).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                MarsIndependentCheckpoint(projectFixture.get(), lifetime, file.toNioPath(), fixture.editor.document, createSession)
            }
            body(checkpoint, lifetime)
        } finally { withContext(Dispatchers.EDT) {
            FileDocumentManager.getInstance().saveDocument(fixture.editor.document)
            Disposer.dispose(lifetime)
        } }
    }

    @Test
    fun `cached assembly supports five independent cases and different labels and registers`() = timeoutRunBlocking {
        for (source in listOf(correct, correct.replace("\$t0", "\$s1").replace("main", "student_entry"))) {
            exercise(source) { checkpoint, _ ->
                assertFalse(checkpoint.passed())
                repeat(2) {
                    val result = assertInstanceOf(MarsIndependentCheckpoint.Feedback.Checked::class.java, checkpoint.check())
                    assertEquals(5, result.results.size)
                    assertTrue(result.results.all { it.passed })
                    assertTrue(checkpoint.passed())
                }
                withContext(Dispatchers.EDT) {
                    assertTrue(checkpoint.accepts(fixture.file.virtualFile.toNioPath(), fixture.editor.document))
                    assertFalse(checkpoint.accepts(fixture.file.virtualFile.toNioPath().resolveSibling("other.asm"), fixture.editor.document))
                    val other = fixture.addFileToProject("other.asm", "# other").virtualFile
                    assertFalse(checkpoint.accepts(fixture.file.virtualFile.toNioPath(), FileDocumentManager.getInstance().getDocument(other)!!))
                    WriteCommandAction.runWriteCommandAction(projectFixture.get()) { fixture.editor.document.insertString(0, "# edit\n") }
                    assertFalse(checkpoint.passed())
                }
            }
        }
    }

    @Test
    fun `prediction prerequisites block execution and completion without erasing genuine source evidence`() = timeoutRunBlocking {
        val policy = projectFixture.get().service<MarsLearningPolicy>()
        val progress = projectFixture.get().service<MarsLearningProgress>()
        var machines = 0
        exercise(createSession = { machines++; MarsSessionService.createMarsComponent(backstepLimit = 1).context() }) { checkpoint, _ ->
            policy.update(allowFreeWriting = false)
            assertEquals(MarsIndependentCheckpoint.Feedback.POLICY, checkpoint.check())
            policy.update(requirePredictions = true, allowFreeWriting = true)
            assertEquals(MarsIndependentCheckpoint.Feedback.POLICY, checkpoint.check())
            assertEquals(0, machines)
            assertFalse(checkpoint.passed())
            val dialog = withContext(Dispatchers.EDT) { MarsIndependentFeedbackDialog(projectFixture.get(), MarsIndependentCheckpoint.Feedback.POLICY) }
            try { assertTrue(dialog.report.contains("Your course requires both prediction quizzes")) }
            finally { withContext(Dispatchers.EDT) { dialog.close(DialogWrapper.OK_EXIT_CODE) } }
            policy.update(requirePredictions = false)
            assertInstanceOf(MarsIndependentCheckpoint.Feedback.Checked::class.java, checkpoint.check())
            assertTrue(checkpoint.passed())
            policy.update(requirePredictions = true)
            assertFalse(checkpoint.passed())
            assertTrue(MarsIndependentCheckpoint.STEP in progress.snapshot(MarsIndependentCheckpoint.identity)!!.successfulChecks)
            progress.open(MarsPredictions.identity, MarsPredictions.identity.contentSha256)
            progress.recordChoice(MarsPredictions.identity, "doubling", MarsPredictions.doubling.correct)
            progress.recordChoice(MarsPredictions.identity, "negative", MarsPredictions.negative.correct)
            assertTrue(checkpoint.passed())
        }
    }

    @Test
    fun `a policy restriction during execution prevents otherwise correct results from earning a pass`() = timeoutRunBlocking {
        val policy = projectFixture.get().service<MarsLearningPolicy>()
        exercise(createSession = {
            policy.update(requirePredictions = true)
            MarsSessionService.createMarsComponent(backstepLimit = 1).context()
        }) { checkpoint, _ ->
            assertEquals(MarsIndependentCheckpoint.Feedback.STALE, checkpoint.check())
            policy.update(requirePredictions = false)
            assertFalse(checkpoint.passed())
        }
    }

    @Test
    fun `required source evidence permits checking but an inactive prerequisite edit during execution rejects the result`() = timeoutRunBlocking {
        val project = projectFixture.get()
        val requirement = MarsGuidedRequirement.RUN
        val file = withContext(Dispatchers.IO) {
            val path = MarsLearningWorkspace.ensureExample(project)
            checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path))
        }
        val document = readAction { checkNotNull(FileDocumentManager.getInstance().getDocument(file)) }
        val original = document.text
        val policy = project.service<MarsLearningPolicy>()
        policy.setGuidedRequirement(requirement, true)
        for (changePrerequisite in listOf(false, true)) {
            withContext(Dispatchers.EDT) {
                val source = MarsLearningSource(project, file.toNioPath(), document, requirement.identity)
                assertTrue(source.finish(source.begin(requirement.step), true))
            }
            var changed = false
            exercise(createSession = {
                if (changePrerequisite && !changed) {
                    changed = true
                    ApplicationManager.getApplication().invokeAndWait {
                        WriteCommandAction.runWriteCommandAction(project) {
                            document.setText(original + "\n# Changed while another exercise was running\n")
                            document.setText(original)
                        }
                    }
                }
                MarsSessionService.createMarsComponent(backstepLimit = 1).context()
            }) { checkpoint, _ ->
                val feedback = checkpoint.check()
                if (changePrerequisite) {
                    assertEquals(MarsIndependentCheckpoint.Feedback.STALE, feedback)
                    assertFalse(checkpoint.passed())
                } else {
                    assertInstanceOf(MarsIndependentCheckpoint.Feedback.Checked::class.java, feedback)
                    assertTrue(checkpoint.passed())
                }
            }
        }
    }

    @Test
    fun `public cases reject doubling and a constant that matches only one input`() = timeoutRunBlocking {
        val doubled = worked.replace("Twice your number", "Three times your number")
        val constant = correct.replace("    move \$a0, \$t0", "    li \$a0, 61")
        for (source in listOf(doubled, constant)) exercise(source) { checkpoint, _ ->
            val result = assertInstanceOf(MarsIndependentCheckpoint.Feedback.Checked::class.java, checkpoint.check())
            assertEquals(1, result.results.count { it.passed })
            assertFalse(checkpoint.passed())
            assertTrue(result.results.any { it.failure?.contains("Expected output") == true })
        }
    }

    @Test
    fun `assembly errors and additional sources do not earn a current single-file pass`() = timeoutRunBlocking {
        exercise(".text\nnot_an_instruction\n") { checkpoint, _ ->
            assertEquals(MarsIndependentCheckpoint.Feedback.ASSEMBLY, checkpoint.check())
            assertFalse(checkpoint.passed())
        }
        withContext(Dispatchers.EDT) { fixture.addFileToProject("mars-labs/console/extra.inc", "# extra\n") }
        exercise(correct + "\n.include \"extra.inc\"\n") { checkpoint, _ ->
            assertEquals(MarsIndependentCheckpoint.Feedback.STALE, checkpoint.check())
            assertFalse(checkpoint.passed())
        }
    }

    @Test
    fun `an edit during isolated execution invalidates otherwise matching results`() = timeoutRunBlocking {
        var edited = false
        val machines = mutableListOf<MarsContext>()
        exercise(createSession = {
            if (!edited) {
                edited = true
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(projectFixture.get()) { fixture.editor.document.insertString(0, "# changed\n") }
                }
            }
            MarsSessionService.createMarsComponent(backstepLimit = 1).context().also(machines::add)
        }) { checkpoint, _ ->
            assertEquals(MarsIndependentCheckpoint.Feedback.STALE, checkpoint.check())
            assertFalse(checkpoint.passed())
            assertEquals(5, machines.size)
            for (machine in machines) assertThrows<IllegalStateException> { machine.claimStoppedSession() }
        }
    }

    @Test
    fun `closing the lesson during execution discards results and disables further checks`() = timeoutRunBlocking {
        lateinit var lifetime: Disposable
        exercise(createSession = {
            Disposer.dispose(lifetime)
            MarsSessionService.createMarsComponent(backstepLimit = 1).context()
        }) { checkpoint, owner ->
            lifetime = owner
            assertEquals(MarsIndependentCheckpoint.Feedback.STALE, checkpoint.check())
            assertFalse(checkpoint.passed())
            assertFalse(checkpoint.active)
            assertFalse(checkpoint.accepts(fixture.file.virtualFile.toNioPath(), fixture.editor.document))
            assertEquals(MarsIndependentCheckpoint.Feedback.STALE, checkpoint.check())
        }
    }

    @Test
    fun `a superseded observation cannot remove the current independent assessment`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val first = Disposer.newDisposable()
            val second = Disposer.newDisposable()
            try {
                val file = fixture.addFileToProject("independent.asm", correct).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val service = projectFixture.get().service<MarsLearningExecution>()
                service.observeIndependent(first, file.toNioPath(), fixture.editor.document)
                val current = service.observeIndependent(second, file.toNioPath(), fixture.editor.document)
                Disposer.dispose(first)
                assertEquals(current, service.independent)
                Disposer.dispose(second)
                assertEquals(null, service.independent)
            } finally { Disposer.dispose(first); Disposer.dispose(second) }
        }
    }
}
