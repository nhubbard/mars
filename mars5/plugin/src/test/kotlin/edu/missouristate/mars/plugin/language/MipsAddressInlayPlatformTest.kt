package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.hints.declarative.DeclarativeInlayHintsSettings
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.codeInsight.hints.declarative.impl.inlayRenderer.DeclarativeInlayRenderer
import com.intellij.codeInsight.hints.declarative.impl.views.TextInlayPresentationEntry
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import java.awt.Point
import java.awt.event.MouseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsAddressInlayPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val refresh get() = project.service<MipsAssemblyInlayRefresh>()

    @Test
    fun `native inline hints update after unsaved includes and assembly settings change`() = timeoutRunBlocking<Unit> {
        val source = """
            .data
            .include "pad.txt"
            item: .word 7
            .extern extbuf 4
            .text
            entry: nop
            .eqv NAME expanded
            NAME: nop
            .macro emit (%name)
                %name: nop
            .end_macro
            emit (hidden)
        """.trimIndent()
        val (entry, pad) = withContext(Dispatchers.EDT) {
            val pad = fixture.addFileToProject("pad.txt", "padding: .space 4\n")
            val other = fixture.addFileToProject("unrelated.asm", "nop\n")
            val fileEditorManager = FileEditorManager.getInstance(project)
            fileEditorManager.openFile(pad.virtualFile, FileEditorOpenRequest.withFocus(false))
            fileEditorManager.openFile(other.virtualFile, FileEditorOpenRequest.withFocus(false))
            val entry = fixture.addFileToProject("entry.asm", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry to pad
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withEnabledHints {
            withContext(Dispatchers.EDT) {
                val provider = MipsAddressInlayProvider()
                assertNull(provider.createCollector(pad, fixture.editor))
                assertNoPublishedHints(entry)
            }
            assertHints(listOf("0x10010004", "0x10000000", "0x00400000"))
            withContext(Dispatchers.EDT) {
                val docs = PsiDocumentManager.getInstance(project)
                val active = docs.getDocument(entry)!!
                WriteCommandAction.runWriteCommandAction(project) { active.insertString(active.textLength, "\n# unsaved") }
                assertNoPublishedHints(entry)
                docs.commitDocument(active)
                val include = docs.getDocument(pad)!!
                WriteCommandAction.runWriteCommandAction(project) { include.setText("padding: .space 12\n") }
                docs.commitDocument(include)
                assertNoPublishedHints(entry)
            }
            assertHints(listOf("0x1001000c", "0x10000000", "0x00400000"))
            withContext(Dispatchers.EDT) {
                project.service<MarsAssemblySettings>().changeMemoryLayout(MarsMemoryLayout.TEXT_AT_ZERO)
                assertNoPublishedHints(entry)
            }
            assertHints(listOf("0x0000200c", "0x00001000", "0x00000000"))
            withContext(Dispatchers.EDT) {
                val docs = PsiDocumentManager.getInstance(project)
                val active = docs.getDocument(entry)!!
                WriteCommandAction.runWriteCommandAction(project) { active.insertString(active.textLength, "\nj missing\n") }
                docs.commitDocument(active)
                assertNoPublishedHints(entry)
            }
            assertHints(emptyList())
        }
    }

    @Test
    fun `address hints hide when no single assembly owns the include`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("part.s", "entry: nop\n").also { fixture.configureFromExistingVirtualFile(it.virtualFile) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withEnabledHints {
            assertHints(listOf("0x00400000"))
            withContext(Dispatchers.EDT) {
                fixture.addFileToProject("first.asm", ".include \"part.s\"\n")
                fixture.addFileToProject("second.asm", ".include \"part.s\"\n")
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) { assertNoPublishedHints(file) }
            assertHints(emptyList())
        }
    }

    @Test
    fun `native pseudo expansions follow unsaved aliases and outer macro calls`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "values.txt"
            li ${'$'}t0, 1
            COMMAND ${'$'}t1, VALUE
            nop
            .macro inner (%value)
                li ${'$'}t2, %value
                nop
            .end_macro
            .macro outer (%value)
                inner (%value)
            .end_macro
            outer (VALUE)
        """.trimIndent()
        val (entry, values) = withContext(Dispatchers.EDT) {
            val values = fixture.addFileToProject("values.txt", ".eqv COMMAND li\n.eqv VALUE 7\n")
            val entry = fixture.addFileToProject("entry.asm", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry to values
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withEnabledHints(MIPS_EXPANSION_HINTS_ID) {
            withContext(Dispatchers.EDT) {
                assertNull(MipsExpansionInlayProvider().createCollector(values, fixture.editor))
                assertNoPublishedHints(entry, MipsExpansionInlayProvider())
            }
            assertHints(List(3) { "1 instruction" }, MIPS_EXPANSION_HINTS_ID)
            withContext(Dispatchers.EDT) {
                val docs = PsiDocumentManager.getInstance(project)
                val include = docs.getDocument(values)!!
                WriteCommandAction.runWriteCommandAction(project) {
                    include.setText(".eqv COMMAND li\n.eqv VALUE 0x12345678\n")
                }
                docs.commitDocument(include)
                assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(include))
                assertNoPublishedHints(entry, MipsExpansionInlayProvider())
            }
            assertHints(listOf("1 instruction", "2 instructions", "2 instructions"), MIPS_EXPANSION_HINTS_ID)
            withContext(Dispatchers.EDT) {
                val editor = fixture.editor
                val expansions = editor.inlayModel.getAfterLineEndElementsInRange(
                    0, editor.document.textLength, DeclarativeInlayRenderer::class.java,
                ).filter { it.renderer.providerId == MIPS_EXPANSION_HINTS_ID }
                assertEquals(listOf(1, 2, 11), expansions.map { editor.document.getLineNumber(it.offset) })
                val renderer = expansions.last().renderer
                val mouse = MouseEvent(editor.contentComponent, MouseEvent.MOUSE_CLICKED, 0, 0, 0, 0, 1, false, MouseEvent.BUTTON1)
                renderer.handleLeftClick(EditorMouseEvent(editor, mouse, editor.getMouseEventArea(mouse)), Point(10, 5), false)
                val text = renderer.presentationList.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text }
                assertTrue(text.contains("lui ") && text.contains("ori ") && !text.contains("nop"), text)
                project.service<MarsAssemblySettings>().changeAssemblerOptions(allowPseudoInstructions = false, warningsAreErrors = false)
                assertNoPublishedHints(entry, MipsExpansionInlayProvider())
            }
            assertHints(emptyList(), MIPS_EXPANSION_HINTS_ID)
        }
    }

    private suspend fun assertHints(expected: List<String>, providerId: String = MIPS_ADDRESS_HINTS_ID) {
        awaitEditorRefreshes()
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        awaitEditorRefreshes()
        withContext(Dispatchers.EDT) {
            fixture.doHighlighting()
            val editor = fixture.editor
            val model = editor.inlayModel
            val inlays = if (providerId == MIPS_EXPANSION_HINTS_ID) {
                model.getAfterLineEndElementsInRange(0, editor.document.textLength, DeclarativeInlayRenderer::class.java)
            } else {
                model.getInlineElementsInRange(0, editor.document.textLength, DeclarativeInlayRenderer::class.java)
            }
            val hints = inlays.filter { it.renderer.providerId == providerId }
                .map { inlay -> inlay.renderer.presentationList.getEntries().joinToString("") { (it as TextInlayPresentationEntry).text } }
            assertEquals(expected, hints)
        }
    }

    private fun awaitEditorRefreshes() {
        // A folding refresh restarts the same daemon the highlighting fixture drives.
        project.service<MipsFoldingRefresh>().waitForRefresh()
        refresh.waitForRefresh()
    }

    private fun assertNoPublishedHints(file: PsiFile, provider: InlayHintsProvider = MipsAddressInlayProvider()) {
        val collector = provider.createCollector(file, fixture.editor) as OwnBypassCollector
        collector.collectHintsForFile(file, object : InlayTreeSink {
            override fun addPresentation(
                position: InlayPosition, payloads: List<InlayPayload>?, tooltip: String?, hintFormat: HintFormat,
                builder: PresentationTreeBuilder.() -> Unit,
            ) = error("Unexpected current inlay at $position")

            override fun whenOptionEnabled(optionId: String, block: () -> Unit) = error("Unexpected option $optionId")
        })
    }

    private suspend fun withEnabledHints(providerId: String = MIPS_ADDRESS_HINTS_ID, action: suspend () -> Unit) {
        val settings = readAction { DeclarativeInlayHintsSettings.getInstance() }
        val previous = withContext(Dispatchers.EDT) {
            val previous = settings.isProviderEnabled(providerId)
            WriteCommandAction.runWriteCommandAction(project) { settings.setProviderEnabled(providerId, true) }
            DeclarativeInlayHintsPassFactory.scheduleRecompute(fixture.editor, project)
            previous
        }
        try {
            action()
        } finally {
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { settings.setProviderEnabled(providerId, previous == true) }
            }
        }
    }
}
