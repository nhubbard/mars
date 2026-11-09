package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.util.indexing.FileBasedIndex
import edu.missouristate.mars.plugin.analysis.IndexedSymbolCandidates
import edu.missouristate.mars.plugin.analysis.IndexedSymbolSite
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SourceId
import edu.missouristate.mars.plugin.analysis.SymbolKey
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.language.index.MipsDeclarationIndex
import edu.missouristate.mars.plugin.language.index.MipsReferenceExternalizer
import edu.missouristate.mars.plugin.language.index.MipsReferenceIndex
import edu.missouristate.mars.plugin.language.index.MipsReferenceSite
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            ,,.macro,, .emit (%arg FORMALS, 7)
              .macro rejected
              .end_macro stray
              local: lw,, ${'$'}t0,,,Shared+4(${'$'}sp)
              lw ${'$'}t0,0(${'$'}sp CLOSE
            .end_macro
            .macro nop
            .end_macro
            ,,top,, :,, .emit(,,Shared,,,NOP)
            b NOP
            .globl Shared
            .word ${'$'}gcc, .1_private
            .float NaN, Infinity
            .set noat ), :
            .data DATA_ADDRESS trailing
            .text ${'$'}textAddress unused
            .kdata 0x90000020 ignored
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
            val operandKinds = listOf(SymbolKind.LABEL, SymbolKind.EXTERNAL, SymbolKind.CONSTANT)
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
                    .flatMap { offset -> listOf(SymbolKind.LABEL, SymbolKind.EXTERNAL).map { MipsReferenceSite(it, offset, -1) } },
                sites("NOP"),
            )
            assertEquals(
                SymbolKind.entries.map { MipsReferenceSite(it, source.indexOf(".emit"), -1) } +
                    MipsReferenceSite(SymbolKind.CONSTANT, source.indexOf(".emit ("), macro) +
                    SymbolKind.entries.map { MipsReferenceSite(it, source.indexOf(".emit("), -1) },
                sites(".emit"),
            )
            assertEquals(listOf(MipsReferenceSite(SymbolKind.CONSTANT, source.indexOf("FORMALS"), macro)), sites("FORMALS"))
            assertEquals(listOf(MipsReferenceSite(SymbolKind.CONSTANT, source.indexOf("rejected"), macro)), sites("rejected"))
            assertEquals(operandKinds.map { MipsReferenceSite(it, source.indexOf("\$gcc"), -1) }, sites("\$gcc"))
            assertEquals(operandKinds.map { MipsReferenceSite(it, source.indexOf(".1_private"), -1) }, sites(".1_private"))
            for (name in listOf("DATA_ADDRESS", "\$textAddress")) {
                assertEquals(listOf(MipsReferenceSite(SymbolKind.CONSTANT, source.indexOf(name), -1)), sites(name))
            }
            for (name in listOf("Alias", "Literal", "local", "top", "shared", "%arg", "\$t0", "lw", "NaN", "Infinity",
                "trailing", "unused", "ignored", "stray",
                "noat", "invalid%name", "literal", "comment", "erased", "BAD", "hidden", "missing.s", "nop")) {
                assertTrue(sites(name).isEmpty(), name)
            }
            val extension = MipsReferenceIndex()
            assertSame(MipsReferenceIndex.NAME, extension.name)
            assertEquals(16, extension.version)
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
        val editedSource = ",,b,,,unsaved,,\n"
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(use.virtualFile)
            val document = fixture.editor.document
            WriteCommandAction.runWriteCommandAction(project) { document.setText(editedSource) }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
        }
        readAction {
            assertTrue(index.getContainingFiles(MipsReferenceIndex.NAME, "remote", scope).isEmpty())
            assertEquals(setOf(use.virtualFile), index.getContainingFiles(MipsReferenceIndex.NAME, "unsaved", scope).toSet())
        }
        val edited = project.service<MarsAnalysisService>().lookupCandidates(SymbolKey("unsaved", SymbolKind.LABEL), scope)
        assertEquals(
            IndexedSymbolCandidates(emptyList(), listOf(IndexedSymbolSite(SourceId(use.virtualFile.url), editedSource.indexOf("unsaved"), -1))),
            edited,
        )
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

    @Test
    fun `project service joins exact names and kinds across the supplied native scope`() = timeoutRunBlocking<Unit> {
        val project = project.get()
        val (label, macro, constant, branch, call) = withContext(Dispatchers.EDT) {
            listOf(
                fixture.addFileToProject("label.asm", "shared: nop\n").virtualFile,
                fixture.addFileToProject("macro.s", ".macro shared (%x)\n.end_macro\n").virtualFile,
                fixture.addFileToProject("constant.asm", ".eqv shared 7\n").virtualFile,
                fixture.addFileToProject("branch.s", "b shared\n").virtualFile,
                fixture.addFileToProject("call.asm", "shared(7)\n").virtualFile,
            )
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val service = project.service<MarsAnalysisService>()
        val before = service.snapshot
        val scope = GlobalSearchScope.projectScope(project)
        val expectedBranch = IndexedSymbolSite(SourceId(branch.url), 2, -1)
        val expectedCall = IndexedSymbolSite(SourceId(call.url), 0, -1)
        val expectedLabel = IndexedSymbolSite(SourceId(label.url), 0, -1)

        val labels = service.lookupCandidates(SymbolKey("shared", SymbolKind.LABEL), scope)
        assertEquals(listOf(expectedLabel), labels.declarations)
        assertEquals(setOf(expectedBranch, expectedCall), labels.references.toSet())
        val macros = service.lookupCandidates(SymbolKey("shared", SymbolKind.MACRO), scope)
        assertEquals(listOf(IndexedSymbolSite(SourceId(macro.url), 7, -1)), macros.declarations)
        assertEquals(listOf(expectedCall), macros.references)
        val constants = service.lookupCandidates(SymbolKey("shared", SymbolKind.CONSTANT), scope)
        assertEquals(listOf(IndexedSymbolSite(SourceId(constant.url), 5, -1)), constants.declarations)
        assertEquals(setOf(expectedBranch, expectedCall, IndexedSymbolSite(SourceId(macro.url), 7, 0)), constants.references.toSet())

        val narrow = GlobalSearchScope.filesScope(project, listOf(label, call))
        assertEquals(
            IndexedSymbolCandidates(listOf(expectedLabel), listOf(expectedCall)),
            service.lookupCandidates(SymbolKey("shared", SymbolKind.LABEL), narrow),
        )
        val empty = IndexedSymbolCandidates(emptyList(), emptyList())
        assertEquals(empty, service.lookupCandidates(SymbolKey("Shared", SymbolKind.LABEL), scope))
        assertEquals(empty, service.lookupCandidates(SymbolKey("shared", SymbolKind.LABEL), GlobalSearchScope.EMPTY_SCOPE))
        assertSame(before, service.snapshot)
    }

    @Test
    fun `lookup can wait for smart mode or be cancelled while indexing`() = timeoutRunBlocking<Unit> {
        val project = project.get()
        val file = withContext(Dispatchers.EDT) { fixture.addFileToProject("code.s", "entry: b entry\n").virtualFile }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val service = project.service<MarsAnalysisService>()
        val scope = GlobalSearchScope.fileScope(project, file)
        val key = SymbolKey("entry", SymbolKind.LABEL)
        val dumbTask = DumbModeTestUtils.startEternalDumbModeTask(project)
        val waiting = try {
            val lookup = async(start = CoroutineStart.UNDISPATCHED) { service.lookupCandidates(key, scope) }
            val cancelled = async(start = CoroutineStart.UNDISPATCHED) { service.lookupCandidates(key, scope) }
            cancelled.cancelAndJoin()
            assertTrue(cancelled.isCancelled)
            assertFalse(lookup.isCompleted)
            lookup
        } finally {
            DumbModeTestUtils.endEternalDumbModeTaskAndWaitForSmartMode(project, dumbTask)
        }
        assertEquals(
            IndexedSymbolCandidates(
                listOf(IndexedSymbolSite(SourceId(file.url), 0, -1)),
                listOf(IndexedSymbolSite(SourceId(file.url), 9, -1)),
            ),
            waiting.await(),
        )
    }
}
