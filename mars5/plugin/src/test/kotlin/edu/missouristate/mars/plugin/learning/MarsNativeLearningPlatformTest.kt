@file:Suppress("OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.ui.UIUtil
import edu.missouristate.mars.api.SessionSyscallEnvironment
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.plugin.MarsSessionService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import training.dsl.*
import training.lang.LangManager
import training.learn.CourseManager
import training.learn.course.LessonType
import training.ui.LearningUiManager

@TestApplication
class MarsNativeLearningPlatformTest {
  private val projectFixture = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projectFixture)

  @Test
  fun `native course and language extensions resolve to the packaged MARS learning module`() =
      timeoutRunBlocking {
        val support = LangManager.getInstance().getLangSupportById(MARS_LEARNING_LANGUAGE)!!
        assertInstanceOf(MarsLearningLanguageSupport::class.java, support)
        assertEquals(MARS_LEARNING_LANGUAGE, support.primaryLanguage)
        assertEquals("MARSLearningProject", support.contentRootDirectoryName)
        assertEquals("mars-labs/console/first-program.asm", support.sampleFilePath)
        assertTrue(support.useUserProjects)
        val extension =
            CourseManager.COURSE_MODULES_EP.extensionList.single {
              it.language == MARS_LEARNING_LANGUAGE
            }
        val module = extension.instance.modules().single()
        assertEquals("mars.console", module.id)
        assertEquals(LessonType.USER_PROJECT, module.moduleType)
        assertEquals("mars-labs/console/first-program.asm", module.sampleFilePath)
        assertEquals("left", module.preferredLearnWindowAnchor(projectFixture.get()).toString())
        assertEquals(MARS_LEARNING_LANGUAGE, module.lessons.first().languageId)
        assertFalse(module.lessons.first().properties.openFileAtStart)
        assertTrue(module.lessons.first().alwaysRestart)
        assertTrue(support.isSdkConfigured(projectFixture.get()))
        assertNull(support.getSdkForProject(projectFixture.get(), null))
        support.checkSdk(null, projectFixture.get())
        support.applyToProjectAfterConfigure()(projectFixture.get())
      }

  @Test
  fun `restarting preparation preserves a student file and its earlier predictions`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val file =
              fixture.addFileToProject("mars-labs/console/first-program.asm", "# student work\n")
          val support = MarsLearningLanguageSupport()
          val lesson = MarsLearningCourse().modules().single().lessons.first()
          lesson.prepare(projectFixture.get())
          projectFixture
              .get()
              .service<MarsLearningProgress>()
              .recordChoice(MarsPredictions.identity, "doubling", "b")
          support.cleanupBeforeLessons(projectFixture.get())
          lesson.prepare(projectFixture.get())
          assertEquals("# student work\n", file.text)
          assertTrue(MarsLearningWorkspace.openExample(projectFixture.get()).await())
          assertEquals(
              "b",
              projectFixture
                  .get()
                  .service<MarsLearningProgress>()
                  .snapshot(MarsPredictions.identity)!!
                  .quizAttempts
                  .last()
                  .choice,
          )
        }
      }

  @Test
  fun `public learning project resource copies without hidden solutions and executes the worked contract`(
      @TempDir folder: Path,
  ) {
    val support = MarsLearningLanguageSupport()
    assertTrue(support.copyLearningProjectFiles(folder.toFile(), null))
    val file = folder.resolve("first-program.asm")
    val source = Files.readString(file)
    assertTrue(source.contains("calculate:"))
    for ((input, expected) in listOf("20\n" to "41", "0\n" to "1", "-3\n" to "-5")) {
      MarsSessionService.createMarsComponent().context().use { context ->
        val program = context.assemble(listOf(file), null, extended = true, warnings = true)
        val bytes = ByteArrayOutputStream()
        PrintStream(bytes, true, Charsets.UTF_8).use { output ->
          SessionSyscallEnvironment(
                  context.memory,
                  ByteArrayInputStream(input.toByteArray()),
                  output,
                  output,
              )
              .use { io ->
                program.setSyscallEnvironment(io)
                context.simulator().setInstructionLimit(1000)
                val stop = context.simulator().runAsync(0, null).get(10, TimeUnit.SECONDS)
                assertEquals(StopEvent.Reason.NORMAL_EXIT, stop.reason)
                assertEquals(
                    "Enter a small integer: Twice your number plus one is: $expected\n",
                    bytes.toString(Charsets.UTF_8),
                )
              }
        }
      }
    }
    assertEquals(
        setOf("first-program.asm", "wrong-output.asm", "independent.asm"),
        Files.list(folder).use { it.map { p -> p.fileName.toString() }.toList().toSet() },
    )
  }

  @Test
  fun `prediction actions are repeatable and preserve wrong attempts before a correct choice`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          for ((id, prediction) in
              listOf(
                  "Mars.Learning.PredictDoubling" to MarsPredictions.doubling,
                  "Mars.Learning.PredictNegative" to MarsPredictions.negative,
                  "Mars.Learning.PredictOutput" to MarsPredictions.output,
                  "Mars.Learning.PredictWrongOutput" to MarsPredictions.wrongOutput,
                  "Mars.Learning.DiagnoseOutput" to MarsPredictions.diagnosis,
              )) {
            val progress = projectFixture.get().service<MarsLearningProgress>()
            progress.reset(MarsPredictions.identity)
            assertFalse(MarsPredictions.hasCorrectAnswer(projectFixture.get(), prediction))
            inspectPrediction(id) { dialog ->
              val buttons = UIUtil.findComponentsOfType(dialog.panel, JBRadioButton::class.java)
              assertEquals(3, buttons.size)
              dialog.doOKAction()
              assertTrue(progress.snapshot(MarsPredictions.identity)!!.quizAttempts.isEmpty())
              val wrong =
                  prediction.choices.indices.filter {
                    prediction.choices[it].id != prediction.correct
                  }
              for (choice in wrong) {
                buttons[choice].doClick()
                dialog.doOKAction()
                assertFalse(dialog.isOK)
                assertFalse(MarsPredictions.hasCorrectAnswer(projectFixture.get(), prediction))
              }
              buttons[prediction.choices.indexOfFirst { it.id == prediction.correct }].doClick()
              dialog.doOKAction()
              assertTrue(dialog.isOK)
              assertTrue(MarsPredictions.hasCorrectAnswer(projectFixture.get(), prediction))
              assertEquals(
                  wrong.map { prediction.choices[it].id } + prediction.correct,
                  progress.snapshot(MarsPredictions.identity)!!.quizAttempts.map { it.choice },
              )
            }
            inspectPrediction(id) { dialog -> dialog.doCancelAction() }
            assertEquals(3, progress.snapshot(MarsPredictions.identity)!!.quizAttempts.size)
          }
        }
      }

  @Test
  fun `prediction actions are unavailable without a project`() {
    for (id in
        listOf(
            "Mars.Learning.PredictDoubling",
            "Mars.Learning.PredictNegative",
            "Mars.Learning.PredictOutput",
            "Mars.Learning.PredictWrongOutput",
            "Mars.Learning.DiagnoseOutput",
        )) {
      val action = ActionManager.getInstance().getAction(id)
      assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
      val event =
          AnActionEvent.createEvent(
              action,
              DataContext.EMPTY_CONTEXT,
              null,
              ActionPlaces.MAIN_MENU,
              ActionUiKind.NONE,
              null,
          )
      action.update(event)
      assertFalse(event.presentation.isEnabled)
      action.actionPerformed(event)
    }
  }

  @Test
  fun `native lesson DSL registers meaningful predicates without modifying its document`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val source =
              fixture.addFileToProject(
                  "mars-labs/console/first-program.asm",
                  "# unchanged student text\n",
              )
          fixture.configureFromExistingVirtualFile(source.virtualFile)
          for (lesson in MarsLearningCourse().modules().single().lessons) {
            lesson.prepare(projectFixture.get())
            if (lesson is MarsIndependentLesson) {
              projectFixture.get().service<MarsLearningPolicy>().update(requirePredictions = true)
              projectFixture.get().service<MarsLearningProgress>().reset(MarsPredictions.identity)
            }
            // The host's DSL construction types are internal. Test-only reflection exercises its
            // actual registrar; packaged MARS code uses only public course/task APIs.
            val loader = LessonContext::class.java.classLoader
            val executorClass = Class.forName("training.dsl.impl.LessonExecutor", true, loader)
            val executor =
                executorClass.constructors
                    .single()
                    .newInstance(
                        lesson,
                        projectFixture.get(),
                        fixture.editor,
                        fixture.file.virtualFile,
                    )
            try {
              val contextClass = Class.forName("training.dsl.impl.LessonContextImpl", true, loader)
              val context =
                  contextClass.constructors.single().newInstance(executor) as LessonContext
              lesson.fullLessonContent(context)
              val tasks =
                  executorClass
                      .getDeclaredField("taskActions")
                      .apply { isAccessible = true }
                      .get(executor) as List<*>
              assertTrue(tasks.size >= 3)
              val recorderClass = Class.forName("training.learn.ActionsRecorder", true, loader)
              val recorder =
                  recorderClass.constructors
                      .single()
                      .newInstance(projectFixture.get(), fixture.editor.document, executor)
              val previous: () -> PreviousTaskInfo = {
                error("No restore state needed by this lesson")
              }
              val runtime =
                  TaskRuntimeContext::class
                      .java
                      .constructors
                      .single { it.parameterCount == 4 }
                      .newInstance(executor, recorder, {}, previous) as TaskRuntimeContext
              val predicates = mutableListOf<TaskRuntimeContext.() -> Boolean>()
              val preparations = mutableListOf<CompletableFuture<Boolean>>()
              val runtimeTexts = mutableListOf<RuntimeTextContext.() -> String?>()
              val eagerTexts = mutableListOf<String>()
              var timedChecks = 0
              val taskContext =
                  object : TaskContext() {
                    override val project: Project
                      get() = projectFixture.get()

                    override fun runtimeText(callback: RuntimeTextContext.() -> String?) {
                      runtimeTexts += callback
                    }

                    override fun text(text: String, useBalloon: LearningBalloonConfig?) {
                      eagerTexts += text
                    }

                    override fun before(preparation: TaskRuntimeContext.() -> Unit) {
                      preparation(runtime)
                    }

                    override fun stateCheck(checkState: TaskRuntimeContext.() -> Boolean) =
                        super.stateCheck(checkState).also { predicates += checkState }

                    override fun timerCheck(
                        delayMillis: Int,
                        checkState: TaskRuntimeContext.() -> Boolean,
                    ): CompletableFuture<Boolean> {
                      timedChecks++
                      predicates += checkState
                      return super.timerCheck(delayMillis, checkState)
                    }

                    override fun addFutureStep(p: DoneStepContext.() -> Unit) {
                      val future = CompletableFuture<Boolean>()
                      preparations += future
                      p(DoneStepContext(future, runtime))
                    }

                    override fun addStep(step: CompletableFuture<Boolean>) {
                      preparations += step
                    }
                  }
              for (task in tasks) {
                val field =
                    task!!.javaClass.getDeclaredField("taskContent").apply { isAccessible = true }
                @Suppress("UNCHECKED_CAST")
                val content = field.get(task) as? (TaskContext.() -> Unit)
                content?.invoke(taskContext)
                for (preparation in preparations) assertTrue(preparation.await())
              }
              for (preparation in preparations) assertTrue(preparation.await())
              assertEquals(
                  when (lesson) {
                    is MarsConsoleRunLesson -> 1
                    is MarsConsoleDebugLesson -> 3
                    is MarsConsoleOutputLesson -> 4
                    is MarsConsoleDiagnosisLesson -> 5
                    is MarsConsoleRepairLesson -> 1
                    is MarsIndependentLesson -> 2
                    else -> 2
                  },
                  predicates.size,
              )
              assertEquals(
                  when (lesson) {
                    is MarsConsoleRunLesson -> 1
                    is MarsConsoleDebugLesson -> 3
                    is MarsConsoleOutputLesson -> 3
                    is MarsConsoleDiagnosisLesson -> 3
                    is MarsConsoleRepairLesson -> 1
                    is MarsIndependentLesson -> 2
                    else -> 0
                  },
                  timedChecks,
              )
              for (prediction in
                  listOf(
                      MarsPredictions.doubling,
                      MarsPredictions.negative,
                      MarsPredictions.output,
                      MarsPredictions.wrongOutput,
                      MarsPredictions.diagnosis,
                  )) {
                assertFalse(eagerTexts.any { it.contains(prediction.answerExplanation) })
              }
              assertTrue(predicates.all { !it(runtime) })
              if (lesson is MarsIndependentLesson) {
                assertTrue(
                    runtimeTexts.first()(RuntimeTextContext(runtime))!!.contains(
                        "Your course requires",
                    ),
                )
                val controls = runtimeTexts.last()(RuntimeTextContext(runtime))!!
                assertTrue(controls.contains("Check my program"))
                assertTrue(controls.contains("Open a hint"))
                assertFalse(controls.contains("<action>"))
                Regex("id=\"([0-9]+)\"").findAll(controls).forEach {
                  assertNotNull(LearningUiManager.getAndClearCallback(it.groupValues[1]))
                }
              }
              val progress = projectFixture.get().service<MarsLearningProgress>()
              when (lesson) {
                is MarsConsoleRunLesson -> {
                  val ticket =
                      progress.beginCheck(
                          MarsConsoleRunCheckpoint.identity,
                          MarsConsoleRunCheckpoint.STEP,
                          "test observation",
                      )
                  progress.finishCheck(ticket, true)
                }

                is MarsConsoleDebugLesson -> {
                  val ticket =
                      progress.beginCheck(
                          MarsConsoleDebugCheckpoint.identity,
                          MarsConsoleDebugCheckpoint.STEP,
                          "test debug observation",
                      )
                  progress.finishCheck(ticket, true)
                }

                is MarsConsoleOutputLesson -> {
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "output",
                      MarsPredictions.output.correct,
                  )
                  val ticket =
                      progress.beginCheck(
                          MarsConsoleDebugCheckpoint.Segment.OUTPUT.identity,
                          MarsConsoleDebugCheckpoint.STEP,
                          "test output observation",
                      )
                  progress.finishCheck(ticket, true)
                }

                is MarsConsoleDiagnosisLesson -> {
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "wrong-output",
                      MarsPredictions.wrongOutput.correct,
                  )
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "diagnosis",
                      MarsPredictions.diagnosis.correct,
                  )
                  val ticket =
                      progress.beginCheck(
                          MarsConsoleDebugCheckpoint.Segment.DIAGNOSIS.identity,
                          MarsConsoleDebugCheckpoint.STEP,
                          "test faulty argument observation",
                      )
                  progress.finishCheck(ticket, true)
                }

                is MarsConsoleRepairLesson -> {
                  val ticket =
                      progress.beginCheck(
                          MarsConsoleRunCheckpoint.repairIdentity,
                          MarsConsoleRunCheckpoint.STEP,
                          "test repair",
                      )
                  progress.finishCheck(ticket, true)
                }

                is MarsIndependentLesson -> {
                  progress.open(MarsPredictions.identity, MarsPredictions.identity.contentSha256)
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "doubling",
                      MarsPredictions.doubling.correct,
                  )
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "negative",
                      MarsPredictions.negative.correct,
                  )
                  val ticket =
                      progress.beginCheck(
                          MarsIndependentCheckpoint.identity,
                          MarsIndependentCheckpoint.STEP,
                          "test independent assessment",
                      )
                  progress.finishCheck(ticket, true)
                }

                else -> {
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "doubling",
                      MarsPredictions.doubling.correct,
                  )
                  progress.recordChoice(
                      MarsPredictions.identity,
                      "negative",
                      MarsPredictions.negative.correct,
                  )
                }
              }
              assertTrue(predicates.all { it(runtime) })
              if (lesson is MarsIndependentLesson)
                  assertNull(runtimeTexts.first()(RuntimeTextContext(runtime)))
              assertEquals("# unchanged student text\n", fixture.editor.document.text)
            } finally {
              Disposer.dispose(executor as Disposable)
            }
          }
        }
      }

  @Test
  fun `workspace initialization creates only missing sources in independent projects`(
      @TempDir folder: Path,
  ) {
    val first = Files.createDirectory(folder.resolve("first"))
    val second = Files.createDirectory(folder.resolve("second"))
    val source = MarsLearningWorkspace.ensureExampleAt(first)
    val original = Files.readString(source)
    assertTrue(original.contains("calculate:"))
    Files.writeString(source, "# retained edits\n")
    assertEquals(source, MarsLearningWorkspace.ensureExampleAt(first))
    assertEquals("# retained edits\n", Files.readString(source))
    assertEquals(original, Files.readString(MarsLearningWorkspace.ensureExampleAt(second)))
  }

  @Test
  fun `counterexample and repair sources are independent and edits survive initialization`(
      @TempDir root: Path,
  ) {
    val files =
        MarsLearningWorkspace.Exercise.entries.associateWith {
          MarsLearningWorkspace.ensureExampleAt(root, it)
        }
    val worked = files.getValue(MarsLearningWorkspace.Exercise.EXAMPLE)
    val faulty = files.getValue(MarsLearningWorkspace.Exercise.DIAGNOSIS)
    val repair = files.getValue(MarsLearningWorkspace.Exercise.REPAIR)
    assertTrue(Files.readString(worked).contains($$"move $a0, $t0"))
    val originalFault = Files.readString(faulty)
    assertTrue(originalFault.contains($$"move $a0, $v0"))
    assertEquals(originalFault, Files.readString(repair))
    Files.writeString(repair, "# student repair retained\n")
    for (exercise in MarsLearningWorkspace.Exercise.entries) MarsLearningWorkspace.ensureExampleAt(
        root,
        exercise,
    )
    assertEquals("# student repair retained\n", Files.readString(repair))
    assertEquals(originalFault, Files.readString(faulty))
    assertTrue(Files.readString(worked).contains($$"move $a0, $t0"))
  }

  @Test
  fun `workspace initializer rejects occupied paths and symlinks without changing their targets`(
      @TempDir folder: Path,
  ) {
    val badDirectory = Files.createDirectory(folder.resolve("bad-directory"))
    Files.writeString(badDirectory.resolve("mars-labs"), "retain this file")
    assertThrows<IllegalArgumentException> { MarsLearningWorkspace.ensureExampleAt(badDirectory) }
    assertEquals("retain this file", Files.readString(badDirectory.resolve("mars-labs")))

    val external = Files.createDirectory(folder.resolve("external"))
    val linkedDirectory = Files.createDirectory(folder.resolve("linked-directory"))
    Files.createSymbolicLink(linkedDirectory.resolve("mars-labs"), external)
    assertThrows<IllegalArgumentException> {
      MarsLearningWorkspace.ensureExampleAt(linkedDirectory)
    }
    assertTrue(Files.list(external).use { it.toList().isEmpty() })

    for (link in listOf(false, true)) {
      val root = Files.createDirectory(folder.resolve("bad-file-$link"))
      val console = Files.createDirectories(root.resolve("mars-labs/console"))
      if (link) {
        val target = Files.writeString(folder.resolve("retained.asm"), "# retained target\n")
        Files.createSymbolicLink(console.resolve("first-program.asm"), target)
        assertThrows<IllegalArgumentException> { MarsLearningWorkspace.ensureExampleAt(root) }
        assertEquals("# retained target\n", Files.readString(target))
      } else {
        Files.createDirectory(console.resolve("first-program.asm"))
        assertThrows<IllegalArgumentException> { MarsLearningWorkspace.ensureExampleAt(root) }
      }
    }
  }

  @Test
  fun `workspace requires a real project and a prepared file before opening`() =
      timeoutRunBlocking {
        val emptyProject = ProjectManager.getInstance().defaultProject
        assertThrows<IllegalArgumentException> { MarsLearningWorkspace.ensureExample(emptyProject) }
        withContext(Dispatchers.EDT) {
          assertThrows<IllegalStateException> {
            MarsLearningWorkspace.openExample(projectFixture.get()).await()
          }
        }
        Unit
      }

  @Test
  fun `a fresh lesson file opens from the host write unsafe context without refreshing on that thread`() =
      timeoutRunBlocking {
        val project = projectFixture.get()
        val source = MarsLearningWorkspace.ensureExample(project)
        val requested = CompletableDeferred<CompletableFuture<Boolean>>()
        val completionOnEdt = CompletableDeferred<Boolean>()
        ApplicationManager.getApplication()
            .invokeLater(
                {
                  try {
                    val opening = MarsLearningWorkspace.openExample(project)
                    opening.thenRun {
                      completionOnEdt.complete(ApplicationManager.getApplication().isDispatchThread)
                    }
                    requested.complete(opening)
                  } catch (error: Throwable) {
                    requested.completeExceptionally(error)
                  }
                },
                ModalityState.any(),
            )
        assertTrue(requested.await().await())
        assertTrue(completionOnEdt.await())
        withContext(Dispatchers.EDT) {
          assertEquals(
              source,
              FileEditorManager.getInstance(project).selectedFiles.single().toNioPath(),
          )
        }
      }

  private fun inspectPrediction(actionId: String, inspect: (MarsPredictionDialog) -> Unit) {
    val action = ActionManager.getInstance().getAction(actionId)
    var inspected = false
    UiInterceptors.register(
        object :
            UiInterceptors.UiInterceptor<MarsPredictionDialog>(MarsPredictionDialog::class.java) {
          override fun doIntercept(dialog: MarsPredictionDialog) {
            try {
              inspect(dialog)
              inspected = true
            } finally {
              if (!dialog.isOK) dialog.doCancelAction()
            }
          }
        },
    )
    try {
      val context =
          SimpleDataContext.builder().add(CommonDataKeys.PROJECT, projectFixture.get()).build()
      val event =
          AnActionEvent.createEvent(
              action,
              context,
              null,
              ActionPlaces.MAIN_MENU,
              ActionUiKind.NONE,
              null,
          )
      action.update(event)
      assertTrue(event.presentation.isEnabled)
      action.actionPerformed(event)
      assertTrue(inspected)
    } finally {
      UiInterceptors.clear()
    }
  }
}
