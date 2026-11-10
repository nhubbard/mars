package edu.missouristate.mars.plugin.project

import com.intellij.ide.IdeView
import com.intellij.ide.actions.TestDialogBuilder
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDirectory
import com.intellij.psi.PsiElement
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.IncorrectOperationException
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@TestApplication
class MarsNewFilePlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val action get() = assertInstanceOf(MarsNewFileAction::class.java, ActionManager.getInstance().getAction("Mars.NewMipsFile"))

    @ParameterizedTest
    @CsvSource(
        "entry, MIPS Assembly, entry.asm",
        "routine, MIPS Source, routine.s",
        "lib/module.s, MIPS Assembly, lib/module.s",
        "explicit.asm, MIPS Source, explicit.asm",
    )
    fun `native New action creates opens and assembles both source types`(name: String, kind: String, expected: String) =
        timeoutRunBlocking<Unit> {
            val file = withContext(Dispatchers.EDT) {
                val directory = fixture.addFileToProject("src/existing.asm", "nop\n").containingDirectory
                val view = DirectoryView(directory)
                val event = event(context(view, name, kind))
                action.update(event)
                assertTrue(event.presentation.isEnabledAndVisible)
                assertFalse(action.startInWriteAction())
                assertEquals("Create MIPS File $name", action.getActionName(directory, name, kind))
                action.actionPerformed(event)

                val source = assertInstanceOf(MipsFile::class.java, view.selected)
                val virtualFile = source.virtualFile
                assertEquals(directory.virtualFile.findFileByRelativePath(expected), virtualFile)
                assertEquals(MipsFileType, source.fileType)
                assertEquals(listOf(".text", "nop"), source.text.trim().lines().map(String::trim))
                assertTrue(FileEditorManager.getInstance(project).isFileOpen(virtualFile))
                virtualFile
            }
            assertTrue(project.service<MarsBuildService>().assemble(file.toNioPath()))
            assertTrue(project.service<MarsAssemblySettings>().projectSources.isEmpty())
        }

    @Test
    fun `native action is unavailable without a directory and protects existing files`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val unavailable = event(DataContext.EMPTY_CONTEXT)
            action.update(unavailable)
            assertFalse(unavailable.presentation.isEnabledAndVisible)

            val existing = fixture.addFileToProject("src/existing.s", ".data\n.word 42\n")
            val view = DirectoryView(existing.containingDirectory)
            assertThrows<IncorrectOperationException> { action.actionPerformed(event(context(view, "existing.s", "MIPS Assembly"))) }
            assertEquals(".data\n.word 42\n", existing.text)
        }
    }

    private fun context(view: DirectoryView, name: String, kind: String): DataContext = SimpleDataContext.builder()
        .add(CommonDataKeys.PROJECT, project)
        .add(LangDataKeys.IDE_VIEW, view)
        .add(TestDialogBuilder.TestAnswers.KEY, TestDialogBuilder.TestAnswers(name, kind))
        .build()

    private fun event(data: DataContext) = AnActionEvent.createEvent(action, data, null, ActionPlaces.PROJECT_VIEW_POPUP, ActionUiKind.NONE, null)

    private class DirectoryView(private val directory: PsiDirectory) : IdeView {
        var selected: PsiElement? = null
            private set
        override fun selectElement(element: PsiElement) { selected = element }
        override fun getDirectories() = arrayOf(directory)
        override fun getOrChooseDirectory() = directory
    }
}
