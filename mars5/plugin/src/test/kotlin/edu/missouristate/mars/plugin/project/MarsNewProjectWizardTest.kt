package edu.missouristate.mars.plugin.project

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.ide.util.projectWizard.WizardContext
import com.intellij.ide.wizard.GeneratorNewProjectWizard
import com.intellij.ide.wizard.NewProjectWizardBaseData.Companion.baseData
import com.intellij.ide.wizard.NewProjectWizardStepPanel
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.runWriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.GeneralModuleType
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.module.ModuleType
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.task.ProjectTaskManager
import com.intellij.testFramework.IndexingTestUtil
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.junit5.fixture.projectFixture
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.util.ui.UIUtil
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.XDebugSessionListener
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsBuildTask
import edu.missouristate.mars.plugin.debug.MarsLineBreakpointType
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsRunConfiguration
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JCheckBox
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.concurrency.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsNewProjectWizardTest {
    private val projectFixture = projectFixture(openAfterCreation = true)

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `wizard creates a portable project and launches its starter`(separateLibrary: Boolean) = timeoutRunBlocking<Unit> {
        val project = projectFixture.get()
        val root = Path.of(requireNotNull(project.basePath))
        val lifetime = Disposer.newDisposable()
        val paused = CompletableDeferred<XSourcePosition>()
        val exit = CompletableDeferred<Int>()
        val console = StringBuffer()
        val debugger = XDebuggerManager.getInstance(project)
        try {
            val generator = ExtensionPointName.create<GeneratorNewProjectWizard>("com.intellij.newProjectWizard.generator")
                .findExtensionOrFail(MarsNewProjectWizard::class.java)
            assertEquals("MarsMips", generator.id)
            assertEquals("MARS MIPS", generator.name)
            assertSame(MipsFileType.icon, generator.icon)
            withContext(Dispatchers.EDT) {
                val context = WizardContext(null, lifetime)
                val step = generator.createStep(context)
                val base = requireNotNull(step.baseData)
                base.path = root.parent.toString()
                base.name = root.fileName.toString()
                val panel = NewProjectWizardStepPanel(step)
                val option = UIUtil.findComponentsOfType(panel.component, JCheckBox::class.java)
                    .single { it.text == "Add a separate console library" }
                assertFalse(option.isSelected)
                option.isSelected = separateLibrary
                panel.apply()
                step.setupProject(project)
            }
            val assembly = project.service<MarsAssemblySettings>()
            val paths = assembly.projectSources
            val expected = if (separateLibrary) listOf("main.asm", "console.s") else listOf("main.asm")
            assertEquals(expected.map { root.resolve("src").resolve(it) }, paths)
            assertEquals(expected.map { "\$PROJECT_DIR\$/src/$it" }, assembly.getState().sources)
            val module = ModuleManager.getInstance(project).modules.single()
            val roots = ModuleRootManager.getInstance(module)
            assertEquals(GeneralModuleType.TYPE_ID, ModuleType.get(module).id)
            assertNull(roots.sdk)
            assertEquals(root, roots.contentRoots.single().toNioPath())
            assertEquals(root.resolve("src"), roots.sourceRoots.single().toNioPath())
            IndexingTestUtil.suspendUntilIndexesAreReady(project)
            val files = readAction {
                paths.map { path ->
                    requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(path)).also { file ->
                        val psi = assertInstanceOf(MipsFile::class.java, PsiManager.getInstance(project).findFile(file))
                        assertNull(PsiTreeUtil.findChildOfType(psi, PsiErrorElement::class.java))
                    }
                }
            }
            pollAssertionsAsync(5.seconds, 10.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(files.first() in FileEditorManager.getInstance(project).openFiles)
                }
            }
            openStarterInEditor(project, root.resolve("src/deleted.asm"))
            withContext(Dispatchers.EDT) {
                assertEquals(listOf(files.first()), FileEditorManager.getInstance(project).openFiles.toList())
            }
            val sourceSet = requireNotNull(project.service<MarsAssemblySettings>().projectSourceSet())
            val build = ProjectTaskManager.getInstance(project).run(MarsBuildTask(sourceSet)).await()
            assertFalse(build.isAborted)
            assertFalse(build.hasErrors())
            withContext(Dispatchers.EDT) {
                val settings = requireNotNull(RunManager.getInstance(project).selectedConfiguration)
                assertEquals("Run MIPS Program", settings.name)
                assertTrue(settings.isStoredInDotIdeaFolder)
                val config = assertInstanceOf(MarsRunConfiguration::class.java, settings.configuration)
                assertTrue(config.options.useProjectSources)
                assertNull(config.options.sourcePath)
                val executor = if (separateLibrary) DefaultDebugExecutor.getDebugExecutorInstance() else DefaultRunExecutor.getRunExecutorInstance()
                if (separateLibrary) {
                    val breakpointType = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)
                    runWriteAction { debugger.breakpointManager.addLineBreakpoint(breakpointType, files.last().url, 3, null) }
                }
                val environment = ExecutionEnvironmentBuilder.create(executor, settings).build()
                project.messageBus.connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        if (env !== environment) return
                        handler.addProcessListener(object : ProcessListener {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                if (outputType === ProcessOutputTypes.STDOUT) console.append(event.text)
                            }
                            override fun processTerminated(event: ProcessEvent) { exit.complete(event.exitCode) }
                        })
                        if (separateLibrary) {
                            val session = debugger.debugSessions.single()
                            session.addSessionListener(object : XDebugSessionListener {
                                override fun sessionPaused() { paused.complete(requireNotNull(session.topFramePosition)) }
                            }, lifetime)
                        }
                    }
                })
                environment.runner.execute(environment)
            }
            if (separateLibrary) {
                val position = paused.await()
                assertEquals(files.last(), position.file)
                assertEquals(3, position.line)
                withContext(Dispatchers.EDT) { debugger.debugSessions.single().resume() }
            }
            assertEquals(0, exit.await())
            assertEquals("Hello from MARS!\n", console.toString())
        } finally {
            withContext(Dispatchers.EDT) {
                debugger.debugSessions.forEach { it.stop() }
                Disposer.dispose(lifetime)
            }
        }
    }

    @Test
    fun `an existing source directory is preserved and no partial template remains`(@TempDir root: Path) {
        val src = Files.createDirectory(root.resolve("src"))
        val existing = Files.writeString(src.resolve("main.asm"), "existing")
        assertThrows<FileAlreadyExistsException> { writeStarterSources(root, separateLibrary = true) }
        assertEquals("existing", Files.readString(existing))
        assertEquals(listOf(src), Files.list(root).use { it.toList() })
        assertEquals(listOf(existing), Files.list(src).use { it.toList() })
    }
}
