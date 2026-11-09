package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.service
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsExternalReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `first valid external storage wins in expanded include order`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val source = ".eqv DEFINE .eqv\nDEFINE SIZE 4\n.data\n.word sha<caret>red\n.include \"parts.s\"\n.extern shared 12\n.extern next 4\n.extern ) 4\n"
        val included = ".extern shared SIZE\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("parts.s", included) }
        assertEquals(Target("parts.s", 8), target(source))
        val entry = directory.resolve("entry.s").also { it.writeText(source.replace("<caret>", "")) }
        directory.resolve("parts.s").writeText(included)
        val context = project.get().service<MarsSessionService>().context
        context.assemble(listOf(entry), null, true, false)
        val base = context.memory.externBaseAddress
        assertEquals(base, context.symbolTable.getAddress("shared"))
        assertEquals(base + 4, context.symbolTable.getAddress("next"))
        assertEquals(base + 8, context.symbolTable.getAddress(")"))
        assertEquals(base, context.memory.getWordNoNotify(context.memory.dataBaseAddress))
    }

    @Test
    fun `local labels shadow external storage and exports require a local label`() = timeoutRunBlocking<Unit> {
        val source = ".extern shared 4\n.data\nshared: .word 0\n.text\nla \$t0, sha<caret>red\n"
        assertEquals(source.indexOf("shared:"), target(source)?.offset)
        assertNull(target(".extern shared 4\n.globl sha<caret>red\n"))
        assertEquals(8, target(".extern shared 4\n.text\nla \$t0, sha<caret>red\n")?.offset)
        assertNull(target(".extern shared 4\n.extern sha<caret>red 4\n"))
        assertEquals(8, target(".extern \$shared 4\nla \$t0, \$sha<caret>red\n")?.offset)
        assertEquals(8, target(".extern nop 4\nla \$t0, n<caret>op\n")?.offset)
        val renamed = ".eqv STORAGE shared\n.extern STORAGE 4\nla \$t0, sha<caret>red\n"
        assertEquals(renamed.lastIndexOf("STORAGE"), target(renamed)?.offset)
    }

    @Test
    fun `only active external storage participates in operand completion`() = timeoutRunBlocking<Unit> {
        configure("""
            .macro unused
              .extern sharedMacro 4
            .end_macro
            .extern sharedMissing
            .extern sharedNegative -4
            .extern sharedSigned 0xffffffff
            .extern sharedUnknown UNKNOWN
            .eqv
            .eqv MISSING
            .extern sharedMalformed MISSING
            .eqv FIRST SECOND
            .eqv SECOND FIRST
            .extern sharedCyclic FIRST
            .eqv SIZE 4
            .extern sharedFirst SIZE
            .extern sharedSecond SIZE
            .eqv STORAGE sharedAlias
            .extern STORAGE SIZE
            .eqv sharedWrong unrelated
            .extern sharedWrong 4
            .eqv sharedRegister ${'$'}t0
            .extern sharedRegister 4
            .extern separate 4
            la ${'$'}t0, shared<caret>
        """.trimIndent())
        withContext(Dispatchers.EDT) {
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith("shared") }
            assertEquals(setOf("sharedFirst", "sharedSecond", "sharedAlias"), names.toSet())
        }
    }

    private data class Target(val file: String, val offset: Int)

    private suspend fun target(source: String): Target? {
        configure(source)
        return readAction {
            assertTrue(PsiTreeUtil.collectElementsOfType(fixture.file, PsiErrorElement::class.java).isEmpty(), source)
            val declaration = GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset)
                as? MipsDeclaration ?: return@readAction null
            if (declaration.kind == SymbolKind.EXTERNAL) assertSame(AllIcons.Nodes.Field, declaration.presentation.getIcon(false))
            Target(declaration.containingFile.name, declaration.textOffset)
        }
    }

    private suspend fun configure(source: String) {
        withContext(Dispatchers.EDT) { fixture.configureByText("extern.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
    }
}
