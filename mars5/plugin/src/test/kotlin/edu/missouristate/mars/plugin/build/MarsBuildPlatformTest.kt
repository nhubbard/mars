package edu.missouristate.mars.plugin.build

import com.intellij.build.BuildViewManager
import com.intellij.build.DefaultBuildDescriptor
import com.intellij.build.events.BuildEvent
import com.intellij.build.events.FailureResult
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.FinishBuildEvent
import com.intellij.build.events.MessageEvent
import com.intellij.build.events.SkippedResult
import com.intellij.build.events.StartBuildEvent
import com.intellij.build.events.SuccessResult
import com.intellij.codeInsight.navigation.actions.GotoDeclarationAction
import com.intellij.codeInsight.lookup.Lookup
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.PathChooserDialog
import com.intellij.openapi.fileChooser.impl.FileChooserFactoryImpl
import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.include.FileIncludeManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.task.ProjectTask
import com.intellij.task.ProjectTaskContext
import com.intellij.task.ProjectTaskManager
import com.intellij.task.ProjectTaskRunner
import com.intellij.task.impl.ProjectTaskList
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.replaceService
import com.intellij.testFramework.TestActionEvent
import com.intellij.ui.ToolbarDecorator
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import edu.missouristate.mars.ApplicationInfo
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.api.AssemblyError
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.MarsExecutionOptions
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.analysis.assembleIn
import edu.missouristate.mars.plugin.analysis.assemblySourceSyntax
import edu.missouristate.mars.plugin.analysis.captureAssemblySources
import edu.missouristate.mars.plugin.analysis.captureBuildSources
import edu.missouristate.mars.plugin.analysis.collectBackgroundAssemblySources
import edu.missouristate.mars.plugin.analysis.collectEditorAssembly
import edu.missouristate.mars.plugin.language.MipsAssemblyAnnotator
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import java.awt.Component
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.HexFormat
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.JList
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.jdom.Element
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

@TestApplication
class MarsBuildPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()

    @Test
    fun `native build task batches report failures and assemble duplicate requests once`() = timeoutRunBlocking<Unit> {
        val (first, broken, last) = withContext(Dispatchers.EDT) {
            fun task(name: String, source: String): MarsBuildTask =
                MarsBuildTask(MarsSourceSet(fixture.addFileToProject(name, source).virtualFile.toNioPath()))
            Triple(task("first.asm", "nop\n"), task("broken.asm", "la \$t0, undefined\n"), task("last.asm", "nop\n"))
        }
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val context = ProjectTaskContext()
            val unrelated = ProjectTask { "Unrelated build task" }
            val runner = ProjectTaskRunner.EP_NAME.findExtensionOrFail(MarsProjectTaskRunner::class.java)
            assertTrue(runner.canRun(project, first, context))
            assertFalse(runner.canRun(project, unrelated, null))
            assertEquals("Assemble first.asm", first.presentableName)
            assertFalse(first.isIncrementalBuild)
            val tasks = ProjectTaskList.asList(first, MarsBuildTask(first.sources), broken, unrelated, last)
            val manager = ProjectTaskManager.getInstance(project)
            val result = manager.run(context, tasks).await()
            assertSame(context, result.context)
            assertFalse(result.isAborted)
            assertTrue(result.hasErrors())
            val received = buildList {
                while (true) add(events.tryReceive().getOrNull() ?: break)
            }
            val starts = received.filterIsInstance<StartBuildEvent>()
            val finishes = received.filterIsInstance<FinishBuildEvent>()
            assertEquals(listOf(first.presentableName, broken.presentableName, last.presentableName), starts.map { it.message })
            assertEquals(starts.map { it.id }, finishes.map { it.id })
            assertInstanceOf(SuccessResult::class.java, finishes[0].result)
            assertInstanceOf(FailureResult::class.java, finishes[1].result)
            assertInstanceOf(SuccessResult::class.java, finishes[2].result)
            assertEquals(1, received.filterIsInstance<FileMessageEvent>().size)
            val success = manager.run(last).await()
            assertFalse(success.isAborted)
            assertFalse(success.hasErrors())
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(disposable) }
        }
    }

    @Test
    fun `opening project entries and includes debounces one assembly and warms the launch image`() = timeoutRunBlocking<Unit> {
        val settings = project.service<MarsAssemblySettings>()
        val request = FileEditorOpenRequest.withFocus(false)
        val (entry, include, library) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("main.asm", ".include \"body.s\"\nla \$a0, library\n").virtualFile
            val include = fixture.addFileToProject("body.s", "nop\n").virtualFile
            val library = fixture.addFileToProject("library.s", ".globl library\nlibrary: nop\n").virtualFile
            TrustedProjects.setProjectTrusted(project, true)
            settings.changeSources(listOf(entry.path, library.path))
            val page = MarsAssemblyConfigurable(project)
            try {
                val form = page.createComponent()
                page.reset()
                val option = UIUtil.findComponentsOfType(form, JCheckBox::class.java)
                    .single { it.text == "Assemble MIPS sources when opened" }
                assertFalse(option.isSelected)
                option.isSelected = true
                page.apply()
                val persisted = XmlSerializer.serialize(settings.getState())
                settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                assertTrue(settings.assembleOnOpen)
            } finally {
                page.disposeUIResources()
            }
            Triple(entry, include, library)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        val opened = project.service<MarsOpenAssembly>()
        try {
            withContext(Dispatchers.EDT) {
                val editors = FileEditorManager.getInstance(project)
                editors.openFile(include, request)
                editors.openFile(entry, request)
                editors.openFile(library, request)
            }
            opened.awaitPending()
            val start = assertInstanceOf(StartBuildEvent::class.java, events.tryReceive().getOrThrow())
            assertEquals("Assemble main.asm", start.message)
            val descriptor = assertInstanceOf(DefaultBuildDescriptor::class.java, start.buildDescriptor)
            assertFalse(descriptor.isActivateToolWindowWhenAdded)
            assertFalse(descriptor.isActivateToolWindowWhenFailed)
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.tryReceive().getOrThrow())
            assertFalse(assertInstanceOf(SuccessResult::class.java, finish.result).isUpToDate)
            assertTrue(events.tryReceive().isFailure)

            val build = project.service<MarsBuildService>()
            val prepared = requireNotNull(build.prepare(requireNotNull(settings.projectSourceSet()), settings.executionOptions, false))
            assertEquals(setOf(entry.toNioPath(), include.toNioPath(), library.toNioPath()), prepared.sources.snapshot.texts().keys)
            assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val launch = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertTrue(assertInstanceOf(SuccessResult::class.java, launch.result).isUpToDate)
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `opening a file respects scope and trust and reports assembly failures without activating Build`() = timeoutRunBlocking<Unit> {
        val settings = project.service<MarsAssemblySettings>()
        val request = FileEditorOpenRequest.withFocus(false)
        val editors = FileEditorManager.getInstance(project)
        val opened = project.service<MarsOpenAssembly>()
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        suspend fun expectQuiet(reason: String) {
            opened.awaitPending()
            assertNull(events.tryReceive().getOrNull(), reason)
        }
        try {
            val (disabled, closed, rejected) = withContext(Dispatchers.EDT) {
                TrustedProjects.setProjectTrusted(project, true)
                val disabled = fixture.addFileToProject("disabled.asm", "nop\n").virtualFile
                val closed = fixture.addFileToProject("closed.asm", "nop\n").virtualFile
                val rejected = fixture.addFileToProject("untrusted.asm", "nop\n").virtualFile
                editors.openFile(disabled, request)
                Triple(disabled, closed, rejected)
            }
            expectQuiet("disabled by default")
            withContext(Dispatchers.EDT) {
                settings.changeAssembleOnOpen(true)
                val plain = fixture.addFileToProject("other.txt", "nop\n").virtualFile
                editors.openFile(plain, request)
                editors.openFile(LightVirtualFile("snapshot.asm", MipsFileType, "nop\n"), request)
                editors.openFile(closed, request)
                editors.closeFile(closed)
                val retagged = fixture.addFileToProject("retagged.asm", "nop\n").virtualFile
                editors.openFile(retagged, request)
                WriteAction.run<RuntimeException> { retagged.rename(this, "retagged.txt") }
                editors.openFile(retagged, request)
                assertTrue(editors.isFileOpen(retagged))
            }
            expectQuiet("non-MIPS, nonlocal, closed and renamed files")
            withContext(Dispatchers.EDT) {
                editors.closeFile(disabled)
                editors.openFile(disabled, request)
                settings.changeAssembleOnOpen(false)
            }
            expectQuiet("disabled after queuing")
            val trustProperty = TrustedProjects.TRUST_HEADLESS_DISABLED_PROPERTY
            val previous = System.setProperty(trustProperty, "false")
            try {
                withContext(Dispatchers.EDT) {
                    settings.changeAssembleOnOpen(true)
                    TrustedProjects.setProjectTrusted(project, false)
                    editors.openFile(rejected, request)
                }
                expectQuiet("untrusted project")
            } finally {
                if (previous == null) System.clearProperty(trustProperty) else System.setProperty(trustProperty, previous)
            }

            val (ambiguous, broken) = withContext(Dispatchers.EDT) {
                TrustedProjects.setProjectTrusted(project, true)
                fixture.addFileToProject("one.asm", ".include \"shared.s\"\n")
                fixture.addFileToProject("two.asm", ".include \"shared.s\"\n")
                val ambiguous = fixture.addFileToProject("shared.s", "nop\n").virtualFile
                val broken = fixture.addFileToProject("broken.asm", "j missing\n").virtualFile
                ambiguous to broken
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            withContext(Dispatchers.EDT) { editors.openFile(ambiguous, request) }
            expectQuiet("ambiguous source")

            withContext(Dispatchers.EDT) { editors.openFile(broken, request) }
            opened.awaitPending()
            val start = assertInstanceOf(StartBuildEvent::class.java, events.tryReceive().getOrThrow())
            val descriptor = assertInstanceOf(DefaultBuildDescriptor::class.java, start.buildDescriptor)
            assertFalse(descriptor.isActivateToolWindowWhenFailed)
            val diagnostic = assertInstanceOf(FileMessageEvent::class.java, events.tryReceive().getOrThrow())
            assertEquals(broken.toNioPath(), diagnostic.filePosition.path)
            assertTrue(diagnostic.message.contains("missing"))
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.tryReceive().getOrThrow())
            assertInstanceOf(FailureResult::class.java, finish.result)
            assertTrue(events.tryReceive().isFailure)
        } finally {
            withContext(Dispatchers.EDT) {
                TrustedProjects.setProjectTrusted(project, true)
                Disposer.dispose(lifetime)
            }
        }
    }

    @Test
    fun `launch reuses a successful build with current diagnostic positions and rebuild replaces it`() = timeoutRunBlocking<Unit> {
        val (entry, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("cached.asm", ".include \"value.s\"\nli \$t0, VALUE\n").virtualFile
            val value = fixture.addFileToProject("value.s", ".set noat\n.eqv VALUE 42\n").virtualFile
            main to value
        }
        val path = include.toNioPath()
        val sourceSet = MarsSourceSet(entry.toNioPath())
        val build = project.service<MarsBuildService>()
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        fun warning(upToDate: Boolean): FileMessageEvent {
            val received = buildList { while (true) add(events.tryReceive().getOrNull() ?: break) }
            val result = assertInstanceOf(SuccessResult::class.java, received.filterIsInstance<FinishBuildEvent>().single().result)
            assertEquals(upToDate, result.isUpToDate)
            return received.filterIsInstance<FileMessageEvent>().single()
        }
        suspend fun prepare() = requireNotNull(build.prepare(sourceSet, MarsExecutionOptions(), activateBuildView = false))
        try {
            val initial = prepare()
            assertEquals(path, warning(upToDate = false).filePosition.path)
            withContext(Dispatchers.EDT) {
                fixture.addFileToProject("unrelated.txt", "new file")
                edit(include, ".eqv VALUE 99\n")
                edit(include, ".set noat\n.eqv VALUE 42\n")
            }
            val hit = prepare()
            assertSame(initial.image, hit.image)
            assertTrue(initial.sources.revisions[path] != hit.sources.revisions[path])
            assertEquals(0, warning(upToDate = true).filePosition.startLine)

            assertTrue(build.assemble(sourceSet))
            assertEquals(path, warning(upToDate = false).filePosition.path)
            val rebuilt = prepare()
            assertNotSame(hit.image, rebuilt.image)
            warning(upToDate = true)
            assertSame(rebuilt.image, prepare().image)
            warning(upToDate = true)

            withContext(Dispatchers.EDT) { edit(include, ".set noat\n.eqv VALUE 99\n") }
            val changed = prepare()
            assertNotSame(rebuilt.image, changed.image)
            warning(upToDate = false)
            MarsSessionService.createMarsComponent().context().use { session ->
                session.loadAssemblyImage(initial.image) {}
                assertEquals(0x2408002a, session.program.machineList.single().binaryStatement)
                session.loadAssemblyImage(changed.image) {}
                assertEquals(0x24080063, session.program.machineList.single().binaryStatement)
            }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `launch image changes with source ordering include paths handler and execution options`() = timeoutRunBlocking<Unit> {
        val (entry, include, handler) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("project/cached.asm", ".include \"body.inc\"\nnop\n").virtualFile
            val body = fixture.addFileToProject("project/body.inc", "nop\n").virtualFile
            val trap = fixture.addFileToProject("trap.asm", ".ktext 0x80000180\nnop\n").virtualFile
            Triple(main, body, trap)
        }
        val build = project.service<MarsBuildService>()
        val settings = project.service<MarsAssemblySettings>()
        val options = MarsExecutionOptions()
        val sourceSet = MarsSourceSet(entry.toNioPath(), MarsAssemblyMode.DIRECTORY)
        suspend fun prepare(policy: MarsExecutionOptions = options) =
            requireNotNull(build.prepare(sourceSet, policy, activateBuildView = false))

        val original = prepare()
        val added = withContext(Dispatchers.EDT) { fixture.addFileToProject("project/another.asm", "nop\n").virtualFile }
        val expanded = prepare()
        assertNotSame(original.image, expanded.image)
        assertEquals(listOf(entry.toNioPath(), added.toNioPath()), expanded.sources.snapshot.entries())
        val delayed = prepare(options.copy(delayedBranching = true))
        assertNotSame(expanded.image, delayed.image)
        assertSame(expanded.image, prepare().image)
        withContext(Dispatchers.EDT) { settings.changeExceptionHandler(handler.path) }
        val withHandler = prepare()
        assertNotSame(expanded.image, withHandler.image)
        assertEquals(handler.toNioPath(), withHandler.sources.exceptionHandler)

        val oldPath = include.toNioPath()
        withContext(Dispatchers.EDT) {
            WriteAction.run<RuntimeException> { include.rename(this, "moved.inc") }
            edit(entry, ".include \"moved.inc\"\nnop\n")
        }
        val moved = prepare()
        assertNotSame(withHandler.image, moved.image)
        assertFalse(oldPath in moved.sources.snapshot.texts())
        assertTrue(include.toNioPath() in moved.sources.snapshot.texts())
        assertSame(moved.image, prepare().image)
    }

    @Test
    fun `native build task cancellation reports aborted and stops the batch`() = timeoutRunBlocking<Unit> {
        val (first, last) = withContext(Dispatchers.EDT) {
            fun task(name: String, source: String) =
                MarsBuildTask(MarsSourceSet(fixture.addFileToProject(name, source).virtualFile.toNioPath()))
            task("cancel-first.s", "la \$t0, undefined\n") to task("cancel-last.s", "nop\n")
        }
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable) { if (it is FileMessageEvent) throw ProcessCanceledException() }
            val result = ProjectTaskManager.getInstance(project).run(ProjectTaskList.asList(first, last)).await()
            assertTrue(result.isAborted)
            assertFalse(result.hasErrors())
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(SkippedResult::class.java, finish.result)
            assertTrue(events.tryReceive().isFailure)
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(disposable) }
        }
    }

    @Test
    fun `ordered project sources persist and update assembly and editor analysis`() = timeoutRunBlocking<Unit> {
        val (main, alpha, zulu) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("app/main.asm", "la \$t0, fromAlpha\nla \$t1, fromZulu\n") as MipsFile
            val alpha = fixture.addFileToProject("lib-a/alpha.s", ".eqv NUMBER 7\n.include \"shared.s\"\n.globl fromAlpha\nfromAlpha: nop\n") as MipsFile
            val zulu = fixture.addFileToProject("lib-z/zulu", ".eqv NUMBER 9\n.include \"../lib-a/shared.s\"\n.globl fromZulu\nfromZulu: nop\n")
            fixture.addFileToProject("lib-a/shared.s", "li \$t4, NUMBER\n")
            Triple(main, alpha, zulu)
        }
        val paths = listOf(main, alpha, zulu).map { it.virtualFile.toNioPath() }
        val settings = project.service<MarsAssemblySettings>()
        val analysis = project.service<MarsAnalysisService>()
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        val action = ActionManager.getInstance().getAction("Mars.AssembleProject")
        fun event(data: DataContext) = AnActionEvent.createEvent(action, data, null, ActionPlaces.MAIN_MENU, ActionUiKind.NONE, null)
        val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
        try {
            val unrelated = withContext(Dispatchers.EDT) {
                assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
                val empty = event(context)
                action.update(empty)
                assertFalse(empty.presentation.isEnabled)
                action.actionPerformed(empty)
                val noProject = event(DataContext.EMPTY_CONTEXT)
                action.update(noProject)
                assertFalse(noProject.presentation.isEnabled)
                action.actionPerformed(noProject)

                val binary = fixture.addFileToProject("image.png", "").virtualFile
                ApplicationManager.getApplication().replaceService(FileChooserFactory::class.java, object : FileChooserFactoryImpl() {
                    override fun createPathChooser(descriptor: FileChooserDescriptor, project: Project?, parent: Component?) =
                        PathChooserDialog { _, callback ->
                            assertTrue(descriptor.isChooseMultiple)
                            callback.consume(listOf(main.virtualFile, zulu.virtualFile, alpha.virtualFile, alpha.virtualFile,
                                binary, main.virtualFile.parent, LightVirtualFile("light.s", MipsFileType, "nop")))
                        }
                }, lifetime)
                val page = MarsAssemblyConfigurable(project)
                try {
                    val form = page.createComponent()
                    val list = requireNotNull(UIUtil.findComponentOfType(form, JList::class.java))
                    val add = requireNotNull(ToolbarDecorator.findAddButton(form))
                    val up = requireNotNull(ToolbarDecorator.findUpButton(form))
                    page.reset()
                    assertFalse(page.isModified)
                    add.actionPerformed(TestActionEvent.createTestEvent(add))
                    assertEquals(3, list.model.size)
                    assertTrue(page.isModified)
                    up.actionPerformed(TestActionEvent.createTestEvent(up))
                    page.apply()
                    assertEquals(paths, settings.projectSources)
                    settings.changeSources(paths.map { it.toString() })
                    val persisted = XmlSerializer.serialize(settings.getState())
                    settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                    assertEquals(paths, settings.projectSources)
                    page.reset()
                    assertFalse(page.isModified)
                } finally {
                    page.disposeUIResources()
                }
                val unrelated = fixture.addFileToProject("other/unrelated.s", "nop\n") as MipsFile
                DumbModeTestUtils.runInDumbModeSynchronously(project) {
                    assertNull(analysis.selectedAssembly(unrelated))
                }
                unrelated
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            val (first, include) = readAction {
                assertNull(analysis.selectedAssembly(unrelated))
                val capture = requireNotNull(collectEditorAssembly(alpha))
                assertEquals(paths, capture.sources.snapshot.entries())
                val shared = requireNotNull(VirtualFileManager.getInstance().findFileByNioPath(paths[1].resolveSibling("shared.s")))
                val include = PsiManager.getInstance(project).findFile(shared) as MipsFile
                assertEquals(MarsAssemblyMode.PROJECT, analysis.selectedAssembly(include)?.sourceSet?.mode)
                val number = requireNotNull(include.findElementAt(include.text.indexOf("NUMBER")))
                assertEquals(alpha.virtualFile, analysis.equivalenceTarget(include, number)?.containingFile?.virtualFile)
                capture to include
            }
            withContext(Dispatchers.EDT) {
                val build = event(context)
                action.update(build)
                assertTrue(build.presentation.isEnabled)
                action.actionPerformed(build)
            }
            var buildEvent = events.receive()
            while (buildEvent !is FinishBuildEvent) buildEvent = events.receive()
            assertInstanceOf(SuccessResult::class.java, buildEvent.result)
            readAction {
                val shared = requireNotNull(VirtualFileManager.getInstance().findFileByNioPath(paths[1].resolveSibling("shared.s")))
                val psi = PsiManager.getInstance(project).findFile(shared) as MipsFile
                assertEquals(MarsAssemblyMode.PROJECT, analysis.selectedAssembly(psi)?.sourceSet?.mode)
                assertEquals(MarsAssemblyMode.PROJECT, analysis.selectedAssembly(alpha)?.sourceSet?.mode)
                val reference = requireNotNull(main.findElementAt(main.text.indexOf("fromAlpha")))
                assertEquals(alpha.virtualFile, analysis.labelTargets(main, reference, false).single().containingFile.virtualFile)
            }
            val previous = analysis.backgroundAssembly.assemble(first.sources, first.structureStamp, first.options)
            val previousSymbols = requireNotNull(previous.symbols)
            analysis.publishBackgroundSymbols(previousSymbols)
            assertTrue(previousSymbols.symbols.globals().getValue("fromAlpha").address() <
                previousSymbols.symbols.globals().getValue("fromZulu").address())
            val reordered = listOf(paths[0], paths[2], paths[1])
            withContext(Dispatchers.EDT) { settings.changeSources(reordered.map { it.toString() }) }
            val refreshed = readAction {
                assertFalse(first.validate(alpha) { _, _, _ -> true })
                val reference = requireNotNull(main.findElementAt(main.text.indexOf("fromAlpha")))
                assertTrue(analysis.labelTargets(main, reference, false).isEmpty())
                val number = requireNotNull(include.findElementAt(include.text.indexOf("NUMBER")))
                assertEquals(zulu.virtualFile, analysis.equivalenceTarget(include, number)?.containingFile?.virtualFile)
                requireNotNull(collectEditorAssembly(main)).also {
                    assertEquals(reordered, it.sources.snapshot.entries())
                    assertTrue(it.validate(main) { _, document, stamp -> document.modificationStamp == stamp })
                }
            }
            val result = analysis.backgroundAssembly.assemble(refreshed.sources, refreshed.structureStamp, refreshed.options)
            val symbols = requireNotNull(result.symbols)
            assertTrue(symbols.symbols.globals().getValue("fromZulu").address() <
                symbols.symbols.globals().getValue("fromAlpha").address())
            project.service<MarsBuildService>().assemble(requireNotNull(settings.projectSourceSet()))
            withContext(Dispatchers.EDT) {
                edit(alpha.virtualFile, ".globl fromAlpha\nfromAlpha: nop\n")
                edit(zulu.virtualFile, ".globl fromZulu\nfromZulu: nop\n")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            readAction {
                assertNull(analysis.selectedAssembly(include))
                assertEquals(include.virtualFile.toNioPath(), collectEditorAssembly(include)?.sources?.sourceSet?.entry)
                val number = requireNotNull(include.findElementAt(include.text.indexOf("NUMBER")))
                assertNull(analysis.equivalenceTarget(include, number))
            }
            withContext(Dispatchers.EDT) { settings.changeSources(emptyList()) }
            readAction { assertNull(analysis.selectedAssembly(main)) }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `project memory layout persists and invalidates cached images and editor diagnostics`() = timeoutRunBlocking<Unit> {
        val (main, fixed) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("layout.asm", ".data\nvalue: .word 42\n.text\nstart: nop\n")
            val fixed = fixture.addFileToProject("compact-only.asm", ".text 0x3000\nfixed: nop\n") as MipsFile
            main.virtualFile to fixed
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val settings = project.service<MarsAssemblySettings>()
        val build = project.service<MarsBuildService>()
        val sources = MarsSourceSet(main.toNioPath())
        val annotator = MipsAssemblyAnnotator()
        suspend fun analyzeFixed() = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(fixed)) })
        suspend fun prepare() = requireNotNull(build.prepare(sources, settings.executionOptions, false))
        val original = prepare()
        assertSame(original.image, prepare().image)
        val oldDiagnostic = analyzeFixed()
        assertTrue(oldDiagnostic.diagnostics.any { it.message().contains("Invalid address") })
        assertTrue(readAction { annotator.acceptResult(fixed, oldDiagnostic).isNotEmpty() })

        withContext(Dispatchers.EDT) {
            settings.loadState(settings.getState().copy(memoryLayout = "unsupported"))
            assertEquals(MarsMemoryLayout.DEFAULT, settings.memoryLayout)
            val page = MarsAssemblyConfigurable(project)
            try {
                val panel = page.createComponent()
                page.reset()
                val layout = UIUtil.findComponentsOfType(panel, JComboBox::class.java).single()
                assertEquals(MarsMemoryLayout.DEFAULT, layout.selectedItem)
                layout.selectedItem = MarsMemoryLayout.DATA_AT_ZERO
                page.apply()
                assertEquals(MarsMemoryLayout.DATA_AT_ZERO, settings.memoryLayout)
                settings.changeMemoryLayout(MarsMemoryLayout.DATA_AT_ZERO)
                val persisted = XmlSerializer.serialize(settings.getState())
                settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                assertEquals("CompactDataAtZero", settings.getState().memoryLayout)
            } finally {
                page.disposeUIResources()
            }
        }
        val compact = prepare()
        assertNotSame(original.image, compact.image)
        assertSame(compact.image, prepare().image)
        assertTrue(readAction { annotator.acceptResult(fixed, oldDiagnostic).isEmpty() })
        val compactAnalysis = analyzeFixed()
        assertTrue(compactAnalysis.diagnostics.isEmpty())
        assertEquals(0x3000, requireNotNull(compactAnalysis.symbols).localSymbols.getValue("fixed").address())
        assertTrue(build.assemble(fixed.virtualFile.toNioPath()))

        withContext(Dispatchers.EDT) { settings.changeMemoryLayout(MarsMemoryLayout.TEXT_AT_ZERO) }
        val textAtZero = prepare()
        assertNotSame(compact.image, textAtZero.image)
        MarsSessionService.createMarsComponent().context().use { session ->
            session.loadAssemblyImage(original.image) {}
            assertEquals(0x00400000, session.memory.textBaseAddress)
            session.loadAssemblyImage(compact.image) {}
            assertEquals(0x3000, session.registerFile.programCounter)
            assertEquals(42, session.memory.getWord(0))
            session.loadAssemblyImage(textAtZero.image) {}
            assertEquals(0, session.registerFile.programCounter)
            assertEquals(42, session.memory.getWord(0x2000))
        }
    }

    @Test
    fun `assembler policy persists and changes native build and editor diagnostics`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("policy.asm", """
                .data
                .byte 300
                .text
                li ${'$'}t0, 7
                .macro emit (%name)
                %name: nop
                .end_macro
                emit(generated)
                j generated
            """.trimIndent()) as MipsFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val path = file.virtualFile.toNioPath()
        val settings = project.service<MarsAssemblySettings>()
        val manager = ProjectTaskManager.getInstance(project)
        val annotator = MipsAssemblyAnnotator()
        val analysis = project.service<MarsAnalysisService>()
        suspend fun generatedLabels() = readAction {
            val site = requireNotNull(file.findElementAt(file.text.lastIndexOf("generated")))
            analysis.labelCompletionNames(file, site, "generated")
        }
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        suspend fun build() = manager.run(MarsBuildTask(MarsSourceSet(path))).await()
        fun diagnostics() = buildList {
            while (true) add(events.tryReceive().getOrNull() ?: break)
        }.filterIsInstance<FileMessageEvent>()
        try {
            val unbuilt = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(file)) })
            withContext(Dispatchers.EDT) { settings.changeAssemblerOptions(true, true) }
            readAction { assertTrue(annotator.acceptResult(file, unbuilt).isEmpty()) }
            withContext(Dispatchers.EDT) { settings.changeAssemblerOptions(true, false) }
            assertFalse(build().hasErrors())
            assertEquals(setOf("generated"), generatedLabels())
            val warning = diagnostics().single()
            assertEquals(MessageEvent.Kind.WARNING, warning.kind)
            assertEquals(1, warning.filePosition.startLine)
            val before = readAction { requireNotNull(annotator.collectInformation(file)) }
            val oldResult = annotator.doAnnotate(before)
            assertTrue(oldResult.diagnostics.single().isWarning)

            withContext(Dispatchers.EDT) {
                val configurable = MarsAssemblyConfigurable(project)
                try {
                    val panel = configurable.createComponent()
                    configurable.reset()
                    val choices = UIUtil.findComponentsOfType(panel, JCheckBox::class.java).associateBy { it.text }
                    val pseudo = choices.getValue("Allow pseudo-instructions")
                    val warnings = choices.getValue("Treat assembler warnings as errors")
                    assertTrue(pseudo.isSelected)
                    assertFalse(warnings.isSelected)
                    assertFalse(configurable.isModified)
                    warnings.isSelected = true
                    assertTrue(configurable.isModified)
                    configurable.apply()
                    configurable.apply()
                    assertEquals(MarsExecutionOptions(warningsAreErrors = true), settings.executionOptions)
                    pseudo.isSelected = false
                    assertTrue(configurable.isModified)
                    configurable.apply()
                    val persisted = XmlSerializer.serialize(settings.getState())
                    settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                    assertEquals(MarsExecutionOptions(allowPseudoInstructions = false, warningsAreErrors = true), settings.executionOptions)
                    configurable.reset()
                    assertFalse(pseudo.isSelected)
                    assertTrue(warnings.isSelected)
                    pseudo.isSelected = true
                    configurable.apply()
                } finally {
                    configurable.disposeUIResources()
                }
            }

            assertTrue(generatedLabels().isEmpty())
            // A warning alone fails the build, and the native Build window receives an error at its source.
            assertTrue(build().hasErrors())
            assertEquals(MessageEvent.Kind.ERROR, diagnostics().single().kind)
            readAction { assertTrue(annotator.acceptResult(file, oldResult).isEmpty()) }
            val strict = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(file)) })
            assertFalse(strict.diagnostics.single().isWarning)
            assertEquals(warning.message, strict.diagnostics.single().message())
            assertEquals(1, readAction { annotator.acceptResult(file, strict).size })

            withContext(Dispatchers.EDT) { settings.changeAssemblerOptions(false, true) }
            readAction { assertTrue(annotator.acceptResult(file, strict).isEmpty()) }
            val basicOnly = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(file)) })
            assertEquals(2, basicOnly.diagnostics.size)
            assertTrue(basicOnly.diagnostics.any { it.message().contains("Extended") })
            assertTrue(basicOnly.diagnostics.none { it.isWarning })
            assertTrue(build().hasErrors())
            assertEquals(listOf(MessageEvent.Kind.ERROR, MessageEvent.Kind.ERROR), diagnostics().map { it.kind })
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `backstep limit validates and persists without invalidating assembly`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val settings = project.service<MarsAssemblySettings>()
            for (invalid in listOf(0, 100_001)) {
                assertThrows(IllegalArgumentException::class.java) { settings.changeBackstepLimit(invalid) }
            }
            val configurable = MarsAssemblyConfigurable(project)
            try {
                val panel = configurable.createComponent()
                configurable.reset()
                val label = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text?.startsWith("Backstep history limit") == true }
                val field = label.labelFor as JTextField
                assertEquals(ApplicationInfo.maximumBacksteps.toString(), field.text)
                for (invalid in listOf("0", "100001", "word")) {
                    field.text = invalid
                    assertThrows(ConfigurationException::class.java) { configurable.apply() }
                }
                field.text = "2"
                val stamp = settings.sourceChanges.modificationCount
                configurable.apply()
                assertEquals(stamp, settings.sourceChanges.modificationCount)
                val persisted = XmlSerializer.serialize(settings.getState())
                settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                assertEquals(2, settings.backstepLimit)
                for (invalid in listOf(0, 100_001)) {
                    settings.loadState(settings.getState().copy(backstepLimit = invalid))
                    assertEquals(ApplicationInfo.maximumBacksteps, settings.backstepLimit)
                }
            } finally {
                configurable.disposeUIResources()
            }
        }
    }

    @Test
    fun `assembler error limit validates persists and refreshes Build and editor diagnostics`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("error-limit.asm", (0..7).joinToString("\n", postfix = "\n") { ".byte 'ab'" }) as MipsFile
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val settings = project.service<MarsAssemblySettings>()
        val build = project.service<MarsBuildService>()
        val annotator = MipsAssemblyAnnotator()
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        fun diagnostics() = buildList {
            while (true) add(events.tryReceive().getOrNull() ?: break)
        }.filterIsInstance<MessageEvent>().filter { it.kind == MessageEvent.Kind.ERROR }
        suspend fun analyze() = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(file)) })
        try {
            assertFalse(build.assemble(file.virtualFile.toNioPath()))
            assertEquals(8, diagnostics().size)
            val before = analyze()
            assertEquals(8, before.diagnostics.size)
            withContext(Dispatchers.EDT) {
                assertThrows(IllegalArgumentException::class.java) { settings.changeAssemblerErrorLimit(0) }
                val configurable = MarsAssemblyConfigurable(project)
                try {
                    val panel = configurable.createComponent()
                    configurable.reset()
                    val label = UIUtil.findComponentsOfType(panel, JLabel::class.java).single { it.text?.startsWith("Assembler error limit") == true }
                    val field = label.labelFor as JTextField
                    assertEquals(ApplicationInfo.maximumErrorMessages.toString(), field.text)
                    for (invalid in listOf("0", "-1", "word", "2147483648")) {
                        field.text = invalid
                        assertThrows(ConfigurationException::class.java) { configurable.apply() }
                    }
                    field.text = "2"
                    configurable.apply()
                    val stamp = settings.sourceChanges.modificationCount
                    configurable.apply()
                    settings.changeAssemblerErrorLimit(2)
                    assertEquals(stamp, settings.sourceChanges.modificationCount)
                    val persisted = XmlSerializer.serialize(settings.getState())
                    settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                    assertEquals(2, settings.executionOptions.assemblerErrorLimit)
                } finally {
                    configurable.disposeUIResources()
                }
            }
            readAction { assertTrue(annotator.acceptResult(file, before).isEmpty()) }
            val limited = analyze()
            assertEquals(2, limited.diagnostics.size)
            assertFalse(build.assemble(file.virtualFile.toNioPath()))
            val errors = diagnostics()
            assertEquals(3, errors.size)
            assertEquals(2, errors.filterIsInstance<FileMessageEvent>().size)
            assertTrue(errors.any { it.message.contains("Error Limit of 2 exceeded") })
            withContext(Dispatchers.EDT) { settings.changeAssemblerErrorLimit(4) }
            assertEquals(4, analyze().diagnostics.size)
            withContext(Dispatchers.EDT) {
                settings.loadState(MarsAssemblySettings.Settings(assemblerErrorLimit = -1))
                assertEquals(ApplicationInfo.maximumErrorMessages, settings.executionOptions.assemblerErrorLimit)
                settings.loadState(XmlSerializer.deserialize(Element("state"), MarsAssemblySettings.Settings::class.java))
                assertEquals(ApplicationInfo.maximumErrorMessages, settings.assemblerErrorLimit)
            }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `project execution defaults persist and bare machine updates Build and editor diagnostics`() = timeoutRunBlocking<Unit> {
        val (named, numeric) = withContext(Dispatchers.EDT) {
            val named = fixture.addFileToProject("named.s", "addiu \$t0,\$zero,7\n") as MipsFile
            val numeric = fixture.addFileToProject("numeric.s", "addiu \$8,\$0,7\n") as MipsFile
            named to numeric
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val settings = project.service<MarsAssemblySettings>()
        val build = project.service<MarsBuildService>()
        val annotator = MipsAssemblyAnnotator()
        assertTrue(build.assemble(named.virtualFile.toNioPath()))
        val before = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(named)) })
        assertTrue(before.diagnostics.isEmpty())

        withContext(Dispatchers.EDT) {
            val page = MarsAssemblyConfigurable(project)
            try {
                val panel = page.createComponent()
                page.reset()
                val choices = UIUtil.findComponentsOfType(panel, JCheckBox::class.java).associateBy { it.text }
                val delayed = choices.getValue("Enable delayed branching")
                val modify = choices.getValue("Allow self-modifying code")
                val bare = choices.getValue("Require numeric integer register operands (bare machine)")
                assertTrue(listOf(delayed, modify, bare).none { it.isSelected })
                listOf(delayed, modify, bare).forEach { it.isSelected = true }
                assertTrue(page.isModified)
                page.apply()
                val saved = XmlSerializer.serialize(settings.getState())
                settings.loadState(XmlSerializer.deserialize(saved, MarsAssemblySettings.Settings::class.java))
                page.reset()
                assertTrue(listOf(delayed, modify, bare).all { it.isSelected })
                assertEquals(MarsExecutionOptions(delayedBranching = true, selfModifyingCode = true, bareMachine = true), settings.executionOptions)
                val generation = settings.sourceChanges.modificationCount
                settings.changeDelayedBranching(true)
                settings.changeBareMachine(true)
                assertEquals(generation, settings.sourceChanges.modificationCount)
            } finally {
                page.disposeUIResources()
            }
        }

        assertFalse(build.assemble(named.virtualFile.toNioPath()))
        assertTrue(build.assemble(numeric.virtualFile.toNioPath()))
        val result = annotator.doAnnotate(readAction { requireNotNull(annotator.collectInformation(named)) })
        assertTrue(result.diagnostics.single().message().contains("Use register number"))
    }

    @Test
    fun `project settings select a single exception handler and invalidate stale analysis and builds`() = timeoutRunBlocking<Unit> {
        val (main, handler) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("trap/main.asm", "la \$t0, trapEntry\nnop\n") as MipsFile
            val handler = fixture.addFileToProject("trap/handler.asm", ".ktext 0x80000180\nnop\n") as MipsFile
            fixture.addFileToProject("trap/parts/body.s", "trapEntry: nop\n")
            edit(handler.virtualFile, ".ktext 0x80000180\n.globl trapEntry\n.include \"parts/body.s\"\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            main to handler
        }
        val settings = project.service<MarsAssemblySettings>()
        val analysis = project.service<MarsAnalysisService>()
        val entry = main.virtualFile.toNioPath()
        val kernel = handler.virtualFile.toNioPath()
        val before = readAction { requireNotNull(collectEditorAssembly(main)) }
        val lifetime = Disposer.newDisposable()
        val events = recordBuildEvents(lifetime)
        suspend fun finish(): FinishBuildEvent {
            var event = events.receive()
            while (event !is FinishBuildEvent) event = events.receive()
            return event
        }
        try {
            withContext(Dispatchers.EDT) {
                val page = MarsAssemblyConfigurable(project)
                try {
                    val path = requireNotNull(UIUtil.findComponentOfType(page.createComponent(), TextFieldWithBrowseButton::class.java))
                    page.reset()
                    assertEquals("", path.text)
                    path.text = "relative/handler.asm"
                    assertThrows(ConfigurationException::class.java) { page.apply() }
                    assertNull(settings.exceptionHandler)
                    path.text = kernel.toString()
                    page.apply()
                    assertEquals(kernel, settings.exceptionHandler)
                    settings.changeExceptionHandler(kernel.toString())
                    val persisted = XmlSerializer.serialize(settings.getState())
                    settings.loadState(XmlSerializer.deserialize(persisted, MarsAssemblySettings.Settings::class.java))
                    assertEquals(kernel, settings.exceptionHandler)
                    page.reset()
                    assertEquals(kernel.toString(), path.text)
                } finally {
                    page.disposeUIResources()
                }
            }
            val directory = captureBuildSources(MarsSourceSet(entry, MarsAssemblyMode.DIRECTORY), exceptionHandler = settings.exceptionHandler)
            assertEquals(listOf(entry), directory.snapshot.entries())
            val projectSources = MarsSourceSet(listOf(entry, kernel), MarsAssemblyMode.PROJECT)
            assertEquals(listOf(entry), captureBuildSources(projectSources, kernel).snapshot.entries())
            assertEquals(kernel, directory.rootsBySource[entry.resolveSibling("parts/body.s")])
            assertEquals(".ktext 0x80000180\nnop\n", Files.readString(kernel))
            val service = project.service<MarsBuildService>()
            service.assemble(MarsSourceSet(entry, MarsAssemblyMode.DIRECTORY))
            assertInstanceOf(SuccessResult::class.java, finish().result)
            val current = readAction {
                assertFalse(before.validate(main) { _, _, _ -> true })
                val reference = requireNotNull(main.findElementAt(main.text.indexOf("trapEntry")))
                assertEquals("body.s", analysis.labelTargets(main, reference, false).singleOrNull()?.containingFile?.name)
                requireNotNull(collectEditorAssembly(main)).also {
                    assertEquals(kernel, it.sources.exceptionHandler)
                    assertTrue(it.validate(main) { _, document, stamp -> document.modificationStamp == stamp })
                }
            }
            val result = analysis.backgroundAssembly.assemble(current.sources, current.structureStamp, current.options)
            assertTrue(result.diagnostics.isEmpty())
            analysis.publishBackgroundSymbols(requireNotNull(result.symbols))

            val absent = entry.resolveSibling("missing-handler.asm")
            val another = entry.resolveSibling("other-handler.asm")
            withContext(Dispatchers.EDT) { settings.changeExceptionHandler(absent.toString()) }
            val stale = readAction {
                assertFalse(current.validate(main) { _, _, _ -> true })
                val reference = requireNotNull(main.findElementAt(main.text.indexOf("trapEntry")))
                assertTrue(analysis.labelTargets(main, reference, false).isEmpty())
                requireNotNull(collectEditorAssembly(main))
            }
            val missing = analysis.backgroundAssembly.assemble(stale.sources, stale.structureStamp, stale.options)
            assertTrue(missing.diagnostics.values.flatten().any { it.message().contains(absent.toString()) })
            withContext(Dispatchers.EDT) { settings.changeExceptionHandler(another.toString()) }
            val updated = readAction { requireNotNull(collectEditorAssembly(main)) }
            assertEquals(stale.sources.revisions, updated.sources.revisions)
            val missingAgain = analysis.backgroundAssembly.assemble(updated.sources, updated.structureStamp, updated.options)
            assertTrue(missingAgain.diagnostics.values.flatten().any { it.message().contains(another.toString()) })
            service.assemble(entry)
            assertInstanceOf(FailureResult::class.java, finish().result)

            withContext(Dispatchers.EDT) { settings.changeExceptionHandler(kernel.toString()) }
            readAction { assertEquals(entry, analysis.selectedAssembly(handler)?.entry) }
            service.assemble(kernel)
            assertInstanceOf(SuccessResult::class.java, finish().result)
            withContext(Dispatchers.EDT) {
                val page = MarsAssemblyConfigurable(project)
                try {
                    val path = requireNotNull(UIUtil.findComponentOfType(page.createComponent(), TextFieldWithBrowseButton::class.java))
                    page.reset()
                    path.text = " "
                    page.apply()
                } finally {
                    page.disposeUIResources()
                }
            }
            assertNull(settings.exceptionHandler)
            readAction { assertNull(collectBackgroundAssemblySources(project, MarsSourceSet(kernel)).exceptionHandler) }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `directory assembly captures sibling documents in deterministic order and shares their analysis`() = timeoutRunBlocking<Unit> {
        val (main, helper, last) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("project/main.asm", ".text\n.globl entry\nentry: nop\n") as MipsFile
            val last = fixture.addFileToProject("project/zulu.s", ".eqv NUMBER 11\n.include \"shared/defs.s\"\n.text\nzulu: nop\n") as MipsFile
            val helper = fixture.addFileToProject("project/Alpha.MARS", ".globl helper\nhelper: nop\n") as MipsFile
            fixture.addFileToProject("project/shared/defs.s", "addiu \$t2, \$zero, NUMBER\n")
            fixture.addFileToProject("project/shared/value.inc", ".eqv OTHER_NUMBER 7\n")
            fixture.addFileToProject("project/shared/ignored.s", "invalid instruction\n")
            fixture.addFileToProject("project/notes.txt", "not assembly\n")
            fixture.addFileToProject("project/include-only.inc", "not a top-level assembly source\n")
            fixture.addFileToProject("project/LICENSE", "not assembly\n")
            edit(helper.virtualFile, ".eqv NUMBER 7\n.include \"shared/value.inc\"\n.include \"shared/defs.s\"\n.globl helper\nhelper: addiu \$t0, \$zero, NUMBER\nprivateHelper: nop\njal entry\n")
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            Triple(main, helper, last)
        }
        val entry = main.virtualFile.toNioPath()
        val helperPath = helper.virtualFile.toNioPath()
        val roots = listOf(entry, helperPath, last.virtualFile.toNioPath())
        val capture = captureBuildSources(MarsSourceSet(entry, MarsAssemblyMode.DIRECTORY))
        val absent = entry.resolveSibling("missing/main.asm")
        assertEquals(listOf(absent), captureBuildSources(MarsSourceSet(absent, MarsAssemblyMode.DIRECTORY)).snapshot.entries())
        assertEquals(roots, capture.snapshot.entries())
        assertEquals(helperPath, capture.rootsBySource[entry.resolveSibling("shared/defs.s")])
        assertEquals(helperPath, capture.rootsBySource[entry.resolveSibling("shared/value.inc")])
        assertEquals(".globl helper\nhelper: nop\n", Files.readString(helperPath))
        val lifetime = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(lifetime)
            val customType = withContext(Dispatchers.EDT) {
                CodeInsightTestFixtureImpl.associateExtensionTemporarily(MipsFileType, "marsfixture", lifetime)
                fixture.addFileToProject("project/excluded.marsfixture", "nop\n") as MipsFile
            }
            withContext(Dispatchers.EDT) {
                val context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, project).add(CommonDataKeys.VIRTUAL_FILE, main.virtualFile).build()
                val action = ActionManager.getInstance().getAction("Mars.AssembleDirectory")
                assertInstanceOf(MarsAssembleDirectoryAction::class.java, action)
                val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
                action.update(event)
                assertTrue(event.presentation.isEnabledAndVisible)
                action.actionPerformed(event)
            }
            var event = events.receive()
            while (event !is FinishBuildEvent) event = events.receive()
            assertInstanceOf(SuccessResult::class.java, event.result)
            val analysis = project.service<MarsAnalysisService>()
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            val (fromMain, fromHelper, single) = readAction {
                val element = requireNotNull(helper.findElementAt(helper.text.lastIndexOf("entry")))
                assertEquals(main.virtualFile, analysis.labelTargets(helper, element, false).single().containingFile.virtualFile)
                assertNull(analysis.selectedAssembly(customType))
                val scratch = PsiFileFactory.getInstance(project).createFileFromText("scratch.asm", MipsFileType, "nop") as MipsFile
                assertNull(analysis.selectedAssembly(scratch))
                val nested = VirtualFileManager.getInstance().findFileByNioPath(entry.resolveSibling("shared/ignored.s"))!!
                val nestedPsi = PsiManager.getInstance(project).findFile(nested) as MipsFile
                assertNull(analysis.selectedAssembly(nestedPsi))
                val shared = VirtualFileManager.getInstance().findFileByNioPath(entry.resolveSibling("shared/defs.s"))!!
                val sharedPsi = PsiManager.getInstance(project).findFile(shared) as MipsFile
                val number = requireNotNull(sharedPsi.findElementAt(sharedPsi.text.indexOf("NUMBER")))
                assertEquals(helper.virtualFile, analysis.equivalenceTarget(sharedPsi, number)?.containingFile?.virtualFile)
                val single = collectBackgroundAssemblySources(project, MarsSourceSet(entry))
                assertEquals(listOf(entry), single.snapshot.entries())
                val fromMain = requireNotNull(collectEditorAssembly(main))
                val fromHelper = requireNotNull(collectEditorAssembly(helper))
                assertSame(fromMain.sources, fromHelper.sources)
                assertEquals(roots, fromHelper.sources.snapshot.entries())
                assertTrue(fromMain.validate(main) { _, document, stamp -> document.modificationStamp == stamp })
                assertEquals(MarsAssemblyMode.DIRECTORY, analysis.selectedAssembly(last)?.sourceSet?.mode)
                Triple(fromMain, fromHelper, single)
            }
            val singleOutcome = analysis.backgroundAssembly.assemble(single, fromMain.structureStamp, MarsExecutionOptions())
            assertTrue(singleOutcome.diagnostics.isEmpty())
            val result = analysis.backgroundAssembly.assemble(fromHelper.sources, fromHelper.structureStamp, fromHelper.options)
            val symbols = requireNotNull(result.symbols)
            assertTrue(result.diagnostics.isEmpty())
            assertTrue(symbols.hasCurrentDirectory())
            analysis.publishBackgroundSymbols(symbols)
            assertEquals(listOf("privateHelper"), symbols.matchingNames(helperPath, "private"))
            assertTrue(symbols.matchingNames(entry, "private").isEmpty())
            assertEquals(listOf("helper"), symbols.matchingNames(entry, "help"))
            val created = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("project/beta.s", "beta: nop\n") as MipsFile
            }
            readAction {
                assertFalse(fromMain.validate(main) { _, _, _ -> true })
                assertFalse(symbols.hasCurrentDirectory())
                val element = requireNotNull(helper.findElementAt(helper.text.lastIndexOf("entry")))
                assertTrue(analysis.labelTargets(helper, element, false).isEmpty())
                analysis.publishBackgroundSymbols(requireNotNull(singleOutcome.symbols))
                val entryLabel = requireNotNull(main.findElementAt(main.text.lastIndexOf("entry")))
                assertTrue(analysis.labelCompletionNames(main, entryLabel, "helper").isEmpty())
                assertEquals(MarsAssemblyMode.DIRECTORY, analysis.selectedAssembly(created)?.sourceSet?.mode)
                assertEquals(listOf(entry, helperPath, created.virtualFile.toNioPath(), last.virtualFile.toNioPath()),
                    collectEditorAssembly(created)!!.sources.snapshot.entries())
            }
        } finally {
            withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
        }
    }

    @Test
    fun `directory assembly includes dotfiles only by name or with a real assembly extension`() = timeoutRunBlocking<Unit> {
        val (entry, include, sibling) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("dotfiles/main.asm", ".include \".asm\"\naddiu \$t0,\$zero,VALUE\n")
            val include = fixture.addFileToProject("dotfiles/.asm", ".eqv VALUE 7\n")
            val sibling = fixture.addFileToProject("dotfiles/.local.S", "nop\n")
            fixture.addFileToProject("dotfiles/.s", "not a valid instruction\n")
            Triple(entry.virtualFile.toNioPath(), include.virtualFile.toNioPath(), sibling.virtualFile.toNioPath())
        }
        val capture = captureBuildSources(MarsSourceSet(entry, MarsAssemblyMode.DIRECTORY))
        assertEquals(listOf(entry, sibling), capture.snapshot.entries())
        assertEquals(entry, capture.rootsBySource[include])
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val result = capture.assembleIn(component)
            assertTrue(result.succeeded(), result.errors().toString())
            assertEquals(listOf(0x24080007, 0), session.program.machineList.map { it.binaryStatement })
        }
    }

    @Test
    fun `an included file uses earlier definitions in the selected entry even after a failed build`() = timeoutRunBlocking<Unit> {
        val source = """
            li ${'$'}t0, %value
            li ${'$'}t1, COUNT
            .end_macro
            helper ARGS
            la ${'$'}t4, reservation
            j publicAfter
            li ${'$'}t2, LATE
        """.trimIndent()
        fun entry(values: String) = """
            .eqv UNUSED 1
            .eqv RESERVE .extern
            RESERVE reservation 4
            .include "$values"
            .macro helper (%number)
              li ${'$'}t3, %number
            .end_macro
            .eqv DEFINE .eqv
            DEFINE ARGS (9)
            .macro body (%value)
              .include "parts/wrapper.s"
            body (42)
            .eqv LATE 99
            publicAfter: nop
        """.trimIndent()
        val (first, second) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("parts/shared.s", source).virtualFile
            fixture.addFileToProject("parts/wrapper.s", ".include \"shared.s\"\n")
            fixture.addFileToProject("first-values.s", ".eqv COUNT 7\n")
            fixture.addFileToProject("second-values.s", ".eqv COUNT 11\n")
            val first = fixture.addFileToProject("first.s", entry("first-values.s")).virtualFile
            val second = fixture.addFileToProject("second.s", entry("second-values.s")).virtualFile
            fixture.configureFromExistingVirtualFile(shared)
            first to second
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        fun target(name: String) = fixture.file.findReferenceAt(source.indexOf(name))?.resolve()
        suspend fun assemble(file: VirtualFile): Boolean {
            val component = MarsSessionService.createMarsComponent()
            return component.context().use {
                project.service<MarsBuildService>().assemble(file.toNioPath(), component, activateBuildView = false)
            }
        }
        readAction {
            assertNull(target("COUNT"))
            assertNull(target("%value"))
            assertNull(target("reservation"))
            assertNull(target("publicAfter"))
        }
        for ((entry, values) in listOf(first to "first-values.s", second to "second-values.s")) {
            assertFalse(assemble(entry))
            readAction {
                assertEquals(values, target("COUNT")?.containingFile?.name)
                assertEquals(entry, target("%value")?.containingFile?.virtualFile)
                assertEquals("%value", target("%value")?.text)
                assertEquals(entry, target("helper")?.containingFile?.virtualFile)
                assertEquals("helper (%number)", target("helper")?.text)
                assertEquals(entry, target("ARGS")?.containingFile?.virtualFile)
                assertEquals(entry, target("reservation")?.containingFile?.virtualFile)
                assertEquals("RESERVE", target("reservation")?.text)
                assertEquals(entry, target("publicAfter")?.containingFile?.virtualFile)
                assertEquals("publicAfter:", target("publicAfter")?.text)
                assertNull(target("LATE"))
            }
        }
        withContext(Dispatchers.EDT) {
            edit(second, ".eqv COUNT\n" + entry("second-values.s"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction { assertEquals("second-values.s", target("COUNT")?.containingFile?.name) }
        withContext(Dispatchers.EDT) {
            edit(second, entry("second-values.s").replace(".include \"parts/wrapper.s\"", ".end_macro"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction {
            assertEquals("first-values.s", target("COUNT")?.containingFile?.name)
            assertEquals(first, target("%value")?.containingFile?.virtualFile)
            assertEquals(first, target("helper")?.containingFile?.virtualFile)
            assertEquals(first, target("reservation")?.containingFile?.virtualFile)
            assertEquals(first, target("publicAfter")?.containingFile?.virtualFile)
        }
        val plain = withContext(Dispatchers.EDT) {
            WriteCommandAction.runWriteCommandAction(project) { second.delete(this) }
            fixture.addFileToProject("entry.txt", entry("second-values.s")).virtualFile
        }
        readAction { assertEquals("first-values.s", target("COUNT")?.containingFile?.name) }
        assertFalse(assemble(plain))
        readAction {
            assertEquals("second-values.s", target("COUNT")?.containingFile?.name)
            assertEquals(plain, target("%value")?.containingFile?.virtualFile)
        }
        val ignored = withContext(Dispatchers.EDT) {
            fixture.tempDirFixture.createFile("entry.txt~", entry("second-values.s"))
        }
        assertFalse(assemble(ignored))
        readAction {
            assertNull(PsiManager.getInstance(project).findFile(ignored))
            assertEquals("first-values.s", target("COUNT")?.containingFile?.name)
        }
    }

    @Test
    fun `an included data continuation inherits the inferred and selected entry segment and list`() = timeoutRunBlocking<Unit> {
        val (entry, data) = withContext(Dispatchers.EDT) {
            val data = fixture.addFileToProject("values.s", "value\n").virtualFile
            val entry = fixture.addFileToProject("entry.s", """
                .eqv DATA_SEG .data
                .eqv WORDS .word
                DATA_SEG
                value: WORDS 42
                .include "values.s"
                .text
                nop
            """.trimIndent() + "\n").virtualFile
            fixture.configureFromExistingVirtualFile(data)
            entry to data
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        fun target() = fixture.file.findReferenceAt(1)?.resolve()
        readAction { assertEquals(entry, target()?.containingFile?.virtualFile) }
        project.service<MarsBuildService>().assemble(entry.toNioPath())
        readAction {
            assertEquals(data, fixture.file.virtualFile)
            assertEquals(entry, target()?.containingFile?.virtualFile)
            assertEquals("value:", target()?.text)
        }
    }

    @Test
    fun `choosing a containing entry changes navigation and completion in a shared include`() = timeoutRunBlocking<Unit> {
        val source = "j selectedName\n"
        val (first, second, shared) = withContext(Dispatchers.EDT) {
            val shared = fixture.addFileToProject("parts/shared.s", source).virtualFile
            fixture.addFileToProject("parts/wrapper.s", ".include \"shared.s\"\n")
            val first = fixture.addFileToProject("first_entry.asm", "selectedName: nop\nselectedAlpha: nop\n.include \"parts/wrapper.s\"\n").virtualFile
            val second = fixture.addFileToProject("second.asm", "selectedBeta: nop\nselectedName: nop\n.include \"parts/wrapper.s\"\n").virtualFile
            fixture.configureFromExistingVirtualFile(shared)
            fixture.editor.caretModel.moveToOffset(5)
            Triple(first, second, shared)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val group = containingEntriesGroup()
        val actions = readAction { group.getChildren(event(group, project, shared)) }
        assertEquals(listOf(first.presentableUrl, second.presentableUrl), actions.map { it.templatePresentation.text })
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            fun target() = GotoDeclarationAction.findTargetElement(project, fixture.editor, fixture.editor.caretModel.offset)
            suspend fun choose(action: AnAction, entry: VirtualFile) {
                withContext(Dispatchers.EDT) { action.actionPerformed(event(action, project, shared)) }
                val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
                assertEquals("Assemble ${entry.name}", start.buildDescriptor.title)
                assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
            }
            fun assertNames(vararg names: String) {
                fixture.editor.caretModel.moveToOffset(5)
                fixture.completeBasic()
                assertEquals(names.toSet(), fixture.lookupElementStrings.orEmpty().filter { it.startsWith("sel") }.toSet())
                fixture.lookup.hideLookup(true)
            }

            readAction { assertNull(target()) }
            choose(actions[0], first)
            val unrelated = withContext(Dispatchers.EDT) {
                fixture.addFileToProject("unrelated.s", "j sel\n") as MipsFile
            }
            readAction {
                assertEquals(first, target()?.containingFile?.virtualFile)
                val analysis = project.service<MarsAnalysisService>()
                val include = fixture.file as MipsFile
                assertTrue(analysis.labelTargets(include, include.findElementAt(0)!!, allowExternal = true).isEmpty())
                assertTrue(analysis.labelCompletionNames(unrelated, unrelated.findElementAt(2)!!, "sel").isEmpty())
            }
            withContext(Dispatchers.EDT) {
                assertNames("selectedName", "selectedAlpha")
                edit(shared, "j sel\n")
                PsiDocumentManager.getInstance(project).commitAllDocuments()
                assertNames("selectedName", "selectedAlpha")
            }
            readAction { assertNull(target()) }
            withContext(Dispatchers.EDT) {
                edit(shared, source)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            choose(actions[1], second)
            readAction {
                val declaration = target()
                assertEquals(second, declaration?.containingFile?.virtualFile)
                assertEquals("selectedBeta: nop\n".length, declaration?.textOffset)
            }
            withContext(Dispatchers.EDT) {
                assertNames("selectedName", "selectedBeta")
                edit(second, "# changed entry\n" + Files.readString(second.toNioPath()))
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }
            readAction {
                val declaration = target()
                assertEquals(second, declaration?.containingFile?.virtualFile)
                assertEquals("# changed entry\nselectedBeta: nop\n".length, declaration?.textOffset)
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `containing entry menu follows only MIPS roots and handles cycles and indexing`() = timeoutRunBlocking<Unit> {
        val (payload, entry, cycle) = withContext(Dispatchers.EDT) {
            val payload = fixture.addFileToProject("payload.txt", "nop\n").virtualFile
            val entry = fixture.addFileToProject("entry.s", ".include \"payload.txt\"\n").virtualFile
            fixture.addFileToProject("foreign.xml", """<xi:include xmlns:xi="http://www.w3.org/2001/XInclude" href="payload.txt"/>""")
            val cycle = fixture.addFileToProject("cycle.s", ".include \"other.s\"\n").virtualFile
            fixture.addFileToProject("other.s", ".include \"cycle.s\"\n")
            Triple(payload, entry, cycle)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        val group = containingEntriesGroup()
        val ignored = withContext(Dispatchers.EDT) {
            lateinit var file: VirtualFile
            WriteCommandAction.runWriteCommandAction(project) {
                file = payload.parent.createChildData(this, ".DS_Store")
            }
            file
        }
        readAction {
            assertEquals(ActionUpdateThread.BGT, group.actionUpdateThread)
            assertTrue(group.templatePresentation.isPopupGroup)
            assertTrue(group.templatePresentation.isHideGroupIfEmpty)
            assertEquals("Assemble Containing MIPS Entry", group.templatePresentation.text)
            assertTrue(group.getChildren(null).isEmpty())
            assertTrue(group.getChildren(event(group, null, payload)).isEmpty())
            assertTrue(group.getChildren(event(group, project, null)).isEmpty())
            assertTrue(group.getChildren(event(group, project, payload.parent)).isEmpty())
            assertTrue(group.getChildren(event(group, project, ignored)).isEmpty())
            assertTrue(group.getChildren(event(group, project, cycle)).isEmpty())
            assertTrue(group.getChildren(event(group, project, entry)).isEmpty())
            assertEquals(setOf("entry.s", "foreign.xml"), FileIncludeManager.getManager(project).getIncludingFiles(payload, true).map { it.name }.toSet())
            val child = group.getChildren(event(group, project, payload)).single()
            assertEquals(entry.presentableUrl, child.templatePresentation.text)
        }
        withContext(Dispatchers.EDT) {
            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                assertTrue(group.getChildren(event(group, project, payload)).isEmpty())
            }
        }
    }

    private fun containingEntriesGroup() = assertInstanceOf(
        MarsContainingEntriesGroup::class.java, ActionManager.getInstance().getAction("Mars.AssembleContainingEntry"),
    )

    @Test
    fun `completion keeps generated names while typing a direct instruction operand`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "macro.s"
            emit (generatedOne)
            emit (generated_M0)
            reserve (generatedStorage)
            .extern ) 4
            .extern ${'$'}t0 4
            .globl generatedOne
            nop: nop
            j generatedOne
        """.trimIndent()
        val body = """
            .macro emit (%entry)
              generatedPrivate: nop
              %entry: nop
            .end_macro
            .macro reserve (%name)
              .extern %name 4
            .end_macro
        """.trimIndent()
        val (main, include) = withContext(Dispatchers.EDT) {
            val definitions = fixture.addFileToProject("macro.s", body).virtualFile
            val entry = fixture.addFileToProject("entry.asm", source).virtualFile
            fixture.configureFromExistingVirtualFile(entry)
            entry to definitions
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        project.service<MarsBuildService>().assemble(main.toNioPath())
        withContext(Dispatchers.EDT) {
            val names = setOf("generatedOne", "generated_M0", "generatedStorage")
            fun candidates(caret: Int): Set<String> {
                fixture.editor.caretModel.moveToOffset(caret)
                fixture.completeBasic()
                return fixture.lookupElementStrings.orEmpty().filter { it.startsWith("gen") }.toSet()
            }
            fun change(file: VirtualFile, text: String) {
                edit(file, text)
                PsiDocumentManager.getInstance(project).commitAllDocuments()
            }

            val originalOperand = source.lastIndexOf("generatedOne")
            assertEquals(names, candidates(originalOperand + 3))
            fixture.lookup.hideLookup(true)
            val partial = source.substring(0, originalOperand) + "genOld"
            change(main, partial)
            assertEquals(names, candidates(originalOperand + 3))
            fixture.lookup.setCurrentItem(fixture.lookup.items.single { it.lookupString == "generatedOne" })
            fixture.finishLookup(Lookup.REPLACE_SELECT_CHAR)
            assertEquals(source, fixture.editor.document.text)

            change(main, "$source\nj unmatched")
            assertTrue(candidates(fixture.editor.document.textLength).isEmpty())
            val changedInvocation = source.replace("emit (generatedOne)", "emit (anotherName)")
            change(main, "$changedInvocation\nj gen")
            assertTrue(candidates(fixture.editor.document.textLength).isEmpty())

            change(main, "$source\nj gen")
            assertEquals(names, candidates(fixture.editor.document.textLength))
            fixture.lookup.hideLookup(true)
            change(include, "# an include changed\n$body")
            assertTrue(candidates(fixture.editor.document.textLength).isEmpty())
        }
    }

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
                ".eqv RESERVE .extern\n.macro reserve (%name)\n  RESERVE %name 4\n.end_macro\n"
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
                val storageDeclaration = GotoDeclarationAction.findTargetElement(project, fixture.editor, storage)
                assertEquals(target(storage), storageDeclaration)
                assertEquals("%name", storageDeclaration?.text)
                assertEquals(body.lastIndexOf("%name"), storageDeclaration?.textOffset)
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
        analysis.publishBuiltSymbols(older, capture, program.symbolMap())
        fun target(file: PsiElement) = file.containingFile.findReferenceAt(file.text.lastIndexOf("generated"))?.resolve()
        readAction { assertNull(target(entry)) }
        analysis.publishBuiltSymbols(current, capture, program.symbolMap())
        readAction {
            assertEquals("%entry", target(entry)?.text)
            assertNull(target(other))
        }
        val definition = program.symbolMap().locals().getValue(path).getValue("generated")
        fun publishWithoutColumn(value: Definition) {
            val symbols = AssemblySymbolMap(mapOf(path to mapOf("generated" to value)), emptyMap())
            analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), capture, symbols)
        }
        publishWithoutColumn(Definition(definition.address(), definition.kind(), definition.origin()))
        readAction { assertEquals("%entry", target(entry)?.text) }
        publishWithoutColumn(Definition(definition.address(), definition.kind(), null))
        readAction { assertNull(target(entry)) }
        val missingRevision = capture.copy(revisions = capture.revisions - setOf(includePath))
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), missingRevision, program.symbolMap())
        readAction { assertNull(target(entry)) }

        val plainText = "# include left as plain text\n" + include.text.replace("\n%entry:", "\n\t%entry:")
        val plain = withContext(Dispatchers.EDT) { fixture.addFileToProject("macro.txt", plainText).virtualFile }
        val plainPath = plain.toNioPath()
        withContext(Dispatchers.EDT) {
            val file = entry.virtualFile
            edit(file, entry.text.replace("macro.s", "macro.txt"))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        val plainCapture = captureAssemblySources(listOf(path), null)
        val plainComponent = MarsSessionService.createMarsComponent()
        val plainProgram = plainComponent.context().use { plainCapture.assembleIn(plainComponent).program() }
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), plainCapture, plainProgram.symbolMap())
        assertEquals(plainPath, plainProgram.symbolMap().locals()[path]?.get("generated")?.origin()?.location()?.file())
        val plainDeclaration = readAction {
            target(entry)!!.also {
                assertEquals(plain, it.containingFile.virtualFile)
                assertEquals("%entry", it.text)
                assertEquals(plainText.lastIndexOf("%entry"), it.textOffset)
            }
        }
        withContext(Dispatchers.EDT) {
            edit(plain, "# changed\n$plainText")
            assertFalse(plainDeclaration.isValid)
            assertNull(target(entry))
            WriteCommandAction.runWriteCommandAction(project) { plain.delete(this) }
            assertFalse(plainDeclaration.isValid)
        }
        val ignored = withContext(Dispatchers.EDT) {
            val file = fixture.tempDirFixture.createFile("macro.txt~", plainText)
            edit(entry.virtualFile, capture.snapshot.texts().getValue(path).replace("macro.s", file.name))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            file
        }
        val ignoredCapture = captureAssemblySources(listOf(path), null)
        val ignoredComponent = MarsSessionService.createMarsComponent()
        val ignoredProgram = ignoredComponent.context().use { ignoredCapture.assembleIn(ignoredComponent).program() }
        analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), ignoredCapture, ignoredProgram.symbolMap())
        readAction {
            assertEquals(ignored.toNioPath(), ignoredProgram.symbolMap().locals()[path]?.get("generated")?.origin()?.location()?.file())
            assertNull(PsiManager.getInstance(project).findFile(ignored))
            assertNull(target(entry))
        }

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
                val updated = capture.copy(snapshot = changed, revisions = revisions)
                analysis.publishBuiltSymbols(analysis.beginSymbolBuild(path), updated, changedResult.program().symbolMap())
                assertFalse(PsiDocumentManager.getInstance(project).isCommitted(includeDocument))
                assertNull(target(entry))
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
        }
        readAction { assertEquals("%entry", target(entry)?.text) }
    }

    @ParameterizedTest
    @CsvSource(
        "efbbbf, UTF-8, fffe, UTF-16LE, s",
        "feff, UTF-16BE, efbbbf, UTF-8, inc",
        "0000feff, UTF-32BE, fffe0000, UTF-32LE, txt",
    )
    fun `marked Unicode files assemble with the same image and source map from disk and IntelliJ documents`(
        entryBom: String, entryEncoding: String, includeBom: String, includeEncoding: String, includeExtension: String,
    ) =
        timeoutRunBlocking<Unit> {
            val includeName = "pièce.$includeExtension"
            val entryText = "\r\n.include \"$includeName\"\r\n.text\r\nentrée: assign(\$t0)"
            val includeText = ".eqv LIMIT 7\r.data\rtexte: .asciiz \"é\uFEFF\"\n" +
                ".macro assign (%reg)\r\n\taddiu %reg, \$zero, LIMIT\r.end_macro"
            val normalizedEntry = entryText.replace("\r\n", "\n")
            val normalizedInclude = includeText.replace("\r\n", "\n").replace('\r', '\n')
            val hex = HexFormat.of()
            val entryBytes = hex.parseHex(entryBom) + entryText.toByteArray(Charset.forName(entryEncoding))
            val includeBytes = hex.parseHex(includeBom) + includeText.toByteArray(Charset.forName(includeEncoding))
            val (entryFile, includeFile) = withContext(Dispatchers.EDT) {
                val source = fixture.tempDirFixture.createFile("entrée.mars")
                val part = fixture.tempDirFixture.createFile(includeName)
                WriteCommandAction.runWriteCommandAction(project) {
                    source.setBinaryContent(entryBytes)
                    part.setBinaryContent(includeBytes)
                }
                // The fixture's caret-markup loader rewrites the file. Open the original bytes.
                FileEditorManager.getInstance(project).openFile(source, FileEditorOpenRequest.withFocus(false))
                source to part
            }
            val entry = entryFile.toNioPath()
            val include = includeFile.toNioPath()
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            readAction {
                val file = PsiManager.getInstance(project).findFile(entryFile) as MipsFile
                assertEquals(normalizedEntry, file.text)
                assertTrue(PsiTreeUtil.findChildrenOfType(file, PsiErrorElement::class.java).isEmpty())
                val includedFile = file.findReferenceAt(file.text.indexOf(includeName))?.resolve()?.containingFile
                assertEquals(include, includedFile?.virtualFile?.toNioPath())
                val includedSyntax = checkNotNull(assemblySourceSyntax(includedFile))
                assertEquals(normalizedInclude, includedSyntax.text)
                assertTrue(PsiTreeUtil.findChildrenOfType(includedSyntax, PsiErrorElement::class.java).isEmpty())
            }
            assertTrue(Files.readAllBytes(entry).contentEquals(entryBytes), "Opening the entry must preserve its bytes")
            assertTrue(Files.readAllBytes(include).contentEquals(includeBytes), "Resolving the include must preserve its bytes")

            val capture = captureAssemblySources(listOf(entry), null)
            assertEquals(
                mapOf(entry to normalizedEntry, include to normalizedInclude),
                capture.snapshot.texts(),
            )
            val component = MarsSessionService.createMarsComponent()
            MarsSessionService.createMarsComponent().context().use { disk ->
                val expected = try {
                    disk.assemble(listOf(entry))
                } catch (failure: ProcessingException) {
                    throw AssertionError(failure.errors().generateErrorReport(), failure)
                }
                component.context().use { ide ->
                    val result = capture.assembleIn(component)
                    assertTrue(result.succeeded(), result.errors().toString())
                    val expectedWords = expected.machineList.map { it.binaryStatement }
                    assertEquals(listOf(0x24080007), expectedWords)
                    assertEquals(expectedWords, ide.program.machineList.map { it.binaryStatement })
                    assertEquals(expected.sourceMap(), result.program().sourceMap())
                    assertEquals(expected.symbolMap(), result.program().symbolMap())
                    val origin = result.program().sourceMap().origins().values.single()
                    assertEquals(Location(include, 5), origin.location())
                    assertEquals(Location(entry, 4), origin.caller().location())
                    val base = ide.memory.dataBaseAddress
                    assertEquals(listOf(0xe9, 0xff, 0), (0..2).map { ide.memory.getByte(base + it) })
                }
            }
        }

    @Test
    fun `build and include navigation follow a symlink before resolving a parent segment`() = timeoutRunBlocking<Unit> {
        val source = """
            .include "shortcut/../value.s"
            .include "value.s"
            .text
            addiu ${'$'}t0, ${'$'}zero, REMOTE
            addiu ${'$'}t1, ${'$'}zero, LOCAL
            addiu ${'$'}t2, ${'$'}zero, NESTED
        """.trimIndent()
        val (entry, remote, local) = withContext(Dispatchers.EDT) {
            val temp = fixture.tempDirFixture
            val main = fixture.addFileToProject("local/main.asm", source).virtualFile
            val localValue = fixture.addFileToProject("local/value.s", ".eqv LOCAL 2\n").virtualFile
            val remoteValue = fixture.addFileToProject("actual/value.s", ".eqv REMOTE 7\n.include \"next.s\"\n").virtualFile
            fixture.addFileToProject("actual/next.s", ".eqv NESTED 8\n")
            val target = temp.findOrCreateDir("actual/dir").toNioPath()
            val link = Files.createSymbolicLink(main.toNioPath().resolveSibling("shortcut"), target)
            checkNotNull(VirtualFileManager.getInstance().refreshAndFindFileByNioPath(link))
            fixture.configureFromExistingVirtualFile(main)
            Triple(main.toNioPath(), remoteValue, localValue)
        }
        IndexingTestUtil.suspendUntilIndexesAreReady(project)
        readAction {
            val file = fixture.file
            val first = source.indexOf("value.s")
            val second = source.lastIndexOf("value.s")
            assertEquals(remote, file.findReferenceAt(first)?.resolve()?.containingFile?.virtualFile)
            assertEquals(local, file.findReferenceAt(second)?.resolve()?.containingFile?.virtualFile)
            assertEquals(
                setOf(remote, local),
                FileIncludeManager.getManager(project).getIncludedFiles(file.virtualFile, true).toSet(),
            )
        }
        val expected = listOf(0x24080007, 0x24090002, 0x240a0008)
        MarsSessionService.createMarsComponent().context().use { disk ->
            assertEquals(expected, disk.assemble(listOf(entry)).machineList.map { it.binaryStatement })
        }
        val capture = captureAssemblySources(listOf(entry), null)
        assertEquals(
            setOf(entry, remote.toNioPath(), local.toNioPath(), remote.toNioPath().resolveSibling("next.s")),
            capture.revisions.keys,
        )
        val component = MarsSessionService.createMarsComponent()
        component.context().use { session ->
            val build = project.service<MarsBuildService>()
            assertTrue(build.assemble(entry, component, activateBuildView = false))
            assertEquals(expected, session.program.machineList.map { it.binaryStatement })

            withContext(Dispatchers.EDT) { edit(remote, ".eqv REMOTE 11\n.include \"next.s\"\n") }
            assertTrue(build.assemble(entry, component, activateBuildView = false))
            assertEquals(listOf(0x2408000b, 0x24090002, 0x240a0008), session.program.machineList.map { it.binaryStatement })
            assertTrue(Files.readString(remote.toNioPath()).contains(".eqv REMOTE 7"))

            val broken = withContext(Dispatchers.EDT) {
                fixture.tempDirFixture.createFile("local/broken.asm", ".include \"value.s\"\n.include \"missing/../value.s\"\n")
            }.toNioPath()
            val failure = captureAssemblySources(listOf(broken), null).assembleIn(component)
            assertFalse(failure.succeeded())
            val error = failure.errors().single()
            assertEquals(broken, error.file())
            assertEquals(2, error.line())
            assertTrue(error.message().contains("Error reading include file"))
        }
    }

    @Test
    fun `build links a recursive include to the directive that closes the cycle`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val path = "\".././main.asm\""
            val child = "# child\n  .include $path\n"
            val (entry, include) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"parts/child.s\"\nnop\n").virtualFile
                val part = fixture.addFileToProject("parts/child.s", "nop\n").virtualFile
                edit(part, child)
                main to part
            }

            invokeAction(entry)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.contains("Recursive include of file"))
            assertEquals(include.toNioPath(), error.filePosition.path)
            assertEquals(1, error.filePosition.startLine)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(child.indexOf(path), link.offset)
            }
            assertInstanceOf(FailureResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)

            withContext(Dispatchers.EDT) { edit(include, "nop\n") }
            invokeAction(entry)
            assertInstanceOf(StartBuildEvent::class.java, events.receive())
            assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build reports each malformed token once at its physical source`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val mainSource = ".include \"child.s\"\n\tfake/.include \"missing.s\"\nnop\n"
            val childSource = "\tbad%name: nop\n"
            val (main, child) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", mainSource).virtualFile
                val child = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(child, childSource)
                main to child
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            for ((file, source, token) in listOf(
                Triple(child, childSource, "bad%name"),
                Triple(main, mainSource, "fake/.include"),
            )) {
                val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
                assertEquals(start.id, error.parentId)
                assertTrue(error.message.endsWith("Invalid language element: $token"), error.message)
                assertEquals(file.toNioPath(), error.filePosition.path)
                val offset = source.indexOf(token)
                assertEquals(source.take(offset).count { it == '\n' }, error.filePosition.startLine)
                assertEquals(1, error.filePosition.startColumn)
                readAction {
                    val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                    assertEquals(file, link.file)
                    assertEquals(offset, link.offset)
                }
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build reports an unfinished string on an unsaved included data continuation`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val line = "  \"unfinished"
            val source = ".data\n.asciiz \"head\"\n$line\n"
            val (main, include) = withContext(Dispatchers.EDT) {
                val entry = fixture.addFileToProject("main.asm", ".include \"strings.s\"\n.text\nnop\n").virtualFile
                val part = fixture.addFileToProject("strings.s", ".data\n.asciiz \"saved\"\n").virtualFile
                edit(part, source)
                entry to part
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertEquals("Unterminated string literal", error.message)
            assertEquals(include.toNioPath(), error.filePosition.path)
            assertEquals(2, error.filePosition.startLine)
            assertEquals(2, error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(include, link.file)
                assertEquals(source.indexOf(line) + 2, link.offset)
            }
            assertInstanceOf(FailureResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
            assertEquals(".data\n.asciiz \"saved\"\n", Files.readString(include.toNioPath()))

            withContext(Dispatchers.EDT) { edit(include, source.replace(line, "$line\"")) }
            invokeAction(main)
            assertInstanceOf(StartBuildEvent::class.java, events.receive())
            assertInstanceOf(SuccessResult::class.java, assertInstanceOf(FinishBuildEvent::class.java, events.receive()).result)
        } finally {
            Disposer.dispose(disposable)
        }
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
    fun `build reports a lexical error once at its written column after equivalence expansion`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val use = "\t.byte FIRST, SECOND, 'ab'"
            val source = ".eqv FIRST 100\n.eqv SECOND 2000\n.data\n$use\n"
            val (main, child) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\n.text\nnop\n").virtualFile
                val child = fixture.addFileToProject("child.s", ".data\n.byte 0\n").virtualFile
                edit(child, source)
                main to child
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.endsWith("Invalid language element: 'ab'"), error.message)
            assertEquals(child.toNioPath(), error.filePosition.path)
            assertEquals(3, error.filePosition.startLine)
            assertEquals(use.indexOf("'ab'"), error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(child, link.file)
                assertEquals(source.indexOf("'ab'"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build links invalid operands after equivalence expansion to the written operand`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val use = "\tentry: lwc1 DEST, ADDRESS \$s29)"
            val source = ".eqv DEST \$f0\n.eqv ADDRESS OFFSET\n.eqv OFFSET -4(\n$use\n"
            val (main, child) = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", ".include \"child.s\"\n").virtualFile
                val child = fixture.addFileToProject("child.s", "nop\n").virtualFile
                edit(child, source)
                main to child
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.contains("operand is of incorrect type"), error.message)
            assertEquals(child.toNioPath(), error.filePosition.path)
            assertEquals(3, error.filePosition.startLine)
            assertEquals(use.indexOf("\$s29"), error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(child, link.file)
                assertEquals(source.indexOf("\$s29"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `build opens the argument forwarded through nested macros in an unsaved include`() = timeoutRunBlocking<Unit> {
        val disposable = Disposer.newDisposable()
        try {
            val events = recordBuildEvents(disposable)
            val call = "outer (BAD)"
            val mainText = ".include \"macros.s\"\n.eqv BAD \$s29\n$call\n"
            val main = withContext(Dispatchers.EDT) {
                val main = fixture.addFileToProject("main.asm", mainText).virtualFile
                val include = fixture.addFileToProject("macros.s", "nop\n").virtualFile
                edit(include, """
                    .macro inner (%argument)
                        lwc1 ${'$'}f0, -4(%argument)
                    .end_macro
                    .macro outer (%forwarded)
                        inner (%forwarded)
                    .end_macro
                """.trimIndent())
                main
            }
            invokeAction(main)
            val start = assertInstanceOf(StartBuildEvent::class.java, events.receive())
            val error = assertInstanceOf(FileMessageEvent::class.java, events.receive())
            assertEquals(start.id, error.parentId)
            assertTrue(error.message.contains("operand is of incorrect type"), error.message)
            assertEquals(main.toNioPath(), error.filePosition.path)
            assertEquals(2, error.filePosition.startLine)
            assertEquals(call.indexOf("BAD"), error.filePosition.startColumn)
            readAction {
                val link = assertInstanceOf(OpenFileDescriptor::class.java, error.getNavigatable(project))
                assertEquals(main, link.file)
                assertEquals(mainText.lastIndexOf("BAD"), link.offset)
            }
            val finish = assertInstanceOf(FinishBuildEvent::class.java, events.receive())
            assertEquals(start.id, finish.id)
            assertInstanceOf(FailureResult::class.java, finish.result)
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

    private fun event(action: AnAction, project: Project?, file: VirtualFile?): AnActionEvent {
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
