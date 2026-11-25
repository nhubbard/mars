package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionManager
import com.intellij.execution.configurations.runConfigurationType
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.NopProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.run.MarsRunType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MarsConsoleRunCheckpointTest {
  private val projectFixture = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projectFixture)
  private val example =
      MarsLearningWorkspace::class
          .java
          .getResourceAsStream("/learnProjects/mars_mips/MARSLearningProject/first-program.asm")!!
          .use { it.readBytes().toString(Charsets.UTF_8) }

  @Test
  fun `bounded transcript accepts chunked stdout and rejects diagnostics excess and wrong results`() {
    fun matches(vararg chunks: Pair<String, Key<*>>): Boolean =
        MarsConsoleRunCheckpoint.Transcript()
            .apply { chunks.forEach { append(it.first, it.second) } }
            .matches()
    val good = MarsConsoleRunCheckpoint.EXPECTED
    assertTrue(
        matches(
            good.take(7) to ProcessOutputTypes.STDOUT,
            good.drop(7) to ProcessOutputTypes.STDOUT,
        ),
    )
    assertTrue(
        matches(
            "ignored" to ProcessOutputTypes.SYSTEM,
            "" to ProcessOutputTypes.STDERR,
            good to ProcessOutputTypes.STDOUT,
        ),
    )
    assertFalse(matches(good.replace("41", "42") to ProcessOutputTypes.STDOUT))
    assertFalse(matches((good + "extra") to ProcessOutputTypes.STDOUT))
    assertFalse(matches("failure" to ProcessOutputTypes.STDERR, good to ProcessOutputTypes.STDOUT))
  }

  @Test
  fun `native process starting event observes actual console input output and source`() =
      timeoutRunBlocking {
        exercise(true) { _, handler ->
          val project = projectFixture.get()
          withContext(Dispatchers.EDT) {
            val type = runConfigurationType<MarsRunType>()
            val config =
                MarsRunConfiguration(project, type.configurationFactories.single(), "Observed run")
            val env =
                ExecutionEnvironmentBuilder.create(
                        DefaultRunExecutor.getRunExecutorInstance(),
                        config,
                    )
                    .build()
            project.messageBus
                .syncPublisher(ExecutionManager.EXECUTION_TOPIC)
                .processStarting(DefaultRunExecutor.EXECUTOR_ID, env, handler)
          }
          run(handler, "20\n")
        }
      }

  @Test
  fun `wrong input and changed source cannot satisfy a successful earlier run`() =
      timeoutRunBlocking {
        exercise(false) { checkpoint, handler ->
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
          run(handler, "3\n")
        }
      }

  @Test
  fun `editing away and back while running rejects a late matching transcript`() =
      timeoutRunBlocking {
        exercise(false) { checkpoint, handler ->
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
          edit { "$it\n# student edit\n" }
          edit { example }
          run(handler, "20\n")
        }
      }

  @Test
  fun `unrelated executors handlers options and paths do not become attempts`() =
      timeoutRunBlocking {
        exercise(false, options = MarsExecutionOptions(delayedBranching = true)) {
            checkpoint,
            handler ->
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
          checkpoint.starting("Debug", handler)
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, NopProcessHandler())
          val other =
              projectFixture
                  .get()
                  .service<MarsRunService>()
                  .createProcess(
                      handler.sourceSet.entry.resolveSibling("other.asm"),
                      memoryDumps = emptyList(),
                  )
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, other)
          other.destroyProcess()
          run(handler, "20\n")
        }
      }

  @Test
  fun `successful run is invalidated before a later source edit and not restored by undo`() =
      timeoutRunBlocking {
        exercise(
            true,
            after = { checkpoint ->
              edit { "$it\n# changed after success\n" }
              assertFalse(checkpoint.passed())
              edit { example }
              assertFalse(checkpoint.passed())
            },
        ) { checkpoint, handler ->
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
          run(handler, "20\n")
        }
      }

  @Test
  fun `assembly failure does not complete the observation`() = timeoutRunBlocking {
    exercise(false, source = ".text\nnot_an_instruction\n") { checkpoint, handler ->
      checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
      run(handler, "", expectedExit = 1)
    }
  }

  @Suppress("UsePropertyAccessSyntax")
  @Test
  fun `saving source and unrelated files retain success but renaming it invalidates it`() =
      timeoutRunBlocking {
        exercise(
            true,
            after = { checkpoint ->
              withContext(Dispatchers.EDT) {
                val document = fixture.editor.document
                FileDocumentManager.getInstance().saveDocument(document)
                val unrelated = fixture.addFileToProject("unrelated.txt", "other work")
                WriteCommandAction.runWriteCommandAction(projectFixture.get()) {
                  FileDocumentManager.getInstance()
                      .getDocument(unrelated.virtualFile)!!
                      .setText("edited other work")
                }
                assertTrue(checkpoint.passed())
                WriteCommandAction.runWriteCommandAction(projectFixture.get()) {
                  fixture.file.virtualFile.rename(this, "renamed.asm")
                }
                assertFalse(checkpoint.passed())
              }
            },
        ) { checkpoint, handler ->
          edit { "$it\n# unsaved work included in this run\n" }
          checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
          run(handler, "20\n")
        }
      }

  @Test
  fun `guided single-file observation rejects an additional include`() = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      fixture.addFileToProject("mars-labs/console/extra.inc", "# included source\n")
    }
    exercise(false, source = "$example.include \"extra.inc\"\n") { checkpoint, handler ->
      checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
      run(handler, "20\n")
    }
  }

  @Test
  fun `a configured exception handler is outside this guided single-file observation`() =
      timeoutRunBlocking {
        val settings = projectFixture.get().service<MarsAssemblySettings>()
        val handlerFile =
            withContext(Dispatchers.EDT) {
              fixture.addFileToProject("exception.asm", ".ktext 0x80000180\nnop\n").virtualFile
            }
        settings.changeExceptionHandler(handlerFile.path)
        try {
          exercise(false) { checkpoint, handler ->
            checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
            run(handler, "20\n")
          }
        } finally {
          settings.changeExceptionHandler("")
        }
      }

  @Test
  fun `checkpoint refuses a document not belonging to the selected file`() = timeoutRunBlocking {
    val project = projectFixture.get()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val lifetime = Disposer.newDisposable()
    try {
      withContext(Dispatchers.EDT) {
        val file = fixture.addFileToProject("source.asm", example).virtualFile
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        assertThrows<IllegalArgumentException> {
          MarsConsoleRunCheckpoint(
              project,
              scope,
              lifetime,
              file.toNioPath().resolveSibling("other.asm"),
              document,
          )
        }
        assertThrows<IllegalArgumentException> {
          MarsConsoleRunCheckpoint(
              project,
              scope,
              lifetime,
              file.toNioPath(),
              EditorFactory.getInstance().createDocument(example),
          )
        }
      }
    } finally {
      Disposer.dispose(lifetime)
      scope.cancel()
    }
    Unit
  }

  @Test
  fun `repair rejects the counterexample and accepts different correct argument preparations`() =
      timeoutRunBlocking {
        val faulty = example.replace($$"move $a0, $t0", $$"move $a0, $v0")
        val alternative = example.replace($$"move $a0, $t0", $$"or $a0, $t0, $zero")
        for ((source, expected) in listOf(faulty to false, example to true, alternative to true)) {
          exercise(
              expected,
              source,
              lessonIdentity = MarsConsoleRunCheckpoint.repairIdentity,
              fileName = "repair-output.asm",
          ) { checkpoint, handler ->
            checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
            run(handler, "20\n")
          }
        }
      }

  private suspend fun exercise(
      expected: Boolean,
      source: String = example,
      options: MarsExecutionOptions = MarsExecutionOptions(),
      lessonIdentity: MarsLessonIdentity = MarsConsoleRunCheckpoint.identity,
      fileName: String = "first-program.asm",
      after: suspend (MarsConsoleRunCheckpoint) -> Unit = {},
      body: suspend (MarsConsoleRunCheckpoint, MarsProcessHandler) -> Unit,
  ) {
    val project = projectFixture.get()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val lifetime = Disposer.newDisposable()
    val file =
        withContext(Dispatchers.EDT) {
          fixture.addFileToProject("mars-labs/console/$fileName", source).virtualFile.also {
            fixture.configureFromExistingVirtualFile(it)
          }
        }
    val document =
        withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().getDocument(file)!! }
    val handler =
        project
            .service<MarsRunService>()
            .createProcess(file.toNioPath(), options = options, memoryDumps = emptyList())
    val checkpoint =
        withContext(Dispatchers.EDT) {
          MarsConsoleRunCheckpoint(
              project,
              scope,
              lifetime,
              file.toNioPath(),
              document,
              lessonIdentity,
          )
        }
    try {
      body(checkpoint, handler)
      scope.coroutineContext[Job]!!.children.toList().joinAll()
      assertEquals(expected, checkpoint.passed())
      after(checkpoint)
      project.service<MarsLearningProgress>().reset(lessonIdentity)
      assertFalse(checkpoint.passed())
    } finally {
      withContext(Dispatchers.EDT) {
        Disposer.dispose(lifetime)
        val before = project.service<MarsLearningProgress>().snapshot(lessonIdentity)
        checkpoint.starting(DefaultRunExecutor.EXECUTOR_ID, handler)
        assertEquals(before, project.service<MarsLearningProgress>().snapshot(lessonIdentity))
        if (!handler.isProcessTerminated) handler.destroyProcess()
      }
      scope.cancel()
    }
  }

  @Suppress("UsePropertyAccessSyntax")
  private suspend fun edit(change: (String) -> String) =
      withContext(Dispatchers.EDT) {
        val document = FileDocumentManager.getInstance().getDocument(fixture.file.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(projectFixture.get()) {
          document.setText(change(document.text))
        }
      }

  private suspend fun run(handler: MarsProcessHandler, input: String, expectedExit: Int = 0) {
    val exit = CompletableDeferred<Int>()
    handler.addProcessListener(
        object : ProcessListener {
          override fun processTerminated(event: ProcessEvent) {
            exit.complete(event.exitCode)
          }
        },
    )
    withContext(Dispatchers.EDT) { handler.startNotify() }
    if (input.isNotEmpty())
        withContext(Dispatchers.IO) {
          handler.processInput.write(input.toByteArray())
          handler.processInput.flush()
        }
    assertEquals(expectedExit, exit.await())
  }
}
