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
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
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
    fun `extern names receive declaration highlighting across the whole written name`() = timeoutRunBlocking<Unit> {
        val names = listOf("\$t0", ".word", ".set", ")", "+9", "\"slot name\"", "'A'", ".include")
        val source = names.joinToString("\n", postfix = "\n") { ".extern $it, 4" }
        configure(source)
        withContext(Dispatchers.EDT) {
            val highlights = fixture.doHighlighting().filter { it.forcedTextAttributesKey == labelColor }
            assertEquals(names, highlights.map { source.substring(it.startOffset, it.endOffset) })
        }
    }

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
    fun `external operands bind macro formals and keep storage names out of the outline until expansion`(
        @TempDir directory: Path,
    ) = timeoutRunBlocking<Unit> {
        val source = """
            .eqv RESERVE .extern
            .eqv NAME ${'$'}slot
            .macro reserve (${'$'}slot, %other)
              .extern ${'$'}slot 4
              .extern %other 4
              .extern NAME 4
              RESERVE ${'$'}slot 4
            .end_macro
            reserve(payload, second)
            .extern ${'$'}standalone 4
            .extern %unaddressable 4
            .data
            .word payload, second
        """.trimIndent()
        val project = project.get()
        val entry = directory.resolve("extern.asm").also { it.writeText(source) }
        val context = project.service<MarsSessionService>().context
        context.assemble(listOf(entry), null, true, false)
        val memory = context.memory
        assertEquals(memory.externBaseAddress, context.symbolTable.getAddress("payload"))
        assertEquals(memory.externBaseAddress + 4, context.symbolTable.getAddress("second"))
        assertEquals(memory.externBaseAddress, memory.getWordNoNotify(memory.dataBaseAddress))
        assertEquals(memory.externBaseAddress + 4, memory.getWordNoNotify(memory.dataBaseAddress + 4))

        configure(source)
        val slot = source.indexOf(".extern \$slot") + ".extern ".length
        val other = source.indexOf(".extern %other") + ".extern ".length
        val alias = source.indexOf(".extern NAME") + ".extern ".length
        readAction {
            val file = fixture.file as MipsFile
            assertTrue(PsiTreeUtil.collectElementsOfType(file, PsiErrorElement::class.java).isEmpty())
            val expected = mapOf(slot to source.indexOf("(\$slot") + 1, other to source.indexOf("%other"), alias to source.indexOf("NAME"))
            for ((use, declaration) in expected) {
                assertEquals(declaration, file.findReferenceAt(use)?.resolve()?.textOffset)
            }
            val timeline = project.service<MarsAnalysisService>().editorTimeline(file)
            assertTrue(timeline.replacesExternal(file, slot))
            assertTrue(timeline.replacesExternal(file, other))
            assertTrue(timeline.replacesExternal(file, alias))
            assertTrue(timeline.expandedExternalsIn(file).isEmpty())
            assertEquals(setOf("\$standalone"), timeline.externalStorage.keys)
        }
        withContext(Dispatchers.EDT) {
            val highlights = fixture.doHighlighting()
            for ((offset, length) in mapOf(slot to 5, other to 6, alias to 4)) {
                assertEquals(listOf(macroParameterColor), highlights
                    .filter { it.startOffset == offset && it.endOffset == offset + length }
                    .mapNotNull { it.forcedTextAttributesKey })
            }
        }
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

    @Test
    fun `aliased external storage and exports match the assembler symbol table`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val aliases = """
            .eqv DEFINE .eqv
            DEFINE RESERVE .extern
            DEFINE BUNDLED .extern bundled 4
            DEFINE SIZE 4
            DEFINE PUBLISH .globl
            DEFINE PREFIX row:
        """.trimIndent() + "\n"
        val storage = "PREFIX RESERVE shared SIZE\nBUNDLED\n"
        val source = """
            .include "aliases.s"
            .include "storage.s"
            .extern shared 12
            .data
            .word shared, bundled
            .text
            PUBLISH target, other
            target: nop
            other: nop
        """.trimIndent() + "\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.s", aliases)
            fixture.addFileToProject("storage.s", storage)
        }
        assertEquals(Target("storage.s", 0), target(source.replace(".word shared", ".word sha<caret>red")))
        assertEquals(Target("storage.s", storage.indexOf("BUNDLED")), target(source.replace(", bundled", ", bun<caret>dled")))
        assertEquals(source.indexOf("target:"), target(source.replace("PUBLISH target", "PUBLISH tar<caret>get"))?.offset)
        assertEquals(source.indexOf("other:"), target(source.replace(", other", ", oth<caret>er"))?.offset)
        assertNull(target(source.replace("PUBLISH target", "PUBLISH sha<caret>red")))
        assertNull(target(source.replace("PUBLISH target", "PUBLISH (other), tar<caret>get")))
        val reordered = source.replace(
            ".include \"storage.s\"\n.extern shared 12", ".extern shared 12\n.include \"storage.s\"",
        )
        assertEquals(reordered.indexOf(".extern shared") + 8,
            target(reordered.replace(".word shared", ".word sha<caret>red"))?.offset)

        directory.resolve("aliases.s").writeText(aliases)
        directory.resolve("storage.s").writeText(storage)
        val entry = directory.resolve("entry.asm").also { it.writeText(source) }
        val context = project.get().service<MarsSessionService>().context
        context.assemble(listOf(entry), null, true, false)
        val memory = context.memory
        assertEquals(memory.externBaseAddress, context.symbolTable.getAddress("shared"))
        assertEquals(memory.externBaseAddress + 4, context.symbolTable.getAddress("bundled"))
        assertEquals(memory.externBaseAddress, memory.getWordNoNotify(memory.dataBaseAddress))
        assertEquals(memory.externBaseAddress + 4, memory.getWordNoNotify(memory.dataBaseAddress + 4))
        assertEquals(memory.textBaseAddress, context.symbolTable.getAddress("target"))
        assertEquals(memory.textBaseAddress + 4, context.symbolTable.getAddress("other"))
    }

    @Test
    fun `equivalence expansions expose external names with the assembler spelling`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val source = """
                .eqv PUNCT )
                .eqv CHARACTER 'A'
                .eqv QUOTED "slot name"
                .eqv RESERVE .extern
                .extern PUNCT 4
                RESERVE CHARACTER 4
                .extern QUOTED 4
                .extern ordinary 4
                .text
                nop
            """.trimIndent()
            val names = listOf(")", "65", "\"slot name\"", "ordinary")
            val entry = directory.resolve("expanded-extern.mars").also { it.writeText(source) }
            val context = project.get().service<MarsSessionService>().context
            context.assemble(listOf(entry), null, true, false)
            val base = context.memory.externBaseAddress
            assertEquals(names.indices.map { base + 4 * it }, names.map(context.symbolTable::getAddress))

            configure(source)
            readAction {
                val file = fixture.file as MipsFile
                val timeline = project.get().service<MarsAnalysisService>().editorTimeline(file)
                val expansions = timeline.expandedExternalsIn(file).values.toList()
                assertEquals(names.dropLast(1), expansions.map { it.name })
                assertEquals(listOf("PUNCT 4", "RESERVE CHARACTER", "QUOTED 4").map(source::indexOf),
                    expansions.map { it.offset })
                assertEquals(setOf("ordinary"), timeline.externalStorage.keys)
            }
        }

    @Test
    fun `completion includes only valid external aliases outside macro bodies`() = timeoutRunBlocking<Unit> {
        configure("""
            .eqv RESERVE .extern
            .eqv FIRST SECOND
            .eqv SECOND FIRST
            RESERVE sharedGood 4
            RESERVE sharedOther 0
            RESERVE sharedMissing
            RESERVE sharedNegative -4
            RESERVE sharedCyclic FIRST
            RESERVE ${'$'}t0 4
            .macro hidden
              RESERVE sharedHidden 4
            .end_macro
            la ${'$'}t0, shared<caret>
        """.trimIndent())
        withContext(Dispatchers.EDT) {
            val names = fixture.completeBasic().orEmpty().map { it.lookupString }.filter { it.startsWith("shared") }
            assertEquals(setOf("sharedGood", "sharedOther"), names.toSet())
        }
    }

    private data class Target(val file: String, val offset: Int)

    private suspend fun target(source: String): Target? {
        configure(source)
        return readAction {
            assertTrue(PsiTreeUtil.collectElementsOfType(fixture.file, PsiErrorElement::class.java).isEmpty(), source)
            val declaration = GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset)
                ?: return@readAction null
            if (declaration is MipsDeclaration && declaration.kind == SymbolKind.EXTERNAL) {
                assertSame(AllIcons.Nodes.Field, declaration.presentation.getIcon(false))
            }
            Target(declaration.containingFile.name, declaration.textOffset)
        }
    }

    private suspend fun configure(source: String) {
        withContext(Dispatchers.EDT) { fixture.configureByText("extern.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
    }
}
