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
