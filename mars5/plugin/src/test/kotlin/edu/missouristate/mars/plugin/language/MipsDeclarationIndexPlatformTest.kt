package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.indexing.FileBasedIndex
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.index.MipsDeclarationExternalizer
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsDeclarationSite
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MipsDeclarationIndexPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native index records all declaration kinds duplicates and macro ownership`() = timeoutRunBlocking<Unit> {
        val source = """
            .EqV shared 7
            shared: nop
            shared: nop
            .MaCrO emit(%x)
              local: li ${'$'}v0, %x
              .eqv inside 2
            .EnD_MaCrO
            .data
            .private: .word 1
            ${'$'}gcc: .word 2
            .eqv
            # ignored: .macro ghost
            .asciiz "fake: .eqv ghost 4"
        """.trimIndent() + "\n"
        withContext(Dispatchers.EDT) { fixture.addFileToProject("declarations.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.projectScope(project.get())
            fun sites(name: String) = index.getValues(MipsDeclarationIndex.NAME, name, scope).flatten()
            assertEquals(
                listOf(
                    MipsDeclarationSite(SymbolKind.CONSTANT, source.indexOf("shared"), -1),
                    MipsDeclarationSite(SymbolKind.LABEL, source.indexOf("shared:"), -1),
                    MipsDeclarationSite(SymbolKind.LABEL, source.lastIndexOf("shared:"), -1),
                ), sites("shared"),
            )
            assertEquals(listOf(MipsDeclarationSite(SymbolKind.MACRO, source.indexOf("emit"), -1)), sites("emit"))
            val macro = source.indexOf(".MaCrO emit")
            assertEquals(listOf(MipsDeclarationSite(SymbolKind.LABEL, source.indexOf("local:"), macro)), sites("local"))
            assertEquals(listOf(MipsDeclarationSite(SymbolKind.CONSTANT, source.indexOf("inside"), macro)), sites("inside"))
            assertEquals(1, sites(".private").size)
            assertEquals(1, sites("\$gcc").size)
            for (absent in listOf("ghost", "fake", "ignored", "\$v0", "%x")) assertTrue(sites(absent).isEmpty(), absent)
        }
    }

    @Test
    fun `native index follows unsaved documents deletion and file type filtering`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("ignored.txt", "ignored: nop\n")
            fixture.addFileToProject("empty.asm", "# empty\n.eqv\n")
            fixture.addFileToProject("alternate.s", "alternate: nop\n")
            fixture.addFileToProject("changing.asm", "before: nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.projectScope(project.get())
            assertEquals(1, index.getContainingFiles(MipsDeclarationIndex.NAME, "before", scope).size)
            assertEquals(1, index.getContainingFiles(MipsDeclarationIndex.NAME, "alternate", scope).size)
            assertTrue(index.getContainingFiles(MipsDeclarationIndex.NAME, "ignored", scope).isEmpty())
            val extension = MipsDeclarationIndex()
            assertSame(MipsDeclarationIndex.NAME, extension.name)
            assertEquals(2, extension.version)
            assertTrue(extension.dependsOnFileContent())
            assertTrue(extension.inputFilter.acceptInput(file.virtualFile))
            assertTrue(extension.keyDescriptor.isEqual("key", "key"))
            assertSame(MipsDeclarationExternalizer, extension.valueExternalizer)
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(file.virtualFile)
            WriteCommandAction.runWriteCommandAction(project.get()) {
                fixture.editor.document.setText("after: nop\n")
            }
            PsiDocumentManager.getInstance(project.get()).commitAllDocuments()
        }
        readAction {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.projectScope(project.get())
            assertTrue(index.getContainingFiles(MipsDeclarationIndex.NAME, "before", scope).isEmpty())
            assertEquals(1, index.getContainingFiles(MipsDeclarationIndex.NAME, "after", scope).size)
        }
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project.get()) { file.virtualFile.delete(this) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            assertTrue(FileBasedIndex.getInstance().getContainingFiles(
                MipsDeclarationIndex.NAME, "after", GlobalSearchScope.projectScope(project.get()),
            ).isEmpty())
        }
    }

    @Test
    fun `native query scopes separate files with the same declaration name`() = timeoutRunBlocking<Unit> {
        val files = withContext(Dispatchers.EDT) {
            listOf(
                fixture.addFileToProject("first.asm", "main: nop\n").virtualFile,
                fixture.addFileToProject("second.asm", "main: syscall\n").virtualFile,
            )
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val index = FileBasedIndex.getInstance()
            assertEquals(files.toSet(), index.getContainingFiles(
                MipsDeclarationIndex.NAME, "main", GlobalSearchScope.projectScope(project.get()),
            ).toSet())
            for (file in files) {
                assertEquals(setOf(file), index.getContainingFiles(
                    MipsDeclarationIndex.NAME, "main", GlobalSearchScope.fileScope(project.get(), file),
                ).toSet())
            }
            assertTrue(index.getContainingFiles(MipsDeclarationIndex.NAME, "main", GlobalSearchScope.EMPTY_SCOPE).isEmpty())
        }
    }

    @Test
    fun `declaration values round trip all symbol kinds and empty contributions`() {
        for (sites in listOf(emptyList(), SymbolKind.entries.mapIndexed { i, kind -> MipsDeclarationSite(kind, i * 7, i - 1) })) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { MipsDeclarationExternalizer.save(it, sites) }
            val input = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
            assertEquals(sites, MipsDeclarationExternalizer.read(input))
            assertEquals(-1, input.read())
        }
    }
}
