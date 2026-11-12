package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.daemon.LineMarkerInfo.LineMarkerGutterIconRenderer
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsTransferLineMarkerPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native gutter exposes concrete branch call and jump destinations`() = timeoutRunBlocking<Unit> {
        val source = $$"""
            .include "targets.txt"
            .eqv DEST first
            .eqv CALL jal DEST
            .eqv DEFINED entry:
            DEFINED CALL
            beq $t0, $zero, first # Conditional branch
            beq $t0, 100000, first
            bgeu $t0, $t1, DEST
            bc1t 1, first
            j nop
            nop: nop
            j missing
            jalr $t0
            la $t0, first
            beq $t0, first
            .extern storage 4
            j storage
            .macro emit
                first: nop
                b first
            .end_macro
            .eqv DATA .word
            DATA first
            .eqv CYCLE LOOP
            .eqv LOOP CYCLE
            beq $t0, $zero, CYCLE
            unknown first
        """.trimIndent()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("targets.txt", "# physical target\n  first: nop\n")
            fixture.configureByText("entry.asm", source)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val markers = markers()
            assertEquals(listOf(4, 5, 6, 7, 8, 9, 19), markers.map { fixture.editor.document.getLineNumber(it.startOffset) })
            assertEquals(listOf("DEFINED", "beq", "beq", "bgeu", "bc1t", "j", "b"), markers.map { it.element?.text })
            for (marker in markers.take(5)) {
                val target = marker.createGotoRelatedItems().single().element!!
                assertEquals("targets.txt", target.containingFile.name)
                assertEquals(20, target.textOffset)
                assertNotNull(marker.createGutterRenderer().clickAction)
            }
            assertEquals(source.indexOf("nop: nop"), markers[5].createGotoRelatedItems().single().element?.textOffset)
            assertEquals(source.indexOf("first: nop"), markers.last().createGotoRelatedItems().single().element?.textOffset)
        }
    }

    @Test
    fun `unsaved include edits refresh the gutter and stale navigation rechecks the label`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val include = withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("targets.txt", ".eqv DEST first\nfirst: nop\nsecond: nop\n")
            fixture.configureByText("entry.asm", ".include \"targets.txt\"\njal DEST\n")
            include
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val original = markers().single()
            assertEquals("Go to MARS target first", original.lineMarkerTooltip)
            val documents = PsiDocumentManager.getInstance(current)
            val document = documents.getDocument(include)!!
            WriteCommandAction.runWriteCommandAction(current) { document.setText(".eqv DEST second\nfirst: nop\nsecond: nop\n") }
            documents.commitDocument(document)
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            assertEquals("second", original.createGotoRelatedItems().single().element?.text)
            val updated = markers().single()
            assertEquals("Go to MARS target second", updated.lineMarkerTooltip)
            assertEquals("second", updated.createGotoRelatedItems().single().element?.text)
            WriteCommandAction.runWriteCommandAction(current) { document.setText(".eqv DEST missing\nfirst: nop\nsecond: nop\n") }
            documents.commitDocument(document)
            assertTrue(markers().isEmpty())
        }
    }

    @Test
    fun `old gutter actions have no target while indexing or after source deletion`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("entry.asm", "j target\nj target\ntarget: nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val (indexing, removed) = markers()
            DumbModeTestUtils.runInDumbModeSynchronously(current) { assertTrue(indexing.createGotoRelatedItems().isEmpty()) }
            val editor = fixture.editor
            val document = editor.document
            WriteCommandAction.runWriteCommandAction(current) { document.deleteString(0, document.getLineStartOffset(2)) }
            PsiDocumentManager.getInstance(current).commitDocument(document)
            assertTrue(removed.createGotoRelatedItems().isEmpty())
        }
    }

    private fun markers(): List<RelatedItemLineMarkerInfo<*>> = fixture.findAllGutters()
        .filterIsInstance<LineMarkerGutterIconRenderer<*>>()
        .map { it.lineMarkerInfo }
        .filterIsInstance<RelatedItemLineMarkerInfo<*>>()
        .filter { it.lineMarkerTooltip?.startsWith("Go to MARS target ") == true }
}
