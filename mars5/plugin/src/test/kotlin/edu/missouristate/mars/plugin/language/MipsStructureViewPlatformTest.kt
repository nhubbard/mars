package edu.missouristate.mars.plugin.language

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.util.Disposer
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsBuildService
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

@TestApplication
class MipsStructureViewPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native structure lists declarations in source order and groups macro-local labels`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.configureByText("outline.asm", """
                .eqv SIZE 4
                .extern buffer 4
                entry: nop
                .macro emit (%label)
                  local: nop
                  .macro nested
                  %label: nop
                  <caret>inner: lw ${'$'}t0,4(
                .end_macro
                entry: nop
                .macro unfinished
                  tail: nop
            """.trimIndent())
            val current = project.get()
            val builder = requireNotNull(LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(fixture.file))
            val editor = FileEditorManager.getInstance(current).getSelectedEditor(fixture.file.virtualFile)
            val view = builder.createStructureView(editor, current)
            try {
                val model = view.treeModel
                val root = model.root
                assertEquals("outline.asm", root.presentation.presentableText)
                assertNotNull(root.presentation.getIcon(false))
                val items = root.children.map { it as StructureViewTreeElement }
                assertEquals(listOf("SIZE", "buffer", "entry", "emit", "entry", "unfinished"), items.map(::name))
                assertNotSame(items[2].value, items[4].value)
                assertEquals(listOf("tail"), items.last().children.map { it.presentation.presentableText })
                val macro = items[3]
                val children = macro.children.map { it as StructureViewTreeElement }
                assertEquals(listOf("local", "nested", "inner"), children.map(::name))
                assertTrue(children[1].children.isEmpty())
                val inner = children.last()
                assertSame(inner.value, model.currentEditorElement)
                assertTrue(view.navigateToSelectedElement(false))
                val target = items[4]
                val declaration = target.value as MipsDeclaration
                assertNotNull(target.presentation.getIcon(false))
                assertTrue(target.canNavigate())
                assertTrue(target.canNavigateToSource())
                assertNotNull(withContext(Dispatchers.Default) { readAction { target.navigationRequest() } })
                target.navigate(true)
                assertEquals(declaration.textOffset, fixture.editor.caretModel.offset)
                assertEquals(fixture.file.text.lastIndexOf("entry:"), fixture.editor.caretModel.offset)
            } finally {
                Disposer.dispose(view)
            }
        }
    }

    @Test
    fun `outline shows effective macro names and their local declarations in the including entry`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = """
            EXTERNAL: nop
            ordinaryExternal: nop
            .end_macro
            entry: OPEN NAME (%reg)
                local: nop
            CLOSE
            WHOLE
                second: nop
            CLOSE
            .macro WRITTEN
                third: nop
            .end_macro
            outside: nop
        """.trimIndent()
        withContext(Dispatchers.EDT) {
            val macros = fixture.addFileToProject("macro-outline.s", source)
            fixture.addFileToProject("outline-entry.asm", """
                .eqv OPEN .macro
                .eqv CLOSE .end_macro
                .eqv NAME emit
                .eqv WHOLE .macro whole
                .eqv WRITTEN written
                .eqv EXTERNAL externalLocal
                .macro external
                .include "macro-outline.s"
            """.trimIndent())
            fixture.configureFromExistingVirtualFile(macros.virtualFile)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(fixture.file) as TreeBasedStructureViewBuilder
            val model = builder.createStructureViewModel(fixture.editor)
            try {
                val root = model.root
                val nodes = root.children.map { it as StructureViewTreeElement }
                assertEquals(listOf("externalLocal", "ordinaryExternal", "entry", "emit", "whole", "written", "outside"), nodes.map(::name))
                assertEquals(listOf("local"), nodes[3].children.map { it.presentation.presentableText })
                assertEquals(listOf("second"), nodes[4].children.map { it.presentation.presentableText })
                assertEquals(listOf("third"), nodes[5].children.map { it.presentation.presentableText })
                for ((node, spelling) in listOf(nodes[3] to "NAME", nodes[4] to "WHOLE")) {
                    assertNotNull(node.presentation.getIcon(false))
                    assertTrue(node.canNavigateToSource())
                    assertNotNull(withContext(Dispatchers.Default) { readAction { node.navigationRequest() } })
                    node.navigate(true)
                    assertEquals(source.indexOf(spelling), fixture.editor.caretModel.offset)
                    assertSame(node.value, model.currentEditorElement)
                }
                DumbModeTestUtils.runInDumbModeSynchronously(current) {
                    assertEquals(listOf("EXTERNAL", "ordinaryExternal", "entry", "local", "second", "WRITTEN", "outside"),
                        root.children.map { it.presentation.presentableText })
                    assertNull(model.currentEditorElement)
                }
                WriteCommandAction.runWriteCommandAction(current) {
                    fixture.editor.document.setText(".end_macro\nWHOLE\n    second: nop\nCLOSE\n")
                }
                PsiDocumentManager.getInstance(current).commitAllDocuments()
                val onlyMacro = root.children.single()
                assertEquals("whole", onlyMacro.presentation.presentableText)
                assertEquals("second", onlyMacro.children.single().presentation.presentableText)
            } finally {
                Disposer.dispose(model)
            }
        }
    }

    @Test
    fun `outline shows expanded labels in source order and omits labels replaced by directives`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = """
            .eqv OPEN .data
            .eqv ORIGINAL renamed
            .eqv PREFIX generated:
            .eqv LOCAL privateLabel
            OPEN: .ascii "ignored"
            datum: .word 42
            .text
            ORIGINAL: nop
            PREFIX nop
            .macro emit
                LOCAL: nop
                ordinary: nop
            .end_macro
            emit
        """.trimIndent()
        withContext(Dispatchers.EDT) { fixture.configureByText("effective-labels.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(fixture.file) as TreeBasedStructureViewBuilder
            val model = builder.createStructureViewModel(fixture.editor)
            try {
                val nodes = model.root.children.map { it as StructureViewTreeElement }
                assertEquals(listOf("OPEN", "ORIGINAL", "PREFIX", "LOCAL", "datum", "renamed", "generated", "emit"), nodes.map(::name))
                fixture.editor.caretModel.moveToOffset(source.indexOf("OPEN"))
                assertSame(nodes.first().value, model.currentEditorElement)
                val local = nodes.last().children.map { it as StructureViewTreeElement }
                assertEquals(listOf("privateLabel", "ordinary"), local.map(::name))
                for ((node, spelling) in listOf(nodes[5] to "ORIGINAL:", nodes[6] to "PREFIX nop", local.first() to "LOCAL:")) {
                    assertNotNull(node.presentation.getIcon(false))
                    assertTrue(node.canNavigateToSource())
                    node.navigate(true)
                    assertEquals(source.indexOf(spelling), fixture.editor.caretModel.offset)
                    assertSame(node.value, model.currentEditorElement)
                }
                fixture.editor.caretModel.moveToOffset(source.indexOf("OPEN:"))
                assertNull(model.currentEditorElement)
                DumbModeTestUtils.runInDumbModeSynchronously(current) {
                    assertFalse(model.root.children.any { it.presentation.presentableText == "generated" })
                    val open = model.root.children.filterIsInstance<StructureViewTreeElement>().filter { name(it) == "OPEN" }
                    assertEquals(2, open.size)
                    assertSame(open.last().value, model.currentEditorElement)
                }
                WriteCommandAction.runWriteCommandAction(current) {
                    fixture.editor.document.setText(source.replace("ORIGINAL renamed", "ORIGINAL changed"))
                }
                PsiDocumentManager.getInstance(current).commitAllDocuments()
                assertEquals("changed", model.root.children[5].presentation.presentableText)
            } finally {
                Disposer.dispose(model)
            }
        }
    }

    @Test
    fun `open outline receives updates when the selected assembly changes without source edits`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = "OPEN emit\nbody: nop\nCLOSE\n"
        val (macroEntry, plainEntry) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("shared-outline.s", source)
            val include = ".include \"shared-outline.s\"\n"
            val macro = fixture.addFileToProject("macro-entry.asm", ".eqv OPEN .macro\n.eqv CLOSE .end_macro\n${include}emit\n")
            val plain = fixture.addFileToProject("plain-entry.asm", ".eqv OPEN .set\n.eqv CLOSE .set noat\n$include")
            fixture.configureFromExistingVirtualFile(shared.virtualFile)
            macro.virtualFile.toNioPath() to plain.virtualFile.toNioPath()
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val model = withContext(Dispatchers.EDT) {
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(fixture.file) as TreeBasedStructureViewBuilder
            builder.createStructureViewModel(fixture.editor)
        }
        val updates = Channel<Unit>(Channel.UNLIMITED)
        model.addModelListener { updates.trySend(Unit) }
        suspend fun select(entry: Path) {
            val component = MarsSessionService.createMarsComponent()
            component.context().use {
                assertTrue(current.service<MarsBuildService>().assemble(entry, component, activateBuildView = false))
            }
            withTimeout(5.seconds) { updates.receive() }
        }
        try {
            assertEquals("body", readAction { model.root.children.single().presentation.presentableText })
            select(macroEntry)
            readAction {
                val macro = model.root.children.single()
                assertEquals("emit", macro.presentation.presentableText)
                assertEquals("body", macro.children.single().presentation.presentableText)
            }
            select(plainEntry)
            readAction {
                assertEquals("body", model.root.children.single().presentation.presentableText)
                assertEquals(source, fixture.file.text)
            }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(model) }
        }
    }

    @Test
    fun `outline follows unsaved edits while indexing and releases removed declarations`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            val text = PsiFileFactory.getInstance(current).createFileFromText("notes.txt", PlainTextFileType.INSTANCE, "text")
            assertNull(MipsStructureViewFactory().getStructureViewBuilder(text))
            fixture.configureByText("unsaved.s", ".eqv\n.macro\n.end_macro\nold: nop\n")
            val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(fixture.file) as TreeBasedStructureViewBuilder
            val model = builder.createStructureViewModel(fixture.editor)
            try {
                DumbModeTestUtils.runInDumbModeSynchronously(current) {
                    val root = model.root
                    val removed = root.children.single() as StructureViewTreeElement
                    assertEquals("old", name(removed))
                    fun edit(text: String) {
                        WriteCommandAction.runWriteCommandAction(current) { fixture.editor.document.setText(text) }
                        PsiDocumentManager.getInstance(current).commitAllDocuments()
                    }
                    edit("nop\n")
                    assertTrue(root.children.isEmpty())
                    assertNull(removed.value)
                    assertNull(name(removed))
                    assertTrue(removed.children.isEmpty())
                    assertNull(removed.presentation.getIcon(false))
                    assertNull(removed.navigationRequest())
                    assertFalse(removed.canNavigate())
                    assertFalse(removed.canNavigateToSource())
                    removed.navigate(false)
                    edit(".macro newMacro\nnewLabel: nop\n")
                    val newMacro = root.children.single()
                    assertEquals("newMacro", newMacro.presentation.presentableText)
                    assertEquals("newLabel", newMacro.children.single().presentation.presentableText)
                }
            } finally {
                Disposer.dispose(model)
            }
        }
    }

    private fun name(item: StructureViewTreeElement) = item.presentation.presentableText
}
