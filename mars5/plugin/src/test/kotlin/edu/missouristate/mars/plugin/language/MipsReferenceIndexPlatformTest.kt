package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.indexing.FileBasedIndex
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsReferenceExternalizer
import edu.missouristate.mars.plugin.language.index.MipsReferenceIndex
import edu.missouristate.mars.plugin.language.index.MipsReferenceSite
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
class MipsReferenceIndexPlatformTest {
    private val project = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(project)

    @Test
    fun `native index records unresolved symbol kinds offsets and physical macro ownership`() = timeoutRunBlocking<Unit> {
        val source = """
            .eqv Alias .emit
            .eqv Literal NOP
            .macro .emit (%arg)
              local: lw ${'$'}t0,Shared+4(${'$'}sp)
              lw ${'$'}t0,0(${'$'}sp CLOSE
            .end_macro
            top: .emit(Shared,NOP)
            b NOP
            .globl Shared
            .word ${'$'}gcc
            .float NaN, Infinity
            li ${'$'}v0, invalid%name
            .asciiz "literal"
            # comment
            erased: .eqv BAD hidden .include "missing.s"
        """.trimIndent() + "\n"
        val file = withContext(Dispatchers.EDT) { fixture.addFileToProject("references.asm", source).virtualFile }
        IndexingTestUtil.suspendUntilIndexesAreReady(project.get())
        readAction {
            val index = FileBasedIndex.getInstance()
            val scope = GlobalSearchScope.fileScope(project.get(), file)
            fun sites(name: String) = index.getValues(MipsReferenceIndex.NAME, name, scope).flatten()
            val macro = source.indexOf(".macro")
            val operandKinds = listOf(SymbolKind.LABEL, SymbolKind.CONSTANT)
            assertEquals(
                operandKinds.map { MipsReferenceSite(it, source.indexOf("Shared+"), macro) } +
                    SymbolKind.entries.map { MipsReferenceSite(it, source.indexOf("Shared,"), -1) } +
                    operandKinds.map { MipsReferenceSite(it, source.lastIndexOf("Shared"), -1) },
                sites("Shared"),
            )
            assertEquals(
                operandKinds.map { MipsReferenceSite(it, source.indexOf("CLOSE"), macro) }, sites("CLOSE"),
            )
            assertEquals(
                listOf(source.indexOf("NOP"), source.indexOf("NOP)"), source.lastIndexOf("NOP"))
                    .map { MipsReferenceSite(SymbolKind.LABEL, it, -1) },
                sites("NOP"),
            )
            assertEquals(
                listOf(source.indexOf(".emit"), source.indexOf(".emit(")).flatMap { offset ->
                    SymbolKind.entries.map { MipsReferenceSite(it, offset, -1) }
                },
                sites(".emit"),
            )
            assertEquals(operandKinds.map { MipsReferenceSite(it, source.indexOf("\$gcc"), -1) }, sites("\$gcc"))
            for (name in listOf("Alias", "Literal", "local", "top", "shared", "%arg", "\$t0", "lw", "NaN", "Infinity",
                "invalid%name", "literal", "comment", "erased", "BAD", "hidden", "missing.s")) {
                assertTrue(sites(name).isEmpty(), name)
            }
            val extension = MipsReferenceIndex()
            assertSame(MipsReferenceIndex.NAME, extension.name)
            assertEquals(1, extension.version)
            assertTrue(extension.dependsOnFileContent())
            assertTrue(extension.inputFilter.acceptInput(file))
            assertTrue(extension.keyDescriptor.isEqual("Shared", "Shared"))
            assertSame(MipsReferenceExternalizer, extension.valueExternalizer)
        }
    }

    @Test
    fun `references survive declaration changes and follow unsaved edits and file deletion`() = timeoutRunBlocking<Unit> {
        val project = project.get()
        val use = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("ignored.txt", "b remote\n")
            fixture.addFileToProject("use.s", "b remote\n")
        }
        val index = FileBasedIndex.getInstance()
        val scope = GlobalSearchScope.projectScope(project)
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            assertEquals(setOf(use.virtualFile), index.getContainingFiles(MipsReferenceIndex.NAME, "remote", scope).toSet())
            assertTrue(index.getContainingFiles(MipsDeclarationIndex.NAME, "remote", scope).isEmpty())
        }
        val definition = withContext(Dispatchers.EDT) { fixture.addFileToProject("def.asm", "remote: nop\n") }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            assertEquals(setOf(definition.virtualFile), index.getContainingFiles(MipsDeclarationIndex.NAME, "remote", scope).toSet())
            assertEquals(setOf(use.virtualFile), index.getContainingFiles(MipsReferenceIndex.NAME, "remote", scope).toSet())
        }
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(use.virtualFile)
            val document = fixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.setText("b unsaved\n") }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        }
        readAction {
            assertTrue(index.getContainingFiles(MipsReferenceIndex.NAME, "remote", scope).isEmpty())
            assertEquals(setOf(use.virtualFile), index.getContainingFiles(MipsReferenceIndex.NAME, "unsaved", scope).toSet())
        }
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) { use.virtualFile.delete(this) }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            assertTrue(index.getContainingFiles(MipsReferenceIndex.NAME, "unsaved", scope).isEmpty())
        }
    }

    @Test
    fun `reference index values round trip without resolving their target`() {
        val values = SymbolKind.entries.mapIndexed { index, kind -> MipsReferenceSite(kind, 2 + index, -1) }
        for (sites in listOf(emptyList(), values)) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { MipsReferenceExternalizer.save(it, sites) }
            DataInputStream(ByteArrayInputStream(bytes.toByteArray())).use {
                assertEquals(sites, MipsReferenceExternalizer.read(it))
                assertEquals(-1, it.read())
            }
        }
    }
}
