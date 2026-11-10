package edu.missouristate.mars.plugin.language

import com.intellij.ide.util.PsiNavigationSupport
import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.IncorrectOperationException
import com.intellij.util.indexing.FindSymbolParameters
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsGoToSymbolPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)
    private val contributor
        get() = ChooseByNameContributor.SYMBOL_EP_NAME.extensionList.filterIsInstance<MipsGoToSymbolContributor>().single()

    @Test
    fun `registered symbol search opens exact identifiers and retains all matching kinds`() = timeoutRunBlocking<Unit> {
        val source = ".eqv shared 3\nshared: nop\n.macro shared\n.end_macro\n.eqv\n.eqv 7 8\nignored: .include \"other.asm\"\n"
        val (first, second) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("definitions.asm", source) to
                fixture.addFileToProject("other.s", "shared: syscall\nMAIN: nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val scope = GlobalSearchScope.projectScope(project.get())
            val names = mutableSetOf<String>()
            contributor.processNames(names::add, scope, null)
            assertTrue(names.containsAll(setOf("shared", "MAIN")))

            val firstScope = GlobalSearchScope.fileScope(project.get(), first.virtualFile)
            val firstItems = items("shared", firstScope).map { assertInstanceOf(MipsDeclaration::class.java, it) }
            assertEquals(listOf(SymbolKind.CONSTANT, SymbolKind.LABEL, SymbolKind.MACRO), firstItems.map { it.kind })
            assertEquals(4, items("shared", scope).size)
            assertEquals("MAIN", items("MAIN", GlobalSearchScope.fileScope(project.get(), second.virtualFile)).single().name)
            assertTrue(items("SHARED", scope).isEmpty())
            assertTrue(items("ignored", scope).isEmpty())

            for (item in firstItems) {
                val presentation = item.presentation
                assertEquals("shared", presentation.presentableText)
                assertEquals("definitions.asm", presentation.locationString)
                assertNotNull(presentation.getIcon(false))
                assertTrue(item.canNavigateToSource())
                val descriptor = assertInstanceOf(
                    OpenFileDescriptor::class.java, PsiNavigationSupport.getInstance().getDescriptor(item),
                )
                assertEquals(first.virtualFile, descriptor.file)
                assertEquals(item.nameIdentifier!!.textRange.startOffset, descriptor.offset)
                assertEquals("shared", source.substring(descriptor.offset, descriptor.offset + "shared".length))
            }

            val incomplete = PsiTreeUtil.findChildrenOfType(first, MipsDeclaration::class.java).last()
            assertNull(incomplete.name)
            assertNull(incomplete.nameIdentifier)
            assertEquals(source.indexOf(".eqv 7 8"), incomplete.textOffset)
            val shortDirective = first.findElementAt(source.indexOf(".eqv\n"))!!
            assertEquals(MipsTypes.psi("DIRECTIVE_STATEMENT"), shortDirective.parent.node.elementType)
            assertThrows(IncorrectOperationException::class.java) { firstItems.first().setName("different") }
            assertNull(MipsDeclaration.findAt(first, 0, "shared"))
            assertNull(MipsDeclaration.findAt(first, 5, "stale"))
            assertNull(MipsDeclaration.findAt(first, source.length, "shared"))
            assertNull(MipsDeclaration.findAt(first, source.indexOf("nop"), "shared"))
            assertNull(MipsDeclaration.findAt(null, 5, "shared"))

            val stopped = mutableListOf<NavigationItem>()
            contributor.processElementsWithName("shared", {
                stopped.add(it)
                false
            }, FindSymbolParameters.wrap("shared", scope))
            assertEquals(1, stopped.size)
        }
    }

    @Test
    fun `search uses edited identifiers and removes deleted files`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) { fixture.addFileToProject("changing.asm", "before: nop\n") }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val scope = GlobalSearchScope.projectScope(project.get())
        readAction { assertEquals("before", items("before", scope).single().name) }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(file.virtualFile)
            WriteCommandAction.runWriteCommandAction(project.get()) { fixture.editor.document.setText("  after: nop\n") }
            PsiDocumentManager.getInstance(project.get()).commitAllDocuments()
        }
        readAction {
            assertTrue(items("before", scope).isEmpty())
            val item = assertInstanceOf(MipsDeclaration::class.java, items("after", scope).single())
            assertEquals(2, item.textOffset)
        }
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project.get()) { file.virtualFile.delete(this) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction { assertTrue(items("after", scope).isEmpty()) }
    }

    private fun items(name: String, scope: GlobalSearchScope): List<NavigationItem> = buildList {
        contributor.processElementsWithName(name, ::add, FindSymbolParameters.wrap(name, scope))
    }
}
