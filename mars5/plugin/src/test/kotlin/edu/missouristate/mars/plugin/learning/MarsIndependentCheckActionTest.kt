package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import edu.missouristate.mars.cli.grading.ConsoleCheckResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsIndependentCheckActionTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val action get() = ActionManager.getInstance().getAction("Mars.Learning.CheckIndependent")
    private fun event(data: DataContext) = AnActionEvent.createEvent(action, data, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
    private val correct = MarsLearningWorkspace::class.java.getResourceAsStream(
        "/learnProjects/mars_mips/MARSLearningProject/first-program.asm")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        .replace("Twice your number", "Three times your number")
        .replace("    addiu \$t0, \$t0, 1", "    addu \$t0, \$t0, \$v0\n    addiu \$t0, \$t0, 1")

    @Test
    fun `the check action requires the active local file and matching editor`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            val project = projectFixture.get()
            val lifetime = Disposer.newDisposable()
            try {
                val file = fixture.addFileToProject("independent.asm", correct).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val contexts = listOf(
                    DataContext.EMPTY_CONTEXT,
                    SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build(),
                    SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, LightVirtualFile("preview.asm")).build(),
                    SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, file).build(),
                )
                for (context in contexts) {
                    val e = event(context); action.update(e); assertFalse(e.presentation.isEnabled); action.actionPerformed(e)
                }
                val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.VIRTUAL_FILE, file).add(CommonDataKeys.EDITOR, fixture.editor).build()
                val e = event(context)
                action.update(e); assertFalse(e.presentation.isEnabled); action.actionPerformed(e)
                project.service<MarsLearningExecution>().observeIndependent(lifetime, file.toNioPath(), fixture.editor.document)
                action.update(e); assertTrue(e.presentation.isEnabled)
                val other = fixture.addFileToProject("other.asm", correct).virtualFile
                val wrong = event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
                    .add(CommonDataKeys.VIRTUAL_FILE, other).add(CommonDataKeys.EDITOR, fixture.editor).build())
                action.update(wrong); assertFalse(wrong.presentation.isEnabled)
            } finally { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `native Learn context uses the selected editor while rejecting other renamed and deleted files`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            val project = projectFixture.get()
            val lifetime = Disposer.newDisposable()
            val data = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
            val e = AnActionEvent.createEvent(action, data, null, ActionPlaces.LEARN_TOOLWINDOW, ActionUiKind.NONE, null)
            try {
                action.update(e)
                assertFalse(e.presentation.isEnabled)
                action.actionPerformed(e)
                val file = fixture.addFileToProject("independent.asm", correct).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val manager = FileEditorManager.getInstance(project)
                manager.openFile(file, FileEditorOpenRequest.withFocus(true))
                action.update(e)
                assertFalse(e.presentation.isEnabled)
                project.service<MarsLearningExecution>().observeIndependent(lifetime, file.toNioPath(), fixture.editor.document)
                action.update(e)
                assertTrue(e.presentation.isEnabled)
                val other = fixture.addFileToProject("other.asm", correct).virtualFile
                manager.openFile(other, FileEditorOpenRequest.withFocus(true))
                action.update(e)
                assertFalse(e.presentation.isEnabled)
                action.actionPerformed(e)
                manager.openFile(file, FileEditorOpenRequest.withFocus(true))
                WriteCommandAction.runWriteCommandAction(project) { file.rename(this, "renamed.asm") }
                action.update(e)
                assertFalse(e.presentation.isEnabled)
                WriteCommandAction.runWriteCommandAction(project) { file.delete(this) }
                action.update(e)
                assertFalse(e.presentation.isEnabled)
            } finally { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `the ordinary action runs real cases and shows a native report`() = timeoutRunBlocking {
        val shown = CompletableDeferred<String>()
        val lifetime = Disposer.newDisposable()
        try {
            withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("independent.asm", correct).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                projectFixture.get().service<MarsLearningExecution>().observeIndependent(lifetime, file.toNioPath(), fixture.editor.document)
                UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsIndependentFeedbackDialog>(MarsIndependentFeedbackDialog::class.java) {
                    override fun doIntercept(dialog: MarsIndependentFeedbackDialog) {
                        shown.complete(dialog.report)
                        dialog.close(DialogWrapper.OK_EXIT_CODE)
                    }
                })
                ActionUtil.performAction(action, event(SimpleDataContext.builder().add(CommonDataKeys.PROJECT, projectFixture.get())
                    .add(CommonDataKeys.VIRTUAL_FILE, file).add(CommonDataKeys.EDITOR, fixture.editor).build()))
            }
            val report = shown.await()
            assertEquals(5, report.lines().count { it.startsWith("PASS ") })
        } finally {
            withContext(Dispatchers.EDT) { UiInterceptors.clear(); Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `leaving the lesson before the asynchronous action finishes suppresses its dialog`() = timeoutRunBlocking {
        val lifetime = Disposer.newDisposable()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val pending = withContext(Dispatchers.EDT) {
                val file = fixture.addFileToProject("independent.asm", correct).virtualFile
                fixture.configureFromExistingVirtualFile(file)
                val checkpoint = projectFixture.get().service<MarsLearningExecution>().observeIndependent(lifetime, file.toNioPath(), fixture.editor.document)
                (action as MarsIndependentCheckAction).runCheck(projectFixture.get(), checkpoint, scope).also {
                    Disposer.dispose(lifetime)
                }
            }
            pending.join()
        } finally { scope.cancel(); Disposer.dispose(lifetime) }
    }

    @Test
    fun `reports distinguish assembly stale work and per-case failures`() = timeoutRunBlocking {
        withContext(Dispatchers.EDT) {
            for (feedback in listOf(MarsIndependentCheckpoint.Feedback.ASSEMBLY, MarsIndependentCheckpoint.Feedback.STALE,
                MarsIndependentCheckpoint.Feedback.Checked(listOf(ConsoleCheckResult("good", null), ConsoleCheckResult("bad", "wrong number"))))) {
                val dialog = MarsIndependentFeedbackDialog(projectFixture.get(), feedback)
                try {
                    assertTrue(dialog.report.isNotBlank())
                    if (feedback is MarsIndependentCheckpoint.Feedback.Checked) {
                        assertTrue(dialog.report.contains("PASS good"))
                        assertTrue(dialog.report.contains("FAIL bad\nwrong number"))
                    }
                } finally { dialog.close(DialogWrapper.CANCEL_EXIT_CODE) }
            }
        }
    }
}
