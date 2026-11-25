@file:Suppress("OverrideOnly", "UsePropertyAccessSyntax")

package edu.missouristate.mars.plugin.learning

import com.google.gson.JsonParser
import com.intellij.configurationStore.deserialize
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import com.intellij.util.xmlb.XmlSerializer
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.learning.MarsDownloadedAssessmentCheckpoint.Feedback
import edu.missouristate.mars.plugin.ui.findComponentsOfType
import edu.missouristate.mars.util.javaClassOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.swing.JCheckBox
import javax.swing.JLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDownloadedRequirementsTest {
  private val fixture = codeInsightProjectFixture()
  @TempDir lateinit var temporary: Path
  private val program =
      $$"""
      .text
      main:
      nop
      li $a0, 2
      li $v0, 1
      syscall
      li $v0, 10
      syscall
      """
          .trimIndent() + "\n"
  private val project
    get() = fixture.get()

  private val policy
    get() = project.service<MarsLearningPolicy>()

  private val progress
    get() = project.service<MarsLearningProgress>()

  private fun content(): MarsDownloadedLessonContent {
    val archive = Files.createTempFile(temporary, "requirements-", ".zip")
    val manifest =
        """{"formatVersion":1,"id":"003-prerequisites","version":"1.0.0","title":"Prerequisite example","entry":"main.asm","assignment":"assignment.md","nativeLesson":"lesson.json","environment":{"isa":"MIPS64 Release 6","syscallAbi":"MARS educational"}}"""
    val plan =
        $$"""{"formatVersion":4,"introduction":"Observe before writing.","conclusion":"Explain.","steps":[
          {"kind":"prediction","id":"predict","explanation":"Predict <the argument> & retain it.","question":"Which value?","choices":[{"id":"a","text":"two","feedback":"Two."},{"id":"b","text":"three","feedback":"Three."}],"correctChoice":"a"},
          {"kind":"run","id":"run-one","explanation":"Run the first program.","integerInputs":[],"stdout":"2","success":"Two printed."},
          {"kind":"debug","id":"debug-one","explanation":"Observe the boundary.","integerInputs":[],"stops":[{"id":"before","explanation":"Before nop.","anchor":{"label":"main","mnemonic":"nop","occurrence":0},"registers":{"$zero":"0x0000000000000000"},"success":"Zero retained."}],"success":"Boundary observed."},
          {"kind":"assessment","id":"first","source":"first.asm","caseProfile":"cases.json","explanation":"Write the first program.","success":"First complete."},
          {"kind":"prediction","id":"again","explanation":"Predict again.","question":"Which argument?","choices":[{"id":"a","text":"two","feedback":"Two."},{"id":"b","text":"three","feedback":"Three."}],"correctChoice":"a"},
          {"kind":"run","id":"run-two","explanation":"Run again.","integerInputs":[],"stdout":"2","success":"Two printed."},
          {"kind":"assessment","id":"second","source":"second.asm","caseProfile":"cases.json","explanation":"Write the second program.","success":"Second complete."},
          {"kind":"prediction","id":"future","explanation":"A later activity.","question":"Which value?","choices":[{"id":"a","text":"two","feedback":"Two."},{"id":"b","text":"three","feedback":"Three."}],"correctChoice":"a"}]}"""
    ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
      for ((name, text) in
          mapOf(
              "package.json" to manifest,
              "lesson.json" to plan,
              "assignment.md" to "Explain.",
              "cases.json" to
                  """{"formatVersion":1,"cases":[{"name":"two","input":"","stdout":"2","instructionLimit":20000}]}""",
              "main.asm" to program,
              "first.asm" to program,
              "second.asm" to program,
          )) {
        zip.putNextEntry(ZipEntry("003-prerequisites/$name"))
        zip.write(text.toByteArray())
        zip.closeEntry()
      }
    }
    return MarsLessonPackages.prepare(archive).use {
      MarsDownloadedLessonContent(it.install(Path.of(project.basePath!!)), it.nativeLesson!!)
    }
  }

  private fun withoutAssessments(
      content: MarsDownloadedLessonContent,
  ): MarsDownloadedLessonContent {
    val archive = Files.createTempFile(temporary, "guided-only-", ".zip")
    ZipFile(content.installed.directory.resolve(".mars-package.zip").toFile()).use { original ->
      ZipOutputStream(Files.newOutputStream(archive)).use { zip ->
        for (entry in original.entries().asSequence()) {
          var bytes = original.getInputStream(entry).use { it.readBytes() }
          if (entry.name.endsWith("/lesson.json")) {
            val plan = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            plan.getAsJsonArray("steps").removeAll {
              it.asJsonObject.get("kind").asString == "assessment"
            }
            bytes = plan.toString().toByteArray()
          }
          zip.putNextEntry(ZipEntry(entry.name))
          zip.write(bytes)
          zip.closeEntry()
        }
      }
    }
    return MarsLessonPackages.prepare(archive).use {
      MarsDownloadedLessonContent(it.install(Path.of(project.basePath!!)), it.nativeLesson!!)
    }
  }

  private fun assessment(content: MarsDownloadedLessonContent, id: String) =
      content.plan.steps.single { it.id == id } as MarsNativeAssessmentStep

  private fun require(content: MarsDownloadedLessonContent, id: String, vararg activities: String) =
      policy.setDownloadedRequirements(content, mapOf(id to activities.toSet()))

  private fun answer(content: MarsDownloadedLessonContent, id: String) {
    progress.open(content.identity, content.identity.contentSha256)
    progress.recordChoice(content.identity, id, "a")
  }

  private suspend fun grant(content: MarsDownloadedLessonContent, id: String) =
      withContext(Dispatchers.EDT) {
        val activity = content.plan.steps.single { it.id == id }
        val identity =
            when (activity) {
              is MarsNativeRunStep -> content.runIdentity(activity)
              is MarsNativeDebugStep -> content.debugIdentity(activity)
              is MarsNativeAssessmentStep -> content.assessmentIdentity(activity)
              else -> error("Use answer for a prediction")
            }
        val path = content.source(activity)
        val file =
            withContext(Dispatchers.IO) {
              VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)!!
            }
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        val source = MarsLearningSource(project, path, document, identity)
        val attempt = source.begin(id)
        if (activity is MarsNativeAssessmentStep)
            assertTrue(source.finishAssessment(attempt, true, 0))
        else assertTrue(source.finish(attempt, true))
      }

  private suspend fun edit(path: Path, text: String) =
      withContext(Dispatchers.EDT) {
        val file = VirtualFileManager.getInstance().findFileByNioPath(path)!!
        WriteCommandAction.runWriteCommandAction(project) {
          FileDocumentManager.getInstance().getDocument(file)!!.setText(text)
        }
      }

  @Test
  fun `global preceding predictions and selected subsets keep their own current edition answers`() =
      timeoutRunBlocking {
        val content = content()
        val second = assessment(content, "second")
        val model = MarsDownloadedRequirements(project, content, second)
        assertTrue(model.ready())
        assertEquals("", model.description())
        policy.update(requirePredictions = true)
        assertFalse(model.ready())
        model.refresh()
        assertFalse(model.ready())
        answer(content, "predict")
        assertFalse(model.ready())
        answer(content, "again")
        assertTrue(model.ready())
        assertTrue(model.description().contains("predict"))
        assertTrue(model.description().contains("again"))
        assertFalse(model.description().contains("future"))
        progress.reset(content.identity)
        assertFalse(model.ready())
        policy.update(requirePredictions = false)
        require(content, "second", "predict")
        model.refresh()
        assertFalse(model.ready())
        answer(content, "predict")
        assertTrue(model.ready())
        assertFalse(model.description().contains("again"))
      }

  @Test
  fun `transitive independent prerequisites require current run debug and source evidence`() =
      timeoutRunBlocking {
        val content = content()
        require(content, "first", "predict", "run-one", "debug-one")
        require(content, "second", "first")
        val model = MarsDownloadedRequirements(project, content, assessment(content, "second"))
        answer(content, "predict")
        grant(content, "run-one")
        grant(content, "debug-one")
        grant(content, "first")
        assertFalse(model.ready())
        model.refresh()
        assertTrue(model.ready())
        require(content, "second", "first", "run-one")
        model.refresh()
        assertTrue(model.ready())
        val description = model.description()
        assertTrue(description.contains("Run observation: run-one"))
        assertTrue(description.contains("Debug observations: debug-one"))
        assertTrue(description.contains("Independent check: first"))
        edit(content.installed.entry, "$program# changed\n")
        assertFalse(model.ready())
        edit(content.installed.entry, program)
        assertFalse(model.ready())
        grant(content, "run-one")
        assertFalse(model.ready())
        grant(content, "debug-one")
        assertTrue(model.ready())
        val first = content.installed.directory.resolve("first.asm")
        edit(first, "$program# first changed\n")
        assertFalse(model.ready())
        grant(content, "first")
        assertTrue(model.ready())
        require(content, "second", "first", "run-two")
        assertFalse(model.ready())
        model.refresh()
        assertFalse(model.ready())
        grant(content, "run-two")
        assertTrue(model.ready())
        withContext(Dispatchers.EDT) {
          WriteCommandAction.runWriteCommandAction(project) {
            VirtualFileManager.getInstance().findFileByNioPath(first)!!.delete(this)
          }
        }
        assertFalse(model.ready())
        model.refresh()
        assertFalse(model.ready())
        withContext(Dispatchers.IO) { Files.createDirectory(first) }
        model.refresh()
        assertFalse(model.ready())
      }

  @Test
  fun `Run and Debug prerequisites retain separate source revisions`() = timeoutRunBlocking {
    val original = content()
    val content =
        original.copy(
            plan =
                original.plan.copy(
                    steps =
                        original.plan.steps.map {
                          when (it) {
                            is MarsNativeRunStep ->
                                if (it.id == "run-one") it.copy(source = "first.asm") else it
                            is MarsNativeDebugStep -> it.copy(source = "second.asm")
                            else -> it
                          }
                        },
                ),
        )
    require(content, "second", "run-one", "debug-one")
    val model = MarsDownloadedRequirements(project, content, assessment(content, "second"))
    model.refresh()
    assertFalse(model.ready())
    grant(content, "run-one")
    assertFalse(model.ready())
    grant(content, "debug-one")
    assertTrue(model.ready())
    edit(content.installed.entry, "$program# unrelated guided entry edit\n")
    assertTrue(model.ready())
    edit(content.installed.directory.resolve("first.asm"), "$program# full range edit\n")
    assertFalse(model.ready())
    grant(content, "run-one")
    assertTrue(model.ready())
    edit(content.installed.directory.resolve("second.asm"), "$program# empty range edit\n")
    assertFalse(model.ready())
    grant(content, "run-one")
    assertFalse(model.ready())
    grant(content, "debug-one")
    assertTrue(model.ready())
  }

  @Test
  fun `offline source changes cannot reuse saved execution evidence when prerequisites are first bound`() =
      timeoutRunBlocking {
        val content = content()
        require(content, "second", "run-one")
        val run = content.plan.steps.single { it.id == "run-one" } as MarsNativeRunStep
        val ticket =
            progress.beginCheck(
                content.runIdentity(run),
                run.id,
                MarsLearningSource.revision(program),
            )
        progress.finishCheck(ticket, true)
        withContext(Dispatchers.IO) {
          Files.writeString(content.installed.entry, "$program# offline edit\n")
        }
        val model = MarsDownloadedRequirements(project, content, assessment(content, "second"))
        model.refresh()
        assertFalse(model.ready())
      }

  @Test
  fun `course policy validates ordering saves concrete state records and isolates revisions`() {
    val content = content()
    val first = assessment(content, "first")
    val second = assessment(content, "second")
    for (invalid in listOf("missing", "first", "second", "again", "future")) {
      assertThrows<IllegalArgumentException> { require(content, "first", invalid) }
    }
    assertThrows<IllegalArgumentException> { require(content, "missing", "predict") }
    assertThrows<IllegalArgumentException> { content.preceding(first.copy(id = "foreign")) }
    require(content, "first", "predict", "run-one")
    require(content, "second", "first")
    val saved = XmlSerializer.serialize(policy.getState())
    val restored = MarsLearningPolicy(project)
    restored.loadState(deserialize<MarsLearningPolicy.Policy>(saved))
    assertEquals(
        setOf("predict", "run-one"),
        restored.downloadedRequirements(content.assessmentIdentity(first)),
    )
    assertEquals(
        setOf("first"),
        restored.downloadedRequirements(content.assessmentIdentity(second)),
    )
    val detached =
        restored.downloadedRequirements(content.assessmentIdentity(first)) as MutableSet<String>
    detached.clear()
    assertEquals(2, restored.downloadedRequirements(content.assessmentIdentity(first)).size)
    val next =
        content.copy(
            installed =
                content.installed.copy(
                    metadata = content.installed.metadata.copy(version = "1.1.0"),
                    sha256 = "b".repeat(64),
                ),
        )
    assertTrue(MarsDownloadedRequirements(project, next, second).ready())
    require(content, "first")
    assertTrue(policy.downloadedRequirements(content.assessmentIdentity(first)).isEmpty())
    assertEquals(setOf("first"), policy.downloadedRequirements(content.assessmentIdentity(second)))
  }

  @Test
  fun `unsupported persisted requirements block checking until an instructor explicitly repairs them`() =
      timeoutRunBlocking {
        val content = content()
        val second = assessment(content, "second")
        val key = content.assessmentIdentity(second).key
        policy.loadState(
            policy
                .getState()
                .copy(
                    downloadedPrerequisites =
                        mapOf(key to MarsLearningPolicy.DownloadedRequirements(setOf("future"))),
                ),
        )
        val model = MarsDownloadedRequirements(project, content, second)
        assertFalse(model.ready())
        assertTrue(model.description().contains("saved prerequisite"))
        model.refresh()
        assertFalse(model.ready())
        withContext(Dispatchers.EDT) {
          var dialog = MarsDownloadedPrerequisitesDialog.create(project, content)
          try {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          } finally {
            assertEquals(
                setOf("future"),
                policy.downloadedRequirements(content.assessmentIdentity(second)),
            )
          }
          dialog = MarsDownloadedPrerequisitesDialog.create(project, content)
          try {
            dialog.doOKAction()
            assertTrue(dialog.isOK)
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
        }
        assertTrue(model.ready())
      }

  @Test
  fun `native dialog keeps per-exercise drafts through switches and cancellation then saves atomically`() =
      timeoutRunBlocking {
        val content = content()
        withContext(Dispatchers.EDT) {
          fun configure(dialog: MarsDownloadedPrerequisitesDialog) {
            fun box(id: String) =
                findComponentsOfType<JCheckBox>(dialog.requirementsPanel).single {
                  it.text.endsWith(": $id")
                }
            assertEquals(
                3,
                findComponentsOfType<JCheckBox>(dialog.requirementsPanel).size,
            )
            assertTrue(box("predict").toolTipText.contains("&lt;the argument&gt; &amp; retain it."))
            box("run-one").doClick()
            box("debug-one").doClick()
            dialog.activityBox.selectedIndex = 1
            assertEquals(
                6,
                findComponentsOfType<JCheckBox>(dialog.requirementsPanel).size,
            )
            box("first").doClick()
            box("again").doClick()
            dialog.activityBox.selectedIndex = 0
            assertTrue(box("run-one").isSelected)
            assertTrue(box("debug-one").isSelected)
            box("debug-one").doClick()
          }
          var dialog = MarsDownloadedPrerequisitesDialog.create(project, content)
          try {
            configure(dialog)
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          } finally {
            assertTrue(policy.getState().downloadedPrerequisites.isEmpty())
          }
          dialog = MarsDownloadedPrerequisitesDialog.create(project, content)
          try {
            configure(dialog)
            dialog.doOKAction()
            assertTrue(dialog.isOK)
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
        }
        assertEquals(
            setOf("run-one"),
            policy.downloadedRequirements(content.assessmentIdentity(assessment(content, "first"))),
        )
        assertEquals(
            setOf("first", "again"),
            policy.downloadedRequirements(
                content.assessmentIdentity(assessment(content, "second")),
            ),
        )
        val first = assessment(content, "first")
        val only = content.copy(plan = content.plan.copy(steps = listOf(first)))
        withContext(Dispatchers.EDT) {
          val dialog = MarsDownloadedPrerequisitesDialog.create(project, only)
          try {
            assertTrue(
                findComponentsOfType<JLabel>(dialog.requirementsPanel).any {
                  it.text.contains("no earlier")
                },
            )
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
        }
      }

  @Test
  fun `long prerequisite lists keep their natural height inside a bounded native scroll pane`() =
      timeoutRunBlocking {
        val content = content()
        val target = assessment(content, "second")
        val prediction = content.plan.steps.first() as MarsNativeLessonStep
        val long =
            content.copy(
                plan =
                    content.plan.copy(
                        steps =
                            (0..49).map {
                              prediction.copy(
                                  prediction = prediction.prediction.copy(id = "question-$it"),
                              )
                            } + target,
                    ),
            )
        withContext(Dispatchers.EDT) {
          val dialog = MarsDownloadedPrerequisitesDialog.create(project, long)
          try {
            assertEquals(
                50,
                findComponentsOfType<JCheckBox>(dialog.requirementsPanel).size,
            )
            assertTrue(
                dialog.requirementsPanel.preferredSize.height >
                    dialog.requirementsScroll.preferredSize.height,
            )
            dialog.requirementsScroll.size = dialog.requirementsScroll.preferredSize
            dialog.requirementsScroll.doLayout()
            dialog.requirementsScroll.viewport.doLayout()
            assertTrue(dialog.requirementsScroll.verticalScrollBar.isVisible)
            assertTrue(
                dialog.requirementsScroll.viewport.viewSize.height >
                    dialog.requirementsScroll.viewport.extentSize.height,
            )
          } finally {
            dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
          }
        }
      }

  @ParameterizedTest
  @ValueSource(booleans = [false, true])
  fun `source or stricter policy changes while grading cannot accept stale evidence or consume an attempt`(
      changePolicy: Boolean,
  ) = timeoutRunBlocking {
    val content = content()
    val second = assessment(content, "second")
    require(content, "second", "run-one")
    grant(content, "run-one")
    val requirements = MarsDownloadedRequirements(project, content, second)
    val life = Disposer.newDisposable()
    var changed = false
    try {
      val checkpoint =
          withContext(Dispatchers.EDT) {
            val file =
                withContext(Dispatchers.IO) {
                  VirtualFileManager.getInstance()
                      .refreshAndFindFileByNioPath(
                          content.installed.directory.resolve(second.source),
                      )!!
                }
            MarsDownloadedAssessmentCheckpoint(
                project,
                life,
                file.toNioPath(),
                FileDocumentManager.getInstance().getDocument(file)!!,
                content.assessmentIdentity(second),
                second,
                requirements::ready,
                createSession = {
                  if (!changed) {
                    changed = true
                    if (changePolicy) require(content, "second", "run-one", "again")
                    else
                        ApplicationManager.getApplication().invokeAndWait {
                          WriteCommandAction.runWriteCommandAction(project) {
                            val main =
                                VirtualFileManager.getInstance()
                                    .findFileByNioPath(content.installed.entry)!!
                            FileDocumentManager.getInstance()
                                .getDocument(main)!!
                                .setText("$program# prerequisite edited during check\n")
                          }
                        }
                  }
                  MarsSessionService.createMarsComponent(backstepLimit = 1).context()
                },
                refreshRequirements = requirements::refresh,
            )
          }
      assertEquals(Feedback.STALE, checkpoint.check())
      assertEquals(0, progress.snapshot(content.assessmentIdentity(second))!!.completedAssessments)
      assertEquals(Feedback.POLICY, checkpoint.check())
      if (changePolicy) answer(content, "again") else grant(content, "run-one")
      assertInstanceOf<Feedback.Checked>(checkpoint.check())
      assertTrue(checkpoint.passed())
      assertEquals(1, progress.snapshot(content.assessmentIdentity(second))!!.completedAssessments)
    } finally {
      withContext(Dispatchers.EDT) { Disposer.dispose(life) }
    }
  }

  @Test
  fun `cancelled or disposed prerequisite preparation cannot start an assessment`() =
      timeoutRunBlocking {
        val content = content()
        val second = assessment(content, "second")
        for (dispose in listOf(false, true)) {
          val life = Disposer.newDisposable()
          try {
            val checkpoint =
                withContext(Dispatchers.EDT) {
                  val path = content.installed.directory.resolve(second.source)
                  val file =
                      withContext(Dispatchers.IO) {
                        VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)!!
                      }
                  MarsDownloadedAssessmentCheckpoint(
                      project,
                      life,
                      path,
                      FileDocumentManager.getInstance().getDocument(file)!!,
                      content.assessmentIdentity(second),
                      second,
                      { true },
                      createSession = { error("No evaluation") },
                      refreshRequirements = {
                        if (dispose) Disposer.dispose(life)
                        else throw CancellationException("Cancelled prerequisite refresh")
                      },
                  )
                }
            if (dispose) assertEquals(Feedback.STALE, checkpoint.check())
            else assertThrows<CancellationException> { checkpoint.check() }
            assertEquals(
                0,
                progress.snapshot(content.assessmentIdentity(second))!!.completedAssessments,
            )
          } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(life) }
          }
        }
      }

  @Test
  fun `instructor action validates retained content and opens native controls without changing learner history`() =
      timeoutRunBlocking {
        val content = content()
        val guidedOnly = withoutAssessments(content)
        val action =
            ActionManager.getInstance().getAction("Mars.Learning.ConfigureDownloadedPrerequisites")
        fun event(data: DataContext) =
            AnActionEvent.createEvent(
                action,
                data,
                null,
                ActionPlaces.MAIN_MENU,
                ActionUiKind.NONE,
                null,
            )
        withContext(Dispatchers.EDT) {
          assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
          val absent = event(DataContext.EMPTY_CONTEXT)
          action.update(absent)
          assertFalse(absent.presentation.isEnabled)
          action.actionPerformed(absent)
          val normal =
              event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build())
          action.update(normal)
          assertFalse(normal.presentation.isEnabled)
          var dialogs = 0
          val previous = TestDialogManager.setTestDialog {
            dialogs++
            Messages.OK
          }
          try {
            action.actionPerformed(normal)
          } finally {
            TestDialogManager.setTestDialog(previous)
          }
          assertEquals(1, dialogs)
          project.service<MarsDownloadedLessonSelection>().remember(content.installed)
          UiInterceptors.register(
              object :
                  UiInterceptors.UiInterceptor<MarsDownloadedPrerequisitesDialog>(
                      javaClassOf<MarsDownloadedPrerequisitesDialog>(),
                  ) {
                override fun doIntercept(dialog: MarsDownloadedPrerequisitesDialog) {
                  dialog.close(DialogWrapper.CANCEL_EXIT_CODE)
                }
              },
          )
          try {
            action.update(normal)
            assertTrue(normal.presentation.isEnabled)
            action.actionPerformed(normal)
          } finally {
            UiInterceptors.clear()
          }
          assertTrue(progress.getState().lessons.isEmpty())
          for (bad in listOf("invalid archive", "{bad", """{"formatVersion":1,"other":[}""")) {
            val path = content.installed.directory.resolve(".mars-package.zip")
            if (bad == "invalid archive") Files.writeString(path, bad)
            else
                ZipOutputStream(Files.newOutputStream(path)).use { z ->
                  z.putNextEntry(ZipEntry("003-prerequisites/package.json"))
                  z.write(bad.toByteArray())
                  z.closeEntry()
                }
            val prev = TestDialogManager.setTestDialog { Messages.OK }
            try {
              action.actionPerformed(normal)
            } finally {
              TestDialogManager.setTestDialog(prev)
            }
          }
          project.service<MarsDownloadedLessonSelection>().remember(guidedOnly.installed)
          assertThrows<IllegalArgumentException> {
            MarsDownloadedPrerequisitesDialog.create(project, guidedOnly)
          }
          var unavailable = false
          val prev = TestDialogManager.setTestDialog {
            unavailable = true
            Messages.OK
          }
          try {
            action.actionPerformed(normal)
          } finally {
            TestDialogManager.setTestDialog(prev)
          }
          assertTrue(unavailable)
        }
      }
}
