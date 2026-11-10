package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import edu.missouristate.mars.plugin.language.psi.MipsMacroFormal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsMacroFormalRenamePlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native rename rewrites one parameter across includes and supports undo and redo`() = timeoutRunBlocking<Unit> {
        val rootText = """
            .eqv REG ${'$'}a0
            .macro emit (%value)
                .include "body.s"
                li ${'$'}a1, %value
            .end_macro
            .macro other (%value)
                li ${'$'}a0, %value
            .end_macro
            emit(7)
            .data
            .asciiz "%value"
        """.trimIndent()
        val bodyText = "li REG, %value # leave %value in the comment\n.extern %value 4\n"
        val (root, body) = withContext(Dispatchers.EDT) {
            val body = fixture.addFileToProject("body.s", bodyText)
            val root = fixture.addFileToProject("rename.asm", rootText)
            fixture.configureFromExistingVirtualFile(root.virtualFile)
            root to body
        }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val target = readAction { formals(root).first() }
        val newName = "\$operand"
        readAction {
            val validator = MipsMacroFormalRenameInputValidator()
            assertTrue(validator.pattern.accepts(target))
            assertTrue(validator.isInputValid(newName, target, ProcessingContext()))
            assertFalse(validator.isInputValid("\$t0", target, ProcessingContext()))
            assertFalse(validator.isInputValid("%two words", target, ProcessingContext()))
            val processor = RenamePsiElementProcessor.forElement(target)
            assertTrue(processor is MipsMacroFormalRenameProcessor)
            assertFalse(processor.canProcessElement(root))
            target.checkSetName(target.name)
            val incomplete = assertThrows(IncorrectOperationException::class.java) {
                processor.renameElement(target, newName, emptyArray(), null)
            }
            assertTrue(incomplete.message!!.contains("every reference"))
        }
        withContext(Dispatchers.EDT) {
            val documents = PsiDocumentManager.getInstance(current)
            fun assertSources(expectedRoot: String, expectedBody: String) {
                documents.commitAllDocuments()
                assertEquals(expectedRoot, root.text)
                assertEquals(expectedBody, body.text)
            }
            RenameProcessor(current, target, newName, false, false).run()
            val renamedRoot = rootText.replaceFirst("(%value)", "($newName)").replaceFirst("\$a1, %value", "\$a1, $newName")
            val renamedBody = bodyText.replaceFirst("%value", newName).replace(".extern %value", ".extern $newName")
            assertSources(renamedRoot, renamedBody)
            PsiTestUtil.checkFileStructure(body)

            val editor = FileEditorManager.getInstance(current).selectedEditor
            val undo = UndoManager.getInstance(current)
            val previousDialog = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                undo.undo(editor)
                assertSources(rootText, bodyText)
                undo.redo(editor)
                assertSources(renamedRoot, renamedBody)
            } finally {
                TestDialogManager.setTestDialog(previousDialog)
            }
        }
    }

    @Test
    fun `rename refuses ambiguous headers references and new token boundaries`() = timeoutRunBlocking<Unit> {
        val cases = listOf(
            Triple(".macro emit (%arg)\n.end_macro", "%", "Enter one MARS macro parameter"),
            Triple(".macro emit (%arg, %arg)\n.end_macro", "%next", "unambiguous effective name"),
            Triple(".eqv FORM %arg\n.macro emit (FORM)\n.end_macro", "%next", "unambiguous effective name"),
            Triple(".macro emit (%arg, %next)\n.end_macro", "%next", "conflicts"),
            Triple(".eqv \$next 3\n.macro emit (%arg)\n.end_macro", "\$next", "conflicts"),
            Triple(".macro emit (%arg)\n%next\n.end_macro", "%next", "conflicts"),
            Triple(".macro emit (\$arg)\nli \$a0, \$arg-2\n.end_macro", "%next", "conflicts"),
            Triple(".macro emit (%arg)\nli \$a0, %arg-2\n.end_macro", "\$spare", "conflicts"),
            Triple(".eqv VALUE %arg\n.macro emit (%arg)\nli \$a0, VALUE\n.end_macro", "%next", "supplied through an equivalence"),
            Triple(".macro emit (\$arg)\n.eqv EXPORTED \$arg\n.end_macro", "%next", "supplied through an equivalence"),
            Triple(".macro emit (%arg)\nli \$a0, @\n.end_macro", "%next", "fully resolved"),
            Triple(".macro emit (%arg)\n.include \"absent.s\"\n.end_macro", "%next", "fully resolved"),
            Triple(".macro emit (%arg)\nnop", "%next", "closed macro"),
            Triple(".macro emit\n.macro nested (%arg)\n.end_macro", "%next", "closed macro"),
        )
        for ((source, name, message) in cases) {
            val file = withContext(Dispatchers.EDT) { fixture.configureByText("rename-unsafe.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
            readAction {
                for (formal in formals(file).filter { it.name != name }) {
                    val conflict = assertThrows(IncorrectOperationException::class.java) { formal.checkSetName(name) }
                    assertTrue(conflict.message!!.contains(message), "$source\n${conflict.message}")
                }
                assertEquals(source, file.text)
            }
        }
        val nested = withContext(Dispatchers.EDT) {
            fixture.configureByText("rename-nested.asm", ".macro emit (%arg)\n.macro nested (%arg)\n.end_macro\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val problem = assertThrows(IncorrectOperationException::class.java) { formals(nested).first().checkSetName("%next") }
            assertTrue(problem.message!!.contains("cannot be rewritten"))
        }
    }

    @Test
    fun `rename refuses declarations or references shared by independent entries`() = timeoutRunBlocking<Unit> {
        val body = "li \$a0, %arg\n"
        val (first, header) = withContext(Dispatchers.EDT) {
            val header = fixture.addFileToProject("header.s", ".macro included (%arg)\n.end_macro\n")
            fixture.addFileToProject("body.s", body)
            val prefix = ".include \"header.s\"\n.macro emit (%arg)\n.include \"body.s\"\n.end_macro\n.eqv LIMIT 1\nli \$t0, LIMIT\n"
            val first = fixture.addFileToProject("first.asm", prefix)
            fixture.addFileToProject("second.asm", prefix)
            first to header
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            for (file in listOf(first, header)) {
                val problem = assertThrows(IncorrectOperationException::class.java) { formals(file).single().checkSetName("%next") }
                assertTrue(problem.message!!.contains("one assembly entry"), "${file.name}: ${problem.message}")
            }
            val constant = first.findReferenceAt(first.text.lastIndexOf("LIMIT"))!!
            assertThrows(IncorrectOperationException::class.java) { constant.handleElementRename("OTHER") }
        }
    }

    private fun formals(file: PsiFile) =
        PsiTreeUtil.findChildrenOfType(file, MipsMacroFormal::class.java).sortedBy { it.textOffset }
}
