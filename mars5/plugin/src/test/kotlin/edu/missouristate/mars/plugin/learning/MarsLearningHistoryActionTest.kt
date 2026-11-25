@file:Suppress("OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsLearningHistoryActionTest {
  private val fixture = codeInsightProjectFixture()

  @Test
  fun `native history actions require available project history and cancellation preserves it`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val progress = fixture.get().service<MarsLearningProgress>()
          for (id in listOf("Mars.Learning.Reset", "Mars.Learning.Recover")) {
            val action = ActionManager.getInstance().getAction(id)
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
                SimpleDataContext.builder().add(CommonDataKeys.PROJECT, fixture.get()).build()
            val event =
                AnActionEvent.createEvent(
                    action,
                    data,
                    null,
                    ActionPlaces.MAIN_MENU,
                    ActionUiKind.NONE,
                    null,
                )
            action.update(event)
            assertFalse(event.presentation.isEnabled)
            action.actionPerformed(event)
            progress.open(MarsPredictions.identity, "source")
            progress.recordChoice(MarsPredictions.identity, "doubling", "a")
            if (id.endsWith("Recover"))
                assertTrue(progress.resetRecoverably(MarsLearningUnit.identities))
            action.update(event)
            assertTrue(event.presentation.isEnabled)
            val before = progress.getState()
            var intercepted = false
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsLearningHistoryDialog>(
                        MarsLearningHistoryDialog::class.java,
                    ) {
                  override fun doIntercept(dialog: MarsLearningHistoryDialog) {
                    assertTrue(dialog.panel.componentCount > 0)
                    dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                    intercepted = true
                  }
                },
            )
            try {
              action.actionPerformed(event)
            } finally {
              UiInterceptors.clear()
            }
            assertTrue(intercepted)
            assertEquals(before, progress.getState())
            progress.loadState(MarsLearningProgress.Progress())
          }
        }
      }

  @Test
  fun `confirmed history actions reset and recover only this unit and reopen its first guided segment`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = fixture.get()
          val progress = project.service<MarsLearningProgress>()
          progress.open(MarsPredictions.identity, "source")
          progress.recordChoice(MarsPredictions.identity, "doubling", "a")
          assertEquals(7, MarsLearningUnit.identities.size)
          val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
          for (id in listOf("Mars.Learning.Reset", "Mars.Learning.Recover")) {
            val action = ActionManager.getInstance().getAction(id)
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsLearningHistoryDialog>(
                        MarsLearningHistoryDialog::class.java,
                    ) {
                  override fun doIntercept(dialog: MarsLearningHistoryDialog) {
                    dialog.close(DialogWrapper.OK_EXIT_CODE)
                  }
                },
            )
            try {
              action.actionPerformed(
                  AnActionEvent.createEvent(
                      action,
                      data,
                      null,
                      ActionPlaces.MAIN_MENU,
                      ActionUiKind.NONE,
                      null,
                  ),
              )
            } finally {
              UiInterceptors.clear()
            }
            assertEquals(
                MarsLearningNavigation.FIRST_GUIDED,
                project.service<MarsLearningNavigation>().guidedLesson,
            )
            assertEquals(id.endsWith("Reset"), progress.canRecover(MarsLearningUnit.identities))
          }
          assertEquals(
              "a",
              progress.snapshot(MarsPredictions.identity)!!.quizAttempts.single().choice,
          )
        }
      }

  @Test
  fun `history disappearing during confirmation does not start a lesson or fabricate a backup`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = fixture.get()
          val progress = project.service<MarsLearningProgress>()
          progress.open(MarsPredictions.identity, "source")
          progress.recordChoice(MarsPredictions.identity, "doubling", "a")
          val navigation = project.service<MarsLearningNavigation>()
          navigation.opened("mars.console.repair")
          val action = ActionManager.getInstance().getAction("Mars.Learning.Reset")
          UiInterceptors.register(
              object :
                  UiInterceptors.UiInterceptor<MarsLearningHistoryDialog>(
                      MarsLearningHistoryDialog::class.java,
                  ) {
                override fun doIntercept(dialog: MarsLearningHistoryDialog) {
                  progress.loadState(MarsLearningProgress.Progress())
                  dialog.close(DialogWrapper.OK_EXIT_CODE)
                }
              },
          )
          try {
            val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
            action.actionPerformed(
                AnActionEvent.createEvent(
                    action,
                    data,
                    null,
                    ActionPlaces.MAIN_MENU,
                    ActionUiKind.NONE,
                    null,
                ),
            )
          } finally {
            UiInterceptors.clear()
          }
          assertFalse(progress.canRecover(MarsLearningUnit.identities))
          assertEquals("mars.console.repair", navigation.guidedLesson)
        }
      }
}
