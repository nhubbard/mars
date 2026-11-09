package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupElementPresentation
import com.intellij.openapi.application.EDT
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
