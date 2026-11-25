package edu.missouristate.mars.plugin.learning

import com.intellij.ide.impl.OpenProjectTask
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.edtWriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.JDOMUtil
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import java.awt.Rectangle
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.swing.Timer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import training.dsl.LessonContext
import training.dsl.PreviousTaskInfo
import training.dsl.RuntimeTextContext
import training.dsl.TaskContext
import training.dsl.TaskRuntimeContext
import training.ui.LearningUiManager

@TestApplication
class MarsInlinePredictionTest {
  private val projectFixture = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projectFixture)

  private fun callbacks(text: String): List<() -> Unit> =
      Regex("<callback id=\"([0-9]+)\">")
          .findAll(text)
          .map {
            LearningUiManager.getAndClearCallback(it.groupValues[1]).also { callback ->
              assertNotNull(callback)
            }!!
          }
          .toList()

  @Test
  fun `all five questions answer in the lesson without action search and retain wrong attempts`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = projectFixture.get()
          val progress = project.service<MarsLearningProgress>()
          for (prediction in
              listOf(
                  MarsPredictions.doubling,
                  MarsPredictions.negative,
                  MarsPredictions.output,
                  MarsPredictions.wrongOutput,
                  MarsPredictions.diagnosis,
              )) {
            progress.reset(MarsPredictions.identity)
            val feedback = mutableListOf<String>()
            var completed = 0
            val text =
                MarsInlinePrediction.text(project, prediction, { true }, feedback::add) {
                  completed++
                }
            assertFalse(text.contains("<action>"))
            assertFalse(text.contains("href="))
            assertTrue(text.contains(prediction.question))
            val choices = callbacks(text)
            assertEquals(3, choices.size)
            val answer = prediction.choices.indexOfFirst { it.id == prediction.correct }
            for (wrong in choices.indices.filter { it != answer }) {
              choices[wrong]()
              assertEquals(0, completed)
              assertFalse(MarsPredictions.hasCorrectAnswer(project, prediction))
            }
            assertEquals(2, feedback.size)
            choices[answer]()
            assertEquals(1, completed)
            assertTrue(MarsPredictions.hasCorrectAnswer(project, prediction))
            assertEquals(3, progress.snapshot(MarsPredictions.identity)!!.quizAttempts.size)
            choices.forEach { it() }
            assertEquals(1, completed)
            assertEquals(3, progress.snapshot(MarsPredictions.identity)!!.quizAttempts.size)
            val restored =
                callbacks(
                    MarsInlinePrediction.text(
                        project,
                        prediction,
                        { true },
                        { error("No new feedback") },
                    ) {
                      error("Completed history must not be answered again")
                    },
                )
            restored.forEach { it() }
            assertEquals(3, progress.snapshot(MarsPredictions.identity)!!.quizAttempts.size)
          }
        }
      }

  @Test
  fun `an inactive earlier question cannot change history and native text escapes choice markup`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = projectFixture.get()
          val progress = project.service<MarsLearningProgress>()
          progress.reset(MarsPredictions.identity)
          val prediction =
              MarsPredictions.output.copy(
                  question = "A < B & C",
                  choices =
                      listOf(
                          MarsPredictionChoice("a", "<a href=\"man:bad\">bad</a>", "A < B & C"),
                      ),
              )
          var active = false
          val feedback = mutableListOf<String>()
          val text =
              MarsInlinePrediction.text(project, prediction, { active }, feedback::add) {
                error("Wrong choice")
              }
          assertTrue(text.contains("A &lt; B &amp; C"))
          assertFalse(text.contains("<a href="))
          val choice = callbacks(text).single()
          choice()
          assertTrue(progress.snapshot(MarsPredictions.identity)!!.quizAttempts.isEmpty())
          active = true
          choice()
          assertEquals(listOf("A &lt; B &amp; C"), feedback)
          assertEquals(1, progress.snapshot(MarsPredictions.identity)!!.quizAttempts.size)
        }
      }

  @Test
  fun `a callback retained by a closed project is harmless`(@TempDir folder: Path) =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project =
              ProjectManagerEx.getInstanceEx()
                  .newProject(
                      folder,
                      OpenProjectTask {
                        isNewProject = true
                        useDefaultProjectAsTemplate = false
                      },
                  )!!
          val choices =
              callbacks(
                  MarsInlinePrediction.text(
                      project,
                      MarsPredictions.doubling,
                      { true },
                      {
                        error("Closed project")
                      },
                  ) {
                    error("Closed project")
                  },
              )
          edtWriteAction { Disposer.dispose(project) }
          choices.forEach { it() }
        }
      }

  @Suppress("UsePropertyAccessSyntax")
  @Test
  fun `source anchors follow parsed instructions and preserve student text`() = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val source =
          $$".text\n# addu in a comment\nmain:\n    addu $t0, $v0, $v0\n    addiu $t0, $t0, 1\n    move $a0, $t0\n    addu $t2, $t2, $t2\n"
      fixture.configureByText("anchors.asm", source)
      for ((prediction, mnemonic) in
          listOf(
              MarsPredictions.doubling to "addu",
              MarsPredictions.negative to "addiu",
              MarsPredictions.output to "move",
              MarsPredictions.wrongOutput to "move",
              MarsPredictions.diagnosis to "move",
          )) {
        val range = MarsInlinePrediction.instructionRange(fixture.editor, prediction)!!
        assertTrue(source.substring(range.startOffset, range.endOffset).startsWith(mnemonic))
        val rectangle =
            MarsInlinePrediction.instructionHighlight(fixture.editor, prediction)(
                fixture.editor.contentComponent,
            )!!
        assertTrue(rectangle.width > 0)
        assertEquals(fixture.editor.lineHeight, rectangle.height)
      }
      assertEquals(source, fixture.editor.document.text)
      WriteCommandAction.runWriteCommandAction(projectFixture.get()) {
        fixture.editor.document.setText(".text\nnop\n")
      }
      assertNull(MarsInlinePrediction.instructionRange(fixture.editor, MarsPredictions.doubling))
      PsiDocumentManager.getInstance(projectFixture.get()).commitAllDocuments()
      assertNull(MarsInlinePrediction.instructionRange(fixture.editor, MarsPredictions.doubling))
      assertNull(
          MarsInlinePrediction.instructionHighlight(fixture.editor, MarsPredictions.doubling)(
              fixture.editor.contentComponent,
          ),
      )
    }
  }

  @Test
  fun `highlight timer acquires read access outside the lesson task context`() =
      timeoutRunBlocking {
        val result = CompletableDeferred<Rectangle?>()
        withContext(Dispatchers.EDT) {
          fixture.configureByText("timer.asm", $$".text\naddu $t0, $v0, $v0\n")
          val editor = fixture.editor
          Timer(1) {
                try {
                  assertFalse(ApplicationManager.getApplication().isReadAccessAllowed)
                  result.complete(
                      MarsInlinePrediction.instructionHighlight(editor, MarsPredictions.doubling)(
                          editor.contentComponent,
                      ),
                  )
                } catch (error: Throwable) {
                  result.completeExceptionally(error)
                }
              }
              .apply {
                isRepeats = false
                start()
              }
        }
        assertNotNull(result.await())
      }

  @Test
  fun `unattached editors and documents have no instruction highlight`() = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val factory = EditorFactory.getInstance()
      for (project in listOf(null, projectFixture.get())) {
        val editor = factory.createEditor(factory.createDocument($$"addu $t0, $v0, $v0"), project)
        try {
          assertNull(MarsInlinePrediction.instructionRange(editor, MarsPredictions.doubling))
        } finally {
          factory.releaseEditor(editor)
        }
      }
    }
  }

  @Test
  fun `native runtime callbacks complete the active state check and then present the second question`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          fixture.configureByText(
              "first-program.asm",
              $$".text\naddu $t0, $v0, $v0\naddiu $t0, $t0, 1\n",
          )
          projectFixture.get().service<MarsLearningProgress>().reset(MarsPredictions.identity)
          val lesson = MarsLearningCourse().modules().single().lessons.first()
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
          val recorderClass = Class.forName("training.learn.ActionsRecorder", true, loader)
          val recorder =
              recorderClass.constructors
                  .single()
                  .newInstance(projectFixture.get(), fixture.editor.document, executor)
          val previous: () -> PreviousTaskInfo = { error("No restore state") }
          val runtime =
              TaskRuntimeContext::class
                  .java
                  .constructors
                  .single { it.parameterCount == 4 }
                  .newInstance(executor, recorder, {}, previous) as TaskRuntimeContext
          val stale = mutableListOf<() -> Unit>()
          try {
            for (prediction in
                listOf(
                    MarsPredictions.doubling,
                    MarsPredictions.negative,
                    MarsPredictions.output,
                )) {
              var text: (RuntimeTextContext.() -> String?)? = null
              val finished = CompletableFuture<Boolean>()
              val context =
                  object : TaskContext() {
                    override val project: Project
                      get() = projectFixture.get()

                    override fun runtimeText(callback: RuntimeTextContext.() -> String?) {
                      text = callback
                    }

                    override fun stateCheck(checkState: TaskRuntimeContext.() -> Boolean) = finished
                  }
              context.prediction(prediction)
              val presentation = RuntimeTextContext(runtime)
              val rendered = text!!(presentation)!!
              assertTrue(presentation.removeAfterDone)
              assertFalse(rendered.contains('\n'))
              assertEquals(
                  5,
                  JDOMUtil.load("<root>$rendered</root>")
                      .value
                      .lines()
                      .filter { it.isNotEmpty() }
                      .size,
              )
              val choices = callbacks(rendered)
              stale += choices
              assertFalse(finished.isDone)
              choices[prediction.choices.indexOfFirst { it.id != prediction.correct }]()
              assertFalse(finished.isDone)
              choices[prediction.choices.indexOfFirst { it.id == prediction.correct }]()
              assertTrue(finished.get())
            }
          } finally {
            Disposer.dispose(executor as Disposable)
          }
          assertTrue(runtime.disposed)
          val progress = projectFixture.get().service<MarsLearningProgress>()
          val history = progress.snapshot(MarsPredictions.identity)!!.quizAttempts
          stale.forEach { it() }
          assertEquals(history, progress.snapshot(MarsPredictions.identity)!!.quizAttempts)
        }
      }
}
