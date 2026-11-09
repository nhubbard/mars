package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

@TestApplication
class MipsMacroParameterReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `macro parameters navigate to the exact header tokens`() = timeoutRunBlocking<Unit> {
        val header = ".macro emit ((%base, \$value, %?name, %base))\n"
        val cases = listOf(
            "lw \$t0, 4(%ba<caret>se)" to "%base",
            "li \$t0, \$va<caret>lue" to "\$value",
            "nested(%?<caret>name)" to "%?name",
            ".data %ba<caret>se" to "%base",
            "%ba<caret>se" to "%base",
            "%ba<caret>se: nop" to "%base",
            "\$va<caret>lue: nop" to "\$value",
        )
        for ((line, name) in cases) {
            configure("$header$line\n.end_macro\n")
            val hit = target()
            assertEquals(name, hit?.text, line)
            assertEquals(header.indexOf(name), hit?.offset, line)
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
        val use = "li \$t0, %val<caret>ue\n"
        configure("$open$use$close")
        assertEquals(Target("open.s", "%value", header.indexOf("%value")), target())
        configure("$open%val<caret>ue: nop\n$close")
        assertEquals(Target("open.s", "%value", header.indexOf("%value")), target())
        configure("$open$close$use")
        assertNull(target())
        configure("\$value: nop\n${open}b \$val<caret>ue\n$close")
        assertNull(target())
        configure("\$value: nop\nb \$val<caret>ue\n")
        assertEquals(0, target()?.offset)
    }

    @Test
    fun `formals supplied by aliases navigate through their physical header and equivalences`() = timeoutRunBlocking<Unit> {
        val aliases = ".eqv NAME emit\n.eqv FORMALS (%value)\n.eqv \$SPIM \$value\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("aliases.s", aliases) }
        val include = ".include \"aliases.s\"\n"
        val header = ".macro NAME FORMALS\n"
        configure("$include${header}li \$t0, %val<caret>ue\n.end_macro\n")
        assertEquals(Target("macro-parameters.asm", "NAME FORMALS", include.length + header.indexOf("NAME")), target())
        configure("$include.macro NAME FOR<caret>MALS\n.end_macro\n")
        assertEquals(Target("aliases.s", ".eqv FORMALS (%value)", aliases.indexOf("FORMALS")), target())
        configure("$include.macro NA<caret>ME FORMALS\n.end_macro\n")
        assertEquals(Target("aliases.s", ".eqv NAME emit", aliases.indexOf("NAME")), target())
        configure("$include.macro emit \$SP<caret>IM\n.end_macro\n")
        assertEquals(Target("aliases.s", ".eqv \$SPIM \$value", aliases.indexOf("\$SPIM")), target())
        configure("$include.macro emit \$SPIM\nli \$t0, \$val<caret>ue\n.end_macro\n")
        assertEquals("emit \$SPIM", target()?.text)
    }

    @Test
    fun `parameter lookup observes case scope token roles and indexing`() = timeoutRunBlocking<Unit> {
        val header = ".macro emit (%Value)\n"
        for (line in listOf("li \$t0, %val<caret>ue", "li \$t0, %<caret>", ".asciiz \"%Val<caret>ue\"", "# %Val<caret>ue")) {
            configure("$header$line\n.end_macro\n")
            assertNull(target(), line)
        }
        configure(".macro emit (%Va<caret>lue)\n.end_macro\n")
        readAction { assertNull(referenceAtCaret()) }
        configure("${header}li \$t0, <caret>%\n.end_macro\n")
        readAction {
            val token = fixture.file.findElementAt(fixture.editor.caretModel.offset)!!
            assertEquals("%", token.text)
            assertNull(token.reference)
        }
        configure("${header}li \$t0, %Val<caret>ue\n.end_macro\n")
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(project.get()) { assertNull(referenceAtCaret()?.resolve()) }
        }
        assertEquals(header.indexOf("%Value"), target()?.offset)
    }

    @Test
    fun `parameters in an aliased macro body navigate to its physical opening token`() = timeoutRunBlocking<Unit> {
        val aliases = ".eqv START .macro\n.eqv STOP .end_macro\n"
        val header = "START emit (%value)\n"
        val use = "li \$t0, %val<caret>ue\n"
        configure("$aliases$header$use" + "STOP\n")
        assertEquals(Target("macro-parameters.asm", "START", aliases.length), target())
        configure("$aliases${header}STOP\n$use")
        assertNull(target())
        configure("$aliases${header.replace("%value", "%val<caret>ue")}STOP\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
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
