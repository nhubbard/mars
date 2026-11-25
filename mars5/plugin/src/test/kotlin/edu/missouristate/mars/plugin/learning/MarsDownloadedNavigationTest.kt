@file:Suppress("JetBrainsInternalApiUsage", "OverrideOnly")

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
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import com.intellij.toolWindow.ToolWindowHeadlessManagerImpl
import com.intellij.ui.UiInterceptors
import com.intellij.util.xmlb.XmlSerializer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import training.learn.CourseManager
import training.statistic.LessonStartingWay

@TestApplication
class MarsDownloadedNavigationTest {
  private val fixture = codeInsightProjectFixture()
  @TempDir lateinit var temporary: Path
  private val source = $$".text\nmain:\nli $v0, 10\nsyscall\n"

  private fun content(version: String = "1.0.0"): MarsDownloadedLessonContent {
    val path = Files.createTempFile(temporary, "navigation-", ".zip")
    val manifest =
        """{"formatVersion":1,"id":"003-example","version":"$version","title":"Navigation example","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
    val plan =
        """{"formatVersion":4,"introduction":"Guided introduction.","conclusion":"Guided conclusion.","steps":[
          {"kind":"prediction","id":"predict","explanation":"Predict first.","question":"Which value?","choices":[{"id":"a","text":"two","feedback":"Two."},{"id":"b","text":"three","feedback":"Three."}],"correctChoice":"a"},
          {"kind":"assessment","id":"first","source":"one.asm","caseProfile":"cases.json","explanation":"First independent task.","success":"First complete."},
          {"kind":"assessment","id":"second","source":"two.asm","caseProfile":"cases.json","explanation":"Second independent task.","success":"Second complete."}]}"""
    val profile =
        """{"formatVersion":1,"cases":[{"name":"two","input":"","stdout":"2","instructionLimit":20000}]}"""
    ZipOutputStream(Files.newOutputStream(path)).use { zip ->
      for ((name, text) in
          mapOf(
              "package.json" to manifest,
              "lesson.json" to plan,
              "cases.json" to profile,
              "main.asm" to source,
              "one.asm" to source,
              "two.asm" to source,
              "assignment.md" to "Explain.",
          )) {
        zip.putNextEntry(ZipEntry("003-example/$name"))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
    return MarsLessonPackages.prepare(path).use {
      MarsDownloadedLessonContent(it.install(Path.of(fixture.get().basePath!!)), it.nativeLesson!!)
    }
  }

  @Test
  fun `activity selection is edition bound resumable and does not reset independent evidence or source`() {
    val project = fixture.get()
    val content = content()
    val selection = project.service<MarsDownloadedLessonSelection>()
    assertEquals("", selection.activity(content))
    selection.remember(content.installed, "second")
    val progress = project.service<MarsLearningProgress>()
    val step = content.plan.steps.filterIsInstance<MarsNativeAssessmentStep>().last()
    val identity = content.assessmentIdentity(step)
    val ticket = progress.beginCheck(identity, step.id, MarsLearningSource.revision(source))
    assertTrue(progress.finishAssessment(ticket, true, 0))
    Files.writeString(content.installed.directory.resolve("two.asm"), "# student edit\n$source")
    val saved = XmlSerializer.serialize(selection.getState())
    val restored = MarsDownloadedLessonSelection(project)
    restored.loadState(
        XmlSerializer.deserialize(saved, MarsDownloadedLessonSelection.Selection::class.java),
    )
    val loaded = restored.load()
    assertEquals("second", restored.activity(loaded))
    assertTrue(
        Files.readString(loaded.installed.directory.resolve("two.asm"))
            .startsWith("# student edit"),
    )
    val lessons = MarsDownloadedLessons.module(content).lessons
    assertEquals(3, lessons.size)
    val independent = lessons.last() as MarsDownloadedIndependentLesson
    assertTrue(independent.name.contains("two.asm"))
    assertTrue(independent.alwaysRestart)
    assertFalse(independent.properties.openFileAtStart)
    independent.prepare(project)
    assertEquals("second", selection.activity(content))
    lessons.first().prepare(project)
    assertEquals("", selection.activity(content))
    assertEquals(1, progress.snapshot(identity)!!.completedAssessments)
    assertEquals(setOf(step.id), progress.snapshot(identity)!!.successfulChecks)
    selection.remember(content.installed, "missing")
    assertEquals("", selection.activity(content))
    selection.remember(content.installed, "first")
    assertEquals("", selection.activity(content("1.1.0")))
  }

  @Test
  fun `native activity choices retain the selected task and enforce live course restrictions`() =
      timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
          val project = fixture.get()
          val content = content()
          val policy = project.service<MarsLearningPolicy>()
          val selection = project.service<MarsDownloadedLessonSelection>()
          selection.remember(content.installed, "second")
          var dialog = MarsDownloadedActivityDialog(project, content)
          try {
            assertEquals(3, dialog.activityBox.itemCount)
            assertEquals("second", dialog.selectedActivity)
            assertTrue(dialog.activityBox.getItemAt(0).toString().contains("Guided"))
            assertNotNull(dialog.activityBox.accessibleContext.accessibleName)
            policy.update(allowFreeWriting = false)
            dialog.doOKAction()
            assertFalse(dialog.isOK)
            dialog.activityBox.selectedIndex = 0
            dialog.doOKAction()
            assertTrue(dialog.isOK)
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
          dialog = MarsDownloadedActivityDialog(project, content)
          try {
            assertEquals(1, dialog.activityBox.itemCount)
            assertEquals("", dialog.selectedActivity)
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
          val remembered = selection.getState()
          assertThrows<IllegalArgumentException> {
            MarsDownloadedLessons.open(project, content, "first")
          }
          assertThrows<IllegalArgumentException> {
            MarsDownloadedLessons.open(project, content, "foreign")
          }
          assertEquals(remembered, selection.getState())
        }
      }

  @Test
  fun `choose and continue actions dispatch the chosen independent lesson through the native host`() =
      timeoutRunBlocking {
        val project = fixture.get()
        val content = content()
        val selection = project.service<MarsDownloadedLessonSelection>()
        val choose = ActionManager.getInstance().getAction("Mars.Learning.ChooseDownloaded")
        fun event(data: DataContext) =
            AnActionEvent.createEvent(
                choose,
                data,
                null,
                ActionPlaces.MAIN_MENU,
                ActionUiKind.NONE,
                null,
            )
        withContext(Dispatchers.EDT) {
          val absent = event(DataContext.EMPTY_CONTEXT)
          choose.update(absent)
          assertFalse(absent.presentation.isEnabled)
          choose.actionPerformed(absent)
          assertEquals(ActionUpdateThread.BGT, choose.actionUpdateThread)
          val normal =
              event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
          choose.update(normal)
          assertFalse(normal.presentation.isEnabled)
          var shown = 0
          val previous = TestDialogManager.setTestDialog {
            shown++
            Messages.OK
          }
          try {
            choose.actionPerformed(normal)
          } finally {
            TestDialogManager.setTestDialog(previous)
          }
          assertEquals(1, shown)
          selection.remember(content.installed)
          val life = Disposer.newDisposable()
          val queued = mutableListOf<Runnable>()
          try {
            project.replaceService(
                ToolWindowManager::class.java,
                object : ToolWindowHeadlessManagerImpl(project) {
                  override fun invokeLater(runnable: Runnable) {
                    queued += runnable
                  }
                },
                life,
            )
            for (cancel in listOf(true, false)) {
              UiInterceptors.register(
                  object :
                      UiInterceptors.UiInterceptor<MarsDownloadedActivityDialog>(
                          MarsDownloadedActivityDialog::class.java,
                      ) {
                    override fun doIntercept(dialog: MarsDownloadedActivityDialog) {
                      dialog.activityBox.selectedIndex = 2
                      if (cancel) dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                      else dialog.doOKAction()
                    }
                  },
              )
              try {
                choose.update(normal)
                assertTrue(normal.presentation.isEnabled)
                choose.actionPerformed(normal)
              } finally {
                UiInterceptors.clear()
              }
              assertEquals(if (cancel) "" else "second", selection.activity(content))
            }
            queued.single().run()
          } finally {
            Disposer.dispose(life)
          }
        }
        withTimeout(10_000.milliseconds) {
          while (project.service<MarsLearningProgress>().snapshot(content.identity) == null) delay(
              50.milliseconds,
          )
        }
        withContext(Dispatchers.EDT) {
          val continuation =
              ActionManager.getInstance().getAction("Mars.Learning.ContinueDownloaded")
          continuation.actionPerformed(
              AnActionEvent.createEvent(
                  continuation,
                  SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
                  null,
                  ActionPlaces.MAIN_MENU,
                  ActionUiKind.NONE,
                  null,
              ),
          )
          assertEquals("second", selection.activity(content))
          CourseManager.instance.openLesson(project, null, LessonStartingWay.LEARN_TAB)
          for (bad in listOf("invalid archive", "{bad", """{"formatVersion":1,"other":[}""")) {
            val retained = content.installed.directory.resolve(".mars-package.zip")
            if (bad == "invalid archive") Files.writeString(retained, bad)
            else
                ZipOutputStream(Files.newOutputStream(retained)).use { zip ->
                  zip.putNextEntry(ZipEntry("003-example/package.json"))
                  zip.write(bad.toByteArray())
                  zip.closeEntry()
                }
            val previous = TestDialogManager.setTestDialog { Messages.OK }
            try {
              choose.actionPerformed(
                  event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()),
              )
            } finally {
              TestDialogManager.setTestDialog(previous)
            }
          }
        }
      }
}
