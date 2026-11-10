package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.highlighting.HighlightUsagesHandler
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions.ACTION_HIGHLIGHT_USAGES_IN_FILE
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@TestApplication
class MipsRegisterUsagesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native usage highlighting groups GPR aliases and excludes other banks and non operands`() = timeoutRunBlocking<Unit> {
        val source = """
            .eqv TEMP ${'$'}8
            .asciiz "${'$'}8"
            # ${'$'}8
            .text
            addu ${'$'}t<caret>0, ${'$'}zero, ${'$'}zero
            lw ${'$'}t1, 0(${'$'}8
            mfc0 ${'$'}8, ${'$'}8
            mfc0 ${'$'}8
            mtc0 ${'$'}a1, ${'$'}t0
            mov.s ${'$'}f8, ${'$'}f2
            .macro emit (%value)
                addu ${'$'}0x8, ${'$'}zero, %value
            .end_macro
            emit (${'$'}8)
        """.trimIndent()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("main.asm", source)
            val action = ActionManager.getInstance().getAction(ACTION_HIGHLIGHT_USAGES_IN_FILE)
            fixture.testAction(action)
            val editor = fixture.editor
            val highlights = editor.markupModel.allHighlighters.filter { it.textAttributesKey == EditorColors.SEARCH_RESULT_ATTRIBUTES }
            val lines = highlights.map { editor.document.getLineNumber(it.startOffset) }.sorted()
            assertEquals(listOf(4, 5, 6, 7, 11), lines)
            assertEquals(listOf("${'$'}0x8", "${'$'}8", "${'$'}8", "${'$'}8", "${'$'}t0"), highlights.map {
                editor.document.charsSequence.subSequence(it.startOffset, it.endOffset).toString()
            }.sorted())
        }
    }

    @Test
    fun `floating and CP0 occurrences stay in their own banks across unsaved edits`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = """
            mfc0 ${'$'}8, ${'$'}8
            MTC0 ${'$'}a0, ${'$'}0x8
            mtc0 ${'$'}a1, ${'$'}f8
            mov.s ${'$'}f8, ${'$'}f8
            mov.s ${'$'}f1, ${'$'}f0x8
            addu ${'$'}t0, ${'$'}8, ${'$'}zero
        """.trimIndent()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("main.s", source)
            moveTo("${'$'}8\n")
            assertEquals(listOf("${'$'}8", "${'$'}0x8"), currentUses())
            moveTo("${'$'}f8\n")
            assertNull(handler())
            moveTo("${'$'}f8,")
            assertEquals(listOf("${'$'}f8", "${'$'}f8"), currentUses())
            moveTo("${'$'}f0x8")
            assertNull(handler())

            val document = fixture.editor.document
            WriteCommandAction.runWriteCommandAction(current) { document.setText(source.replace("${'$'}0x8", "${'$'}12")) }
            PsiDocumentManager.getInstance(current).commitDocument(document)
            moveTo("${'$'}8\n")
            assertEquals(listOf("${'$'}8"), currentUses())

            moveTo("addu")
            assertNull(handler())
            fixture.configureByText("other.txt", "${'$'}t0<caret>")
            assertNull(handler())
        }
    }

    @Test
    fun `equivalences project register identities to unambiguous written operands`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = """
            .include "values.txt"
            .eqv CP mfc0
            .eqv PAIR ${'$'}8, ${'$'}9
            .eqv SAME ${'$'}8, ${'$'}t0
            .eqv HOME 0(${'$'}8)
            .eqv DEFINED anchor:
            .eqv LABEL_ONLY marker:
            .eqv WHOLE addu ${'$'}t0, ${'$'}zero, ${'$'}zero
            .eqv CALL emit
            .eqv FLOAT_ALIAS ${'$'}f0x8
            .eqv CYCLE LOOP
            .eqv LOOP CYCLE
            .macro emit (%value)
                addu ${'$'}s0, VAL, ${'$'}zero
            .end_macro
            addu ${'$'}t0, ${'$'}zero, ${'$'}zero
            DEFINED addu ${'$'}s0, VAL, ${'$'}zero
            CP ${'$'}a0, VAL
            addu ${'$'}s0, PAIR
            addu ${'$'}s0, SAME
            lw ${'$'}a1, HOME
            LABEL_ONLY
            WHOLE
            CALL (VAL)
            addu ${'$'}s0, VAL
            addu ${'$'}s0, CYCLE, ${'$'}zero
            mov.s ${'$'}f1, FLOAT_ALIAS
        """.trimIndent()
        val include = withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("values.txt", ".eqv VAL ${'$'}8\n")
            fixture.configureByText("entry.asm", source)
            include
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val direct = source.indexOf("\naddu ${'$'}t0") + 7
            fixture.editor.caretModel.moveToOffset(direct)
            assertEquals(listOf("${'$'}t0", "VAL", "SAME", "HOME"), currentUses())
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertEquals(listOf("${'$'}t0"), currentUses())
            }
            moveTo("VAL\naddu")
            assertEquals(listOf("VAL"), currentUses())
            for (unresolved in listOf("PAIR\naddu", "WHOLE\nCALL", "VAL)\n", "CYCLE,")) {
                moveTo(unresolved)
                assertNull(handler(), unresolved)
            }
            fixture.editor.caretModel.moveToOffset(source.lastIndexOf("FLOAT_ALIAS") + 1)
            assertNull(handler())

            val documents = PsiDocumentManager.getInstance(current)
            val document = documents.getDocument(include)!!
            WriteCommandAction.runWriteCommandAction(current) { document.setText(".eqv VAL ${'$'}9\n") }
            documents.commitDocument(document)
            fixture.editor.caretModel.moveToOffset(direct)
            assertEquals(listOf("${'$'}t0", "SAME", "HOME"), currentUses())
            moveTo("VAL, ${'$'}zero\nCP")
            assertEquals(listOf("VAL"), currentUses())
        }
    }

    private fun moveTo(spelling: String) {
        val editor = fixture.editor
        editor.caretModel.moveToOffset(editor.document.text.indexOf(spelling) + 1)
    }

    private fun handler() = HighlightUsagesHandler.createCustomHandler<PsiElement>(fixture.editor, fixture.file)

    private fun currentUses(): List<String> = requireNotNull(handler()).let { handler ->
        handler.computeUsages(handler.targets)
        handler.readUsages.map { it.substring(fixture.editor.document.text) }
    }
}
