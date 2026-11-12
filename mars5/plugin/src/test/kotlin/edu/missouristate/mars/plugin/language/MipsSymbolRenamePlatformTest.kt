package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.TargetElementUtil
import com.intellij.find.findUsages.CustomUsageSearcher
import com.intellij.find.findUsages.FindUsagesOptions
import com.intellij.notebook.editor.BackedVirtualFile
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
import com.intellij.openapi.vfs.limits.FileSizeLimit
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiPlainTextFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.LocalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.refactoring.rename.RenamePsiElementProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.usageView.UsageInfo
import com.intellij.usages.Usage
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.util.Processor
import com.intellij.util.IncorrectOperationException
import com.intellij.util.ProcessingContext
import com.intellij.util.containers.MultiMap
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.SymbolKind
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import edu.missouristate.mars.plugin.build.MarsAssemblyMode
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsSourceSet
import edu.missouristate.mars.plugin.language.psi.MipsDeclaration
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MipsSymbolRenamePlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val processor = MipsSymbolRenameProcessor()

    @Test
    fun `Find Usages shows off-preview assembly bindings as read-only results and Rename reports the incomplete file`() =
        timeoutRunBlocking<Unit> {
            val source = ".eqv VALUE 1\ntarget: nop\nj target\n.include \"large.inc\"\n"
            val (entry, large) = withContext(Dispatchers.EDT) {
                val entry = fixture.addFileToProject("entry.mars", source)
                val path = entry.virtualFile.toNioPath().resolveSibling("large.inc")
                Files.newBufferedWriter(path).use { writer ->
                    writer.write("j target # Visible usage\n")
                    val comment = "#" + " ".repeat(4094) + "\n"
                    repeat(FileSizeLimit.getContentLoadLimit("inc") / comment.length + 1) { writer.write(comment) }
                    writer.write("j target # First hidden usage\n")
                    writer.write("j target # Second hidden usage\n")
                }
                val large = VirtualFileManager.getInstance().refreshAndFindFileByNioPath(path)!!
                fixture.configureFromExistingVirtualFile(entry.virtualFile)
                entry to large
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            val path = entry.virtualFile.toNioPath()
            val capture = captureAssemblySources(listOf(path), null)
            val component = MarsSessionService.createMarsComponent()
            val symbols = component.context().use {
                val result = component.assembler().assembleSnapshot(capture.snapshot, null, true, false)
                assertTrue(result.succeeded(), result.errors().toString())
                result.program()!!.symbolMap()
            }
            val analysis = project.service<MarsAnalysisService>()
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), capture, symbols)
            val target = readAction { label(entry) }
            val searcher = MipsExcerptUsageSearcher()
            withContext(Dispatchers.EDT) {
                val scope = GlobalSearchScope.projectScope(project)
                val options = FindUsagesOptions(scope).apply { isUsages = true }
                val results = mutableListOf<Usage>()
                val collect = Processor<Usage> { results.add(it) }
                searcher.processElementUsages(entry, collect, options)
                searcher.processElementUsages(constants(entry).single(), collect, options)
                searcher.processElementUsages(target, collect, FindUsagesOptions(scope))
                searcher.processElementUsages(target, collect, FindUsagesOptions(GlobalSearchScope.fileScope(entry)).apply { isUsages = true })
                assertTrue(results.isEmpty())

                val preview = fixture.psiManager.findFile(large)!!
                options.searchScope = LocalSearchScope(arrayOf<PsiElement>(target, preview))
                searcher.processElementUsages(target, collect, options)
                assertEquals(2, results.size)
                for (result in results) {
                    val usage = result as UsageInfo2UsageAdapter
                    assertTrue(usage.isReadOnly)
                    assertTrue(usage.canNavigateToSource())
                    assertEquals(large, BackedVirtualFile.getOriginFileIfBacked(usage.file!!))
                    val info = usage.usageInfo
                    assertEquals("target", info.rangeInElement!!.substring(info.element!!.text))
                }
                var consumed = 0
                searcher.processElementUsages(target, Processor { consumed++; false }, options)
                assertEquals(1, consumed)
                options.searchScope = LocalSearchScope(target)
                searcher.processElementUsages(target, collect, options)
                assertEquals(2, results.size)

                assertTrue(scope.contains(large), "Large include belongs to the fixture project")
                options.searchScope = scope
                results.clear()
                CustomUsageSearcher.EP_NAME.extensionList.filterIsInstance<MipsExcerptUsageSearcher>().single()
                    .processElementUsages(target, collect, options)
                assertEquals(2, results.size, "Global search finds both excerpts")
                assertTrue(results.all { it.isValid })
                val tree = fixture.getUsageViewTreeTextRepresentation(target)
                assertTrue("First hidden usage" in tree, tree)
                assertTrue("Second hidden usage" in tree, tree)
                assertTrue(conflicts(target, "renamed", usages(target)).contains("loaded one of these files as a preview"))
                assertEquals(source, entry.text)

                val largePath = large.toNioPath()
                val hidden = symbols.symbolUses().entries.first { (site) ->
                    site.location.file() == largePath && site.location.line() > capture.previewLineCounts.getValue(largePath)
                }
                val malformed = AssemblySymbolMap(symbols.locals(), symbols.globals(), emptyMap(),
                    mapOf(SourceToken(hidden.key.location, 100) to hidden.value))
                analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), capture, malformed)
                results.clear()
                searcher.processElementUsages(target, collect, options)
                assertTrue(results.isEmpty())
            }
        }

    @Test
    fun `native rename finds nested include references before highlighting and after unsaved edits`() = timeoutRunBlocking<Unit> {
        val source = ".data\ntarget: .word 42\n.text\n.include \"lib/wrapper.txt\"\n"
        val plainText = ".data\n.word target, target # target\n.asciiz \"target\"\n.text\n.include \"tail.s\"\n"
        val tailText = $$"lw $a0, target # target\n"
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
        val source = $$"""
            .data
            target: .word target
            other: .word 9
            .word target, other
            .asciiz "target"
            .text
            .include "uses.s"
            la $t0, target # target
            forward(target)
        """.trimIndent()
        val body = $$"""
            .macro forward (%address)
                la $t1, %address
            .end_macro
            la $t2, target
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
            assertTrue(RenamePsiElementProcessor.forElement(target) is MipsSymbolRenameProcessor)
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
    fun `native rename follows a macro overload through nested calls and aliases`() = timeoutRunBlocking<Unit> {
        val library = $$"""
            .macro emit
                nop
            .end_macro
            .macro emit (%value)
                li $a0, %value
            .end_macro
            .macro invoke (%operation, %value)
                %operation (%value)
            .end_macro
        """.trimIndent()
        val source = """
            .include "library.s"
            .include "aliases.txt"
            emit
            emit (1)
            invoke (ALIAS, 2)
            .include "calls.txt"
            emit: nop
            j emit
        """.trimIndent()
        val aliases = ".eqv ALIAS emit # emit\n"
        val calls = "emit (3) # emit\n"
        val files = withContext(Dispatchers.EDT) {
            val definitions = fixture.addFileToProject("library.s", library)
            val aliasFile = fixture.addFileToProject("aliases.txt", aliases)
            val callFile = fixture.addFileToProject("calls.txt", calls)
            val entry = fixture.addFileToProject("entry.asm", source)
            fixture.configureFromExistingVirtualFile(definitions.virtualFile)
            listOf(definitions, aliasFile, callFile, entry)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = macros(files.first())[1]
            assertEquals(setOf("entry.asm", "aliases.txt", "calls.txt"), usages(target).map { it.virtualFile!!.name }.toSet())
            RenameProcessor(project, target, "write_value", false, false).run()
            assertEquals(listOf(library.replace("emit (%value)", "write_value (%value)"),
                aliases.replaceFirst("emit", "write_value"), calls.replaceFirst("emit", "write_value"),
                source.replace("emit (1)", "write_value (1)")), files.map { it.text })
            val previous = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertEquals(listOf(library, aliases, calls, source), files.map { it.text })
            } finally {
                TestDialogManager.setTestDialog(previous)
            }
        }
    }

    @Test
    fun `native rename can rename a committed macro without call sites`() = timeoutRunBlocking<Unit> {
        val source = ".macro idle\nnop\n.end_macro\nnop\n"
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val idle = macros(file).single()
            val validator = MipsSymbolRenameInputValidator()
            assertTrue(validator.pattern.accepts(idle))
            assertTrue(validator.isInputValid("main", idle, ProcessingContext()))
            assertFalse(validator.isInputValid("nop", idle, ProcessingContext()))
            assertTrue(usages(idle).isEmpty())
            RenameProcessor(project, idle, "main", false, false).run()
            assertEquals(source.replace("idle", "main"), file.text)
            RenameProcessor(project, macros(file).single(), "pause", false, false).run()
            assertEquals(source.replace("idle", "pause"), file.text)
        }
    }

    @Test
    fun `rename refuses a forwarded spelling shared by two macro overloads`() = timeoutRunBlocking<Unit> {
        val source = $$"""
            .macro emit
                nop
            .end_macro
            .macro emit (%value)
                li $a0, %value
            .end_macro
            .macro both (%op)
                %op
                %op (1)
            .end_macro
            both (emit)
        """.trimIndent()
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val macro = macros(file)[1]
            val references = usages(macro)
            assertEquals(source.lastIndexOf("emit"), references.single().segment!!.startOffset)
            assertTrue(conflicts(macro, "updated", references).contains("could not assemble"))
            assertEquals(source, file.text)
        }
    }

    @Test
    fun `rename refuses macro declarations compiled into several assembly entries`() = timeoutRunBlocking<Unit> {
        val definitions = withContext(Dispatchers.EDT) {
            val library = fixture.addFileToProject("library.s", ".macro emit\nnop\n.end_macro\n")
            val first = fixture.addFileToProject("first.asm", ".include \"library.s\"\nemit\n")
            val second = fixture.addFileToProject("second.asm", ".include \"library.s\"\nemit\n")
            project.service<MarsAssemblySettings>().changeSources(listOf(first, second).map { it.virtualFile.path })
            library
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val macro = macros(definitions).single()
            val references = usages(macro)
            assertEquals(2, references.size)
            assertTrue(conflicts(macro, "updated", references).contains("written declaration"))
        }
    }

    @Test
    fun `label rename refuses to also rename an unrelated alias-defined macro`() = timeoutRunBlocking<Unit> {
        val source = ".eqv ALIAS target\ntarget: nop\nj ALIAS\n.macro ALIAS\nnop\n.end_macro\n"
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = label(file)
            assertTrue(conflicts(target, "updated", usages(target)).contains("different symbol"))
            assertEquals(source, file.text)
        }
    }

    @Test
    fun `native equivalence rename updates repeated definitions and the realized links in alias chains`() = timeoutRunBlocking<Unit> {
        val definitions = ".eqv BASE 42 # BASE\n"
        val aliases = $$".eqv BASE 42\n.eqv VIA BASE\n.eqv LOAD li $a0, VIA\n"
        val source = $$"""
            .include "definitions.s"
            .include "aliases.txt"
            .data
            .word BASE, BASE
            .asciiz "BASE"
            .text
            .macro emit (%value)
                li $a1, %value
            .end_macro
            emit (BASE)
            LOAD
        """.trimIndent()
        val (defs, chain, entry) = withContext(Dispatchers.EDT) {
            val defs = fixture.addFileToProject("definitions.s", definitions)
            val chain = fixture.addFileToProject("aliases.txt", aliases)
            val entry = fixture.addFileToProject("entry.asm", source)
            fixture.configureFromExistingVirtualFile(defs.virtualFile)
            Triple(defs, chain, entry)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val alias = constants(defs).single()
            val references = usages(alias)
            assertEquals(listOf("aliases.txt", "aliases.txt", "entry.asm", "entry.asm", "entry.asm"),
                references.map { it.virtualFile!!.name }.sorted())
            RenameProcessor(project, alias, "NUMBER", false, false).run()
            assertEquals(".eqv NUMBER 42 # BASE\n", defs.text)
            assertEquals(aliases.replace("BASE", "NUMBER"), chain.text)
            assertEquals(source.replace(".word BASE, BASE", ".word NUMBER, NUMBER").replace("emit (BASE)", "emit (NUMBER)"), entry.text)
            assertTrue(fixture.psiManager.findFile(chain.virtualFile) is PsiPlainTextFile)
            val dialog = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertEquals(listOf(definitions, aliases, source), listOf(defs.text, chain.text, entry.text))
            } finally {
                TestDialogManager.setTestDialog(dialog)
            }
        }
    }

    @Test
    fun `native equivalence rename can start at an identical repeated declaration`() = timeoutRunBlocking<Unit> {
        val source = $$".eqv BASE 42\n.eqv BASE 42\nli $t0, BASE\n"
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val alias = constants(file).last()
            assertEquals(2, usages(alias).size)
            RenameProcessor(project, alias, "VALUE", false, false).run()
            assertEquals(source.replace("BASE", "VALUE"), file.text)
        }
    }

    @Test
    fun `rename rejects alias declarations shared by independent entry tokenizers`() = timeoutRunBlocking<Unit> {
        val definitions = withContext(Dispatchers.EDT) {
            val definitions = fixture.addFileToProject("defs.s", ".eqv BASE 42\n")
            val first = fixture.addFileToProject("first.asm", $$".include \"defs.s\"\nli $t0, BASE\n")
            val second = fixture.addFileToProject("second.asm", $$".include \"defs.s\"\nli $t1, BASE\n")
            project.service<MarsAssemblySettings>().changeSources(listOf(first, second).map { it.virtualFile.path })
            definitions
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = constants(definitions).single()
            assertTrue(usages(target).isEmpty())
            assertTrue(conflicts(target, "VALUE", emptyList()).contains("unique definition"))
        }
    }

    @Test
    fun `rename detects a changed alias binding even when both assemblies emit identical code`() = timeoutRunBlocking<Unit> {
        val original = ".eqv BASE 1\n.include \"shared.txt\"\nnop\n"
        val first = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("shared.txt", ".set BASE\n")
            val first = fixture.addFileToProject("first.asm", original)
            val second = fixture.addFileToProject("second.asm", original.replace("BASE 1", "BASE 2"))
            project.service<MarsAssemblySettings>().changeSources(listOf(first, second).map { it.virtualFile.path })
            first
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = constants(first).single()
            val sites = usages(target)
            assertEquals("shared.txt", sites.single().virtualFile!!.name)
            val conflict = conflicts(target, "VALUE", sites)
            assertTrue(conflict.contains("different symbol"), conflict)
            assertEquals(original, first.text)
        }
    }

    @Test
    fun `native rename follows separate tokens in a macro-produced replacement`() = timeoutRunBlocking<Unit> {
        val source = """
            BASE: nop
            j BASE
            other: nop
            .eqv DEFINE .eqv
            .macro define (%directive, %destination)
                %directive PAIR BASE, %destination
            .end_macro
            .macro consume
                .word PAIR
            .end_macro
            .eqv BASE 42
            define DEFINE, other
            .data
            consume
        """.trimIndent()
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = constants(file).last()
            val offset = source.indexOf("BASE, %destination")
            assertEquals(offset, usages(target).single().segment!!.startOffset)
            RenameProcessor(project, target, "VALUE", false, false).run()
            val renamed = source.replace(".eqv BASE 42", ".eqv VALUE 42").replace("PAIR BASE", "PAIR VALUE")
            assertEquals(renamed, file.text)

            val other = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java).single { it.name == "other" }
            assertEquals(renamed.lastIndexOf("other"), usages(other).single().segment!!.startOffset)
            RenameProcessor(project, other, "destination", false, false).run()
            assertEquals(renamed.replace("other", "destination"), file.text)
        }
    }

    @Test
    fun `rename refuses an alias whose use is a synthesized hygienic name`() = timeoutRunBlocking<Unit> {
        val source = """
            BASE_M0: nop
            j BASE_M0
            .macro emit
                BASE: nop
                j BASE
            .end_macro
            .eqv BASE_M0 branch_label
            emit
        """.trimIndent()
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = constants(file).single()
            assertTrue(usages(target).isEmpty())
            val conflict = conflicts(target, "VALUE", emptyList())
            assertTrue(conflict.contains("traceable spellings"), conflict)
            val label = label(file)
            assertEquals("", conflicts(label, "unrelated", usages(label)))
            assertEquals(source, file.text)
        }
    }

    @Test
    fun `native rename from a repeated extern updates its allocation aliases and cross-file operands`() = timeoutRunBlocking<Unit> {
        val application =
          $$".include \"allocation.txt\"\n.include \"repeat.s\"\n.data\n.word $buffer\n.text\nla $t0, CELL\n"
        val allocation = $$".extern $buffer 4\n.eqv CELL $buffer # $buffer\n"
        val repeated = $$".extern $buffer 32\n.extern CELL 16\n"
        val shadow = $$".data\n$buffer: .word 99\n.word $buffer\n"
        val files = withContext(Dispatchers.EDT) {
            val storage = fixture.addFileToProject("allocation.txt", allocation)
            val duplicate = fixture.addFileToProject("repeat.s", repeated)
            val entry = fixture.addFileToProject("entry.asm", application)
            val local = fixture.addFileToProject("shadow.s", shadow)
            project.service<MarsAssemblySettings>().changeSources(listOf(entry, local).map { it.virtualFile.path })
            fixture.configureFromExistingVirtualFile(duplicate.virtualFile)
            listOf(entry, storage, duplicate, local)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = externals(files[2]).first()
            val references = usages(target)
            assertEquals(setOf("allocation.txt", "entry.asm"), references.map { it.virtualFile!!.name }.toSet())
            assertEquals(3, references.size)
            RenameProcessor(project, target, "storage.cell", false, false).run()
            assertEquals(listOf(application.replace($$"$buffer", "storage.cell"),
              $$".extern storage.cell 4\n.eqv CELL storage.cell # $buffer\n", repeated.replace($$"$buffer", "storage.cell"), shadow),
                files.map { it.text })
            assertTrue(fixture.psiManager.findFile(files[1].virtualFile) is PsiPlainTextFile)
            val previous = TestDialogManager.setTestDialog(TestDialog.OK)
            try {
                UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertEquals(listOf(application, allocation, repeated, shadow), files.map { it.text })
            } finally {
                TestDialogManager.setTestDialog(previous)
            }
        }
    }

    @Test
    fun `native rename edits the whole signed extern in MARS and plain text sources`() = timeoutRunBlocking<Unit> {
        val entry = ".extern -42, 4\n.include \"repeat.txt\"\n.include \"repeat.inc\"\n.data\n.word -42\n.text\nnop\n"
        val (file, plain, include) = withContext(Dispatchers.EDT) {
            val plain = fixture.addFileToProject("repeat.txt", ".extern -42, 8\n")
            val include = fixture.addFileToProject("repeat.inc", ".extern -42, 16\n")
            val file = fixture.configureByText("entry.mars", entry)
            Triple(file, plain, include)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = externals(file).single()
            val flags = TargetElementUtil.REFERENCED_ELEMENT_ACCEPTED or TargetElementUtil.ELEMENT_NAME_ACCEPTED
            val targets = target.nameIdentifier!!.textRange.let { it.startOffset until it.endOffset }
                .map { TargetElementUtil.getInstance().findTargetElement(fixture.editor, flags, it) }
            assertEquals(List(3) { target }, targets)
            val sizeOffset = entry.indexOf(", 4") + 2
            assertNull(TargetElementUtil.getInstance().findTargetElement(fixture.editor, flags, sizeOffset))
            fixture.highlightAfterMipsRefresh()
            val found = ReferencesSearch.search(target, GlobalSearchScope.projectScope(project)).findAll()
            assertEquals(setOf(plain.virtualFile, include.virtualFile), found.map { it.element.containingFile.virtualFile }.toSet())
            assertTrue(found.all { it.rangeInElement.substring(it.element.text) == "-42" })
            val references = usages(target)
            assertEquals(setOf(plain.virtualFile, include.virtualFile), references.map { it.virtualFile }.toSet())
            assertEquals(listOf("-42", "-42"), references.map { usage ->
                val source = if (usage.virtualFile == plain.virtualFile) plain else include
                val range = checkNotNull(usage.segment)
                source.text.substring(range.startOffset, range.endOffset)
            })
            assertEquals("", conflicts(target, "signed_slot", references))
            RenameProcessor(project, checkNotNull(targets.last()), "signed_slot", false, false).run()
            assertEquals(entry.replace(".extern -42", ".extern signed_slot"), file.text)
            assertEquals(".extern signed_slot, 8\n", plain.text)
            assertEquals(".extern signed_slot, 16\n", include.text)
        }
    }

    @Test
    fun `native rename from a numeric repeat preserves data matching a character extern`() = timeoutRunBlocking<Unit> {
        val source = ".extern 'A', 4\n.extern 65, 8\n.extern 'A', 16\n.data\n.word 65, 'A'\n.text\nnop\n"
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.mars", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = externals(file)[1]
            val references = usages(target)
            assertEquals(listOf("'A'", "'A'"), references.map {
                val range = checkNotNull(it.segment)
                file.text.substring(range.startOffset, range.endOffset)
            })
            assertEquals("", conflicts(target, "character_slot", references))
            RenameProcessor(project, target, "character_slot", false, false).run()
            assertEquals(source.replace(".extern 'A'", ".extern character_slot")
                .replace(".extern 65", ".extern character_slot"), file.text)
        }
    }

    @Test
    fun `rename refuses a signed extern assembled from a macro parameter and separate sign`() = timeoutRunBlocking<Unit> {
        val source = ".extern -42, 4\n.macro repeat (%number)\n.extern -%number, 8\n.end_macro\nrepeat(42)\nnop\n"
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.mars", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val target = externals(file).first()
            val references = usages(target)
            assertEquals(1, references.size)
            assertTrue(conflicts(target, "signed_slot", references).contains("indirect substitution"))
            assertEquals(source, file.text)
        }
    }

    @Test
    fun `native rename from an allocating extern keeps repeated declarations tied to it`() = timeoutRunBlocking<Unit> {
        val source =
          $$".extern storage 4\n.extern storage 32\n.extern next_cell 4\nla $t0, storage\nla $t1, next_cell\n"
        val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val target = externals(file).first()
            assertEquals(2, usages(target).size)
            RenameProcessor(project, target, "updated", false, false).run()
            assertEquals(source.replace("storage", "updated"), file.text)
        }
    }

    @Test
    fun `rename treats an extern of a preceding export as a use of that export`() = timeoutRunBlocking<Unit> {
        val export = ".data\n.globl storage\nstorage: .word 1\n"
        val consumer = $$".extern storage 4\n.text\nla $t0, storage\n"
        val (provider, user) = withContext(Dispatchers.EDT) {
            val provider = fixture.addFileToProject("provider.s", export)
            val user = fixture.addFileToProject("user.asm", consumer)
            project.service<MarsAssemblySettings>().changeSources(listOf(provider, user).map { it.virtualFile.path })
            provider to user
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val external = externals(user).single()
            assertTrue(usages(external).isEmpty())
            assertTrue(conflicts(external, "updated", emptyList()).contains("written external allocation"))
            RenameProcessor(project, label(provider), "updated", false, false).run()
            assertEquals(export.replace("storage", "updated"), provider.text)
            assertEquals(consumer.replace("storage", "updated"), user.text)
        }
    }

    @Test
    fun `rename requires a realized written extern allocation`() = timeoutRunBlocking<Unit> {
        val cases = listOf(
            ".macro idle\n.extern storage 8\n.end_macro\nnop\n",
            ".extern storage 4\n.macro idle\n.extern storage 8\n.end_macro\nnop\n",
            ".macro reserve (%slot)\n.extern %slot 4\n.end_macro\nreserve (storage)\n.extern storage 8\nnop\n",
        )
        for (source in cases) {
            val file = withContext(Dispatchers.EDT) { fixture.configureByText("entry.asm", source) }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            readAction {
                val target = externals(file).last()
                assertTrue(usages(target).isEmpty())
                assertTrue(conflicts(target, "updated", emptyList()).contains("written external allocation"))
                assertEquals(source, file.text)
            }
        }
    }

    @Test
    fun `native rename preserves a same-name local when renaming a project export`() = timeoutRunBlocking<Unit> {
        val application = ".text\njal target # target\n"
        val library = $$".text\n.globl target\ntarget: jr $ra\n"
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
            val provider = fixture.addFileToProject("library.s", $$".globl target\ntarget: jr $ra\n")
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
            assertEquals($$".globl updated\nupdated: jr $ra\n", provider.text)
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
            assertTrue(conflicts(target, "updated", usages(target)).contains("written declaration"))
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
            Triple("main: nop\n", "next", "written declaration"),
            Triple(".macro unused (%label)\n%label: nop\n.end_macro\n", "next", "written declaration"),
            Triple(".macro unused\ntarget: nop\n.end_macro\ntarget: nop\n", "next", "written declaration"),
            Triple(".macro unused\ntarget: nop\n.end_macro\n.macro define (%name)\n%name: nop\n.end_macro\ndefine(target)\n", "next", "written declaration"),
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
            val validator = MipsSymbolRenameInputValidator()
            assertTrue(validator.pattern.accepts(target))
            assertFalse(processor.canProcessElement(scratch))
            assertTrue(processor.canProcessElement(constant))
            assertTrue(validator.pattern.accepts(constant))
            assertFalse(processor.isInplaceRenameSupported)
            assertTrue(validator.isInputValid("valid.name", target, ProcessingContext()))
            assertTrue(usages(target).isEmpty())
            assertTrue(conflicts(target, "next", emptyList()).contains("written declaration"))
        }
    }

    @Test
    fun `native rename edits the written replacement for chained equivalences and forwarded arguments`() = timeoutRunBlocking<Unit> {
        val source = $$"""
            .include "aliases.txt"
            .data
            target: .word 42
            .word DEST, DEST, target
            .text
            .macro load (%address)
                lw $t0, %address
            .end_macro
            load (THROUGH)
            LOAD
        """.trimIndent()
        val aliases =
          $$".eqv DECLARE .eqv DEST target # target\nDECLARE\n.eqv THROUGH DEST\n.eqv LOAD lw $a0, THROUGH\n"
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
            val conflict = conflicts(target, "updated", sites)
            assertTrue(conflict.contains("could not assemble"), conflict)
            assertEquals("target: nop\n", shared.text)
        }
    }

    @Test
    fun `native rename verifies application edits with a configured exception handler`() = timeoutRunBlocking<Unit> {
        val application = $$".text\nj target\ntarget: teq $zero, $zero\n"
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
            val operand = fixture.addFileToProject("operand.txt", $$".ktext 0x80000180\nla $k0, target\njr $k0\n")
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
            assertTrue(operand.text.contains($$"la $k0, target"))
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

    private fun macros(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)
        .filter { it.kind == SymbolKind.MACRO }

    private fun externals(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)
        .filter { it.kind == SymbolKind.EXTERNAL }

    private fun constants(file: PsiFile) = PsiTreeUtil.findChildrenOfType(file, MipsDeclaration::class.java)
        .filter { it.kind == SymbolKind.CONSTANT }

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
