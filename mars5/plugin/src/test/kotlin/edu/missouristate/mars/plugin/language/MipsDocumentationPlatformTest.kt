package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsDocumentationPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `quick documentation presents the core instruction examples and descriptions`() = timeoutRunBlocking<Unit> {
        val html = documentation("entry: DIV<caret> \$t0, \$t1, \$t2").orEmpty()
        val forms = InstructionDefaults.get().matchOperator("div")
        for (instruction in forms) {
            assertTrue(StringUtil.escapeXmlEntities(instruction.exampleFormat) in html, html)
            assertTrue(StringUtil.escapeXmlEntities(instruction.description) in html, html)
        }
        assertTrue("c.eq.s" in documentation("c.<caret>eq.s \$f0, \$f2").orEmpty())
    }

    @Test
    fun `directive documentation uses the directive in its syntactic role`() = timeoutRunBlocking<Unit> {
        for ((directive, source) in listOf(
            Directives.WORD to ".wo<caret>rd 1",
            Directives.DATA to ".D<caret>ATA",
            Directives.EQV to ".eq<caret>v COUNT 4",
            Directives.SET to ".se<caret>t noat",
            Directives.INCLUDE to "nop .in<caret>clude \"part.s\"",
            Directives.INCLUDE to ".in<caret>clude",
            Directives.MACRO to ".ma<caret>cro emit\n.end_macro",
            Directives.END_MACRO to ".macro emit\n.end_ma<caret>cro",
            Directives.END_MACRO to ".end_ma<caret>cro",
        )) {
            val html = documentation(source).orEmpty()
            assertTrue(directive.name in html && StringUtil.escapeXmlEntities(directive.description) in html, source)
        }
    }

    @Test
    fun `catalog documentation stays off labels references comments and other languages`() = timeoutRunBlocking<Unit> {
        for (source in listOf("<caret>", "n<caret>op: nop", "b n<caret>op", "b .s<caret>et", ".eqv .s<caret>et 1",
            "emit .i<caret>nclude", "emit .end_ma<caret>cro", "# d<caret>iv", ".asciiz \"di<caret>v\"")) {
            assertNull(documentation(source), source)
        }
        assertNull(documentation("di<caret>v", "plain.txt"))
    }

    @Test
    fun `completion documentation uses the same catalog target as the editor`() = timeoutRunBlocking<Unit> {
        val direct = documentation("di<caret>v \$t0, \$t1")
        val item = withContext(Dispatchers.EDT) {
            fixture.configureByText("doc.asm", "di<caret>\n")
            fixture.completeBasic().orEmpty().single { it.lookupString == "div" }
        }
        val pointer = readAction {
            val provider = IdeDocumentationTargetProvider.getInstance(project.get())
            assertTrue(provider.documentationTargets(fixture.editor, fixture.file, LookupElementBuilder.create("div")).isEmpty())
            val target = provider.documentationTargets(fixture.editor, fixture.file, item).single()
            assertEquals("div", target.computePresentation().presentableText)
            target.createPointer()
        }
        assertEquals(direct, computeDocumentationBlocking(pointer)?.html)
    }

    private suspend fun documentation(source: String, filename: String = "doc.asm"): String? {
        withContext(Dispatchers.EDT) { fixture.configureByText(filename, source) }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val pointer = readAction {
            IdeDocumentationTargetProvider.getInstance(current)
                .documentationTargets(fixture.editor, fixture.file, fixture.editor.caretModel.offset)
                .firstOrNull()?.createPointer()
        } ?: return null
        return computeDocumentationBlocking(pointer)?.html
    }
}
