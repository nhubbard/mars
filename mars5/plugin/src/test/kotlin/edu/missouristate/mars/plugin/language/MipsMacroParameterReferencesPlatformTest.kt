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
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@TestApplication
class MipsMacroParameterReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `macro parameters navigate to the exact header tokens`() = timeoutRunBlocking<Unit> {
        val header = $$".macro emit ((%base, $value, %?name, %base))\n"
        val cases = listOf(
            $$"lw $t0, 4(%ba<caret>se)" to "%base",
            $$"li $t0, $va<caret>lue" to $$"$value",
            "nested(%?<caret>name)" to "%?name",
            ".data %ba<caret>se" to "%base",
            "%ba<caret>se" to "%base",
            "%ba<caret>se: nop" to "%base",
            $$"$va<caret>lue: nop" to $$"$value",
        )
        for ((line, name) in cases) {
            configure("$header$line\n.end_macro\n")
            val hit = target()
            assertEquals(name, hit?.text, line)
            assertEquals(header.indexOf(name), hit?.offset, line)
        }
    }

    @Test
    fun `parameters before a malformed formal still resolve inside the macro`() = timeoutRunBlocking<Unit> {
        val header = ".macro emit (%value, 7, %later)\n"
        configure($$"$${header}li $t0, %val<caret>ue\n.end_macro\n")
        assertEquals(header.indexOf("%value"), target()?.offset)
        configure($$"$${header}li $t0, %la<caret>ter\n.end_macro\n")
        assertNull(target())
    }

    @Test
    fun `aliased headers keep formals accepted before a lexical error`() = timeoutRunBlocking<Unit> {
        val definitions = ".eqv FORM %value\n.eqv OPEN .macro\n.eqv COMPLETE entry: .macro emit (FORM\n"
        val headers = mapOf(
            ".macro emit (FORM, @, %later)" to "FORM",
            $$"OPEN emit (FORM, $bad!, %later)" to "FORM",
            "COMPLETE: @, %later)" to "COMPLETE",
        )
        for ((header, written) in headers) {
            val prefix = "$definitions$header\n"
            configure($$"$${prefix}li $t0, %val<caret>ue\n.end_macro\n")
            val hit = target()
            assertEquals(written, hit?.text, header)
            assertEquals(definitions.length + header.indexOf(written), hit?.offset, header)
            configure($$"$${prefix}li $t0, %la<caret>ter\n.end_macro\n")
            assertNull(target(), header)
        }

        configure(".eqv NAME generated\n.eqv STORAGE hidden: .extern region 4\nNAME: @\nSTORAGE: @\nn<caret>op\n")
        readAction {
            val file = fixture.file as MipsFile
            val timeline = project.get().service<MarsAnalysisService>().editorTimeline(file)
            assertTrue(timeline.generatedLabelsIn(file).isEmpty())
            assertTrue(timeline.expandedExternalsIn(file).isEmpty())
        }
    }

    @Test
    fun `a circular alias does not establish a partial macro scope`() = timeoutRunBlocking<Unit> {
        val definitions = ".eqv FIRST SECOND\n.eqv SECOND FIRST\n.eqv OPEN .macro\n" +
            ".eqv COMPLETE entry: .macro emit (%value, FIRST\n"
        for (header in listOf(".macro emit (%value, FIRST)", "OPEN emit (%value, FIRST)", "COMPLETE:)")) {
            configure($$"$$definitions$$header\nli $t0, %val<caret>ue\n.end_macro\n")
            assertNull(target(), header)
        }
    }

    @Test
    fun `included macro headers govern uses until their included closing boundary`() = timeoutRunBlocking<Unit> {
        val header = ".macro emit (%value)\n"
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/open.s", header)
            fixture.addFileToProject("parts/close.s", ".end_macro\n")
        }
        val open = ".include \"parts/open.s\"\n"
        val close = ".include \"parts/close.s\"\n"
        val use = $$"li $t0, %val<caret>ue\n"
        configure("$open$use$close")
        assertEquals(Target("open.s", "%value", header.indexOf("%value")), target())
        configure("$open%val<caret>ue: nop\n$close")
        assertEquals(Target("open.s", "%value", header.indexOf("%value")), target())
        configure("$open$close$use")
        assertNull(target())
        configure($$"$value: nop\n$${open}b $val<caret>ue\n$$close")
        assertNull(target())
        configure($$"$value: nop\nb $val<caret>ue\n")
        assertEquals(0, target()?.offset)
    }

    @Test
    fun `parameters navigate to written formals in a plain include`() = timeoutRunBlocking<Unit> {
        val header = "# written header\n.macro emit (%value)\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("header.txt", header) }
        configure($$".include \"header.txt\"\nli $t0, %val<caret>ue\n.end_macro\n")
        assertEquals(Target("header.txt", "%value", header.indexOf("%value")), target())
    }

    @Test
    fun `formals supplied by aliases navigate through their physical header and equivalences`() = timeoutRunBlocking<Unit> {
        val aliases = $$".eqv NAME emit\n.eqv FORMALS (%value)\n.eqv $SPIM $value\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("aliases.s", aliases) }
        val include = ".include \"aliases.s\"\n"
        val header = ".macro NAME FORMALS\n"
        configure($$"$$include$${header}li $t0, %val<caret>ue\n.end_macro\n")
        assertEquals(Target("macro-parameters.asm", "FORMALS", include.length + header.indexOf("FORMALS")), target())
        configure("$include.macro NAME FOR<caret>MALS\n.end_macro\n")
        assertEquals(Target("aliases.s", ".eqv FORMALS (%value)", aliases.indexOf("FORMALS")), target())
        configure("$include.macro NA<caret>ME FORMALS\n.end_macro\n")
        assertEquals(Target("aliases.s", ".eqv NAME emit", aliases.indexOf("NAME")), target())
        configure($$"$$include.macro emit $SP<caret>IM\n.end_macro\n")
        assertEquals(Target("aliases.s", $$".eqv $SPIM $value", aliases.indexOf($$"$SPIM")), target())
        configure($$"$$include.macro emit $SPIM\nli $t0, $val<caret>ue\n.end_macro\n")
        assertEquals($$"$SPIM", target()?.text)
    }

    @Test
    fun `parameter lookup observes case scope token roles and indexing`() = timeoutRunBlocking<Unit> {
        val header = ".macro emit (%Value)\n"
        for (line in listOf($$"li $t0, %val<caret>ue", $$"li $t0, %<caret>", ".asciiz \"%Val<caret>ue\"", "# %Val<caret>ue")) {
            configure("$header$line\n.end_macro\n")
            assertNull(target(), line)
        }
        configure(".macro emit (%Va<caret>lue)\n.end_macro\n")
        readAction { assertNull(referenceAtCaret()) }
        configure($$"$${header}li $t0, <caret>%\n.end_macro\n")
        readAction {
            val token = fixture.file.findElementAt(fixture.editor.caretModel.offset)!!
            assertEquals("%", token.text)
            assertNull(token.reference)
        }
        configure($$"$${header}li $t0, %Val<caret>ue\n.end_macro\n")
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(project.get()) { assertNull(referenceAtCaret()?.resolve()) }
        }
        assertEquals(header.indexOf("%Value"), target()?.offset)
    }

    @Test
    fun `parameters in an aliased macro body navigate to the exact written formal`() = timeoutRunBlocking<Unit> {
        val aliases = ".eqv START .macro\n.eqv STOP .end_macro\n.eqv FULL .macro emit (%value)\n"
        val header = "START emit (%value)\n"
        val use = $$"li $t0, %val<caret>ue\n"
        configure("$aliases$header$use" + "STOP\n")
        assertEquals(Target("macro-parameters.asm", "%value", aliases.length + header.indexOf("%value")), target())
        configure("${aliases}FULL\n${use}STOP\n")
        assertEquals("FULL", target()?.text)
        assertEquals(aliases.length, target()?.offset)
        configure("$aliases${header}STOP\n$use")
        assertNull(target())
        configure("$aliases${header.replace("%value", "%val<caret>ue")}STOP\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
    }

    @Test
    fun `parameters in an equivalence definition navigate and use the formal color`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val header = $$".macro emit ($name, $value)\n"
            val definition = $$".eqv $name $value"
            val body = $$"\nli $t0, $name\n.end_macro\nemit(CONSTANT, 42)\n.eqv $outside $unbound\n"
            val source = header + definition + body
            val entry = directory.resolve("macro-equivalence.asm").also { it.writeText(source) }
            val program = project.get().service<MarsSessionService>().context.assemble(listOf(entry), null, true, false)
            assertEquals(listOf(0x2408002a), program.machineList!!.map { it.binaryStatement })
            configure(source)
            for (formal in listOf($$"$name", $$"$value")) {
                withContext(Dispatchers.EDT) {
                    fixture.editor.caretModel.moveToOffset(header.length + definition.indexOf(formal) + 1)
                }
                assertEquals(Target("macro-parameters.asm", formal, header.indexOf(formal)), target())
            }
            withContext(Dispatchers.EDT) {
                val highlights = fixture.doHighlighting()
                val expectedColors = mapOf(
                    $$"$name" to macroParameterColor, $$"$value" to macroParameterColor,
                    $$"$outside" to equivalenceColor, $$"$unbound" to equivalenceReplacementColor,
                )
                for ((token, color) in expectedColors) {
                    val offset = if (token in definition) header.length + definition.indexOf(token) else source.indexOf(token)
                    assertEquals(
                        listOf(color),
                        highlights.filter { it.startOffset == offset && it.endOffset == offset + token.length }
                            .mapNotNull { it.forcedTextAttributesKey },
                    )
                }
            }
        }

    private data class Target(val file: String, val text: String, val offset: Int)

    private suspend fun configure(source: String) {
        withContext(Dispatchers.EDT) { fixture.configureByText("macro-parameters.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
    }

    private suspend fun target(): Target? = readAction {
        GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset)?.let {
            Target(it.containingFile.name, it.text, it.textOffset)
        }
    }

    private fun referenceAtCaret() = fixture.file.findReferenceAt(fixture.editor.caretModel.offset)
}
