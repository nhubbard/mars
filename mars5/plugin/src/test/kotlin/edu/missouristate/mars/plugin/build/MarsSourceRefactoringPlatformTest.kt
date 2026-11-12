package edu.missouristate.mars.plugin.build

import com.intellij.execution.RunManager
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiFileFactory
import com.intellij.refactoring.move.moveFilesOrDirectories.MoveFilesOrDirectoriesProcessor
import com.intellij.refactoring.rename.RenameProcessor
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import edu.missouristate.mars.plugin.run.MarsRunType
import java.nio.file.Path
import kotlin.io.path.invariantSeparatorsPathString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsSourceRefactoringPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val settings get() = project.service<MarsAssemblySettings>()

    @Test
    fun `native directory rename updates ordered inputs handler and explicit run paths with undo and redo`() = timeoutRunBlocking<Unit> {
        val (first, handler, sibling) = withContext(Dispatchers.EDT) {
            Triple(fixture.addFileToProject("app/start.asm", "nop\n"), fixture.addFileToProject("app/handler.txt", ".ktext\nnop\n"),
                fixture.addFileToProject("app-other/last.asm", "nop\n"))
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val oldDir = first.containingDirectory.virtualFile.toNioPath()
            val newDir = oldDir.resolveSibling("renamed")
            val other = sibling.virtualFile.toNioPath()
            val originalSource = oldDir.resolve("start.asm")
            val originalHandler = handler.virtualFile.toNioPath()
            settings.changeSources(listOf(other.toString(), originalSource.toString(), "unresolved/path"))
            settings.changeExceptionHandler(originalHandler.toString())
            val run = createRun("Application", stored(originalSource), stored(oldDir))
            val ordered = createRun("Ordered", null, stored(oldDir)).also { it.options.useProjectSources = true }
            val untouched = createRun("Neighbor", stored(other), "relative")
            fun assertPaths(directory: Path) {
                assertEquals(listOf(other, directory.resolve("start.asm")), settings.projectSources)
                assertEquals("unresolved/path", settings.sourcePaths.sources.last())
                assertEquals(directory.resolve("handler.txt"), settings.exceptionHandler)
                assertEquals(stored(directory.resolve("start.asm")), run.options.sourcePath)
                assertEquals(stored(directory), run.options.workingDirectory)
                assertNull(ordered.options.sourcePath)
                assertEquals(stored(directory), ordered.options.workingDirectory)
                assertEquals(stored(other), untouched.options.sourcePath)
                assertEquals("relative", untouched.options.workingDirectory)
                assertEquals(directory, first.containingDirectory.virtualFile.toNioPath())
            }
            RenameProcessor(project, first.containingDirectory, "renamed", false, false).run()
            assertPaths(newDir)
            withUndo { undo ->
                undo.undo(null)
                assertPaths(oldDir)
                undo.redo(null)
                assertPaths(newDir)
            }
        }
    }

    @Test
    fun `native file moves update the configured input and preserve later settings edits on undo`() = timeoutRunBlocking<Unit> {
        val (source, fallback) = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("src/entry.asm", "nop\n") to fixture.addFileToProject("destination/fallback.asm", "nop\n")
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val original = source.virtualFile.toNioPath()
            val alternative = fallback.virtualFile.toNioPath()
            val destination = fallback.containingDirectory
            val moved = alternative.resolveSibling("entry.asm")
            val run = createRun("Application", stored(original))
            settings.changeSources(listOf(original.toString()))
            settings.changeExceptionHandler(original.toString())

            MoveFilesOrDirectoriesProcessor(project, arrayOf(source), destination, false, false, null, null).run()
            assertEquals(listOf(moved), settings.projectSources)
            assertEquals(moved, settings.exceptionHandler)
            assertEquals(stored(moved), run.options.sourcePath)

            settings.changeSources(listOf(alternative.toString(), moved.toString()))
            settings.changeExceptionHandler(alternative.toString())
            run.options.sourcePath = stored(alternative)
            run.options.workingDirectory = stored(alternative.parent)
            withUndo { undo ->
                undo.undo(null)
                assertEquals(original, source.virtualFile.toNioPath())
                assertEquals(listOf(alternative, original), settings.projectSources)
                assertEquals(alternative, settings.exceptionHandler)
                assertEquals(stored(alternative), run.options.sourcePath)
                assertEquals(stored(alternative.parent), run.options.workingDirectory)
            }
        }
    }

    @Test
    fun `native file rename follows a run-only source and an assembly-only handler`() = timeoutRunBlocking<Unit> {
        val (source, handler, unrelated) = withContext(Dispatchers.EDT) {
            Triple(fixture.addFileToProject("entry.asm", "nop\n"), fixture.addFileToProject("handler.txt", ".ktext\nnop\n"),
                fixture.addFileToProject("other.s", "nop\n"))
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        withContext(Dispatchers.EDT) {
            val initial = source.virtualFile.toNioPath()
            val exception = handler.virtualFile.toNioPath()
            val run = createRun("Application", initial.toString())
            RenameProcessor(project, unrelated, "unused.s", false, false).run()
            assertEquals(initial.toString(), run.options.sourcePath)
            assertNull(settings.exceptionHandler)

            RenameProcessor(project, source, "renamed.asm", false, false).run()
            assertEquals(stored(initial.resolveSibling("renamed.asm")), run.options.sourcePath)
            assertNull(settings.exceptionHandler)
            settings.changeExceptionHandler(exception.toString())
            RenameProcessor(project, handler, "exception.txt", false, false).run()
            assertEquals(exception.resolveSibling("exception.txt"), settings.exceptionHandler)
            assertEquals(stored(initial.resolveSibling("renamed.asm")), run.options.sourcePath)
        }
    }

    @Test
    fun `listener accepts only physical file system items`() = timeoutRunBlocking<Unit> {
        readAction {
            val listener = MarsSourceRefactoringListener()
            val factory = PsiFileFactory.getInstance(project)
            val detached = factory.createFileFromText("scratch.asm", MipsFileType, "nop\n")
            val memory = factory.createFileFromText("memory.asm", MipsFileType, "nop\n", 0L, true)
            assertNull(listener.getListener(detached.firstChild))
            assertNull(listener.getListener(detached))
            assertTrue(!memory.virtualFile!!.isInLocalFileSystem)
            assertNull(listener.getListener(memory))
        }
    }

    private fun createRun(name: String, source: String?, directory: String? = null): MarsRunConfiguration {
        val manager = RunManager.getInstance(project)
        val type = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java)
        val registered = manager.createConfiguration(name, type)
        val run = registered.configuration as MarsRunConfiguration
        run.options.sourcePath = source
        run.options.workingDirectory = directory
        manager.addConfiguration(registered)
        return run
    }

    private fun stored(path: Path): String {
        val projectPath = Path.of(requireNotNull(project.basePath))
        return $$"$PROJECT_DIR$/$${projectPath.relativize(path).invariantSeparatorsPathString}"
    }

    private fun withUndo(action: (UndoManager) -> Unit) {
        val undo = UndoManager.getInstance(project)
        assertTrue(undo.isUndoAvailable(null))
        val previous = TestDialogManager.setTestDialog(TestDialog.OK)
        try {
            action(undo)
        } finally {
            TestDialogManager.setTestDialog(previous)
        }
    }
}
