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
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
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
        val source = "shared: nop\n.macro shared\n.end_macro\nignored: .include \"other.asm\"\n.eqv shared 3\n.eqv\n.eqv 7 8\n"
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
            assertEquals(listOf(SymbolKind.LABEL, SymbolKind.MACRO, SymbolKind.CONSTANT), firstItems.map { it.kind })
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
            assertNull(MipsDeclaration.findAt(first, source.indexOf(':'), "shared"))
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
            val namesBeforeStop = mutableListOf<String>()
            contributor.processNames({
                namesBeforeStop.add(it)
                false
            }, scope, null)
            assertEquals(1, namesBeforeStop.size)
        }
    }

    @Test
    fun `search resolves expanded labels in included files and removes rewritten declarations`() = timeoutRunBlocking<Unit> {
        val source = "OPEN: .ascii \"ignored\"\n.text\nORIGINAL: nop\nPREFIX nop\n"
        val (body, entry) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("other-symbol.asm", "generated: nop\n")
            val body = fixture.addFileToProject("symbol-body.s", source)
            val entry = fixture.addFileToProject("symbol-entry.asm", """
                .eqv OPEN .data
                .eqv ORIGINAL renamed
                .eqv PREFIX generated:
                .include "symbol-body.s"
            """.trimIndent())
            body to entry
        }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val bodyScope = GlobalSearchScope.fileScope(current, body.virtualFile)
        readAction {
            val names = mutableSetOf<String>()
            contributor.processNames(names::add, bodyScope, null)
            assertTrue(names.containsAll(setOf("renamed", "generated")))
            val allNames = mutableListOf<String>()
            contributor.processNames({
                allNames.add(it)
                true
            }, GlobalSearchScope.projectScope(current), null)
            assertEquals(1, allNames.count { it == "generated" })
            assertTrue(allNames.none { it.startsWith("#") })
            fun filteredNames(fileId: Int): Set<String> = buildSet {
                val filter = object : IdFilter() {
                    override fun containsFileId(id: Int) = id == fileId
                }
                contributor.processNames({
                    add(it)
                    true
                }, bodyScope, filter)
            }
            assertTrue("renamed" !in filteredNames(FileBasedIndex.getFileId(entry.virtualFile)))
            assertTrue("renamed" in filteredNames(FileBasedIndex.getFileId(body.virtualFile)))
            contributor.processNames({ error("Empty scope produced $it") }, GlobalSearchScope.EMPTY_SCOPE, null)
            assertTrue(items("OPEN", bodyScope).isEmpty())
            assertTrue(items("ORIGINAL", bodyScope).isEmpty())
            for ((name, spelling) in listOf("renamed" to "ORIGINAL:", "generated" to "PREFIX")) {
                val item = items(name, bodyScope).single()
                assertEquals(name, item.name)
                assertEquals("symbol-body.s", item.presentation?.locationString)
                assertTrue(item.canNavigateToSource())
                val descriptor = assertInstanceOf(OpenFileDescriptor::class.java, item)
                assertEquals(body.virtualFile, descriptor.file)
                assertEquals(source.indexOf(spelling), descriptor.offset)
                assertTrue(items(name, GlobalSearchScope.fileScope(current, entry.virtualFile)).isEmpty())
            }
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            WriteCommandAction.runWriteCommandAction(current) {
                fixture.editor.document.setText(".include \"symbol-body.s\"\n")
            }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
        }
        readAction {
            assertTrue(items("renamed", bodyScope).isEmpty())
            assertTrue(items("generated", bodyScope).isEmpty())
            assertEquals("ORIGINAL", items("ORIGINAL", bodyScope).single().name)
        }
    }

    @Test
    fun `search finds expanded macros and equivalences at their written origins`() = timeoutRunBlocking<Unit> {
        val source = """
            .macro WRITTEN
            .end_macro
            WHOLE
            .end_macro
            DEFINE RESULT 7
            .eqv PACK
            .macro literal
            .macro nested
            .end_macro
        """.trimIndent()
        val (body, entry) = withContext(Dispatchers.EDT) {
            val body = fixture.addFileToProject("expanded-symbols.s", source)
            val entry = fixture.addFileToProject("symbol-aliases.asm", """
                .eqv WRITTEN renamed_macro
                .eqv WHOLE .macro generated_macro
                .eqv DEFINE .eqv
                .eqv PACK VALUE 42
                .include "expanded-symbols.s"
            """.trimIndent())
            body to entry
        }
        val current = project.get()
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val scope = GlobalSearchScope.fileScope(current, body.virtualFile)
        readAction {
            val expected = mapOf("renamed_macro" to "WRITTEN", "generated_macro" to "WHOLE", "RESULT" to "DEFINE", "VALUE" to ".eqv PACK")
            val names = mutableSetOf<String>()
            contributor.processNames(names::add, scope, null)
            assertTrue(names.containsAll(expected.keys))
            assertTrue(items("WRITTEN", scope).isEmpty())
            assertEquals("literal", items("literal", scope).single().name)
            assertEquals("nested", items("nested", scope).single().name)
            for ((name, origin) in expected) {
                val item = assertInstanceOf(OpenFileDescriptor::class.java, items(name, scope).single())
                assertEquals(body.virtualFile, item.file)
                assertEquals(source.indexOf(origin), item.offset)
            }
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            WriteCommandAction.runWriteCommandAction(current) {
                fixture.editor.document.setText(fixture.editor.document.text.replace("renamed_macro", "updated_macro"))
            }
            PsiDocumentManager.getInstance(current).commitAllDocuments()
        }
        readAction {
            assertTrue(items("renamed_macro", scope).isEmpty())
            assertEquals("updated_macro", items("updated_macro", scope).single().name)
        }
    }

    @Test
    fun `symbol search uses the effective external reservation names`() = timeoutRunBlocking<Unit> {
        val current = project.get()
        val source = """
            .extern STORAGE SIZE
            RESERVE generated 4
            .extern literal 4
            .extern REGISTER 4
            SWITCH: .extern obsolete 4
            .macro reserve (${'$'}slot, ${'$'}broken)
              .extern ${'$'}slot 4
              .extern ${'$'}broken @
            .end_macro
        """.trimIndent()
        val body = withContext(Dispatchers.EDT) {
            val body = fixture.addFileToProject("external-symbols.s", source)
            fixture.addFileToProject("external-aliases.asm", """
                .eqv STORAGE renamed
                .eqv RESERVE .extern
                .eqv SIZE 4
                .eqv REGISTER ${'$'}t0
                .eqv SWITCH .data
                .include "external-symbols.s"
            """.trimIndent())
            body
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(current)
        val scope = GlobalSearchScope.fileScope(current, body.virtualFile)
        readAction {
            val names = mutableSetOf<String>()
            contributor.processNames(names::add, scope, null)
            assertTrue(names.containsAll(setOf("renamed", "generated", "literal")))
            for ((name, origin) in listOf("renamed" to "STORAGE", "generated" to "RESERVE")) {
                val result = assertInstanceOf(OpenFileDescriptor::class.java, items(name, scope).single())
                assertEquals(body.virtualFile, result.file)
                assertEquals(source.indexOf(origin), result.offset)
            }
            assertEquals(SymbolKind.EXTERNAL, (items("literal", scope).single() as MipsDeclaration).kind)
            for (missing in listOf("STORAGE", "REGISTER", "obsolete", "\$t0", "\$slot", "\$broken")) {
                assertTrue(items(missing, scope).isEmpty(), missing)
            }
        }
    }

    @Test
    fun `symbol search recovers a local alias in a rootless include cycle`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("cycle-b.s", ".include \"cycle-a.asm\"\n")
            fixture.addFileToProject("cycle-a.asm", ".eqv OLD recovered\n.include \"cycle-b.s\"\nOLD: nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        val scope = GlobalSearchScope.fileScope(project.get(), file.virtualFile)
        readAction {
            val results = mutableListOf<NavigationItem>()
            contributor.processElementsWithName("recovered", {
                results.add(it)
                false
            }, FindSymbolParameters.wrap("recovered", scope))
            assertEquals("recovered", results.single().name)
            assertTrue(items("OLD", scope).all { it is MipsDeclaration && it.kind == SymbolKind.CONSTANT })
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
