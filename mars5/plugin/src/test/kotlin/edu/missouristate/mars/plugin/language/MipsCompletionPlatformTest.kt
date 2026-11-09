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
