package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
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
        assertEquals(one, documentation("${include}entry: em<caret>it:"))
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

    @Test
    fun `direct documentation follows an overload redefined in a plain include`() = timeoutRunBlocking<Unit> {
        val source = ".macro emit (%written)\n.end_macro\n.include \"signature.txt\"\nemit(7)\nnop\n"
        val included = "# plain text\n.macro emit (%actual) # <from include>\n.end_macro\n"
        val current = project.get()
        val include = withContext(Dispatchers.EDT) {
            val include = fixture.addFileToProject("signature.txt", included)
            val file = fixture.addFileToProject("direct-included-doc.asm", source)
            fixture.configureFromExistingVirtualFile(file.virtualFile)
            fixture.editor.caretModel.moveToOffset(source.indexOf("emit(7)") + 1)
            include
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val provider = IdeDocumentationTargetProvider.getInstance(current)
        fun target() = provider.documentationTargets(fixture.editor, fixture.file, fixture.editor.caretModel.offset).single()
        val pointer = readAction {
            val target = target()
            assertEquals("emit(%actual)", target.computePresentation().presentableText)
            assertEquals("signature.txt:2", target.computePresentation().locationText)
            val destination = target.navigatable as OpenFileDescriptor
            assertEquals(include.virtualFile, destination.file)
            assertEquals(included.indexOf("emit"), destination.offset)
            target.createPointer()
        }
        assertTrue("&lt;from include&gt;" in computeDocumentationBlocking(pointer)?.html.orEmpty())
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(include.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) { document.setText(included.replace("%actual", "%updated")) }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            assertNull(pointer.dereference())
        }
        readAction {
            assertNull(pointer.dereference())
            assertEquals("emit(%updated)", target().computePresentation().presentableText)
        }
    }

    @Test
    fun `nested documentation shows each selected definition and restores its own source pointer`() = timeoutRunBlocking<Unit> {
        val source = """
            .macro outer
                inner(7)
            .end_macro
            .macro inner (%first) # First <definition>
            .end_macro
            outer
            .include "plain.inc"
            outer
            .macro inner (%last) # Last definition
            .end_macro
            outer
            .include "ignored.inc~"
            outer
            nop
        """.trimIndent()
        val include = ".macro inner (%middle) # Included & empty\n.end_macro\n"
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("plain.inc", include)
            fixture.tempDirFixture.createFile("ignored.inc~", ".macro inner (%ignored)\n.end_macro\n")
            val file = fixture.addFileToProject("nested-doc.asm", source)
            fixture.configureFromExistingVirtualFile(file.virtualFile)
            fixture.editor.caretModel.moveToOffset(source.indexOf("inner(7)") + 1)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val provider = IdeDocumentationTargetProvider.getInstance(current)
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        val pointers = readAction {
            val targets = provider.documentationTargets(fixture.editor, fixture.file, fixture.editor.caretModel.offset)
            assertEquals(listOf("inner(%first)", "inner(%last)", "inner(%middle)"), targets.map { it.computePresentation().presentableText })
            assertEquals(listOf("nested-doc.asm:4", "nested-doc.asm:9", "plain.inc:1"), targets.map { it.computePresentation().locationText })
            val offsets = targets.map { (it.navigatable as OpenFileDescriptor).offset }
            assertEquals(listOf(source.indexOf("inner (%first)"), source.indexOf("inner (%last)"), include.indexOf("inner")), offsets)
            targets.map { it.createPointer() }
        }
        assertTrue("First &lt;definition&gt;" in computeDocumentationBlocking(pointers[0])?.html.orEmpty())
        assertTrue("Last definition" in computeDocumentationBlocking(pointers[1])?.html.orEmpty())
        assertTrue("Included &amp; empty" in computeDocumentationBlocking(pointers[2])?.html.orEmpty())
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(current) {
                val document = fixture.editor.document
                val last = source.indexOf("%last")
                document.replaceString(last, last + "%last".length, "%changed")
                val first = source.indexOf("First <definition>")
                document.replaceString(first, first + "First <definition>".length, "Updated comment")
                document.insertString(source.indexOf(".macro inner (%first)"), "\n")
            }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
            assertNull(pointers[0].dereference())
        }
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        assertTrue("Updated comment" in computeDocumentationBlocking(pointers[0])?.html.orEmpty())
        assertNull(computeDocumentationBlocking(pointers[1]))
        assertTrue("Included &amp; empty" in computeDocumentationBlocking(pointers[2])?.html.orEmpty())
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
