package edu.missouristate.mars.plugin.learning

import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.cli.grading.ConsoleCheckCase
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.learning.MarsDownloadedAssessmentCheckpoint.Feedback
import edu.missouristate.mars.plugin.run.MarsRunService
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDownloadedAssessmentCheckpointTest {
    private val projects = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projects)
    private val identity = MarsLessonIdentity("tests", "offset-by-two", "0.4.0", "a".repeat(64))
    private val correct = $$"""
        .text
        .globl main
        main:
            li $v0, 5
            syscall
            addiu $t3, $v0, 2
            move $a0, $t3
            li $v0, 1
            syscall
            li $v0, 10
            syscall
        """.trimIndent() + "\n"
    private val step = MarsNativeAssessmentStep("offset", "Write a generic console program.", "task.asm", "cases.json",
        listOf(0, 3, -3).map { ConsoleCheckCase("input $it", "$it\n", "${it + 2}", 20_000) }, "Explain the calculation.", emptyList())
    private var fileNumber = 0
    private fun machine() = MarsSessionService.createMarsComponent(backstepLimit = 1).context()

    private data class Fixture(val checkpoint: MarsDownloadedAssessmentCheckpoint, val document: Document, val lifetime: Disposable)
    private suspend fun exercise(text: String = correct, predictions: () -> Boolean = { true }, create: () -> MarsContext = ::machine,
                                 body: suspend (Fixture) -> Unit) {
        val lifetime = Disposer.newDisposable()
        try {
            val state = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("assessment-${fileNumber++}.asm", text).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                Fixture(MarsDownloadedAssessmentCheckpoint(projects.get(), lifetime, file.toNioPath(), fixture.editor.document,
                    identity, step, predictions, create), fixture.editor.document, lifetime)
            }
            body(state)
        } finally { withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) } }
    }

    @Test
    fun `current unsaved text accepts equivalent algorithms and closes independent case machines`() = timeoutRunBlocking {
        val machines = mutableListOf<MarsContext>()
        exercise(text = correct.replace("addiu \$t3, \$v0, 2", "addiu \$s2, \$v0, 1\n    addiu \$s2, \$s2, 1")
            .replace("move \$a0, \$t3", "move \$a0, \$s2"), create = { machine().also { machines += it } }) { f ->
            assertTrue(f.checkpoint.accepts(f.document)); assertFalse(f.checkpoint.accepts(null))
            assertFalse(f.checkpoint.accepts(EditorFactory.getInstance().createDocument("different")))
            val result = assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check())
            assertTrue(result.results.all { it.passed }); assertEquals(1, result.completed); assertEquals(0, result.limit)
            assertTrue(f.checkpoint.passed()); assertEquals(3, machines.size)
            assertEquals(3, machines.toSet().size)
            machines.forEach { assertThrows<IllegalStateException> { it.simulator() } }
            withContext(Dispatchers.EDT) { Disposer.dispose(f.lifetime) }
            assertFalse(f.checkpoint.accepts(f.document)); assertTrue(f.checkpoint.passed())
            assertEquals(Feedback.STALE, f.checkpoint.check())
            val resumedLifetime = Disposer.newDisposable()
            try {
                val resumed = withContext(Dispatchers.EDT) {
                    val entry = FileDocumentManager.getInstance().getFile(f.document)!!.toNioPath()
                    MarsDownloadedAssessmentCheckpoint(projects.get(), resumedLifetime, entry, f.document, identity, step, { true })
                }
                assertTrue(resumed.passed())
                assertEquals(1, count())
            } finally { withContext(Dispatchers.EDT) { Disposer.dispose(resumedLifetime) } }
        }
    }

    @Test
    fun `incorrect current programs consume one assessment and useful case failures do not require a source recipe`() = timeoutRunBlocking {
        exercise(correct.replace("addiu \$t3, \$v0, 2", "move \$t3, \$v0")) { f ->
            val result = assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check())
            assertTrue(result.results.none { it.passed }); assertEquals(1, result.completed); assertFalse(f.checkpoint.passed())
        }
    }

    @Test
    fun `assembly failure unsupported source capture and cancellation use no attempt`() = timeoutRunBlocking {
        exercise("not_an_instruction\n") { f ->
            assertEquals(Feedback.ASSEMBLY, f.checkpoint.check()); assertEquals(0, count())
        }
        withContext(Dispatchers.EDT) { fixture.addFileToProject("extra.inc", "# extra source\n") }
        exercise(correct + ".include \"extra.inc\"\n") { f ->
            assertEquals(Feedback.STALE, f.checkpoint.check()); assertEquals(0, count())
        }
        exercise(create = { throw CancellationException("Canceled test assessment") }) { f ->
            assertThrows<CancellationException> { f.checkpoint.check() }; assertEquals(0, count()); assertFalse(f.checkpoint.passed())
        }
    }

    @Test
    fun `instructor gates and per-activity budgets are checked without erasing previously valid evidence`() = timeoutRunBlocking {
        val policy = projects.get().service<MarsLearningPolicy>()
        var predictions = false
        exercise(predictions = { predictions }) { f ->
            policy.update(allowFreeWriting = false)
            assertEquals(Feedback.POLICY, f.checkpoint.check())
            policy.update(allowFreeWriting = true, requirePredictions = true)
            assertEquals(Feedback.POLICY, f.checkpoint.check()); assertFalse(f.checkpoint.passed())
            predictions = true; policy.update(maxIndependentAssessments = 1)
            assertTrue(assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check()).results.all { it.passed })
            assertEquals(Feedback.LIMIT, f.checkpoint.check()); assertTrue(f.checkpoint.passed())
            predictions = false; assertFalse(f.checkpoint.passed())
            predictions = true; assertTrue(f.checkpoint.passed())
            edit(f.document, correct + "# changed\n")
            assertFalse(f.checkpoint.passed()); assertEquals(Feedback.LIMIT, f.checkpoint.check()); assertEquals(1, count())
            policy.update(maxIndependentAssessments = 2)
            val second = assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check())
            assertEquals(2, second.completed); assertEquals(2, second.limit)
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `edits including edits restored to original source cannot accept an in-flight result`(restore: Boolean) = timeoutRunBlocking {
        lateinit var document: Document
        var changed = false
        exercise(create = {
            if (!changed) {
                changed = true
                ApplicationManager.getApplication().invokeAndWait {
                    WriteCommandAction.runWriteCommandAction(projects.get()) {
                        document.setText(correct + "# in flight\n")
                        if (restore) document.setText(correct)
                    }
                }
            }
            machine()
        }) { f ->
            document = f.document
            assertEquals(Feedback.STALE, f.checkpoint.check()); assertFalse(f.checkpoint.passed()); assertEquals(0, count())
            assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check()); assertTrue(f.checkpoint.passed())
        }
    }

    @Test
    fun `reset disposal and policy changes during execution discard results without consuming attempts`() = timeoutRunBlocking {
        val project = projects.get(); val policy = project.service<MarsLearningPolicy>()
        for (kind in listOf("reset", "dispose", "policy")) {
            project.service<MarsLearningProgress>().reset(identity); policy.update(allowFreeWriting = true)
            lateinit var lifetime: Disposable
            exercise(create = {
                when (kind) {
                    "reset" -> project.service<MarsLearningProgress>().reset(identity)
                    "dispose" -> Disposer.dispose(lifetime)
                    else -> policy.update(allowFreeWriting = false)
                }
                machine()
            }) { f ->
                lifetime = f.lifetime
                assertEquals(Feedback.STALE, f.checkpoint.check()); assertFalse(f.checkpoint.passed()); assertEquals(0, count())
            }
        }
    }

    @Test
    fun `an assessment limit reduced while checking prevents acceptance`() = timeoutRunBlocking {
        val progress = projects.get().service<MarsLearningProgress>()
        val policy = projects.get().service<MarsLearningPolicy>()
        exercise(create = { policy.update(maxIndependentAssessments = 1); machine() }) { f ->
            progress.finishAssessment(progress.beginCheck(identity, "previous", MarsLearningSource.revision(correct)), false, 0)
            assertEquals(1, count())
            assertEquals(Feedback.STALE, f.checkpoint.check()); assertEquals(1, count()); assertFalse(f.checkpoint.passed())
        }
    }

    @Test
    fun `reset records restart at zero and deleted source files cannot be selected for assessment`() = timeoutRunBlocking {
        val project = projects.get()
        exercise { f ->
            project.service<MarsLearningPolicy>().update(maxIndependentAssessments = 1)
            project.service<MarsLearningProgress>().reset(identity)
            assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check())
            assertEquals(1, count())
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) {
                    FileDocumentManager.getInstance().getFile(f.document)!!.delete(this)
                }
            }
            assertFalse(f.checkpoint.accepts(f.document))
        }
    }

    @Test
    fun `a disposed assessment suppresses feedback even when its progress owner remains active`() = timeoutRunBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1)
        exercise(create = { entered.complete(Unit); check(release.await(15, TimeUnit.SECONDS)); machine() }) { f ->
            val owner = Disposer.newDisposable()
            var feedback = 0
            try {
                val job = projects.get().service<MarsLearningExecution>().checkAssessment(owner, f.checkpoint) { feedback++ }
                entered.await()
                withContext(Dispatchers.EDT) { Disposer.dispose(f.lifetime) }
                release.countDown(); job.join()
                assertEquals(0, feedback); assertEquals(0, count())
            } finally { release.countDown(); Disposer.dispose(owner) }
        }
    }

    @Test
    fun `only the latest overlapping check can consume an attempt and grant evidence`() = timeoutRunBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CountDownLatch(1); val starts = AtomicInteger()
        exercise(create = {
            if (starts.incrementAndGet() == 1) { entered.complete(Unit); check(release.await(15, TimeUnit.SECONDS)) }
            machine()
        }) { f -> coroutineScope {
            val old = async(Dispatchers.Default) { f.checkpoint.check() }
            try {
                entered.await()
                assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check())
                release.countDown()
                assertEquals(Feedback.STALE, old.await())
                assertEquals(1, count()); assertTrue(f.checkpoint.passed())
            } finally { release.countDown() }
        } }
    }

    @Test
    fun `a running student program keeps its own console input output and registers while grading`() = timeoutRunBlocking {
        val project = projects.get(); val waiting = CompletableDeferred<Unit>(); val finished = CompletableDeferred<Int>(); val stdout = StringBuffer()
        val live = withContext(Dispatchers.EDT) {
            val source = correct.replace(".text", ".data\nprompt: .asciiz \"Waiting: \"\n.text")
                .replace("    li \$v0, 5", "    la \$a0, prompt\n    li \$v0, 4\n    syscall\n    li \$v0, 5")
            val file = fixture.addFileToProject("live.asm", source).virtualFile
            project.service<MarsRunService>().createProcess(file.toNioPath(), memoryDumps = emptyList())
        }
        live.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (ProcessOutputType.isStdout(outputType)) { stdout.append(event.text); if (stdout.toString().contains("Waiting: ")) waiting.complete(Unit) }
            }
            override fun processTerminated(event: ProcessEvent) { finished.complete(event.exitCode) }
        })
        try {
            live.startNotify(); waiting.await()
            exercise { f -> assertTrue(assertInstanceOf(Feedback.Checked::class.java, f.checkpoint.check()).results.all { it.passed }) }
            assertFalse(finished.isCompleted); assertEquals("Waiting: ", stdout.toString())
            withContext(Dispatchers.IO) { live.processInput.apply { write("10\n".toByteArray()); flush() } }
            assertEquals(0, finished.await()); assertEquals("Waiting: 12", stdout.toString())
        } finally { live.destroyProcess(); finished.await() }
    }

    private fun count() = projects.get().service<MarsLearningProgress>().snapshot(identity)?.completedAssessments ?: 0
    private suspend fun edit(document: Document, text: String) = withContext(Dispatchers.EDT) {
        WriteCommandAction.runWriteCommandAction(projects.get()) { document.setText(text) }
    }
}
