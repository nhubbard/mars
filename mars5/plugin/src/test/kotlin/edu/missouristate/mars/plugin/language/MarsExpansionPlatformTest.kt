package edu.missouristate.mars.plugin.language

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.ListPopupStep
import com.intellij.openapi.util.Disposer
import com.intellij.psi.PsiFileFactory
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.ui.UiInterceptors
import edu.missouristate.mars.api.AssembledInstruction
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsExpansionPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `editor action lists nested emitted instructions from unsaved sources and navigates each frame`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val (definition, entry) = withContext(Dispatchers.EDT) {
            val definition = fixture.addFileToProject("macros.s", """
                .macro inner
                  li ${'$'}t0, 0x12345678
                .end_macro
                .macro outer
                  inner
                .end_macro
            """.trimIndent())
            val entry = fixture.addFileToProject("main.asm", ".include \"macros.s\"\n.text\nnop\n")
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            WriteCommandAction.runWriteCommandAction(current) {
                val document = fixture.editor.document
                val call = document.getLineStartOffset(2)
                document.replaceString(call, call + 3, "outer")
                fixture.editor.caretModel.moveToOffset(call)
            }
            definition.virtualFile to entry.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val intercepted = CompletableDeferred<ListPopup>()
        withContext(Dispatchers.EDT) {
            val action = ActionManager.getInstance().getAction("Mars.ShowExpansion")
            assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
            val disabled = TestActionEvent.createTestEvent(action, DataContext.EMPTY_CONTEXT)
            action.update(disabled)
            assertFalse(disabled.presentation.isEnabled)
            action.actionPerformed(disabled)
            val noEditor = TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
                .add(CommonDataKeys.PSI_FILE, fixture.file).build())
            action.update(noEditor)
            assertFalse(noEditor.presentation.isEnabled)
            val lightFile = PsiFileFactory.getInstance(current).createFileFromText("light.asm", MipsFileType, "nop")
            val nonLocal = TestActionEvent.createTestEvent(action, SimpleDataContext.builder()
                .add(CommonDataKeys.PSI_FILE, lightFile).add(CommonDataKeys.EDITOR, fixture.editor).build())
            action.update(nonLocal)
            assertFalse(nonLocal.presentation.isEnabled)
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, current)
                .add(CommonDataKeys.PSI_FILE, fixture.file)
                .add(CommonDataKeys.EDITOR, fixture.editor)
                .build()
            val event = TestActionEvent.createTestEvent(action, context)
            action.update(event)
            assertTrue(event.presentation.isEnabled)
            UiInterceptors.register(object : UiInterceptors.UiInterceptor<ListPopup>(ListPopup::class.java) {
                override fun doIntercept(component: ListPopup) {
                    intercepted.complete(component)
                }
            })
            ActionUtil.performAction(action, event)
        }
        val popup = intercepted.await()
        withContext(Dispatchers.EDT) {
            try {
                @Suppress("UNCHECKED_CAST")
                val step = popup.listStep as ListPopupStep<AssembledInstruction>
                assertEquals(listOf(0x00400000, 0x00400004), step.values.map(AssembledInstruction::address))
                val first = step.values.first()
                assertTrue(step.hasSubstep(first))
                assertTrue(step.getTextFor(first).contains("lui"))
                @Suppress("UNCHECKED_CAST")
                val chain = step.onChosen(first, true) as ListPopupStep<Origin>
                assertEquals(listOf(Location(definition.toNioPath(), 2), Location(definition.toNioPath(), 5),
                    Location(entry.toNioPath(), 3)), chain.values.map(Origin::location))
                assertTrue(chain.getTextFor(chain.values.first()).endsWith("macros.s:2"))
                chain.onChosen(chain.values.first(), true)
                chain.finalRunnable!!.run()
                assertTrue(definition in FileEditorManager.getInstance(current).selectedFiles)
                WriteCommandAction.runWriteCommandAction(current) {
                    FileDocumentManager.getInstance().getDocument(definition)!!.insertString(0, "# changed\n")
                }
                chain.onChosen(chain.values.first(), true)
                chain.finalRunnable!!.run()
            } finally {
                Disposer.dispose(popup)
                UiInterceptors.clear()
            }
        }
    }

    @Test
    fun `empty failed stale and ambiguous requests yield distinct results`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val (file, text) = withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("shared.s", "# comment\nunknown_instruction\n")
            fixture.configureFromExistingVirtualFile(source.virtualFile)
            (fixture.file as MipsFile) to fixture.editor.document.immutableCharSequence
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        assertEquals(ExpansionPreview.Unavailable.CHANGED, inspectExpansion(file, 1, "nop\n"))
        assertEquals(ExpansionPreview.Unavailable.ASSEMBLY, inspectExpansion(file, 2, text))
        val nextText = withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(current) { fixture.editor.document.setText("# comment\nnop\n") }
            fixture.editor.document.immutableCharSequence
        }
        assertEquals(ExpansionPreview.Unavailable.EMPTY, inspectExpansion(file, 1, nextText))
        withContext(Dispatchers.EDT) {
            showExpansionPreview(current, fixture.editor, ExpansionPreview.Unavailable.EMPTY)
            val factory = EditorFactory.getInstance()
            val editor = factory.createEditor(fixture.editor.document, current)
            factory.releaseEditor(editor)
            showExpansionPreview(current, editor, ExpansionPreview.Unavailable.EMPTY)
        }
        val preview = inspectExpansion(file, 2, nextText) as ExpansionPreview.Instructions
        assertEquals("nop", preview.rows.single().assembly().trim())
        withContext(Dispatchers.EDT) { fixture.addFileToProject("unrelated.txt", "new file") }
        val afterUnrelatedEdit = preview.capture.expansionAt(file, 2) as ExpansionPreview.Instructions
        assertEquals(preview.rows, afterUnrelatedEdit.rows)
        withContext(Dispatchers.EDT) {
            val path = file.virtualFile.toNioPath()
            assertNull(expansionSourceTarget(current, preview.capture, Location(path, 99)))
            assertNull(expansionSourceTarget(current, preview.capture, Location(path.resolveSibling("missing.s"), 1)))
            assertNull(expansionSourceTarget(current, preview.capture, Location(path.parent, 1)))
            WriteCommandAction.runWriteCommandAction(current) { fixture.editor.document.insertString(0, "# changed\n") }
            assertNull(expansionSourceTarget(current, preview.capture, preview.rows.single().origin().location()))
        }
        assertEquals(ExpansionPreview.Unavailable.CHANGED, preview.capture.expansionAt(file, 2))
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("first.asm", ".include \"shared.s\"\n")
            fixture.addFileToProject("second.asm", ".include \"shared.s\"\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        assertEquals(ExpansionPreview.Unavailable.ENTRY, inspectExpansion(file, 1, nextText))
        assertEquals(ExpansionPreview.Unavailable.CHANGED, preview.capture.expansionAt(file, 2))
    }
}
