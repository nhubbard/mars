package edu.missouristate.mars.plugin.language

import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.util.elementType
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@TestApplication
class MipsAssemblyAnnotatorPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val annotator = MipsAssemblyAnnotator()

    @Test
    fun `entry and include editors reuse one captured assembly until a source changes`() = timeoutRunBlocking<Unit> {
        val (main, include) = withContext(Dispatchers.EDT) {
            val part = fixture.addFileToProject("part.s", "j missing\n") as MipsFile
            val entry = fixture.addFileToProject("entry.s", ".set noat\n.include \"part.s\"\n") as MipsFile
            entry to part
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        suspend fun collect(file: MipsFile) = readAction { annotator.collectInformation(file)!! }
        suspend fun analyze(input: MipsAssemblyAnnotator.Input) =
            withContext(Dispatchers.Default) { annotator.doAnnotate(input) }

        val entryInput = collect(main)
        val partInput = collect(include)
        assertSame(entryInput.capture.sources, partInput.capture.sources)
        val entryResult = analyze(entryInput)
        val partResult = analyze(partInput)
        assertSame(entryResult.outcome, partResult.outcome)
        assertTrue(entryResult.diagnostics.single().isWarning)
        assertTrue(partResult.diagnostics.single().message().contains("missing"))
        assertNull(partResult.symbols)

        withContext(Dispatchers.EDT) { edit(include.virtualFile, "defined: nop\n") }
        val changed = collect(include)
        assertNotSame(partInput.capture.sources, changed.capture.sources)
        val corrected = analyze(changed)
        assertNotSame(partResult.outcome, corrected.outcome)
        assertTrue(corrected.diagnostics.isEmpty())
        assertEquals(setOf("defined"), corrected.symbols?.localSymbols?.keys)
        assertSame(corrected.outcome, analyze(collect(main)).outcome)

        withContext(Dispatchers.EDT) { fixture.addFileToProject("new-file.txt", "") }
        val afterCreation = collect(main)
        assertEquals(changed.capture.sources.revisions, afterCreation.capture.sources.revisions)
        assertNotSame(corrected.outcome, analyze(afterCreation).outcome)
    }

    @Test
    fun `background assembly follows option changes for the same selected entry`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("delay-layout.asm", """
                .macro gen (%label)
                %label: nop
                .end_macro
                .set noat
                div ${'$'}t0, ${'$'}t1, ${'$'}t2
                marker: nop
                gen(generated)
                j generated
            """.trimIndent()) as MipsFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val path = file.virtualFile.toNioPath()
        val build = project.service<MarsBuildService>()
        suspend fun analyze(): MipsAssemblyAnnotator.Result {
            val input = readAction { annotator.collectInformation(file)!! }
            return withContext(Dispatchers.Default) { annotator.doAnnotate(input) }
        }
        build.assemble(path)
        val withoutDelay = analyze()
        assertEquals(1, readAction { annotator.acceptResult(file, withoutDelay).size })
        val original = requireNotNull(withoutDelay.symbols).localSymbols.getValue("marker").address()

        val options = MarsExecutionOptions(delayedBranching = true)
        val analysis = project.service<MarsAnalysisService>()
        suspend fun names() = readAction {
            analysis.labelCompletionNames(file, file.findElementAt(file.text.lastIndexOf("generated"))!!, "generated")
        }
        assertEquals(setOf("generated"), names())
        val pending = analysis.beginSymbolBuild(path, options)
        analysis.publishBuiltSymbols(pending, withoutDelay.input.capture.sources, AssemblySymbolMap.EMPTY)
        assertTrue(names().isEmpty())

        val component = MarsSessionService.createMarsComponent()
        val assembledAddress = component.context().use { session ->
            assertTrue(build.assemble(path, component, activateBuildView = false, options = options))
            session.program.symbolMap().locals().getValue(path).getValue("marker").address()
        }
        val delayed = analyze()
        assertEquals(options, delayed.input.capture.options)
        assertSame(withoutDelay.input.capture.sources, delayed.input.capture.sources)
        assertNotSame(withoutDelay.outcome, delayed.outcome)
        assertEquals(original + 4, assembledAddress)
        assertEquals(assembledAddress, requireNotNull(delayed.symbols).localSymbols.getValue("marker").address())
        assertTrue(readAction { annotator.acceptResult(file, withoutDelay).isEmpty() })
        assertEquals(1, readAction { annotator.acceptResult(file, delayed).size })
        assertEquals(setOf("generated"), names())
    }

    @Test
    fun `background highlighting makes generated labels available without a build`() = timeoutRunBlocking<Unit> {
        val source = ".include \"defs.s\"\nemit (generated)\nemit (generatedOther)\nj generated\n"
        val (entry, unrelated) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("defs.s", ".macro emit (%entry)\n%entry: nop\n.end_macro\n")
            val entry = fixture.addFileToProject("entry.asm", source).virtualFile
            val unrelated = fixture.addFileToProject("unrelated.s", "j gen\n") as MipsFile
            fixture.configureFromExistingVirtualFile(entry)
            entry to unrelated
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val file = fixture.file as MipsFile
        val analysis = project.service<MarsAnalysisService>()
        val operand = source.lastIndexOf("generated")
        suspend fun target() = readAction {
            file.findReferenceAt(operand)?.resolve()?.let { it.text to it.containingFile.name }
        }
        suspend fun names() = readAction { analysis.labelCompletionNames(file, file.findElementAt(operand)!!, "gen") }
        suspend fun highlight() = withContext(Dispatchers.EDT) { fixture.doHighlighting() }

        assertNull(target())
        val captured = readAction { annotator.collectInformation(file)!! }
        val older = withContext(Dispatchers.Default) { annotator.doAnnotate(captured) }
        highlight()
        assertEquals("%entry" to "defs.s", target())
        assertEquals(setOf("generated", "generatedOther"), names())
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                assertTrue(analysis.labelCompletionNames(file, file.findElementAt(operand)!!, "gen").isEmpty())
            }
        }
        readAction {
            assertTrue(analysis.labelCompletionNames(unrelated, unrelated.findElementAt(2)!!, "gen").isEmpty())
        }

        withContext(Dispatchers.EDT) { edit(entry, source.substring(0, operand) + "gen\n") }
        highlight()
        assertNull(target())
        withContext(Dispatchers.EDT) {
            fixture.editor.caretModel.moveToOffset(operand + 3)
            fixture.completeBasic()
            assertEquals(setOf("generated", "generatedOther"), fixture.lookupElementStrings.orEmpty().toSet())
            fixture.lookup.hideLookup(true)
        }

        val replacement = source.replace("generated", "genFreshX")
        withContext(Dispatchers.EDT) { edit(entry, replacement) }
        assertTrue(names().isEmpty())
        highlight()
        assertEquals(setOf("genFreshX", "genFreshXOther"), names())
        readAction { assertTrue(annotator.acceptResult(file, older).isEmpty()) }
        assertEquals(setOf("genFreshX", "genFreshXOther"), names())
        assertEquals("%entry" to "defs.s", target())
    }

    @Test
    fun `background symbols respect ambiguity and refresh the selected entry`() = timeoutRunBlocking<Unit> {
        val selectedSource = "target: nop\n.include \"part.s\"\n"
        val (first, second) = withContext(Dispatchers.EDT) {
            val part = fixture.addFileToProject("part.s", "j target\n").virtualFile
            val first = fixture.addFileToProject("first.s", selectedSource).virtualFile
            val second = fixture.addFileToProject("second.s", "nop\n").virtualFile
            fixture.configureFromExistingVirtualFile(part)
            fixture.editor.caretModel.moveToOffset(3)
            first to second
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        suspend fun target() = readAction {
            GotoDeclarationAction.findTargetElement(project, fixture.editor, 3)
                ?.let { it.containingFile.virtualFile to it.textOffset }
        }
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        assertEquals(first, target()?.first)
        withContext(Dispatchers.EDT) { edit(second, selectedSource) }
        assertNull(target())

        project.service<MarsBuildService>().assemble(second.toNioPath())
        assertEquals(second, target()?.first)
        val leading = "# moved\n"
        withContext(Dispatchers.EDT) { edit(second, leading + selectedSource) }
        assertEquals(second to leading.length, target())
        withContext(Dispatchers.EDT) { fixture.doHighlighting() }
        assertEquals(second to leading.length, target())
        withContext(Dispatchers.EDT) { edit(second, selectedSource.replace("target:", "other:")) }
        assertNull(target())
    }

    @Test
    fun `native editor highlighting shows assembler errors and warnings and clears them on edits`() = timeoutRunBlocking<Unit> {
        val source = ".set noat\n.include \"included.s\"\nj missing\n"
        val (main, include) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("entry.asm", source).virtualFile
            val included = fixture.addFileToProject("included.s", ".set noat\nknown: nop\n").virtualFile
            fixture.configureFromExistingVirtualFile(entry)
            entry to included
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val session = project.service<MarsSessionService>().context
        val program = session.assemble(listOf(include.toNioPath()))
        withContext(Dispatchers.EDT) {
            val highlights = fixture.doHighlighting()
            val undefined = highlights.single { it.description?.contains("missing") == true }
            assertEquals(HighlightSeverity.ERROR, undefined.severity)
            assertEquals("missing", source.substring(undefined.startOffset, undefined.endOffset))
            val warning = highlights.single { it.description?.contains("ignores the .set directive") == true }
            assertEquals(HighlightSeverity.WARNING, warning.severity)
            assertEquals(".set", source.substring(warning.startOffset, warning.endOffset))
            edit(main, ".include \"included.s\"\nj known\n")
            assertTrue(fixture.doHighlighting().none {
                it.description?.contains("missing") == true || it.description?.contains("ignores the .set directive") == true
            })
            edit(include, "known: nop\nj missing\n")
            assertTrue(fixture.doHighlighting().none { it.description?.contains("missing") == true })
        }
        assertSame(program, session.program)
    }

    @Test
    fun `only a consumed unfinished quote produces an editor error`() = timeoutRunBlocking<Unit> {
        val unfinished = "\"data"
        val source = ".set \"ignored\n.data\n.asciiz $unfinished\n.text\nnext: nop\n"
        val main = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("quotes.asm", source).virtualFile.also(fixture::configureFromExistingVirtualFile)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val error = fixture.doHighlighting().single { it.severity == HighlightSeverity.ERROR }
            assertEquals("Unterminated string literal", error.description)
            assertEquals(unfinished, source.substring(error.startOffset, error.endOffset))
            edit(main, source.replace(unfinished, "$unfinished\""))
            assertTrue(fixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })

            val deferred = ".macro later\n.asciiz $unfinished\n.end_macro\n.data\n.text\nnext: nop\n"
            edit(main, deferred)
            assertTrue(fixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })
            val invoked = deferred.replace(".data\n", ".data\nlater\n")
            edit(main, invoked)
            val expanded = fixture.doHighlighting().single { it.severity == HighlightSeverity.ERROR }
            assertEquals("Unterminated string literal", expanded.description)
            assertEquals(unfinished, invoked.substring(expanded.startOffset, expanded.endOffset))
        }
    }

    @Test
    fun `inherited circular equivalences highlight their physical use before a build`() = timeoutRunBlocking<Unit> {
        val definitions = ".eqv OPERATION li\n.eqv FIRST SECOND\n.eqv SECOND FIRST + 1\n"
        val source = "\tOPERATION \$t0, FIRST # original operand\n"
        val parent = withContext(Dispatchers.EDT) {
            val parent = fixture.addFileToProject("entry.asm", "${definitions}.include \"part.s\"\n")
            val part = fixture.addFileToProject("part.s", source)
            fixture.configureFromExistingVirtualFile(part.virtualFile)
            parent.virtualFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val error = fixture.doHighlighting().single { it.severity == HighlightSeverity.ERROR }
            assertEquals("Circular .eqv expansion FIRST -> SECOND -> FIRST", error.description)
            assertEquals(source.indexOf("FIRST"), error.startOffset)
            assertEquals("FIRST", source.substring(error.startOffset, error.endOffset))
            edit(parent, "${definitions.replace("SECOND FIRST + 1", "SECOND 7")}.include \"part.s\"\n")
            assertTrue(fixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })
        }
    }

    @Test
    fun `editor marks the invalid literal while assembly retains the error at its alias use`() = timeoutRunBlocking<Unit> {
        val source = ".eqv BROKEN 'ab'\n.data\n.word BROKEN, 7\n"
        val file = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("characters.asm", source).virtualFile
            fixture.configureFromExistingVirtualFile(file)
            file
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val input = readAction { requireNotNull(annotator.collectInformation(fixture.file)) }
        val assembled = withContext(Dispatchers.Default) { annotator.doAnnotate(input) }
        val diagnostics = readAction { annotator.acceptResult(fixture.file as MipsFile, assembled) }
        assertEquals(listOf(source.indexOf("'ab'"), source.lastIndexOf("BROKEN")), diagnostics.map { it.second.startOffset })
        withContext(Dispatchers.EDT) {
            val error = fixture.doHighlighting().single { it.severity == HighlightSeverity.ERROR }
            assertEquals(source.indexOf("'ab'"), error.startOffset)
            assertEquals("Invalid MARS character literal", error.description)
            edit(file, source.replace("'ab'", "'a'"))
            assertTrue(fixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })
        }
    }

    @Test
    fun `unfinished include paths keep highlighting live and resolve when closed`() = timeoutRunBlocking<Unit> {
        val (main, include) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("in-progress.asm", ".include \"\ntail: nop\n").virtualFile
            val part = fixture.addFileToProject("part.s", "target: nop\n").virtualFile
            fixture.configureFromExistingVirtualFile(entry)
            entry to part
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val file = fixture.file as MipsFile
        val entryPath = main.toNioPath()
        val includePath = include.toNioPath()
        withContext(Dispatchers.EDT) {
            for (unfinished in listOf("\"", "\"part.sx")) {
                val source = ".include $unfinished\ntail: nop\n"
                edit(main, source)
                val input = readAction { requireNotNull(annotator.collectInformation(file)) }
                assertEquals(setOf(entryPath), input.capture.sources.revisions.keys)
                val result = withContext(Dispatchers.Default) { annotator.doAnnotate(input) }
                val (error, range) = readAction { annotator.acceptResult(file, result).single() }
                assertEquals("Unterminated .include path", error.message())
                assertEquals(unfinished, range.substring(source))
                assertTrue(fixture.doHighlighting().any { it.severity == HighlightSeverity.ERROR })
                assertEquals(MipsTypes.psi("OPERATION"), file.findElementAt(source.lastIndexOf("nop"))?.parent?.elementType)
            }
            val completed = ".include \"part.s\"\nj target\n"
            edit(main, completed)
            val captured = readAction { requireNotNull(annotator.collectInformation(file)).capture.sources }
            assertEquals(setOf(entryPath, includePath), captured.revisions.keys)
            assertTrue(fixture.doHighlighting().none { it.severity == HighlightSeverity.ERROR })
            assertEquals(include, file.findReferenceAt(completed.indexOf("part.s"))?.resolve()?.containingFile?.virtualFile)
        }
    }

    @Test
    fun `analysis follows unique or selected entries and rejects obsolete include results`() = timeoutRunBlocking<Unit> {
        val (main, other, dependency) = withContext(Dispatchers.EDT) {
            val first = fixture.addFileToProject("first.s", ".include \"dependency.s\"\n.include \"part.s\"\n").virtualFile
            val second = fixture.addFileToProject("second.s", "nop\n").virtualFile
            val dependency = fixture.addFileToProject("dependency.s", "nop\n").virtualFile
            val part = fixture.addFileToProject("part.s", "j missing\n").virtualFile
            fixture.configureFromExistingVirtualFile(part)
            Triple(first, second, dependency)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val part = fixture.file as MipsFile
        suspend fun collect() = readAction { annotator.collectInformation(part) }
        suspend fun analyze(input: MipsAssemblyAnnotator.Input) =
            withContext(Dispatchers.Default) { annotator.doAnnotate(input) }
        suspend fun current(result: MipsAssemblyAnnotator.Result) = readAction { annotator.acceptResult(part, result) }

        val input = collect()!!
        assertEquals(listOf(main.toNioPath()), input.capture.sources.snapshot.entries())
        val original = analyze(input)
        assertEquals(TextRange(2, 9), current(original).single().second)
        val unknownPosition = AssemblyError(input.capture.source, 99, 0, "unknown position", false)
        val incomplete = original.outcome.copy(diagnostics = mapOf(input.capture.source to (original.diagnostics + unknownPosition)))
        assertEquals(1, current(original.copy(outcome = incomplete)).size)
        val cancelled = async(Dispatchers.Default) {
            currentCoroutineContext().job.cancel()
            assertThrows<ProcessCanceledException> { annotator.doAnnotate(input) }
        }
        cancelled.join()
        assertTrue(cancelled.isCancelled)
        withContext(Dispatchers.EDT) { edit(dependency, "# edited include\nnop\n") }
        assertTrue(current(original).isEmpty())

        val newer = analyze(collect()!!)
        withContext(Dispatchers.EDT) { edit(other, ".include \"part.s\"\n") }
        assertTrue(current(newer).isEmpty())
        assertNull(collect())

        project.service<MarsBuildService>().assemble(main.toNioPath())
        val selected = analyze(collect()!!)
        assertEquals(main.toNioPath(), selected.input.capture.selectedAssembly?.entry)
        assertEquals(1, current(selected).size)
        project.service<MarsBuildService>().assemble(other.toNioPath())
        assertTrue(current(selected).isEmpty())
        val switched = analyze(collect()!!)
        assertEquals(other.toNioPath(), switched.input.capture.selectedAssembly?.entry)
        assertEquals(1, current(switched).size)
        readAction {
            val unrelated = PsiManager.getInstance(project).findFile(dependency)!!
            val inferred = annotator.collectInformation(unrelated)!!
            assertNull(inferred.capture.selectedAssembly)
            assertEquals(listOf(main.toNioPath()), inferred.capture.sources.snapshot.entries())
        }
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("new-file.txt", "")
        }
        assertTrue(current(switched).isEmpty())

        withContext(Dispatchers.EDT) { edit(other, "nop\n") }
        assertEquals(listOf(main.toNioPath()), collect()!!.capture.sources.snapshot.entries())
        withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) { other.delete(this) }
        }
        assertEquals(listOf(main.toNioPath()), collect()!!.capture.sources.snapshot.entries())
    }

    @Test
    fun `rootless cycles highlight each participating path and respect the selected build`() = timeoutRunBlocking<Unit> {
        val (first, second, third) = withContext(Dispatchers.EDT) {
            val a = fixture.addFileToProject("a.asm", ".include \"b.s\"\n.include \"leaf.s\"\n.include \"missing.s\"\n")
            val b = fixture.addFileToProject("b.s", ".include \"c.s\"\n")
            val c = fixture.addFileToProject("c.s", ".include \"a.asm\"\n")
            fixture.addFileToProject("leaf.s", ".asciiz \"ordinary string\"\n")
            Triple(a.virtualFile, b.virtualFile, c.virtualFile)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        for ((file, target) in listOf(first to "b.s", second to "c.s", third to "a.asm")) {
            withContext(Dispatchers.EDT) {
                fixture.configureFromExistingVirtualFile(file)
                assertNull(annotator.collectInformation(fixture.file))
                val errors = fixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
                val error = errors.single()
                assertEquals("Recursive .include cycle through $target", error.description)
                assertEquals("\"$target\"", fixture.file.text.substring(error.startOffset, error.endOffset))
            }
        }

        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(first)
            edit(third, "nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val errors = fixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
            assertTrue(errors.single().description.startsWith("Error reading include file"))
            edit(third, ".include \"a.asm\"\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val errors = fixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
            assertEquals("Recursive .include cycle through b.s", errors.single().description)
        }

        project.service<MarsBuildService>().assemble(first.toNioPath())
        withContext(Dispatchers.EDT) {
            fixture.configureFromExistingVirtualFile(third)
            val errors = fixture.doHighlighting().filter { it.severity == HighlightSeverity.ERROR }
            assertTrue(errors.single().description.startsWith("Recursive include of file"))
        }
    }

    @Test
    fun `editor diagnostic ranges handle absent positions and columns beyond the line`() = timeoutRunBlocking<Unit> {
        readAction {
            val document = EditorFactory.getInstance().createDocument("nop\nj label\n")
            val error = AssemblyError(null, 2, 3, "error", false)
            assertEquals(TextRange(6, 11), diagnosticRange(document, error))
            assertEquals(TextRange(4, 11), diagnosticRange(document, AssemblyError(null, 2, 50, "error", false)))
            assertNull(diagnosticRange(document, AssemblyError(null, 0, 0, "error", false)))
            assertNull(diagnosticRange(document, AssemblyError(null, 9, 1, "error", false)))
            val files = PsiFileFactory.getInstance(project)
            assertNull(annotator.collectInformation(files.createFileFromText("plain.txt", PlainTextFileType.INSTANCE, "")))
            assertNull(annotator.collectInformation(files.createFileFromText("light.asm", MipsFileType, "nop")))
        }
    }

    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }
}
