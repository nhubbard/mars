package edu.missouristate.mars.plugin.language

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.Segment
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.usageView.UsageInfo
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import com.intellij.util.containers.MultiMap
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MipsLabelRenamePlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val processor = MipsLabelRenameProcessor()

    @Test
    fun `native rename finds nested include references before highlighting and after unsaved edits`() = timeoutRunBlocking<Unit> {
        val source = ".data\ntarget: .word 42\n.text\n.include \"lib/wrapper.txt\"\n"
        val plainText = ".data\n.word target, target # target\n.asciiz \"target\"\n.text\n.include \"tail.s\"\n"
        val tailText = "lw \$a0, target # target\n"
        val (entry, plain, tail) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("lib/wrapper.txt", ".include \"operands.txt\"\n")
            val plain = fixture.addFileToProject("lib/operands.txt", plainText)
            val tail = fixture.addFileToProject("lib/tail.s", tailText)
            val entry = fixture.addFileToProject("entry.asm", source).also {
                fixture.configureFromExistingVirtualFile(it.virtualFile)
            }
            Triple(entry, plain, tail)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val target = readAction { label(entry) }
        withContext(Dispatchers.EDT) {
            val docs = PsiDocumentManager.getInstance(project)
            val document = docs.getDocument(plain)!!
            val heading = "# unsaved heading\n"
            assertTrue(project.service<MarsAnalysisService>().assembledUsages(target).none())
            assertEquals(3, usages(target).size)
            val limited = processor.findReferences(target, LocalSearchScope(entry), false)
            assertTrue(limited.isEmpty())
            assertTrue(conflicts(target, "updated", emptyList()).contains("every assembled reference"))
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, heading) }
            assertTrue(usages(target).isEmpty())
            docs.commitDocument(document)
            assertTrue(project.service<MarsAnalysisService>().assembledUsages(target).none())
            val editor = FileEditorManager.getInstance(project).selectedEditor
            val undo = UndoManager.getInstance(project)
            RenameProcessor(project, target, "updated", false, false).run()
            docs.commitAllDocuments()
            assertEquals(source.replace("target:", "updated:"), entry.text)
            assertEquals(heading + plainText.replace("target, target", "updated, updated"), plain.text)
            assertEquals(tailText.replaceFirst("target", "updated"), tail.text)
            assertTrue(fixture.psiManager.findFile(plain.virtualFile) is PsiPlainTextFile)
            val dialog = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                undo.undo(editor)
                docs.commitAllDocuments()
                assertEquals(source, entry.text)
                assertEquals(heading + plainText, plain.text)
                assertEquals(tailText, tail.text)
            } finally {
                TestDialogManager.setTestDialog(dialog)
            }
        }
    }

    @Test
    fun `native rename verifies instruction data and forwarded uses across includes and supports undo`() = timeoutRunBlocking<Unit> {
        val source = """
            .data
            target: .word target
            other: .word 9
            .word target, other
            .asciiz "target"
            .text
            .include "uses.s"
            la ${'$'}t0, target # target
            forward(target)
        """.trimIndent()
        val body = """
            .macro forward (%address)
                la ${'$'}t1, %address
            .end_macro
            la ${'$'}t2, target
        """.trimIndent()
        val (entry, included) = withContext(Dispatchers.EDT) {
            val included = fixture.addFileToProject("uses.s", body)
            val entry = fixture.addFileToProject("entry.asm", source)
            fixture.addFileToProject("unrelated.asm", "target: nop\nj target\n")
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry to included
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        val target = readAction { label(entry) }
        val newName = "renamed.label"
        withContext(Dispatchers.EDT) {
            assertTrue(RenamePsiElementProcessor.forElement(target) is MipsLabelRenameProcessor)
            val undo = UndoManager.getInstance(project)
            val editor = FileEditorManager.getInstance(project).selectedEditor
            val docs = PsiDocumentManager.getInstance(project)
            fun assertSources(root: String, part: String) {
                docs.commitAllDocuments()
                assertEquals(root, entry.text)
                assertEquals(part, included.text)
                assertEquals("target: nop\nj target\n", fixture.findFileInTempDir("unrelated.asm").let {
                    PsiDocumentManager.getInstance(project).getDocument(fixture.psiManager.findFile(it)!!)!!.text
                })
            }
            RenameProcessor(project, target, newName, false, false).run()
            val renamed = source.replace("target: .word target", "$newName: .word $newName")
                .replace(".word target, other", ".word $newName, other")
                .replace(", target #", ", $newName #").replace("forward(target)", "forward($newName)")
            val renamedBody = body.replace(", target", ", $newName")
            assertSources(renamed, renamedBody)
            val previous = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                undo.undo(editor)
                assertSources(source, body)
                undo.redo(editor)
                assertSources(renamed, renamedBody)
            } finally {
                TestDialogManager.setTestDialog(previous)
            }
        }
    }

    @Test
    fun `rename rejects indirect exported conflicting and behavior-changing edits`() = timeoutRunBlocking<Unit> {
        val cases = listOf(
            Triple("target: nop\n", "main", "ordinary MARS identifier"),
            Triple("target: nop\n", "nop", "ordinary MARS identifier"),
            Triple("target: nop\n", "", "ordinary MARS identifier"),
            Triple("target: nop\n", "two words", "ordinary MARS identifier"),
            Triple("main: nop\n", "next", "written local label"),
            Triple("target: nop\n.globl target\n", "next", "written local label"),
            Triple(".macro unused (%label)\n%label: nop\n.end_macro\n", "next", "written local label"),
            Triple(".macro unused\ntarget: nop\n.end_macro\ntarget: nop\n", "next", "written local label"),
            Triple("target: nop\n.eqv NAME target\n", "next", "ambiguous"),
            Triple("target: nop\nnext: nop\n", "next", "already occurs"),
            Triple("target: nop\nj missing\n", "next", "could not assemble"),
            Triple(".include \"missing.s\"\ntarget: nop\n", "next", "could not assemble"),
            Triple(".data\ntarget: .word 0\ntarget\n", ".new", "change assembled output"),
        )
        for ((source, newName, message) in cases) {
            val file = withContext(Dispatchers.EDT) { fixture.configureByText("label.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            readAction {
                val target = label(file)
                val conflicts = conflicts(target, newName, usages(target))
                assertTrue(conflicts.contains(message), "$source -> '$newName' reported '$conflicts'")
                assertEquals(source, file.text)
            }
        }
        readAction {
            val scratch = PsiFileFactory.getInstance(project).createFileFromText("scratch.asm", MipsFileType, "target: nop\n.eqv LIMIT 1")
            val target = label(scratch)
            val constant = PsiTreeUtil.findChildrenOfType(scratch, MipsDeclaration::class.java).last()
            val validator = MipsLabelRenameInputValidator()
            assertTrue(validator.pattern.accepts(target))
            assertFalse(processor.canProcessElement(scratch))
            assertFalse(processor.canProcessElement(constant))
            assertFalse(validator.pattern.accepts(constant))
            assertFalse(processor.isInplaceRenameSupported)
            assertTrue(validator.isInputValid("valid.name", target, ProcessingContext()))
            assertTrue(usages(target).isEmpty())
            assertTrue(conflicts(target, "next", emptyList()).contains("written local label"))
        }
    }

    @Test
    fun `rename requires an exclusive assembly graph with supported file types`() = timeoutRunBlocking<Unit> {
        val (foreign, shared, hidden, independent, handler) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("body.xml", "nop\n")
            val foreign = fixture.addFileToProject("foreign.asm", ".include \"body.xml\"\ntarget: nop\n")
            val shared = fixture.addFileToProject("shared.s", "target: nop\n")
            fixture.addFileToProject("owner.asm", ".include \"shared.s\"\n")
            fixture.addFileToProject("other.asm", ".include \"shared.s\"\n")
            val hidden = fixture.addFileToProject("hidden.asm", "target: nop\n")
            fixture.addFileToProject("bridge.txt", ".include \"hidden.asm\"\n")
            fixture.addFileToProject("hidden-owner.asm", ".include \"bridge.txt\"\n")
            val independent = fixture.addFileToProject("independent.asm", "target: nop\n")
            val handler = fixture.addFileToProject("handler.asm", ".ktext\nnop\n")
            listOf(foreign, shared, hidden, independent, handler)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            for (file in listOf(foreign, shared, hidden)) {
                val target = label(file)
                assertTrue(conflicts(target, "next", usages(target)).contains("unshared includes"), file.name)
            }
        }
        val settings = project.service<MarsAssemblySettings>()
        withContext(Dispatchers.EDT) { settings.changeSources(listOf(independent.virtualFile.path)) }
        readAction { assertTrue(conflicts(label(independent), "next", emptyList()).contains("one assembly entry")) }
        withContext(Dispatchers.EDT) {
            settings.changeSources(emptyList())
            settings.changeExceptionHandler(handler.virtualFile.path)
        }
        readAction { assertTrue(conflicts(label(independent), "next", emptyList()).contains("one assembly entry")) }
    }

    @Test
    fun `rename rejects an otherwise verified usage in a shared include`() = timeoutRunBlocking<Unit> {
        val (entry, shared) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("shared.s", "j target\n")
            fixture.addFileToProject("other.asm", "target: nop\n.include \"shared.s\"\n")
            fixture.addFileToProject("entry.asm", "target: nop\n.include \"shared.s\"\n") to shared
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(entry)
            val usage = UsageInfo(shared.findElementAt(2)!!)
            assertTrue(conflicts(target, "next", listOf(usage)).contains("unshared includes"))
        }
    }

    @Test
    fun `rename rejects a plain-text usage shared through another plain include`() = timeoutRunBlocking<Unit> {
        val entry = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("bridge.txt", ".include \"shared.txt\"\n")
            fixture.addFileToProject("shared.txt", "j target\n")
            fixture.addFileToProject("other.asm", "target: nop\n.include \"bridge.txt\"\n")
            fixture.addFileToProject("entry.asm", "target: nop\n.include \"bridge.txt\"\n").also {
                fixture.configureFromExistingVirtualFile(it.virtualFile)
            }
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        readAction {
            val target = label(entry)
            val sites = usages(target)
            assertEquals("shared.txt", sites.single().virtualFile?.name)
            assertTrue(conflicts(target, "next", sites).contains("unshared includes"))
        }
    }

    @Test
    fun `rename rejects incomplete preview and source changes after validation`() = timeoutRunBlocking<Unit> {
        val source = ".include \"used.s\"\ntarget: nop\nj target\n"
        val (root, included, outside) = withContext(Dispatchers.EDT) {
            val part = fixture.addFileToProject("used.s", "nop\n")
            val other = fixture.addFileToProject("elsewhere.asm", "nop\n")
            Triple(fixture.configureByText("root.asm", source), part, other)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val target = readAction { label(root) }
        val references = readAction {
            val initial = MultiMap<PsiElement, String>()
            processor.findExistingNameConflicts(target, "next", initial)
            assertTrue(initial.isEmpty)
            assertThrows<IncorrectOperationException> { processor.renameElement(target, "next", emptyArray(), null) }
            val fileless = object : UsageInfo(target) {
                override fun getVirtualFile(): VirtualFile? = null
            }
            val rangeless = object : UsageInfo(target) {
                override fun getSegment(): Segment? = null
            }
            assertTrue(conflicts(target, "next", listOf(fileless)).contains("verified name"))
            assertTrue(conflicts(target, "next", listOf(rangeless)).contains("verified name"))
            processor.findCollisions(target, "next", emptyMap(), mutableListOf())
            processor.findExistingNameConflicts(target, "next", initial)
            assertFalse(initial.isEmpty)
            assertTrue(conflicts(target, "next", emptyList()).contains("every assembled reference"))
            val usages = usages(target)
            assertEquals("", conflicts(target, "next", usages))
            assertTrue(assertThrows<IncorrectOperationException> {
                processor.renameElement(target, "different", usages.toTypedArray(), null)
            }.message!!.contains("verified name"))
            assertEquals("", conflicts(target, "next", usages))
            assertTrue(assertThrows<IncorrectOperationException> {
                processor.renameElement(target, "next", emptyArray(), null)
            }.message!!.contains("verified name"))
            assertEquals("", conflicts(target, "next", usages))
            usages
        }
        withContext(Dispatchers.EDT) {
            val documents = PsiDocumentManager.getInstance(project)
            val otherDocument = documents.getDocument(outside)!!
            WriteCommandAction.runWriteCommandAction(project) { otherDocument.insertString(0, "# unrelated edit\n") }
            documents.commitDocument(otherDocument)
            assertTrue(assertThrows<IncorrectOperationException> {
                processor.renameElement(target, "next", references.toTypedArray(), null)
            }.message!!.contains("changed after"))
        }
        readAction { assertEquals("", conflicts(target, "next", references)) }
        withContext(Dispatchers.EDT) {
            val documents = PsiDocumentManager.getInstance(project)
            val document = documents.getDocument(included)!!
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "# changed\n") }
            assertTrue(assertThrows<IncorrectOperationException> {
                processor.renameElement(target, "next", references.toTypedArray(), null)
            }.message!!.contains("changed after"))
            assertTrue(conflicts(target, "next", references).contains("changed after"))
            documents.commitDocument(document)
            assertEquals(source, root.text)
        }
    }

    private fun label(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)
        .first { it.kind == SymbolKind.LABEL }

    private fun usages(label: MipsDeclaration) =
        processor.findReferences(label, GlobalSearchScope.projectScope(project), false)
            .map { processor.createUsageInfo(label, it, it.element) }

    private fun conflicts(label: MipsDeclaration, newName: String, usages: List<UsageInfo>): String {
        processor.findCollisions(label, newName, mapOf(label to newName), usages.toMutableList())
        val conflicts = MultiMap<PsiElement, String>()
        processor.findExistingNameConflicts(label, newName, conflicts)
        return conflicts.values().joinToString()
    }
}
