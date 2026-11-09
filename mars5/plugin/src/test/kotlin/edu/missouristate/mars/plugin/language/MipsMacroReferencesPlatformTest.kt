package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
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
class MipsMacroReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `navigation selects the latest closed definition with the same case and argument count`() = timeoutRunBlocking<Unit> {
        val first = ".macro emit\nnop\n.end_macro\n"
        val second = ".macro emit (\$arg)\nnop\n.end_macro\n"
        val newer = ".macro emit ((%replacement))\nnop\n.end_macro\n"
        val definitions = first + second + newer
        assertEquals(definitions.lastIndexOf("emit"), target("${definitions}entry: em<caret>it(,,+,,) # one token\n")?.offset)
        assertEquals(first.indexOf("emit"), target("${definitions}em<caret>it()\n")?.offset)
        assertEquals(first.length + second.indexOf("emit"), target("$first${second}em<caret>it \$t0\n$newer")?.offset)
        assertNull(target("${definitions}Em<caret>it(1)\n"))
        assertNull(target("${definitions}em<caret>it(1,2,3)\n"))
        assertNull(target("em<caret>it\n$first"))
        assertNull(target(".macro emit\nem<caret>it\n"))
        assertEquals(first.length + second.indexOf("emit"), target("$first${second}em<caret>it(symbol)\n")?.offset)
        assertNull(target("$first$second.eqv ARGS 1,2\nem<caret>it(ARGS)\n"))
    }

    @Test
    fun `macro arity follows chained equivalences and substituted parentheses`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val one = ".macro emit (%x)\naddiu \$t0, \$zero, %x\n.end_macro\n"
        val two = ".macro emit (%x,%y)\naddiu \$t1, \$zero, %y\n.end_macro\n"
        val definitions = one + two
        val aliases = ".eqv ARGS PAIR\n.eqv PAIR 1,2 # comment\n.eqv OPEN (\n.eqv CLOSE )\n"
        val call = "em<caret>it(ARGS)\n"
        val twoArguments = "$definitions$aliases$call"
        val oneArgument = "$definitions${aliases}em<caret>it OPEN 1 CLOSE\n"
        assertEquals(definitions.lastIndexOf("emit"), target(twoArguments)?.offset)
        assertEquals(one.indexOf("emit"), target(oneArgument)?.offset)
        assertEquals(one.indexOf("emit"), target("$definitions$call$aliases")?.offset)
        assertNull(target("$definitions.eqv FIRST SECOND\n.eqv SECOND FIRST\nem<caret>it(FIRST)\n"))

        val context = project.get().service<MarsSessionService>().context
        for ((source, encoding) in listOf(twoArguments to 0x24090002, oneArgument to 0x24080001)) {
            val entry = directory.resolve("macro.asm").also { it.writeText(source.replace("<caret>", "")) }
            val program = context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(encoding), program.machineList.map { it.binaryStatement })
        }
    }

    @Test
    fun `macro arguments expand using definitions from earlier nested includes`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/arguments.s", ".eqv ARGS 1,,2\n")
            fixture.addFileToProject("parts/outer.s", ".include \"arguments.s\"\n")
        }
        val one = ".macro emit (%x)\n.end_macro\n"
        val two = ".macro emit (%x,%y)\n.end_macro\n"
        val include = ".include \"parts/outer.s\"\n"
        val source = "$include$one$two"
        assertEquals(source.lastIndexOf("emit"), target("${source}em<caret>it(ARGS)\n")?.offset)
        assertEquals(one.indexOf("emit"), target("$one${two}em<caret>it(ARGS)\n$include")?.offset)
    }

    @Test
    fun `macro calls use expanded headers and keep their physical targets`() = timeoutRunBlocking<Unit> {
        val aliases = ".eqv NAME emit\n.eqv FORMALS (%ignored,%value)\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.s", aliases)
        }
        val header = ".macro NAME FORMALS\n.end_macro\n"
        val include = ".include \"aliases.s\"\n"
        val call = "em<caret>it(0,7)\n"
        val hit = target("$include$header$call")
        assertEquals(SymbolKind.MACRO, hit?.kind)
        assertEquals(include.length + header.indexOf("NAME"), hit?.offset)
        assertNull(target("$include${header}em<caret>it(7)\n"))
        assertNull(target("$header$include$call"))
        assertNull(target(".eqv\n$header$call"))
        assertNull(target(".eqv NAME emit\n.eqv FORMALS LOOP\n.eqv LOOP FORMALS\n$header$call"))
        assertNull(target(".eqv NAME nop\n.eqv FORMALS (%a,%b)\n$header$call"))
    }

    @Test
    fun `macro boundaries follow nested include order across physical files`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/open.s", ".macro emit (%value)\nnop\n")
            fixture.addFileToProject("parts/close.s", ".end_macro\n")
            fixture.addFileToProject("parts/wrapper.s", ".include \"open.s\"\n.include \"close.s\"\n")
            fixture.addFileToProject("parts/cycle.s", ".include \"cycle.s\"\n")
        }
        val call = ".include \"parts/wrapper.s\"\nem<caret>it(1)\n"
        val expected = Target("open.s", 7, SymbolKind.MACRO)
        assertEquals(expected, target(call))
        assertEquals(expected, target(".include \"missing.s\"\n.include \"parts/cycle.s\"\n$call"))
        assertNull(target(".include \"parts/open.s\"\nem<caret>it(1)\n.include \"parts/close.s\"\n"))
        assertEquals(SymbolKind.CONSTANT, target(".eqv emit nop\nem<caret>it\n")?.kind)
    }

    @Test
    fun `aliased macro boundaries follow include order and navigate to their written opener`(@TempDir directory: Path) = timeoutRunBlocking<Unit> {
        val definitions = ".eqv START OPEN\n.eqv OPEN .MaCrO\n.eqv STOP .EnD_MaCrO\n"
        val header = "entry: START emit (%value)\n"
        val body = "addiu \$t0, \$zero, %value\n"
        val footer = "STOP # done\n"
        val include = ".include \"aliases.s\"\n.include \"open.s\"\n"
        val close = ".include \"close.s\"\n"
        val call = "em<caret>it(7)\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.s", definitions)
            fixture.addFileToProject("open.s", header + body)
            fixture.addFileToProject("close.s", footer)
        }
        assertEquals(Target("open.s", header.indexOf("START"), null), target("$include$close$call"))
        assertNull(target("$include$call$close"))
        assertEquals(SymbolKind.CONSTANT, target("${definitions}entry: ST<caret>ART emit (%value)\n")?.kind)
        assertEquals(SymbolKind.CONSTANT, target("$include ST<caret>OP\n")?.kind)

        for ((name, text) in mapOf("aliases.s" to definitions, "open.s" to header + body, "close.s" to footer)) {
            directory.resolve(name).writeText(text)
        }
        val entry = directory.resolve("entry.asm").also { it.writeText("$include$close${call.replace("<caret>", "")}") }
        val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
        assertEquals(listOf(0x24080007), program.machineList.map { it.binaryStatement })
    }

    @Test
    fun `expanded macro boundaries honor labels and reject malformed substitutions`() = timeoutRunBlocking<Unit> {
        val call = "em<caret>it(1)\n"
        val aliases = ".eqv START entry: .macro emit (%value)\n.eqv STOP .end_macro\n"
        val opening = aliases + "START\n"
        assertEquals(Target("macro-call.asm", aliases.length, null), target("${opening}STOP\n$call"))
        for (badClose in listOf("local: STOP", "STOP extra")) {
            assertNull(target("$opening$badClose\n$call"), badClose)
        }
        for (replacement in listOf("nop", "local:", ".macro", ".macro nop (%value)", ".macro emit (bare)", "LOOP")) {
            val badOpening = ".eqv LOOP START\n.eqv START $replacement\n"
            assertNull(target("$badOpening${aliases.substringAfter('\n')}START\nSTOP\n$call"), replacement)
        }
    }

    @Test
    fun `generated equivalences bind both macro headers and call arity`() = timeoutRunBlocking<Unit> {
        val declarations = ".eqv DEFINE .eqv\nDEFINE OPEN .macro\nDEFINE ARGS 1,2\n"
        val first = ".macro emit (%x)\n.end_macro\n"
        val second = "OPEN emit (%x,%y)\n.end_macro\n"
        val source = "$declarations$first$second"
        assertEquals(Target("macro-call.asm", declarations.length + first.length, null), target("${source}em<caret>it(ARGS)\n"))
        assertEquals(SymbolKind.MACRO, target("${source}em<caret>it(1)\n")?.kind)
    }

    @Test
    fun `macro boundary facts stay tolerant around malformed headers and closing lines`() = timeoutRunBlocking<Unit> {
        val malformed = ".end_macro\n.asciiz \"data\"\n.macro nop\n.end_macro\n.macro invalid (ordinary)\n.end_macro\n"
        assertNull(target("$malformed.macro emit\n.end_macro extra\nem<caret>it\n"))
        val nested = ".macro emit\n.macro other\n.end_macro\n"
        assertEquals(nested.indexOf("emit"), target("${nested}em<caret>it\n")?.offset)
    }

    @Test
    fun `macro binding follows unsaved edits to the included header`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val included = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macros.s", ".macro emit (%x)\n.end_macro\n").virtualFile
        }
        assertEquals("macros.s", target(".include \"macros.s\"\nem<caret>it(1)\n")?.file)
        withContext(Dispatchers.EDT) {
            val document = FileDocumentManager.getInstance().getDocument(included)!!
            WriteCommandAction.runWriteCommandAction(current) {
                document.setText(".macro emit (%x,%y)\n.end_macro\n")
                PsiDocumentManager.getInstance(current).commitAllDocuments()
            }
        }
        readAction { assertNull(fixture.file.findReferenceAt(fixture.editor.caretModel.offset)?.resolve()) }
    }

    @Test
    fun `macro lookup yields during indexing`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.configureByText("macro-call.asm", ".macro emit\n.end_macro\nem<caret>it\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val use = file.findElementAt(fixture.editor.caretModel.offset)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) { assertNull(analysis.macroTarget(file, use)) }
            assertEquals(7, analysis.macroTarget(file, use)?.textOffset)
        }
    }

    @Test
    fun `a candidate that expands to a label alone has no macro operation`() = timeoutRunBlocking<Unit> {
        assertEquals(SymbolKind.CONSTANT, target(".eqv PREFIX row:\nPRE<caret>FIX\n")?.kind)
        readAction {
            val file = fixture.file as MipsFile
            val use = file.findElementAt(fixture.editor.caretModel.offset)!!
            assertNull(project.get().service<MarsAnalysisService>().macroTarget(file, use))
        }
    }

    private data class Target(val file: String, val offset: Int, val kind: SymbolKind?)

    private suspend fun target(source: String): Target? {
        withContext(Dispatchers.EDT) { fixture.configureByText("macro-call.asm", source) }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        return readAction {
            val declaration = GotoDeclarationAction.findTargetElement(current, fixture.editor, fixture.editor.caretModel.offset)
                ?: return@readAction null
            Target(declaration.containingFile.name, declaration.textOffset, (declaration as? MipsDeclaration)?.kind)
        }
    }
}
