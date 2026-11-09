package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsLabelReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `go to declaration finds a nested include and skips an unrelated export`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.s", ".include \"inner.s\"\n")
            fixture.addFileToProject("parts/inner.s", "target.label: nop\n")
            fixture.addFileToProject("unrelated.s", ".globl target.label\ntarget.label: nop\n")
        }
        configure(".include \"parts/outer.s\"\nentry: beq \$t0, \$zero, target.<caret>label\n")
        readAction {
            val reference = referenceAtCaret() as PsiPolyVariantReference
            assertTrue(reference.isSoft)
            val declaration = reference.resolve() as MipsDeclaration
            assertEquals("target.label", declaration.name)
            assertEquals("inner.s", declaration.containingFile.name)
            assertEquals(0, declaration.textOffset)
            assertEquals(declaration, GotoDeclarationAction.findTargetElement(project.get(), fixture.editor, fixture.editor.caretModel.offset))
        }
    }

    @Test
    fun `macro local labels shadow outer labels including when introduced by an include`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("inner.s", "target: nop\n")
            fixture.addFileToProject("outer.s", ".include \"inner.s\"\n")
        }
        configure("target: nop\n.macro emit\n.include \"outer.s\"\nb tar<caret>get\n.end_macro\n")
        readAction { assertEquals("inner.s", referenceAtCaret()?.resolve()?.containingFile?.name) }
        configure("target: nop\n.macro emit\ntarget: nop\nb tar<caret>get\n.end_macro\n")
        readAction { assertEquals(fixture.file.text.lastIndexOf("target:"), referenceAtCaret()?.resolve()?.textOffset) }
        configure("target: nop\n.macro emit\ntarget: nop\n.end_macro\nb tar<caret>get\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
    }

    @Test
    fun `ambiguous labels remain separate targets and unmatched names remain unresolved`() = timeoutRunBlocking<Unit> {
        configure("same: nop\nb sa<caret>me\nsame: nop\n")
        readAction {
            val reference = referenceAtCaret() as PsiPolyVariantReference
            assertNull(reference.resolve())
            assertEquals(listOf(0, fixture.file.text.lastIndexOf("same:")), reference.multiResolve(false).map { it.element?.textOffset })
        }
        configure("target: nop\nb Tar<caret>get\n")
        readAction { assertNull(referenceAtCaret()?.resolve()) }
        configure("n<caret>op: nop\n")
        readAction { assertNull(referenceAtCaret()) }
    }

    @Test
    fun `label binding follows assembler operand formats`() = timeoutRunBlocking<Unit> {
        configure("nop: nop\nlw \$t0, n<caret>op+4(\$sp)\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
        for (source in listOf("lw \$t0, 4(\$s<caret>p)\n", "b invalid<caret>%name\n")) {
            configure(source)
            readAction { assertNull(referenceAtCaret(), source) }
        }
        for (source in listOf("sll \$t0, \$t1, shi<caret>ft\n", ".word shi<caret>ft\n")) {
            configure("shift: nop\n$source")
            readAction { assertNull((referenceAtCaret() as PsiPolyVariantReference).resolve(), source) }
        }
    }

    @Test
    fun `direct label resolution avoids active equivalence and macro parameter substitutions`() = timeoutRunBlocking<Unit> {
        configure("target: nop\n.eqv target somewhere\nb tar<caret>get\n")
        readAction { assertEquals(SymbolKind.CONSTANT, (referenceAtCaret()?.resolve() as MipsDeclaration).kind) }
        configure("target: nop\nb tar<caret>get\n.eqv target somewhere\n")
        readAction { assertEquals(0, referenceAtCaret()?.resolve()?.textOffset) }
        configure("\$target: nop\n.macro emit (\$target)\nb \$tar<caret>get\n.end_macro\n")
        readAction { assertNull((referenceAtCaret() as PsiPolyVariantReference).resolve()) }
    }

    private suspend fun configure(source: String) {
        withContext(Dispatchers.EDT) { fixture.configureByText("labels.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
    }

    private fun referenceAtCaret() = fixture.file.findReferenceAt(fixture.editor.caretModel.offset)
}
