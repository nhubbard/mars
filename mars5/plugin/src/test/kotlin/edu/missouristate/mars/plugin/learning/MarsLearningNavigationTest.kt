@file:Suppress("JetBrainsInternalApiUsage", "OverrideOnly")

package edu.missouristate.mars.plugin.learning

import com.intellij.configurationStore.deserialize
import com.intellij.mock.MockProject
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
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.ExtensionTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import com.intellij.ui.components.JBRadioButton
import com.intellij.util.xmlb.XmlSerializer
import edu.missouristate.mars.plugin.ui.findComponentsOfType
import edu.missouristate.mars.util.javaClassOf
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import training.learn.CourseManager
import training.statistic.LessonStartingWay

@TestApplication
class MarsLearningNavigationTest {
  private val fixture = codeInsightProjectFixture()

  @Test
  fun `navigation preserves a separate guided destination and rejects arbitrary lesson routing`() {
    val project = fixture.get()
    val navigation = project.service<MarsLearningNavigation>()
    assertEquals(MarsLearningMode.GUIDED, navigation.mode)
    assertEquals(
        MarsLearningNavigation.FIRST_GUIDED,
        navigation.destination(MarsLearningMode.GUIDED),
    )
    for (id in listOf("predictions", "run", "debug", "output", "diagnosis", "repair")) {
      navigation.opened("mars.console.$id")
      assertEquals("mars.console.$id", navigation.destination(MarsLearningMode.GUIDED))
    }
    navigation.opened(MarsLearningNavigation.INDEPENDENT)
    assertEquals(MarsLearningMode.FREE_WRITING, navigation.mode)
    assertEquals("mars.console.repair", navigation.guidedLesson)
    assertEquals(
        MarsLearningNavigation.INDEPENDENT,
        navigation.destination(MarsLearningMode.FREE_WRITING),
    )
    val copy = MarsLearningNavigation(project)
    copy.loadState(
        deserialize<MarsLearningNavigation.Navigation>(
            XmlSerializer.serialize(navigation.getState()),
        ),
    )
    assertEquals(navigation.getState(), copy.getState())
    navigation.opened("mars.console.run")
    assertEquals(MarsLearningMode.GUIDED, navigation.mode)
    assertEquals(MarsLearningMode.FREE_WRITING, copy.mode)
    copy.loadState(MarsLearningNavigation.Navigation("unknown", "foreign.lesson"))
    assertEquals(MarsLearningMode.GUIDED, copy.mode)
    assertEquals(MarsLearningNavigation.FIRST_GUIDED, copy.guidedLesson)
    assertThrows<IllegalArgumentException> { navigation.opened("foreign.lesson") }
    assertTrue(project.service<MarsLearningProgress>().getState().lessons.isEmpty())
  }

  @Test
  fun `native mode action offers both modes and cancellation leaves navigation unchanged`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val action = ActionManager.getInstance().getAction("Mars.Learning.Open")
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
          val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, fixture.get()).build()
          for (mode in listOf(null, MarsLearningMode.GUIDED, MarsLearningMode.FREE_WRITING)) {
            var observed = false
            UiInterceptors.register(
                object :
                    UiInterceptors.UiInterceptor<MarsLearningModeDialog>(
                        javaClassOf<MarsLearningModeDialog>(),
                    ) {
                  override fun doIntercept(dialog: MarsLearningModeDialog) {
                    val buttons = findComponentsOfType<JBRadioButton>(dialog.panel)
                    assertEquals(2, buttons.size)
                    assertEquals(MarsLearningMode.GUIDED, dialog.selectedMode)
                    if (mode == null) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                    else {
                      buttons[if (mode == MarsLearningMode.GUIDED) 0 else 1].doClick()
                      assertEquals(mode, dialog.selectedMode)
                      dialog.doOKAction()
                    }
                    observed = true
                  }
                },
            )
            try {
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
              assertTrue(event.presentation.isEnabled)
              action.actionPerformed(event)
              assertTrue(observed)
              // The headless platform's native toolwindow queue intentionally does not run UI
              // tasks.
              assertEquals(
                  MarsLearningMode.GUIDED,
                  fixture.get().service<MarsLearningNavigation>().mode,
              )
            } finally {
              UiInterceptors.clear()
            }
          }
        }
      }

  @Test
  fun `course restriction disables free writing and a live change cannot be accepted by an old dialog`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = fixture.get()
          val navigation = project.service<MarsLearningNavigation>()
          val policy = project.service<MarsLearningPolicy>()
          navigation.opened(MarsLearningNavigation.INDEPENDENT)
          var dialog = MarsLearningModeDialog(project)
          try {
            assertEquals(MarsLearningMode.FREE_WRITING, dialog.selectedMode)
            policy.update(allowFreeWriting = false)
            assertFalse(policy.canStartIndependent())
            assertTrue(policy.independentRestriction().contains("requires guided work"))
            dialog.doOKAction()
            assertFalse(dialog.isOK)
            findComponentsOfType<JBRadioButton>(dialog.panel).first().doClick()
            dialog.doOKAction()
            assertTrue(dialog.isOK)
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
          dialog = MarsLearningModeDialog(project)
          try {
            assertEquals(MarsLearningMode.GUIDED, dialog.selectedMode)
            assertFalse(
                findComponentsOfType<JBRadioButton>(dialog.panel).last().isEnabled,
            )
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
        }
      }

  @Test
  fun `missing native course fails without routing to an unrelated lesson`() = timeoutRunBlocking {
    withContext(Dispatchers.EDT) {
      val lifetime = Disposer.newDisposable()
      try {
        ExtensionTestUtil.maskExtensions(CourseManager.COURSE_MODULES_EP, emptyList(), lifetime)
        CourseManager.instance.clearModules()
        val navigation = fixture.get().service<MarsLearningNavigation>()
        assertThrows<IllegalStateException> { navigation.openNow(MarsLearningMode.GUIDED) }
        assertEquals(MarsLearningMode.GUIDED, navigation.mode)
        assertTrue(fixture.get().service<MarsLearningProgress>().getState().lessons.isEmpty())
      } finally {
        Disposer.dispose(lifetime)
        CourseManager.instance.clearModules()
      }
    }
  }

  @Test
  fun `native routing prepares the selected course and disposed projects ignore queued navigation`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val navigation = project.service<MarsLearningNavigation>()
        withContext(Dispatchers.EDT) { navigation.openNow(MarsLearningMode.GUIDED) }
        withTimeout(10_000.milliseconds) {
          val path = Path.of(project.basePath!!).resolve("mars-labs/console/first-program.asm")
          while (!Files.isRegularFile(path)) delay(50.milliseconds)
        }
        assertEquals(MarsLearningMode.GUIDED, navigation.mode)
        withContext(Dispatchers.EDT) {
          CourseManager.instance.openLesson(project, null, LessonStartingWay.LEARN_TAB)
        }
        val lifetime = Disposer.newDisposable()
        val disposed = MockProject(null, lifetime)
        Disposer.dispose(lifetime)
        MarsLearningNavigation(disposed).openNow(MarsLearningMode.FREE_WRITING)
      }
}
