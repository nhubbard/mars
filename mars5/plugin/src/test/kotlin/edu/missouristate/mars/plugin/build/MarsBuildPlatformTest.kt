package edu.missouristate.mars.plugin.build

import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import edu.missouristate.mars.plugin.language.MipsFileType
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@TestApplication
class MarsBuildPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()

    @Test
    fun `registered action assembles unsaved sources and reports clickable diagnostics`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val source = "\tétiquette: j missing\n"
            val (main, include) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\n").virtualFile
                val child = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(child, source)
                main to child
            }
            val session = project.getService(MarsSessionService::class.java).context
            val existingProgram = session.assemble(listOf(include.toNioPath()))

            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            assertEquals("Assemble main.asm", start.buildDescriptor.title)
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertEquals(include.toNioPath(), error.filePosition.path)
            assertEquals(0, error.filePosition.startLine)
            assertEquals(source.indexOf("missing"), error.filePosition.startColumn)
            assertTrue(error.message.contains("missing"))
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(source.indexOf("missing"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
            assertSame(existingProgram, session.program)
            assertEquals("nop\n", Files.readString(include.toNioPath()))

            withContext(Dispatchers.EDT) { edit(include, "nop\n") }
            invokeAction(main)
            val next = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val done = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(next.id, done.id)
            assertInstanceOf(SuccessResult::class.java, done.result)
            assertSame(existingProgram, session.program)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `action only accepts valid local MIPS sources`() = timeoutRunBlocking<Unit> {
        val (text, removed) = withContext(Dispatchers.EDT) {
            val text = fixture.addFileToProject("sample.txt", "nop\n").virtualFile
            val removed = fixture.addFileToProject("removed.s", "nop\n").virtualFile
            WriteCommandAction.runWriteCommandAction(project) { removed.delete(this) }
            text to removed
        }
        val action = assertInstanceOf(
            MarsAssembleAction::class.java, ActionManager.getInstance().getAction("Mars.AssembleCurrentFile"),
        )
        readAction {
            assertEquals("BGT", action.actionUpdateThread.name)
            val noProject = event(action, null, null)
            action.update(noProject)
            assertFalse(noProject.presentation.isEnabled)
            for (file in listOf(null, text, text.parent, LightVirtualFile("light.s", MipsFileType, "nop"))) {
                val invocation = event(action, project, file)
                action.update(invocation)
                assertFalse(invocation.presentation.isEnabled)
                assertFalse(invocation.presentation.isVisible)
            }
            // A retained action context can supply a file which was deleted after selection.
            val retainedSelection = DataContext { name ->
                when (name) {
                    CommonDataKeys.PROJECT.name -> project
                    CommonDataKeys.VIRTUAL_FILE.name -> removed
                    else -> null
                }
            }
            val invalid = AnActionEvent.createEvent(action, retainedSelection, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
            action.update(invalid)
            assertFalse(invalid.presentation.isEnabled)
        }
        withContext(Dispatchers.EDT) {
            action.actionPerformed(event(action, null, null))
            action.actionPerformed(event(action, project, null))
        }
    }

    @Test
    fun `cancellation emits a single skipped finish after start`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val entry = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("cancel.asm", "nop\n").virtualFile.toNioPath()
            }
            val job = async(start = CoroutineStart.LAZY) {
                project.getService(MarsBuildService::class.java).assemble(entry)
            }
            val events = recordBuildEvents(disposable) { if (it is StartBuildEvent) job.cancel() }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(SkippedResult::class.java, finish.result)
            assertTrue(events.tryReceive().isFailure)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `cancelled build coroutine stops repeated data writes inside the assembler`() = timeoutRunBlocking<Unit> {
        val entry = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("repeat.asm", ".data\n.word 7:200\n.text\nnop\n").virtualFile.toNioPath()
        }
        val capture = captureAssemblySources(listOf(entry), null)
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val build = async(start = CoroutineStart.LAZY) { capture.assembleIn(component) }
            val firstWord = session.memory.dataBaseAddress
            session.memory.addObserver({ build.cancel() }, firstWord)
            build.start()
            build.join()
            assertTrue(build.isCancelled)
            assertEquals(7, session.memory.getWordNoNotify(firstWord))
            assertEquals(0, session.memory.getWordNoNotify(firstWord + 4))
            assertNull(session.program)
        }
    }

    @Test
    fun `unavailable or stale diagnostic positions remain messages without misleading links`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("positions.asm", "nop\n").virtualFile
        }
        readAction {
            val path = file.toNioPath()
            val document = FileDocumentManager.getInstance().getDocument(file)!!
            val stamps = mapOf(path to document.modificationStamp)
            val id = Any()
            val warning = AssemblyError(path, 1, 1, "Warning", true)
            val fresh = diagnosticEvent(project, id, warning, stamps)
            assertInstanceOf(FileMessageEvent::class.java, fresh)
            assertEquals("WARNING", fresh.kind.name)
            for ((diagnostic, revisions) in listOf(
                warning to emptyMap(),
                warning to mapOf(path to (document.modificationStamp + 1)),
                AssemblyError(path, 5, 1, "Past the last line", false) to stamps,
                AssemblyError(path, 0, 0, "Unknown line", false) to stamps,
                AssemblyError(path.resolveSibling("absent.s"), 1, 1, "Missing", false) to stamps,
                AssemblyError(null, 0, 0, "Unknown", false) to stamps,
            )) {
                val event = diagnosticEvent(project, id, diagnostic, revisions)
                assertFalse(event is FileMessageEvent)
                assertNull(event.getNavigatable(project))
                assertEquals(diagnostic.message(), event.message)
            }
        }
    }

    private suspend fun invokeAction(file: VirtualFile) {
        val action = assertInstanceOf(
            MarsAssembleAction::class.java, ActionManager.getInstance().getAction("Mars.AssembleCurrentFile"),
        )
        val invocation = event(action, project, file)
        readAction {
            action.update(invocation)
            assertTrue(invocation.presentation.isEnabledAndVisible)
        }
        withContext(Dispatchers.EDT) { action.actionPerformed(invocation) }
    }

    private fun event(action: MarsAssembleAction, project: Project?, file: VirtualFile?): AnActionEvent {
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.VIRTUAL_FILE, file).build()
        return AnActionEvent.createEvent(action, context, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
    }

    private fun edit(file: VirtualFile, text: String) {
        val document = FileDocumentManager.getInstance().getDocument(file)!!
        WriteCommandAction.runWriteCommandAction(project) { document.setText(text) }
    }

    private fun recordBuildEvents(disposable: Disposable, onEvent: (BuildEvent) -> Unit = {}): Channel<BuildEvent> {
        val events = Channel<BuildEvent>(Channel.UNLIMITED)
        project.replaceService(BuildViewManager::class.java, object : BuildViewManager(project) {
            override fun onEvent(buildId: Any, event: BuildEvent) {
                onEvent(event)
                check(events.trySend(event).isSuccess)
            }
        }, disposable)
        return events
    }
}
