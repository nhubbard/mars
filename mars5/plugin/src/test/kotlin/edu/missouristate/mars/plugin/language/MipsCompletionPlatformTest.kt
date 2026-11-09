package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.assembler.Directives
import edu.missouristate.mars.mips.instructions.InstructionDefaults
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsCompletionPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `statement heads offer every catalog instruction and directive once`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val names = (InstructionDefaults.get().instructionList.map { it.name } + Directives.getDirectiveList().map { it.name }).toSet()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("completion.asm", "  <caret>")
            val matches = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it in names }
            assertEquals(names, matches.toSet())
            assertEquals(names.size, matches.size)
        }
    }

    @Test
    fun `catalog completion inserts a dotted mnemonic after a label and a directive inside a macro`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("completion.asm", "entry: C.<caret>eq.s \$f0, \$f2\n")
            val instruction = fixture.completeBasic().orEmpty().single { it.lookupString == "c.eq.s" }
            val example = InstructionDefaults.get().instructionList.first { it.name == "c.eq.s" }.exampleFormat
            val presentation = LookupElementPresentation().also(instruction::renderElement)
            assertEquals(example.removePrefix("c.eq.s"), presentation.tailText)
            fixture.lookup.setCurrentItem(instruction)
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals("entry: c.eq.s \$f0, \$f2\n", fixture.file.text)

            fixture.configureByText("completion.asm", ".macro emit\n  .A<caret>\n.end_macro\n")
            val directive = fixture.completeBasic().orEmpty().single { it.lookupString == ".asciiz" }
            fixture.lookup.setCurrentItem(directive)
            fixture.finishLookup(Lookup.NORMAL_SELECT_CHAR)
            assertEquals(".macro emit\n  .asciiz\n.end_macro\n", fixture.file.text)
        }
    }

    @Test
    fun `register completion follows the operand format and register bank`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fun registers(source: String): Set<String> {
                fixture.configureByText("registers.asm", "$source\n")
                return fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith('$') }.toSet()
            }

            val integer = registers("entry: ADD <caret>")
            assertEquals(64, integer.size)
            assertTrue(integer.containsAll(listOf("\$t0", "\$zero", "\$0", "\$31")))
            assertEquals(integer, registers("lw \$t0, 4(<caret>)"))
            assertEquals(integer, registers("lw \$t0,, -4(<caret>"))
            assertEquals(integer, registers("lw \$t0, (<caret>)"))
            assertEquals(integer, registers("lw \$t0, address+4(<caret>)"))
            assertEquals(integer, registers("div \$t0 \$t1 <caret>"))
            assertEquals(integer, registers("mtc0 <caret>, \$12"))

            val floating = (0..31).map { "\$f$it" }.toSet()
            assertEquals(floating, registers("add.s \$f0, <caret>"))
            assertEquals(floating, registers("mtc1 \$t0, <caret>"))

            val system = setOf("\$8", "\$12", "\$13", "\$14")
            assertEquals(system, registers("mfc0 \$t0, <caret>"))
            assertEquals(system, registers("mtc0 \$t0, <caret>"))

            for (source in listOf("lw \$t0, address <caret>", "sll \$t0, \$t1, <caret>", "nop <caret>", "emit <caret>")) {
                assertTrue(registers(source).isEmpty(), source)
            }
        }
    }

    @Test
    fun `register insertion replaces the complete token and preserves the address punctuation`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            fixture.configureByText("registers.asm", "lw \$t0, 4(\$t<caret>8)\n")
            val item = fixture.completeBasic().orEmpty().single { it.lookupString == "\$t2" }
            fixture.lookup.setCurrentItem(item)
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals("lw \$t0, 4(\$t2)\n", fixture.file.text)
        }
    }

    @Test
    fun `label completion follows current and included files and replaces a dotted target`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val included = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/inner.s", "Target.nested: nop\n.include \"outer.s\"\n")
            fixture.addFileToProject("unrelated.asm", ".globl Target.unrelated\nTarget.unrelated: nop\n")
            fixture.addFileToProject("parts/outer.s", "Target.included: nop\n.include \"inner.s\"\n")
        }
        withContext(Dispatchers.EDT) {
            fixture.configureByText("labels.asm", """
                .include "parts/outer.s"
                .include "missing.s"
                .eqv Target.constant 2
                .macro Target.macro
                  Target.private: nop
                .end_macro
                entry: beq ${'$'}t0, ${'$'}zero, Target.<caret>old
                Target.local: nop
                target.lowercase: nop
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(included.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(current) {
                document.setText("Target.updated: nop\n.include \"inner.s\"\n")
                PsiDocumentManager.getInstance(current).commitAllDocuments()
            }
            val matches = fixture.completeBasic().orEmpty().filter { it.lookupString.startsWith("Target.") }
            assertEquals(setOf("Target.local", "Target.nested", "Target.updated"), matches.map { it.lookupString }.toSet())
            assertTrue("target.lowercase" !in fixture.lookupElementStrings.orEmpty())
            fixture.lookup.setCurrentItem(matches.single { it.lookupString == "Target.updated" })
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertTrue(fixture.file.text.contains("entry: beq \$t0, \$zero, Target.updated\n"))
        }
    }

    @Test
    fun `labels inside macros stay in their physical macro scope`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macro-part.s", "injectedLabel: nop\n\$includedParameterLabel: nop\n")
            fixture.addFileToProject("other-part.s", "hiddenLabel: nop\n")
            fixture.configureByText("macro.asm", """
                outerLabel: nop
                ${'$'}gccLabel: nop
                .macro other
                  .include "other-part.s"
                  otherLabel: nop
                .end_macro
                .macro emit (${'$'}parameterLabel, ${'$'}includedParameterLabel)
                  .include "macro-part.s"
                  currentLabel: nop
                  ${'$'}parameterLabel: nop
                  j <caret>
                .end_macro
            """.trimIndent())
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.endsWith("Label") }
            assertEquals(setOf("outerLabel", "\$gccLabel", "currentLabel", "injectedLabel"), names.toSet())
        }
    }

    @Test
    fun `indexed label completion resumes when indexing finishes`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("targets.s", "targetOne: nop\ntargetTwo: nop\n")
            fixture.configureByText("indexing.asm", ".include \"targets.s\"\nj tar<caret>\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset - 1)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertTrue(analysis.labelCompletionNames(file, position, "tar").isEmpty())
            }
            assertEquals(setOf("targetOne", "targetTwo"), analysis.labelCompletionNames(file, position, "tar"))
        }
    }

    @Test
    fun `catalog heads do not complete in comments strings operands or declaration names`() = timeoutRunBlocking<Unit> {
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        withContext(Dispatchers.EDT) {
            val sources = listOf("# <caret>", ".asciiz \"<caret>\"", "b <caret>", "<caret>: nop",
                ".eqv <caret> 1", ".macro <caret>\n.end_macro")
            for (source in sources) {
                fixture.configureByText("completion.asm", "$source\n")
                val names = fixture.completeBasic().orEmpty().map { it.lookupString }
                assertTrue("addiu" !in names && ".asciiz" !in names, source)
            }
        }
    }
}
