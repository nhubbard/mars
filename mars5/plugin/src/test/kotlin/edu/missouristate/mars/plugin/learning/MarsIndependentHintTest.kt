@file:Suppress("OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.configurationStore.deserialize
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import com.intellij.util.xmlb.XmlSerializer
import edu.missouristate.mars.util.javaClassOf
import java.awt.event.ActionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsIndependentHintTest {
  private val projectFixture = codeInsightProjectFixture()
  private val fixture by codeInsightFixture(projectFixture)

  private suspend fun exercise(body: (MarsIndependentCheckpoint, () -> Unit) -> Unit) =
      withContext(Dispatchers.EDT) {
        val project = projectFixture.get()
        val lifetime = Disposer.newDisposable()
        val file =
            fixture.addFileToProject("independent.asm", $$".text\nli $v0,10\nsyscall\n").virtualFile
        fixture.configureFromExistingVirtualFile(file)
        val checkpoint =
            project
                .service<MarsLearningExecution>()
                .observeIndependent(lifetime, file.toNioPath(), fixture.editor.document)
        try {
          body(checkpoint) { Disposer.dispose(lifetime) }
        } finally {
          Disposer.dispose(lifetime)
        }
      }

  @Test
  fun `hints are optional reveal in order and never edit or complete a program`() =
      timeoutRunBlocking {
        exercise { checkpoint, _ ->
          val original = fixture.editor.document.text
          assertTrue(checkpoint.revealedHints().isEmpty())
          for ((index, hint) in MarsIndependentCheckpoint.hints.withIndex()) {
            assertTrue(checkpoint.revealNextHint())
            assertEquals(
                MarsIndependentCheckpoint.hints.take(index + 1),
                checkpoint.revealedHints(),
            )
            assertEquals(hint, checkpoint.revealedHints().last())
            assertFalse(checkpoint.passed())
            assertEquals(original, fixture.editor.document.text)
          }
          assertFalse(checkpoint.revealNextHint())
          assertEquals(4, checkpoint.revealedHints().size)
        }
      }

  @Test
  fun `course release limits gate new hints while preserving knowledge already revealed`() =
      timeoutRunBlocking {
        exercise { checkpoint, _ ->
          val policy = projectFixture.get().service<MarsLearningPolicy>()
          val original = fixture.editor.document.text
          policy.update(releasedHints = 0)
          val dialog = MarsIndependentHintDialog(projectFixture.get(), checkpoint)
          try {
            assertFalse(checkpoint.canRevealNextHint())
            assertFalse(checkpoint.revealNextHint())
            assertFalse(dialog.revealAction.isEnabled)
            assertTrue(dialog.report.contains("releases 0 of 4"))
            policy.update(releasedHints = 2)
            assertTrue(checkpoint.canRevealNextHint())
            repeat(2) { assertTrue(checkpoint.revealNextHint()) }
            assertFalse(checkpoint.canRevealNextHint())
            assertFalse(checkpoint.revealNextHint())
            policy.update(releasedHints = 0)
            assertEquals(MarsIndependentCheckpoint.hints.take(2), checkpoint.revealedHints())
            assertFalse(checkpoint.revealNextHint())
            policy.update(releasedHints = 4)
            repeat(2) { assertTrue(checkpoint.revealNextHint()) }
            assertFalse(checkpoint.canRevealNextHint())
            assertEquals(original, fixture.editor.document.text)
            assertFalse(checkpoint.passed())
          } finally {
            dialog.close(DialogWrapper.OK_EXIT_CODE)
          }
        }
      }

  @Test
  fun `reveals preserve an in-flight ticket and completed check while edits preserve hint history`() =
      timeoutRunBlocking {
        exercise { checkpoint, _ ->
          val progress = projectFixture.get().service<MarsLearningProgress>()
          val identity = MarsIndependentCheckpoint.identity
          val revision = progress.snapshot(identity)!!.sourceRevision
          val pending = progress.beginCheck(identity, MarsIndependentCheckpoint.STEP, revision)
          assertTrue(checkpoint.revealNextHint())
          assertTrue(progress.isCurrent(pending))
          assertTrue(progress.finishCheck(pending, true))
          assertTrue(checkpoint.revealNextHint())
          assertTrue(checkpoint.passed())
          WriteCommandAction.runWriteCommandAction(projectFixture.get()) {
            fixture.editor.document.insertString(0, "# edit\n")
          }
          assertFalse(checkpoint.passed())
          assertEquals(2, checkpoint.revealedHints().size)
          assertTrue(checkpoint.revealNextHint())
          val xml = XmlSerializer.serialize(progress.getState())
          progress.loadState(
              deserialize<MarsLearningProgress.Progress>(xml),
          )
          assertEquals(MarsIndependentCheckpoint.hints.take(3), checkpoint.revealedHints())
          assertFalse(checkpoint.passed())
          progress.reset(identity)
          assertTrue(checkpoint.canRevealNextHint())
          assertTrue(checkpoint.revealedHints().isEmpty())
          assertTrue(checkpoint.revealNextHint())
          assertEquals(MarsIndependentCheckpoint.hints.take(1), checkpoint.revealedHints())
          assertFalse(checkpoint.passed())
        }
      }

  @Test
  fun `native hint action reveals only requested help and reopens its saved history`() =
      timeoutRunBlocking {
        exercise { checkpoint, _ ->
          val action = ActionManager.getInstance().getAction("Mars.Learning.IndependentHint")
          assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
          val missing =
              AnActionEvent.createEvent(
                  action,
                  DataContext.EMPTY_CONTEXT,
                  null,
                  ActionPlaces.MAIN_MENU,
                  ActionUiKind.NONE,
                  null,
              )
          action.update(missing)
          assertFalse(missing.presentation.isEnabled)
          action.actionPerformed(missing)
          val data =
              SimpleDataContext.builder()
                  .add(CommonDataKeys.PROJECT, projectFixture.get())
                  .add(CommonDataKeys.VIRTUAL_FILE, fixture.file.virtualFile)
                  .add(CommonDataKeys.EDITOR, fixture.editor)
                  .build()
          repeat(2) { opening ->
            var inspected = false
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsIndependentHintDialog>(
                        javaClassOf<MarsIndependentHintDialog>(),
                    ) {
                  override fun doIntercept(dialog: MarsIndependentHintDialog) {
                    try {
                      assertFalse(dialog.report.contains(MarsIndependentCheckpoint.hints[1].title))
                      if (opening == 0) {
                        assertFalse(
                            dialog.report.contains(MarsIndependentCheckpoint.hints[0].title),
                        )
                        dialog.revealAction.actionPerformed(ActionEvent(dialog, 0, "reveal"))
                      }
                      assertTrue(dialog.report.contains(MarsIndependentCheckpoint.hints[0].title))
                      assertEquals(1, checkpoint.revealedHints().size)
                      assertFalse(checkpoint.passed())
                      inspected = true
                    } finally {
                      dialog.close(DialogWrapper.OK_EXIT_CODE)
                    }
                  }
                },
            )
            try {
              val current =
                  if (opening == 0) data
                  else
                      SimpleDataContext.builder()
                          .add(CommonDataKeys.PROJECT, projectFixture.get())
                          .build()
              val event =
                  AnActionEvent.createEvent(
                      action,
                      current,
                      null,
                      ActionPlaces.LEARN_TOOLWINDOW,
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
      }

  @Test
  fun `exhaustion and a closed lesson disable further reveals without changing history`() =
      timeoutRunBlocking {
        exercise { checkpoint, close ->
          val dialog = MarsIndependentHintDialog(projectFixture.get(), checkpoint)
          try {
            repeat(4) { dialog.revealAction.actionPerformed(ActionEvent(dialog, 0, "reveal")) }
            assertFalse(dialog.revealAction.isEnabled)
            assertTrue(dialog.report.contains(MarsIndependentCheckpoint.hints.last().title))
            close()
            assertFalse(checkpoint.revealNextHint())
            assertEquals(4, checkpoint.revealedHints().size)
          } finally {
            dialog.close(DialogWrapper.OK_EXIT_CODE)
          }
        }
      }

  @Test
  fun `a dialog opened before lesson cleanup cannot reveal a hint afterward`() =
      timeoutRunBlocking {
        exercise { checkpoint, close ->
          val dialog = MarsIndependentHintDialog(projectFixture.get(), checkpoint)
          try {
            assertTrue(dialog.revealAction.isEnabled)
            close()
            dialog.revealAction.actionPerformed(ActionEvent(dialog, 0, "reveal after cleanup"))
            assertFalse(dialog.revealAction.isEnabled)
            assertTrue(checkpoint.revealedHints().isEmpty())
            val action = ActionManager.getInstance().getAction("Mars.Learning.IndependentHint")
            val data =
                SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, projectFixture.get())
                    .build()
            val event =
                AnActionEvent.createEvent(
                    action,
                    data,
                    null,
                    ActionPlaces.LEARN_TOOLWINDOW,
                    ActionUiKind.NONE,
                    null,
                )
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event)
          } finally {
            dialog.close(DialogWrapper.OK_EXIT_CODE)
          }
        }
      }
}
