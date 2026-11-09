package edu.missouristate.mars.plugin.language

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
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsBuildService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
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
        }
        assertSame(program, session.program)
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
        suspend fun current(result: MipsAssemblyAnnotator.Result) = readAction { annotator.currentDiagnostics(part, result) }

        val input = collect()!!
        assertEquals(listOf(main.toNioPath()), input.sources.snapshot.entries())
        val original = analyze(input)
        assertEquals(TextRange(2, 9), current(original).single().second)
        val unknownPosition = AssemblyError(input.source, 99, 0, "unknown position", false)
        assertEquals(1, current(original.copy(diagnostics = original.diagnostics + unknownPosition)).size)
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
        assertEquals(main.toNioPath(), selected.input.selectedEntry)
        assertEquals(1, current(selected).size)
        project.service<MarsBuildService>().assemble(other.toNioPath())
        assertTrue(current(selected).isEmpty())
        val switched = analyze(collect()!!)
        assertEquals(other.toNioPath(), switched.input.selectedEntry)
        assertEquals(1, current(switched).size)
        readAction {
            val unrelated = PsiManager.getInstance(project).findFile(dependency)!!
            val inferred = annotator.collectInformation(unrelated)!!
            assertNull(inferred.selectedEntry)
            assertEquals(listOf(main.toNioPath()), inferred.sources.snapshot.entries())
        }
        withContext(Dispatchers.EDT) {
            fixture.addFileToProject("new-file.txt", "")
        }
        assertTrue(current(switched).isEmpty())

        withContext(Dispatchers.EDT) { edit(other, "nop\n") }
        assertEquals(listOf(main.toNioPath()), collect()!!.sources.snapshot.entries())
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
