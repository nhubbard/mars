package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.util.text.StringUtil
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
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

    @Test
    fun `operation aliases show the core documentation and keep declaration navigation`() = timeoutRunBlocking<Unit> {
        val definitions = ".eqv DEFINE .eqv\nDEFINE OP DIV \$t0,\$t1\nDEFINE STORE .WoRd\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("aliases.s", definitions) }
        val include = ".include \"aliases.s\"\n"
        val instruction = documentation("di<caret>v \$t0, \$t1").orEmpty()
        assertTrue("div" in instruction)
        assertEquals(instruction, documentation("${include}entry: O<caret>P"))
        readAction {
            val declaration = fixture.file.findReferenceAt(fixture.editor.caretModel.offset)?.resolve()
            assertEquals("aliases.s", declaration?.containingFile?.name)
            assertEquals("DEFINE", declaration?.text)
            assertEquals(definitions.indexOf("DEFINE OP"), declaration?.textOffset)
        }

        val directive = documentation(".wo<caret>rd 7").orEmpty()
        assertTrue(".word" in directive)
        assertEquals(directive, documentation("${include}STO<caret>RE 7"))
        val nop = documentation("n<caret>op")
        assertEquals(nop, documentation(".eqv OP nop\n${include}O<caret>P"))
        assertNull(documentation("O<caret>P\n$include"))
    }

    @Test
    fun `labels supplied by aliases leave documentation on the written operation`() = timeoutRunBlocking<Unit> {
        val definitions = ".eqv LABEL entry:\n.eqv OP NEXT\n.eqv NEXT nop\n.eqv BOTH row: nop\n"
        val nop = documentation("n<caret>op")
        assertTrue("nop" in nop.orEmpty())
        assertEquals(nop, documentation(definitions + "LABEL n<caret>op"))
        assertEquals(nop, documentation(definitions + "LABEL O<caret>P"))
        assertEquals(nop, documentation(".eqv SEP :\nentry, SEP, n<caret>op"))
        assertEquals(nop, documentation(".eqv SEP :\naddiu, SEP, n<caret>op"))
        assertNull(documentation(".eqv SEP :\nad<caret>diu, SEP, nop"))
        assertEquals(nop, documentation(definitions + "BO<caret>TH"))
        assertNull(documentation(definitions + "LA<caret>BEL nop"))
        assertNull(documentation(definitions + "LABEL nop O<caret>P"))
        assertEquals(documentation(".wo<caret>rd 7"), documentation(definitions + "LABEL .wo<caret>rd 7"))
    }

    @Test
    fun `alias documentation is limited to operation heads with a catalog operation`() = timeoutRunBlocking<Unit> {
        val sources = listOf(
            ".eqv OP nop\nO<caret>P: nop",
            ".eqv OP nop\nbeq \$t0, \$t1, O<caret>P",
            ".eqv OP nop\ncustom O<caret>P",
            ".eqv OP nop\nOP  <caret> 7",
            ".eqv OP custom\nO<caret>P",
            ".eqv OP 7\nO<caret>P",
            ".eqv OP row:\nO<caret>P",
            ".eqv OP NEXT\n.eqv NEXT OP\nO<caret>P",
            ".eqv OP .InClUdE\nO<caret>P \"aliases.s\"",
        )
        for (source in sources) assertNull(documentation(source), source)
    }

    @Test
    fun `macro documentation shows the selected overload and escapes the header comment`() = timeoutRunBlocking<Unit> {
        val definitions = """
            .eqv OPEN .macro
            .eqv TWO (%left, %right)
            .macro emit (%value) # Emit <one> & advance
            .end_macro
            OPEN emit TWO # Emit both
            .end_macro
            .eqv CALL row: emit
        """.trimIndent()
        withContext(Dispatchers.EDT) { fixture.addFileToProject("macros.s", definitions) }
        val include = ".include \"macros.s\"\n"
        val one = documentation("${include}em<caret>it(7)").orEmpty()
        assertTrue("emit(%value)" in one && "Emit &lt;one&gt; &amp; advance" in one, one)
        val two = documentation("${include}CA<caret>LL(7, 8)").orEmpty()
        assertTrue("emit(%left, %right)" in two && "Emit both" in two, two)
        readAction {
            val target = IdeDocumentationTargetProvider.getInstance(project.get())
                .documentationTargets(fixture.editor, fixture.file, fixture.editor.caretModel.offset).single()
            assertEquals("emit", target.computePresentation().presentableText)
            assertEquals("macros.s", target.computePresentation().locationText)
            val navigation = target.navigatable as OpenFileDescriptor
            assertEquals("macros.s", navigation.file.name)
            assertEquals(definitions.indexOf("emit TWO"), navigation.offset)
        }
        assertNull(documentation("${include}em<caret>it()"))
        assertNull(documentation("em<caret>it(7)\n$include"))
        assertNull(documentation("${include}.macro outer\nem<caret>it(7)\n.end_macro\n"))
        assertNull(documentation("${include}emit(CA<caret>LL)"))
    }

    @Test
    fun `macro documentation follows edits and disappears when the invocation stops binding`() = timeoutRunBlocking<Unit> {
        val source = ".macro emit (%first)\n.end_macro\nentry: em<caret>it(1)\n"
        assertTrue("emit(%first)" in documentation(source).orEmpty())
        val current = project.get()
        val pointer = readAction {
            IdeDocumentationTargetProvider.getInstance(current)
                .documentationTargets(fixture.editor, fixture.file, fixture.editor.caretModel.offset)
                .single().createPointer()
        }
        suspend fun edit(text: String, replacement: String) = withContext(Dispatchers.EDT) {
            val document = fixture.editor.document
            val start = document.text.indexOf(text)
            WriteCommandAction.runWriteCommandAction(current) {
                document.replaceString(start, start + text.length, replacement)
            }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
        }
        edit("%first", "%new")
        assertTrue("emit(%new)" in computeDocumentationBlocking(pointer)?.html.orEmpty())
        edit("(1)", "()")
        assertNull(computeDocumentationBlocking(pointer))
        edit("emit()", "")
        assertNull(computeDocumentationBlocking(pointer))
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
