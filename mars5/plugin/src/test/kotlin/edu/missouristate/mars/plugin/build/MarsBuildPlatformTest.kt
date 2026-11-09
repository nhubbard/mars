package edu.missouristate.mars.plugin.build

import com.intellij.build.BuildViewManager
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
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
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import edu.missouristate.mars.plugin.language.MipsFile
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
    fun `successful assembly navigates generated labels until a captured include changes`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val source = """
                .include "definitions.s"
                emit (generated)
                emit (privateLabel)
                reserve (storage)
                j generated
                j privateLabel
                la ${'$'}t0, storage
                .globl generated
            """.trimIndent()
            val body = ".macro emit (%entry)\n  %entry: nop\n.end_macro\n" +
                ".macro reserve (%name)\n  .extern %name 4\n.end_macro\n"
            val offset = source.indexOf("j generated") + 3
            val (main, include) = withContext(Dispatchers.EDT) {
                val entry = fixture.addFileToProject("main.asm", source).virtualFile
                val definitions = fixture.addFileToProject("definitions.s", "# unchanged on disk\n").virtualFile
                edit(definitions, body)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                fixture.configureFromExistingVirtualFile(entry)
                fixture.editor.caretModel.moveToOffset(offset)
                entry to definitions
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            suspend fun assemble() {
                invokeAction(main)
                assertInstanceOf(StartBuildEvent::class.java, events.receive())
                assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
            }
            fun target(at: Int = offset): PsiElement? = fixture.file.findReferenceAt(at)?.resolve()
            readAction { assertNull(target()) }
            assemble()
            readAction {
                val declaration = GotoDeclarationAction.findTargetElement(project, fixture.editor, offset)!!
                assertEquals(include, declaration.containingFile.virtualFile)
                assertEquals("%entry", declaration.text)
                assertEquals(body.lastIndexOf("%entry"), declaration.textOffset)
                assertEquals(declaration, target(source.lastIndexOf("generated")))
                assertEquals(declaration, target(source.lastIndexOf("privateLabel")))
                val storage = source.lastIndexOf("storage")
                assertEquals(".extern", target(storage)?.text)
                val file = fixture.file as MipsFile
                val analysis = project.service<MarsAnalysisService>()
                assertTrue(analysis.labelTargets(file, file.findElementAt(storage)!!, allowExternal = false).isEmpty())
                assertTrue(analysis.labelTargets(file, file.findElementAt(source.indexOf("emit"))!!, allowExternal = true).isEmpty())
            }
            val moved = "# moved in the editor\n$body"
            withContext(Dispatchers.EDT) {
                edit(include, moved)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            readAction { assertNull(target()) }
            assemble()
            readAction { assertEquals(moved.lastIndexOf("%entry"), target()?.textOffset) }
            val parent = withContext(Dispatchers.EDT) {
                include.parent.also { WriteCommandAction.runWriteCommandAction(project) { include.delete(this) } }
            }
            readAction { assertNull(target()) }
            withContext(Dispatchers.EDT) {
                WriteCommandAction.runWriteCommandAction(project) { parent.createChildDirectory(this, "definitions.s") }
            }
            readAction { assertNull(target()) }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `assembled targets observe committed PSI file type and the latest build request`() = timeoutRunBlocking<Unit> {
        val (entry, other, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("entry.asm", ".include \"macro.s\"\nemit (generated)\nj generated\n")
            val unrelated = fixture.addFileToProject("other.asm", "j generated\n")
            val definitions = fixture.addFileToProject("macro.s", ".macro emit (%entry)\n%entry: nop\n.end_macro\n")
            Triple(main, unrelated, definitions)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val analysis = project.service<MarsAnalysisService>()
        val path = entry.virtualFile.toNioPath()
        val includePath = include.virtualFile.toNioPath()
        val capture = captureAssemblySources(listOf(path), null)
        val component = MarsSessionService.createMarsComponent()
        val program = component.context().use { capture.assembleIn(component).program() }
        val older = analysis.beginSymbolBuild(path)
        val current = analysis.beginSymbolBuild(path)
        analysis.publishBuiltSymbols(older, capture.revisions, program.symbolMap())
        fun target(file: PsiElement) = file.containingFile.findReferenceAt(file.text.lastIndexOf("generated"))?.resolve()
        readAction { assertNull(target(entry)) }
        analysis.publishBuiltSymbols(current, capture.revisions, program.symbolMap())
        readAction {
            assertEquals("%entry", target(entry)?.text)
            assertNull(target(other))
        }
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), capture.revisions - setOf(includePath), program.symbolMap())
        readAction { assertNull(target(entry)) }

        val plain = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("macro.txt", include.text).virtualFile
        }
        val plainPath = plain.toNioPath()
        withContext(Dispatchers.EDT) {
            val file = entry.virtualFile
            edit(file, entry.text.replace("macro.s", "macro.txt"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val plainCapture = captureAssemblySources(listOf(path), null)
        val plainComponent = MarsSessionService.createMarsComponent()
        val plainProgram = plainComponent.context().use { plainCapture.assembleIn(plainComponent).program() }
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), plainCapture.revisions, plainProgram.symbolMap())
        assertEquals(plainPath, plainProgram.symbolMap().locals()[path]?.get("generated")?.origin()?.location()?.file())
        readAction { assertNull(target(entry)) }

        val changedText = "# still uncommitted\n" + capture.snapshot.texts().getValue(includePath)
        val changed = AssemblySourceSnapshot(listOf(path), capture.snapshot.texts() + (includePath to changedText))
        val changedComponent = MarsSessionService.createMarsComponent()
        val changedResult = changedComponent.context().use {
            changedComponent.assembler().assembleSnapshot(changed, null, true, false)
        }
        assertTrue(changedResult.succeeded())
        withContext(Dispatchers.EDT) {
            edit(entry.virtualFile, capture.snapshot.texts().getValue(path))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            val documents = FileDocumentManager.getInstance()
            val mainDocument = documents.getDocument(entry.virtualFile)!!
            val includeDocument = documents.getDocument(include.virtualFile)!!
            WriteCommandAction.runWriteCommandAction(project) {
                includeDocument.setText(changedText)
                val revisions = mapOf(path to mainDocument.modificationStamp, includePath to includeDocument.modificationStamp)
                analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), revisions, changedResult.program().symbolMap())
                assertFalse(PsiDocumentManager.getInstance(project).isCommitted(includeDocument))
                assertNull(target(entry))
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction { assertEquals("%entry", target(entry)?.text) }
    }

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
    fun `circular equivalences keep valid editor syntax and link a build error to the original use`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val use = "\tOPERATION \$t0, FIRST"
            val source = ".eqv OPERATION li\n.eqv FIRST SECOND\n.eqv SECOND FIRST + 1\n$use\n"
            val (main, include) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\nnop\n").virtualFile
                val include = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(include, source)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                main to include
            }
            readAction {
                val psi = PsiManager.getInstance(project).findFile(include)!!
                assertNull(PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
                assertEquals(source, psi.text)
            }

            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val diagnostic = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, diagnostic.parentId)
            assertEquals(include.toNioPath(), diagnostic.filePosition.path)
            assertEquals(3, diagnostic.filePosition.startLine)
            assertEquals(use.indexOf("FIRST"), diagnostic.filePosition.startColumn)
            assertTrue(diagnostic.message.contains("FIRST -> SECOND -> FIRST"))
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, diagnostic.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(source.lastIndexOf("FIRST"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
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
