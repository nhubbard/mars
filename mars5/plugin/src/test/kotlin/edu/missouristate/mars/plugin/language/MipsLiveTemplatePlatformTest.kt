package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE
import com.intellij.openapi.actionSystem.IdeActions.ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB
import com.intellij.openapi.application.EDT
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsLiveTemplatePlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native Tab expands macro template with editable name and parameter and positions the caret in the body`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("main.asm", "mipsmacro<caret>\n")
            expand()
            assertEquals("emit", fixture.editor.selectionModel.selectedText)
            fixture.type("emitValue")
            next()
            assertEquals("value", fixture.editor.selectionModel.selectedText)
            fixture.type("argument")
            next()
            assertEquals(1, fixture.editor.caretModel.logicalPosition.line)
            fixture.type("li ${'$'}a0, %argument")
            assertEquals(".macro emitValue (%argument)\n    li ${'$'}a0, %argument\n.end_macro\n", fixture.editor.document.text)
        }
    }

    @Test
    fun `entry equivalence and syscall templates insert MARS source`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("main.s", "mipsentry<caret>\n")
            expand()
            assertEquals("main", fixture.editor.selectionModel.selectedText)
            fixture.type("entry")
            next()
            assertEquals(".text\n.globl entry\nentry:", fixture.editor.document.text.trimEnd())
            assertEquals(3, fixture.editor.caretModel.logicalPosition.line)

            fixture.configureByText("main.s", "mipseqv<caret>\n")
            expand()
            assertEquals("VALUE", fixture.editor.selectionModel.selectedText)
            next()
            assertEquals("0", fixture.editor.selectionModel.selectedText)
            fixture.type("42")
            next()
            fixture.checkResult(".eqv VALUE 42\n")

            for ((shortcut, service) in listOf("mipsprint" to 1, "mipsexit" to 10)) {
                fixture.configureByText("main.s", ".text\n    $shortcut<caret>\n")
                expand()
                fixture.checkResult(".text\n    addiu ${'$'}v0, ${'$'}zero, $service\n    syscall\n")
            }
        }
    }

    @Test
    fun `template contexts follow statement lines and physical macro scope`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val statement = MipsStatementTemplateContext()
            val topLevel = MipsTopLevelTemplateContext()
            for ((name, text) in listOf(
                "main.txt" to "mipsmacro<caret>",
                "main.asm" to "# mipsmacro<caret>",
                "main.asm" to ".asciiz \"mipsmacro<caret>\"",
                "main.asm" to "j mipsmacro<caret>",
                "main.asm" to "mipsmacro<caret> extra",
                "main.asm" to "mipsmacro<caret>: nop",
            )) {
                val file = fixture.configureByText(name, text)
                val context = TemplateActionContext.expanding(file, fixture.editor)
                assertFalse(statement.isInContext(context), text)
                assertFalse(topLevel.isInContext(context), text)
            }
            val body = fixture.configureByText("main.asm", ".macro emit\n    mipsmacro<caret>\n.end_macro\n")
            val context = TemplateActionContext.expanding(body, fixture.editor)
            assertTrue(statement.isInContext(context))
            assertFalse(topLevel.isInContext(context))
            expand()
            assertTrue(fixture.editor.document.text.contains("mipsmacro"))

            val empty = fixture.configureByText("empty.asm", "<caret>")
            assertTrue(topLevel.isInContext(TemplateActionContext.expanding(empty, fixture.editor)))
            assertFalse(statement.isInContext(TemplateActionContext.surrounding(empty, fixture.editor)))

            val closed = fixture.configureByText("main.asm", ".macro emit\n.end_macro\n<caret>")
            assertTrue(topLevel.isInContext(TemplateActionContext.expanding(closed, fixture.editor)))
            val open = fixture.configureByText("main.asm", ".macro emit\n    nop\n<caret>")
            assertFalse(topLevel.isInContext(TemplateActionContext.expanding(open, fixture.editor)))
        }
    }

    private fun expand() = fixture.performEditorAction(ACTION_EXPAND_LIVE_TEMPLATE_BY_TAB)
    private fun next() = fixture.performEditorAction(ACTION_EDITOR_NEXT_TEMPLATE_VARIABLE)
}
