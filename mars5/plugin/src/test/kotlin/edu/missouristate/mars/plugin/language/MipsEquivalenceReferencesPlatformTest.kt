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
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsEquivalenceReferencesPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `go to declaration reaches the first definition in include expansion order`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("parts/outer.s", ".include \"inner.s\"\n.eqv COUNT 4\n")
            fixture.addFileToProject("parts/inner.s", ".eqv COUNT 4\n")
        }
        val include = ".include \"parts/outer.s\"\n"
        val definition = ".eqv COUNT 4\n"
        val use = "li \$t0, CO<caret>UNT\n"
        assertEquals("inner.s", target(".include \"missing.s\"\n$include$definition$use")?.file)
        assertTrue(target("$definition$include$use")?.inEntry == true)
        assertNull(target("$use$include"))
        assertNull(target("$definition${use.replace("CO<caret>UNT", "co<caret>unt")}"))
    }

    @Test
    fun `equivalence lookup terminates on include cycles and waits for the index`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("cycle.s", ".include \"cycle.s\"\n.eqv COUNT 4\n")
            fixture.configureByText("entry.asm", ".include \"cycle.s\"\nli \$t0, CO<caret>UNT\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        withContext(Dispatchers.EDT) {
            val file = fixture.file as MipsFile
            val position = file.findElementAt(fixture.editor.caretModel.offset)!!
            val analysis = current.service<MarsAnalysisService>()
            DumbModeTestUtils.runInDumbModeSynchronously(current) {
                assertNull(analysis.equivalenceTarget(file, position))
            }
            assertEquals("cycle.s", analysis.equivalenceTarget(file, position)?.containingFile?.name)
        }
    }

    @Test
    fun `equivalences bind in immediate segment macro argument and operation positions`() = timeoutRunBlocking<Unit> {
        for ((source, name) in listOf(
            ".eqv VALUE 4\nsll \$t0, \$t1, VA<caret>LUE\n" to "VALUE",
            ".eqv BASE 0x10010000\n.data BA<caret>SE\n" to "BASE",
            ".eqv EMIT nop\nEM<caret>IT\n" to "EMIT",
            ".eqv VALUE 4\nemit(VA<caret>LUE)\n" to "VALUE",
            ".eqv END )\nlw \$t0, 4(\$sp E<caret>ND\n" to "END",
            ".eqv VALUE 4\n.eqv ALIAS VALUE\nli \$t0, AL<caret>IAS\n" to "ALIAS",
            ".macro emit\n.eqv VALUE 4\n.end_macro\n.word VA<caret>LUE\n" to "VALUE",
        )) {
            val declaration = target(source)
            assertEquals(name, declaration?.name, source)
            assertEquals(SymbolKind.CONSTANT, declaration?.kind, source)
        }
        assertNull(target(".eqv VALUE 4\n.eqv ALIAS VA<caret>LUE\n"))
        assertNull(target(".eqv VALUE\nli \$t0, VA<caret>LUE\n"))
    }

    private data class Target(val file: String, val name: String?, val kind: SymbolKind, val inEntry: Boolean)

    private suspend fun target(source: String): Target? {
        withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        return readAction {
            val declaration = GotoDeclarationAction.findTargetElement(current, fixture.editor, fixture.editor.caretModel.offset)
                as? MipsDeclaration ?: return@readAction null
            Target(declaration.containingFile.name, declaration.name, declaration.kind, declaration.containingFile == fixture.file)
        }
    }
}
