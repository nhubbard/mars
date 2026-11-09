package edu.missouristate.mars.plugin.language

import com.intellij.lang.findUsages.LanguageFindUsages
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsFindUsagesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native usage search resolves nested includes externals and macro overloads`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "parts/outer.s"
            j .target.label
            addiu ${'$'}t0, ${'$'}zero, LIMIT
            emit()
            emit(${'$'}t0)
            la ${'$'}t1, ${'$'}buffer
            # j .target.label
            .data
            .asciiz ".target.label ${'$'}buffer"
        """.trimIndent()
        val definitions = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.s", ".include \"defs.s\"\n")
            val defs = fixture.addFileToProject("parts/defs.s", """
                .eqv LIMIT 7
                .extern ${'$'}buffer 4
                .target.label: nop
                .macro emit
                nop
                .end_macro
                .macro emit (%arg)
                move ${'$'}a0, %arg
                .end_macro
            """.trimIndent())
            fixture.addFileToProject("unrelated.s", ".target.label: nop\nj .target.label\n")
            fixture.configureFromExistingVirtualFile(fixture.addFileToProject("entry.asm", source).virtualFile)
            defs
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val targets = readAction { declarations(definitions).sortedBy { it.textOffset } }
        val names = listOf("LIMIT", "\$buffer", ".target.label", "emit", "emit")
        val types = listOf("constant", "external symbol", "label", "macro", "macro")
        readAction {
            assertEquals(names, targets.map { it.name })
            assertEquals(types, targets.map(LanguageFindUsages::getType))
            assertEquals(names, targets.map(LanguageFindUsages::getDescriptiveName))
            assertEquals(names, targets.map { LanguageFindUsages.getNodeText(it, false) })
            assertTrue(targets.all(LanguageFindUsages::canFindUsagesFor))
            assertNotNull(LanguageFindUsages.getHelpId(targets.first()))
        }
        val sites = listOf("LIMIT", "\$buffer", ".target.label", "emit()", "emit(\$t0)")
        for ((target, site) in targets.zip(sites)) {
            assertEquals(setOf("entry.asm" to source.indexOf(site)), usageLocations(target), site)
        }
    }

    @Test
    fun `native usage search finds labels consisting entirely of periods`() = timeoutRunBlocking<Unit> {
        val source = ".: nop\n..: nop\nj .\nj ..\n"
        val file = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("periods.s", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val (one, two) = readAction { declarations(file).sortedBy { it.textOffset } }
        assertEquals(setOf("periods.s" to source.indexOf("j .") + 2), usageLocations(one))
        assertEquals(setOf("periods.s" to source.indexOf("j ..") + 2), usageLocations(two))
    }

    @Test
    fun `native usage search distinguishes macro-local labels from a same-named outer label`() = timeoutRunBlocking<Unit> {
        val source = """
            target: nop
            .macro emit
            target: nop
            b target
            .end_macro
            emit
            j target
            .eqv
        """.trimIndent()
        val file = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("shadow.s", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val (outer, local) = readAction {
            val nodes = declarations(file)
            val unnamed = nodes.single { it.name == null }
            assertFalse(LanguageFindUsages.canFindUsagesFor(unnamed))
            assertEquals(".eqv", LanguageFindUsages.getDescriptiveName(unnamed))
            val instruction = file.findElementAt(source.indexOf("nop"))!!
            assertFalse(LanguageFindUsages.canFindUsagesFor(instruction))
            assertEquals("", LanguageFindUsages.getType(instruction))
            assertEquals("nop", LanguageFindUsages.getNodeText(instruction, true))
            nodes.filter { it.name == "target" }.sortedBy { it.textOffset }
        }
        assertEquals(setOf("shadow.s" to source.indexOf("j target") + 2), usageLocations(outer))
        assertEquals(setOf("shadow.s" to source.indexOf("b target") + 2), usageLocations(local))
    }

    private fun declarations(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)

    private suspend fun usageLocations(target: MipsDeclaration): Set<Pair<String, Int>> = readAction {
        fixture.findUsages(target).map { usage ->
            val reference = requireNotNull(usage.reference)
            reference.element.containingFile.name to (reference.element.textRange.startOffset + reference.rangeInElement.startOffset)
        }.toSet()
    }
}
