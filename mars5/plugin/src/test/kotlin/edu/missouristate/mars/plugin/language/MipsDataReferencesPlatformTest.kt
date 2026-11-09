package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsDataReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `integer directives and export lists resolve forward labels`() = timeoutRunBlocking<Unit> {
        for (use in listOf(".word 1, tar<caret>get", ".half tar<caret>get", ".byte tar<caret>get", ".GlObL other,,tar<caret>get")) {
            assertEquals(SymbolKind.LABEL, target(".data\n$use\nother: .word 0\ntarget: .word 1\n"), use)
        }
        assertEquals(SymbolKind.CONSTANT, target(".data\ntarget: .word 1\n.eqv target 4\n.word tar<caret>get\n"))
        for (use in listOf(".float tar<caret>get", ".space tar<caret>get", ".extern tar<caret>get 4", ".word tar<caret>get+4",
            ".word tar<caret>get : 2", ".word 2 : tar<caret>get", ".word (tar<caret>get)")) {
            assertNull(target(".data\n$use\ntarget: .word 1\n"), use)
        }
        assertNull(target(".data\n.word n<caret>op\nnop: .word 1\n"))
    }

    @Test
    fun `continued lists follow the data state across include and macro boundaries`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/setup.s", ".include \"mode.s\"\n")
            fixture.addFileToProject("parts/mode.s", ".kdata\n.half 1\n")
            fixture.addFileToProject("parts/cycle.s", ".include \"cycle.s\"\n")
        }
        val setup = ".include \"parts/setup.s\"\n"
        val suffix = "\nother: .word 0\ntarget: .word 1\n"
        for (use in listOf("tar<caret>get", "2, tar<caret>get", "other, 2, tar<caret>get", ".align 2\ntar<caret>get",
            ".macro defer\n.float 0\n.end_macro\ntar<caret>get")) {
            assertEquals(SymbolKind.LABEL, target("$setup$use$suffix"), use)
        }
        assertEquals(SymbolKind.LABEL, target(".include \"parts/cycle.s\"\n.include \"missing.s\"\n.data\n2, tar<caret>get$suffix"))
        for (change in listOf(".text", ".ktext", ".float 0", ".asciiz \"\"", ".macro active")) {
            assertNull(target("$setup$change\n2, tar<caret>get$suffix"), change)
        }
    }

    @Test
    fun `macro calls take priority over continuations and invalid punctuation stays out of label binding`() = timeoutRunBlocking<Unit> {
        val prefix = ".macro emit (%x)\n.end_macro\n.data\n.word 0\n"
        val suffix = "\ntarget: .word 1\n"
        assertEquals(SymbolKind.MACRO, target("${prefix}em<caret>it target$suffix"))
        assertNull(target("${prefix}emit tar<caret>get$suffix"))
        assertNull(target("${prefix}unknown(tar<caret>get)$suffix"))
        assertNull(target("${prefix}.unknown tar<caret>get$suffix"))
        assertEquals(SymbolKind.LABEL, target(".data\n.word 0\nmissing, tar<caret>get$suffix"))
    }

    @Test
    fun `the assembler stores the address identified by navigation`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = ".data\n.word 0\n.align 2\ntar<caret>get\n2, target\n.text\n.globl target\ntarget: nop\n"
        assertEquals(SymbolKind.LABEL, target(source))
        val context = project.get().service<MarsSessionService>().context
        val entry = directory.resolve("data.asm").also { it.writeText(source.replace("<caret>", "")) }
        context.assemble(listOf(entry), null, true, false)
        val memory = context.memory
        val address = context.symbolTable.getAddress("target")
        assertEquals(memory.textBaseAddress, address)
        assertEquals(address, memory.getWordNoNotify(memory.dataBaseAddress + 4))
        assertEquals(2, memory.getWordNoNotify(memory.dataBaseAddress + 8))
        assertEquals(address, memory.getWordNoNotify(memory.dataBaseAddress + 12))

        withContext(Dispatchers.EDT) {
            val current = project.get()
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertEquals(false, analysis.hasIntegerDataList(file, position))
            }
        }
    }

    private suspend fun target(source: String): SymbolKind? {
        withContext(Dispatchers.EDT) { fixture.configureByText("data-labels.asm", source) }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        return readAction {
            (GotoDeclarationAction.findTargetElement(current, fixture.editor, fixture.editor.caretModel.offset) as? MipsDeclaration)?.kind
        }
    }
}
