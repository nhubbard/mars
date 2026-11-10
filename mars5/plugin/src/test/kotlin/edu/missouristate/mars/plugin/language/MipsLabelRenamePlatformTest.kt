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
import com.intellij.openapi.vfs.VirtualFileManager
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
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Files
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
    fun `native rename preserves a same-name local when renaming a project export`() = timeoutRunBlocking<Unit> {
        val application = ".text\njal target # target\n"
        val library = ".text\n.globl target\ntarget: jr \$ra\n"
        val data = ".data\n.word target\n"
        val shadow = "target: nop\nj target\n"
        val files = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("entry.asm", application)
            val provider = fixture.addFileToProject("library.s", library)
            val table = fixture.addFileToProject("table.s", data)
            val local = fixture.addFileToProject("shadow.s", shadow)
            val files = listOf(entry, provider, table, local)
            fixture.configureFromExistingVirtualFile(provider.virtualFile)
            project.service<MarsAssemblySettings>().changeSources(files.map { it.virtualFile.path })
            files
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = label(files[1])
            assertEquals(setOf("entry.asm", "library.s", "table.s"), usages(target).map { it.virtualFile!!.name }.toSet())
            RenameProcessor(project, target, "updated", false, false).run()
            val docs = PsiDocumentManager.getInstance(project)
            docs.commitAllDocuments()
            assertEquals(listOf(application.replace("jal target", "jal updated"), library.replace("target", "updated"),
                data.replace("target", "updated"), shadow), files.map { it.text })
            val dialog = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
                docs.commitAllDocuments()
                assertEquals(listOf(application, library, data, shadow), files.map { it.text })
            } finally {
                TestDialogManager.setTestDialog(dialog)
            }
        }
    }

    @Test
    fun `native rename resolves the export from a directory assembly`() = timeoutRunBlocking<Unit> {
        val (entry, provider) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("entry.asm", "jal target\n")
            val provider = fixture.addFileToProject("library.s", ".globl target\ntarget: jr \$ra\n")
            project.service<MarsAnalysisService>().beginSymbolBuild(
                MarsSourceSet(entry.virtualFile.toNioPath(), MarsAssemblyMode.DIRECTORY),
                project.service<MarsAssemblySettings>().executionOptions,
            )
            entry to provider
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            RenameProcessor(project, label(provider), "updated", false, false).run()
            assertEquals("jal updated\n", entry.text)
            assertEquals(".globl updated\nupdated: jr \$ra\n", provider.text)
        }
    }

    @Test
    fun `rename rejects a declaration shared by multiple local symbol tables`() = timeoutRunBlocking<Unit> {
        val shared = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("shared.s", "target: nop\n")
            val first = fixture.addFileToProject("first.asm", ".include \"shared.s\"\n")
            val second = fixture.addFileToProject("second.asm", ".include \"shared.s\"\n")
            project.service<MarsAssemblySettings>().changeSources(listOf(first.virtualFile.path, second.virtualFile.path))
            shared
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(shared)
            assertTrue(conflicts(target, "updated", usages(target)).contains("written label"))
            assertEquals("target: nop\n", shared.text)
        }
    }

    @Test
    fun `rename refuses to edit a source assembled under two physical aliases`() = timeoutRunBlocking<Unit> {
        val library = withContext(Dispatchers.EDT) {
            val library = fixture.addFileToProject("library.s", "target: nop\nj target\n")
            val original = library.virtualFile.toNioPath()
            val alias = Files.createSymbolicLink(original.resolveSibling("alias.s"), original)
            val linked = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(alias)!!
            project.service<MarsAssemblySettings>().changeSources(listOf(library.virtualFile.path, linked.path))
            library
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(library)
            val usages = usages(target)
            assertEquals("library.s", usages.single().virtualFile!!.name)
            assertTrue(conflicts(target, "updated", usages).contains("unshared includes"))
            assertEquals("target: nop\nj target\n", library.text)
        }
    }

    @Test
    fun `rename rejects indirect generated conflicting and behavior-changing edits`() = timeoutRunBlocking<Unit> {
        val cases = listOf(
            Triple("target: nop\n", "main", "ordinary MARS identifier"),
            Triple("target: nop\n", "nop", "ordinary MARS identifier"),
            Triple("target: nop\n", "", "ordinary MARS identifier"),
            Triple("target: nop\n", "two words", "ordinary MARS identifier"),
            Triple("main: nop\n", "next", "written label"),
            Triple(".macro unused (%label)\n%label: nop\n.end_macro\n", "next", "written label"),
            Triple(".macro unused\ntarget: nop\n.end_macro\ntarget: nop\n", "next", "written label"),
            Triple(".macro unused\ntarget: nop\n.end_macro\n.macro define (%name)\n%name: nop\n.end_macro\ndefine(target)\n", "next", "written label"),
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
            assertTrue(conflicts(target, "next", emptyList()).contains("written label"))
        }
    }

    @Test
    fun `native rename edits the written replacement for chained equivalences and forwarded arguments`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "aliases.txt"
            .data
            target: .word 42
            .word DEST, DEST, target
            .text
            .macro load (%address)
                lw ${'$'}t0, %address
            .end_macro
            load (THROUGH)
            LOAD
        """.trimIndent()
        val aliases = ".eqv DECLARE .eqv DEST target # target\nDECLARE\n.eqv THROUGH DEST\n.eqv LOAD lw \$a0, THROUGH\n"
        val (entry, aliasFile) = withContext(Dispatchers.EDT) {
            val aliasFile = fixture.addFileToProject("aliases.txt", aliases)
            val entry = fixture.addFileToProject("entry.asm", source)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry to aliasFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = label(entry)
            val sites = usages(target)
            assertEquals(setOf("entry.asm", "aliases.txt"), sites.map { it.virtualFile!!.name }.toSet())
            assertEquals(2, sites.size)
            RenameProcessor(project, target, "updated.label", false, false).run()
            assertEquals(source.replace("target", "updated.label"), entry.text)
            assertEquals(aliases.replaceFirst("target", "updated.label"), aliasFile.text)
            assertTrue(fixture.psiManager.findFile(aliasFile.virtualFile) is PsiPlainTextFile)
            val previous = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertEquals(source, entry.text)
                assertEquals(aliases, aliasFile.text)
            } finally {
                TestDialogManager.setTestDialog(previous)
            }
        }
    }

    @Test
    fun `rename refuses an equivalence replacement that also binds a different label`() = timeoutRunBlocking<Unit> {
        val export = ".globl target\ntarget: nop\n"
        val entry = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("aliases.txt", ".eqv DEST target\n")
            val provider = fixture.addFileToProject("provider.s", export)
            val user = fixture.addFileToProject("user.asm", ".include \"aliases.txt\"\nj DEST\n")
            val shadow = fixture.addFileToProject("shadow.s", ".include \"aliases.txt\"\ntarget: nop\nj DEST\n")
            project.service<MarsAssemblySettings>().changeSources(listOf(provider, user, shadow).map { it.virtualFile.path })
            provider
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(entry)
            val sites = usages(target)
            assertEquals(setOf("provider.s", "aliases.txt"), sites.map { it.virtualFile!!.name }.toSet())
            assertTrue(conflicts(target, "updated", sites).contains("different symbol"))
            assertEquals(export, entry.text)
        }
    }

    @Test
    fun `rename distinguishes labels with different names at one written location`() = timeoutRunBlocking<Unit> {
        val (shared, direct) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("shared.s", "target: nop\n")
            val direct = fixture.addFileToProject("direct.asm", ".include \"shared.s\"\nj target\n")
            val indirect = fixture.addFileToProject("indirect.asm", ".eqv target shadow\n.include \"shared.s\"\nj shadow\n")
            project.service<MarsAssemblySettings>().changeSources(listOf(direct, indirect).map { it.virtualFile.path })
            shared to direct
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(shared)
            val sites = usages(target)
            assertEquals(direct.virtualFile, sites.single().virtualFile)
            assertTrue(conflicts(target, "updated", sites).contains("ambiguous"))
            assertEquals("target: nop\n", shared.text)
        }
    }

    @Test
    fun `native rename verifies application edits with a configured exception handler`() = timeoutRunBlocking<Unit> {
        val application = ".text\nj target\ntarget: teq \$zero, \$zero\n"
        val exceptionCode = ".ktext 0x80000180\ntarget: nop\nj target\n"
        val (entry, handler) = withContext(Dispatchers.EDT) {
            val handler = fixture.addFileToProject("handler.s", exceptionCode)
            val entry = fixture.addFileToProject("entry.asm", application)
            project.service<MarsAssemblySettings>().changeExceptionHandler(handler.virtualFile.path)
            fixture.configureFromExistingVirtualFile(entry.virtualFile)
            entry to handler
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = label(entry)
            assertEquals("entry.asm", usages(target).single().virtualFile!!.name)
            RenameProcessor(project, target, "updated", false, false).run()
            assertEquals(application.replace("target", "updated"), entry.text)
            assertEquals(exceptionCode, handler.text)

            val renamed = label(entry)
            val references = usages(renamed)
            assertEquals("", conflicts(renamed, "again", references))
            val docs = PsiDocumentManager.getInstance(project)
            val handlerDocument = docs.getDocument(handler)!!
            WriteCommandAction.runWriteCommandAction(project) { handlerDocument.insertString(0, "# changed handler\n") }
            docs.commitDocument(handlerDocument)
            assertTrue(assertThrows<IncorrectOperationException> {
                processor.renameElement(renamed, "again", references.toTypedArray(), null)
            }.message!!.contains("changed after"))
            assertEquals(application.replace("target", "updated"), entry.text)
        }
    }

    @Test
    fun `rename refuses a cross-entry reference in a nested handler include`() = timeoutRunBlocking<Unit> {
        val (entry, operand) = withContext(Dispatchers.EDT) {
            val handler = fixture.addFileToProject("handler.s", ".include \"bridge.txt\"\n")
            fixture.addFileToProject("bridge.txt", ".include \"operand.txt\"\n")
            val operand = fixture.addFileToProject("operand.txt", ".ktext 0x80000180\nla \$k0, target\njr \$k0\n")
            val entry = fixture.addFileToProject("entry.asm", ".globl target\ntarget: nop\n")
            project.service<MarsAssemblySettings>().changeExceptionHandler(handler.virtualFile.path)
            entry to operand
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(entry)
            val usages = usages(target)
            assertEquals(setOf("operand.txt", "entry.asm"), usages.map { it.virtualFile!!.name }.toSet())
            assertTrue(conflicts(target, "updated", usages).contains("project exception handler"))
            assertEquals(".globl target\ntarget: nop\n", entry.text)
            assertTrue(operand.text.contains("la \$k0, target"))
        }
    }

    @Test
    fun `rename requires an exclusive assembly graph with supported file types`() = timeoutRunBlocking<Unit> {
        val (foreign, shared, hidden, independent) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("body.xml", "nop\n")
            val foreign = fixture.addFileToProject("foreign.asm", ".include \"body.xml\"\ntarget: nop\n")
            val shared = fixture.addFileToProject("shared.s", "target: nop\n")
            fixture.addFileToProject("owner.asm", ".include \"shared.s\"\n")
            fixture.addFileToProject("other.asm", ".include \"shared.s\"\n")
            val hidden = fixture.addFileToProject("hidden.asm", "target: nop\n")
            fixture.addFileToProject("bridge.txt", ".include \"hidden.asm\"\n")
            fixture.addFileToProject("hidden-owner.asm", ".include \"bridge.txt\"\n")
            val independent = fixture.addFileToProject("independent.asm", "target: nop\n")
            listOf(foreign, shared, hidden, independent)
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
        readAction { assertEquals("", conflicts(label(independent), "next", emptyList())) }
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
