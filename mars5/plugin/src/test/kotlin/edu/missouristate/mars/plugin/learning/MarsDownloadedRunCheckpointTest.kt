package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.run.MarsRunType
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDownloadedRunCheckpointTest {
  private val projects = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projects)
  private val example =
      javaClass.getResourceAsStream("/learning/byte-inspector.asm")!!.use {
        it.readBytes().toString(Charsets.UTF_8)
      }
  private val identity = MarsLessonIdentity("mars-book", "byte.run", "0.2.0", "e".repeat(64))
  private var fileNumber = 0

  private fun contract(input: Int): MarsNativeRunStep {
    val byte = input and 255
    return MarsNativeRunStep(
        "observe",
        "Run with $input.",
        listOf(input),
        "Enter an integer from 0 to 255: Unsigned byte: $byte\nSigned byte: ${if (byte < 128) byte else byte - 256}\n",
        "Explain both values.",
        null,
        emptyList(),
    )
  }

  @Test
  fun `bounded transcripts inspect consumed input and complete output independently`() {
    fun event(word: Int, transfer: StepEvent.Transfer? = null) = StepEvent(0, word, 1, transfer)
    val trace = MarsDownloadedRunCheckpoint.Transcript(listOf(150), "result\n")
    assertFalse(trace.inputsMatch())
    assertFalse(trace.outputMatches())
    trace.instruction(event(12), 150) // No previous selector has been observed.
    assertFalse(trace.inputsMatch())
    trace.instruction(event(0x24020005), 5)
    trace.instruction(event(12, StepEvent.ExceptionEntry.INSTANCE), 5)
    assertFalse(trace.inputsMatch())
    trace.instruction(event(0x1000000c), 5) // A different opcode is not SYSCALL.
    trace.instruction(event(0), 5) // A different function is not SYSCALL.
    trace.instruction(event(0x0000100c), 150) // The optional syscall code is ignored.
    assertTrue(trace.inputsMatch())
    trace.append("ignored", ProcessOutputType.SYSTEM)
    trace.append("", ProcessOutputType.STDERR)
    trace.append("res", ProcessOutputType.STDOUT)
    trace.append("ult\n", ProcessOutputType.STDOUT)
    assertTrue(trace.outputMatches())
    trace.instruction(event(0x24020005), 5)
    trace.instruction(event(12), 150)
    assertFalse(trace.inputsMatch())
    trace.append("extra", ProcessOutputType.STDOUT)
    assertFalse(trace.outputMatches())
    val diagnostic = MarsDownloadedRunCheckpoint.Transcript(emptyList(), "ok")
    diagnostic.append("ok", ProcessOutputType.STDOUT)
    diagnostic.append("error", ProcessOutputType.STDERR)
    assertTrue(diagnostic.inputsMatch())
    assertFalse(diagnostic.outputMatches())
  }

  @ParameterizedTest
  @ValueSource(ints = [Int.MIN_VALUE, -1, 0, 127, 128, 150, 255, Int.MAX_VALUE])
  fun `native pre-start event accepts each real byte boundary run`(input: Int) =
      timeoutRunBlocking {
        create(contract(input)).use { f ->
          withContext(Dispatchers.EDT) {
            val project = projects.get()
            val type = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)
            val config =
                MarsRunConfiguration(
                    project,
                    type.configurationFactories.single(),
                    "Byte observation",
                )
            val env =
                ExecutionEnvironmentBuilder.create(
                        DefaultRunExecutor.getRunExecutorInstance(),
                        config,
                    )
                    .build()
            project.messageBus
                .syncPublisher(ExecutionManager.EXECUTION_TOPIC)
                .processStarting(DefaultRunExecutor.EXECUTOR_ID, env, f.handler)
          }
          run(f.handler, "$input\n")
          f.await()
          assertEquals(listOf(MarsDownloadedRunCheckpoint.Outcome.PASSED), f.feedback)
          assertTrue(f.checkpoint.passed())
        }
      }

  @Test
  fun `matching printed numbers cannot substitute a different consumed input`() =
      timeoutRunBlocking {
        create(contract(150)).use { f ->
          f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
          run(f.handler, "406\n")
          f.await()
          assertEquals(listOf(MarsDownloadedRunCheckpoint.Outcome.INPUT), f.feedback)
          assertFalse(f.checkpoint.passed())
        }
      }

  @Test
  fun `a missing extension fails while an equivalent conversion passes`() = timeoutRunBlocking {
    val wrong = example.replace("seb ${'$'}t1, ${'$'}t0", "move ${'$'}t1, ${'$'}t0")
    val alternative =
        example.replace(
            "seb ${'$'}t1, ${'$'}t0",
            "andi ${'$'}t1, ${'$'}t0, 0x7f\n    andi ${'$'}t2, ${'$'}t0, 0x80\n    dsubu ${'$'}t1, ${'$'}t1, ${'$'}t2",
        )
    for ((source, result) in
        listOf(
            wrong to MarsDownloadedRunCheckpoint.Outcome.OUTPUT,
            alternative to MarsDownloadedRunCheckpoint.Outcome.PASSED,
        )) {
      create(contract(150), source).use { f ->
        f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
        run(f.handler, "150\n")
        f.await()
        assertEquals(listOf(result), f.feedback)
        assertEquals(result == MarsDownloadedRunCheckpoint.Outcome.PASSED, f.checkpoint.passed())
      }
    }
  }

  @Test
  fun `abnormal termination is reported even after correct output`() = timeoutRunBlocking {
    val failing = example.replace("li ${'$'}v0, 10", "li ${'$'}a0, 7\n    li ${'$'}v0, 17")
    create(contract(150), failing).use { f ->
      f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
      run(f.handler, "150\n", 7)
      f.await()
      assertEquals(listOf(MarsDownloadedRunCheckpoint.Outcome.TERMINATION), f.feedback)
      assertFalse(f.checkpoint.passed())
    }
  }

  @Test
  fun `edits away and back and explicit resets reject late matching runs`() = timeoutRunBlocking {
    for (reset in listOf(false, true)) create(contract(150)).use { f ->
      f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
      if (reset) projects.get().service<MarsLearningProgress>().reset(identity)
      else
          withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(projects.get()) {
              f.document.setText(example + "\n# edit\n")
            }
            WriteCommandAction.runWriteCommandAction(projects.get()) { f.document.setText(example) }
          }
      run(f.handler, "150\n")
      f.await()
      assertEquals(listOf(MarsDownloadedRunCheckpoint.Outcome.SOURCE_CHANGED), f.feedback)
      assertFalse(f.checkpoint.passed())
    }
  }

  @Test
  fun `only the latest overlapping launch can complete its observation`() = timeoutRunBlocking {
    create(contract(150)).use { f ->
      val newer =
          projects
              .get()
              .service<MarsRunService>()
              .createProcess(
                  f.handler.sourceSet.entry,
                  options = MarsExecutionOptions(),
                  memoryDumps = emptyList(),
              )
      try {
        f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
        f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, newer)
        run(f.handler, "150\n")
        f.await()
        assertTrue(f.feedback.isEmpty())
        run(newer, "129\n")
        f.await()
        assertEquals(listOf(MarsDownloadedRunCheckpoint.Outcome.INPUT), f.feedback)
        assertFalse(f.checkpoint.passed())
      } finally {
        if (!newer.isProcessTerminated) newer.destroyProcess()
      }
    }
  }

  @Test
  fun `different executors paths options and disposed lessons cannot replace credit`() =
      timeoutRunBlocking {
        create(contract(150)).use { f ->
          val progress = projects.get().service<MarsLearningProgress>()
          val ticket =
              progress.beginCheck(identity, "observe", MarsLearningSource.revision(example))
          progress.finishCheck(ticket, true)
          f.checkpoint.starting("Debug", f.handler)
          f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, NopProcessHandler())
          val service = projects.get().service<MarsRunService>()
          val wrongPath =
              service.createProcess(
                  f.handler.sourceSet.entry.resolveSibling("another.asm"),
                  memoryDumps = emptyList(),
              )
          val wrongOptions =
              service.createProcess(
                  f.handler.sourceSet.entry,
                  options = MarsExecutionOptions(delayedBranching = true),
                  memoryDumps = emptyList(),
              )
          try {
            f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, wrongPath)
            f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, wrongOptions)
            assertTrue(f.checkpoint.passed())
            Disposer.dispose(f.lifetime)
            f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
            assertTrue(f.checkpoint.passed())
          } finally {
            wrongPath.destroyProcess()
            wrongOptions.destroyProcess()
          }
        }
      }

  @Test
  fun `leaving a lesson detaches its running observation without credit`() = timeoutRunBlocking {
    create(contract(150)).use { f ->
      f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
      Disposer.dispose(f.lifetime)
      run(f.handler, "150\n")
      f.await()
      assertTrue(f.feedback.isEmpty())
      assertFalse(f.checkpoint.passed())
    }
  }

  @Test
  fun `queued results cannot notify a closed lesson or replace a newer observation`() =
      timeoutRunBlocking {
        for (close in listOf(false, true)) {
          val started = CountDownLatch(1)
          val release = CountDownLatch(1)
          val executor = Executors.newSingleThreadExecutor()
          val dispatcher = executor.asCoroutineDispatcher()
          executor.submit {
            started.countDown()
            release.await()
          }
          withContext(Dispatchers.IO) { started.await() }
          try {
            create(contract(150), dispatcher = dispatcher).use { f ->
              f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, f.handler)
              run(f.handler, "150\n")
              var newer: MarsProcessHandler? = null
              if (close) Disposer.dispose(f.lifetime)
              else {
                newer =
                    projects
                        .get()
                        .service<MarsRunService>()
                        .createProcess(
                            f.handler.sourceSet.entry,
                            options = MarsExecutionOptions(),
                            memoryDumps = emptyList(),
                        )
                f.checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, newer)
              }
              try {
                release.countDown()
                f.await()
                assertTrue(f.feedback.isEmpty())
                assertFalse(f.checkpoint.passed())
              } finally {
                newer?.destroyProcess()
              }
            }
          } finally {
            release.countDown()
            dispatcher.close()
          }
        }
      }

  private class Fixture(
      val checkpoint: MarsDownloadedRunCheckpoint,
      val handler: MarsProcessHandler,
      val document: Document,
      val scope: CoroutineScope,
      val lifetime: Disposable,
      val feedback: List<MarsDownloadedRunCheckpoint.Outcome>,
  ) : AutoCloseable {
    suspend fun await() {
      scope.coroutineContext[Job]!!.children.toList().joinAll()
    }

    override fun close() {
      Disposer.dispose(lifetime)
      scope.cancel()
      if (!handler.isProcessTerminated) handler.destroyProcess()
    }
  }

  private suspend fun create(
      step: MarsNativeRunStep,
      source: String = example,
      dispatcher: CoroutineDispatcher = Dispatchers.Default,
  ): Fixture {
    val project = projects.get()
    val file =
        withContext(Dispatchers.EDT) {
          fixture.addFileToProject("byte-${fileNumber++}.asm", source).virtualFile
        }
    val document =
        withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().getDocument(file)!! }
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    val lifetime = Disposer.newDisposable()
    val feedback = CopyOnWriteArrayList<MarsDownloadedRunCheckpoint.Outcome>()
    val checkpoint =
        withContext(Dispatchers.EDT) {
          MarsDownloadedRunCheckpoint(
              project,
              scope,
              lifetime,
              file.toNioPath(),
              document,
              identity,
              step,
              feedback::add,
          )
        }
    val handler =
        project
            .service<MarsRunService>()
            .createProcess(
                file.toNioPath(),
                options = MarsExecutionOptions(),
                memoryDumps = emptyList(),
            )
    return Fixture(checkpoint, handler, document, scope, lifetime, feedback)
  }

  private suspend fun run(handler: MarsProcessHandler, input: String, expectedExit: Int = 0) {
    val done = CompletableDeferred<Int>()
    handler.addProcessListener(
        object : ProcessListener {
          override fun processTerminated(event: ProcessEvent) {
            done.complete(event.exitCode)
          }
        },
    )
    withContext(Dispatchers.EDT) { handler.startNotify() }
    withContext(Dispatchers.IO) {
      handler.processInput.write(input.toByteArray())
      handler.processInput.flush()
    }
    assertEquals(expectedExit, done.await())
  }
}
