package edu.missouristate.mars.plugin.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.configurations.ConfigurationTypeUtil
import com.intellij.execution.configurations.RunProfile
import com.intellij.execution.Executor
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightFixture
import com.intellij.platform.testFramework.junit5.codeInsight.fixture.codeInsightProjectFixture
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.LightVirtualFile
import com.intellij.testFramework.common.timeoutRunBlocking
import com.intellij.testFramework.junit5.TestApplication
import com.intellij.testFramework.pollAssertionsAsync
import com.intellij.ui.UiInterceptors
import com.intellij.ui.ComponentUtil
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.withForcedRespectIsShowingClientProperty
import com.intellij.util.ui.withShowingChanged
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator.XEvaluationCallback
import com.intellij.xdebugger.frame.XExecutionStack.XStackFrameContainer
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleColoredText
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.editor.colors.impl.EditorColorsManagerImpl
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueTextRendererImpl
import com.intellij.ui.SimpleTextAttributes.STYLE_BOLD
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import com.intellij.xdebugger.impl.actions.XDebuggerActions
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.nodes.XStackFrameNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueGroupNodeImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.api.AssemblySymbolMap
import edu.missouristate.mars.api.AssemblySymbolMap.Definition
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.mips.dump.DumpFormat
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.dump.MemoryDumpSnapshot
import edu.missouristate.mars.mips.hardware.AccessNotice
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryConfigurations
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.MarsMemoryLayout
import edu.missouristate.mars.plugin.analysis.CapturedAssemblySources
import edu.missouristate.mars.plugin.analysis.MarsAnalysisService
import edu.missouristate.mars.plugin.build.MarsAssemblySettings
import edu.missouristate.mars.plugin.build.MarsMemoryExportDialog
import edu.missouristate.mars.plugin.build.MarsMemoryExportService
import edu.missouristate.mars.plugin.build.MemoryExportCapture
import edu.missouristate.mars.plugin.build.MemoryExportOptions
import edu.missouristate.mars.plugin.build.MemoryExportRegion
import edu.missouristate.mars.plugin.build.interceptMemoryExportDialog
import edu.missouristate.mars.plugin.build.replaceMemoryExportSaveDialog
import edu.missouristate.mars.plugin.language.MipsFile
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.run.MarsRunType
import edu.missouristate.mars.plugin.run.MarsBeforeLaunchTask
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunSpeedAction
import edu.missouristate.mars.plugin.run.ProjectOptionOverride
import edu.missouristate.mars.plugin.run.MarsRunOptions
import edu.missouristate.mars.plugin.run.MarsRunService
import edu.missouristate.mars.plugin.run.RunMemoryDumpState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.plugin.tools.FloatingPointView
import java.awt.event.ActionEvent
import java.awt.Color
import java.awt.Font
import java.awt.event.MouseEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.RowSorter.SortKey
import javax.swing.SortOrder
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.jdom.Element
import org.jetbrains.concurrency.await
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDebugPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val debugger get() = XDebuggerManager.getInstance(project)
    private val breakpointType get() = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)

    @Test
    fun `native watchpoint dialog validates and saves inclusive unsigned addresses`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val type = XDebuggerUtil.getInstance().findBreakpointType(MarsMemoryWatchpointType::class.java)
            assertTrue(type.isAddBreakpointButtonVisible)
            val manager = debugger.breakpointManager
            fun intercept(action: (MarsMemoryWatchpointDialog) -> Unit) =
                UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsMemoryWatchpointDialog>(MarsMemoryWatchpointDialog::class.java) {
                    override fun doIntercept(component: MarsMemoryWatchpointDialog) = action(component)
                })
            try {
                intercept { it.doCancelAction() }
                assertNull(type.addBreakpoint(project, null))
                intercept { dialog ->
                    val (first, last) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
                    assertSame(first, dialog.getPreferredFocusedComponent())
                    assertSame(first, dialog.performValidateAll().single().component)
                    first.text = " 0Xfffffffe "
                    last.text = "4294967296"
                    assertSame(last, dialog.performValidateAll().single().component)
                    last.text = "0"
                    assertTrue(dialog.performValidateAll().single().message.contains("at or after"))
                    last.text = "4294967295"
                    @Suppress("UNCHECKED_CAST")
                    val modes = UIUtil.findComponentsOfType(dialog.fields, JComboBox::class.java).single() as JComboBox<MemoryWatchMode>
                    val list = JList(MemoryWatchMode.entries.toTypedArray())
                    for ((index, mode) in MemoryWatchMode.entries.withIndex()) {
                        val rendered = modes.renderer.getListCellRendererComponent(list, mode, index, false, false)
                        assertTrue(rendered.preferredSize.width > 0)
                    }
                    modes.selectedItem = MemoryWatchMode.READ
                    assertTrue(dialog.performValidateAll().isEmpty())
                    dialog.performOKAction()
                }
                val point = requireNotNull(type.addBreakpoint(project, null))
                assertSame(point, manager.getBreakpoints(type).single())
                assertEquals("0xfffffffe to 0xffffffff (Read)", type.getDisplayText(point))
                assertSame(MarsDebugEditors, type.getEditorsProvider(point, project))
                val state = XmlSerializer.serialize(requireNotNull(point.properties).getState())
                val restored = type.createProperties()
                restored.loadState(XmlSerializer.deserialize(state, MarsMemoryWatchpointProperties::class.java))
                val range = requireNotNull(restored.range())
                assertEquals(MemoryWatchRange(0xfffffffeL, 0xffffffffL, MemoryWatchMode.READ), range)
                assertTrue(range.overlaps(MemoryAccessNotice(AccessNotice.READ, 0xfffffffc.toInt(), 0)))
                assertFalse(range.overlaps(MemoryAccessNotice(AccessNotice.READ, 0, 0)))
                assertFalse(range.overlaps(MemoryAccessNotice(AccessNotice.WRITE, 0xfffffffc.toInt(), 0)))
                restored.last++
                assertNull(restored.range())
                restored.last = 1
                assertNull(restored.range())
                val invalid = WriteAction.compute<MemoryWatchpoint, RuntimeException> { manager.addBreakpoint(type, type.createProperties()) }
                assertEquals("Invalid MIPS memory watchpoint", type.getDisplayText(invalid))
                val missing = WriteAction.compute<MemoryWatchpoint, RuntimeException> { manager.addBreakpoint(type, null) }
                assertEquals("Invalid MIPS memory watchpoint", type.getDisplayText(missing))
            } finally {
                UiInterceptors.clear()
            }
        }
    }

    @ParameterizedTest
    @EnumSource(MemoryWatchMode::class)
    internal fun `native memory watchpoints stop after overlapping accesses from the program`(mode: MemoryWatchMode) = timeoutRunBlocking<Unit> {
        val type = XDebuggerUtil.getInstance().findBreakpointType(MarsMemoryWatchpointType::class.java)
        val manager = debugger.breakpointManager
        val address = 0x10010000
        val (source, point) = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("watchpoints.asm", """
                .data
                cell: .word 7
                .text
                la ${'$'}t0, cell
                lbu ${'$'}t1, 0(${'$'}t0)
                lw ${'$'}t1, 0(${'$'}t0)
                li ${'$'}t2, 1
                sb ${'$'}t2, 3(${'$'}t0)
                sh ${'$'}t2, 0(${'$'}t0)
                nop
            """.trimIndent()).virtualFile
            addBreakpoint(file, 3)
            val point = WriteAction.compute<MemoryWatchpoint, RuntimeException> {
                manager.addBreakpoint(type, type.createProperties())
                manager.addBreakpoint(type, null)
                manager.addBreakpoint(type, MarsMemoryWatchpointProperties(0x400000, 0x400007, MemoryWatchMode.READ))
                manager.addBreakpoint(type, MarsMemoryWatchpointProperties(address + 1L, address + 2L, mode))
            }
            file to point
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 3)
            suspend fun readWord(): Int {
                val context = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                val read = CompletableDeferred<Int>()
                context.inspectionRequests.send { read.complete(it.getWord(address)) }
                return read.await()
            }
            assertEquals(7, readWord())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            if (mode != MemoryWatchMode.WRITE) {
                debug.assertPausedAt(source, 6)
                assertEquals(7, readWord())
                withContext(Dispatchers.EDT) { debug.session.resume() }
            }
            if (mode != MemoryWatchMode.READ) {
                debug.assertPausedAt(source, 9)
                assertEquals(0x01000001, readWord())
                withContext(Dispatchers.EDT) {
                    WriteAction.run<RuntimeException> { point.isEnabled = false }
                    debug.session.resume()
                }
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Debug reaches a separate exception handler using unsaved editor text`(beforeLaunch: Boolean) = timeoutRunBlocking<Unit> {
        val (main, kernel) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("exception/main.asm", "break\nnop\n").virtualFile
            val kernel = fixture.addFileToProject("exception/kernel/handler.s", ".ktext 0x80000180\nnop\n").virtualFile
            val document = requireNotNull(FileDocumentManager.getInstance().getDocument(kernel))
            WriteAction.run<RuntimeException> {
                document.setText(".ktext 0x80000180\nli \$t0, 42\nli \$v0, 10\nsyscall\n")
            }
            project.getService(MarsAssemblySettings::class.java).changeExceptionHandler(kernel.path)
            addBreakpoint(kernel, 2)
            main to kernel
        }
        withDebug(main, beforeLaunch = beforeLaunch) { debug ->
            debug.assertPausedAt(kernel, 2)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x0000002a", evaluate(debug.session, frame, "\$t0"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `Debug stops in another assembly source and resolves its own data symbols`(orderedProject: Boolean) = timeoutRunBlocking<Unit> {
        val (main, sibling) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("directory/main.asm", """
                jal sibling
                nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile
            val libraryPath = if (orderedProject) "library/library.s" else "directory/library.s"
            val sibling = fixture.addFileToProject(libraryPath, """
                .data
                siblingValue: .word 23
                .text
                .globl sibling
                sibling: lw ${'$'}t0, siblingValue
                jr ${'$'}ra
                nop
            """.trimIndent()).virtualFile
            if (orderedProject) {
                project.getService(MarsAssemblySettings::class.java).changeSources(listOf(main.path, sibling.path))
            }
            addBreakpoint(sibling, 4)
            main to sibling
        }
        withDebug(main, { assembleDirectory = !orderedProject; useProjectSources = orderedProject }) { debug ->
            debug.assertPausedAt(sibling, 4)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000017", evaluate(debug.session, frame, "word(siblingValue)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `the interactive sort example parses and runs in the native debugger`() = timeoutRunBlocking<Unit> {
        val (entry, macros) = withContext(Dispatchers.EDT) {
            fun source(name: String) = fixture.addFileToProject(name,
                requireNotNull(javaClass.getResourceAsStream("/debug-sort/$name")).bufferedReader().use { it.readText() },
            ).also {
                assertTrue(PsiTreeUtil.findChildrenOfType(it, PsiErrorElement::class.java).isEmpty(), name)
            }
            val macros = source("console.s")
            val main = source("sort-and-search.asm")
            val line = main.text.lineSequence().indexOfFirst { it.startsWith("report_ready:") }
            assertTrue(breakpointType.canPutAt(main.virtualFile, line, project))
            addBreakpoint(main.virtualFile, line)
            main.virtualFile to macros.virtualFile
        }
        withDebug(entry) { debug ->
            val console = withContext(Dispatchers.EDT) {
                assertInstanceOf(ConsoleViewImpl::class.java, debug.session.consoleView).also { it.component }
            }
            pollAssertionsAsync(5.seconds, 10.milliseconds) {
                withContext(Dispatchers.EDT) {
                    assertTrue(console.editor!!.document.text.contains("Search for an integer: "))
                }
            }
            withContext(Dispatchers.EDT) { console.type(console.editor!!, "7\n") }
            // The breakpoint is on a macro call. The execution frame points to its body.
            debug.assertPausedAt(macros, 1)
            withContext(Dispatchers.EDT) {
                val registers = registerValues(debug.session, requireNotNull(debug.session.currentStackFrame))
                assertEquals("10.1 (0x4121999a)", registers["\$f12"])
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
            assertEquals(
                "Before: 19 -4 7 0 23 7 -8 12 42 3\n" +
                    "After:  -8 -4 0 3 7 7 12 19 23 42\n" +
                    "Search for an integer: First index: 4\nSum: 101\nAverage: 10.1\n",
                debug.stdout.toString(),
            )
        }
    }

    @Test
    fun `native debugger stops in an include before its syscall and steps and runs to the cursor`() = timeoutRunBlocking<Unit> {
        val (entry, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("main.s", ".include \"print.s\"\nnop\nnop\n").virtualFile
            val body = fixture.addFileToProject("print.s", "li \$a0, 42\nli \$v0, 1\nsyscall\nnop\n").virtualFile
            assertFalse(breakpointType.canPutAt(main, 0, project))
            assertTrue(breakpointType.canPutAt(body, 2, project))
            assertFalse(breakpointType.canPutAt(body, 12, project))
            assertFalse(breakpointType.canPutAt(LightVirtualFile("scratch.s", MipsFileType, "nop"), 0, project))
            val text = fixture.addFileToProject("other.txt", "nop").virtualFile
            assertFalse(breakpointType.canPutAt(text, 0, project))
            val directory = WriteAction.compute<VirtualFile, RuntimeException> {
                body.parent.createChildDirectory(this, "directory.s")
            }
            assertFalse(breakpointType.canPutAt(directory, 0, project))
            assertNull(breakpointType.createBreakpointProperties(body, 2))
            XDebuggerUtil.getInstance().toggleLineBreakpoint(project, breakpointType, body, 2)
            addBreakpoint(main, 0)
            addBreakpoint(main, 1).also { point ->
                WriteAction.run<RuntimeException> { point.setCondition("0") }
            }
            addBreakpoint(main, 2).also { point ->
                WriteAction.run<RuntimeException> { point.setLogExpression("1") }
            }
            WriteAction.run<RuntimeException> {
                debugger.breakpointManager.addLineBreakpoint(breakpointType, "${main.url}.missing", 0, null)
            }
            main to body
        }
        pollAssertionsAsync(5.seconds, 10.milliseconds) {
            withContext(Dispatchers.EDT) {
                assertEquals(1, debugger.breakpointManager.findBreakpointsAtLine(breakpointType, include, 2).size)
            }
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(include, 2)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            val counter = process.instructionCounter
            assertEquals(2L, counter.snapshot().total)
            assertEquals(listOf(2L, 0L, 0L, 0L, 0L), process.instructionStatistics.countsSnapshot().counts)
            assertEquals("", debug.stdout.toString())
            withContext(Dispatchers.EDT) {
                val stack = requireNotNull(requireNotNull(debug.session.suspendContext).activeExecutionStack)
                stack.computeStackFrames(1, object : XStackFrameContainer {
                    override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                        assertTrue(stackFrames.isEmpty())
                        assertTrue(last)
                    }
                    override fun errorOccurred(errorMessage: String) { fail<Unit>(errorMessage) }
                })
                val scratch = LightVirtualFile("scratch.s", MipsFileType, "nop")
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(scratch, 0)!!, false)
            }
            debug.assertPausedAt(include, 2)
            for (invalidLine in listOf(-1, Int.MAX_VALUE)) {
                withContext(Dispatchers.EDT) {
                    debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(entry, invalidLine)!!, false)
                }
                debug.assertPausedAt(include, 2)
            }
            withContext(Dispatchers.EDT) { debug.session.stepOut() }
            debug.assertPausedAt(include, 2)
            withContext(Dispatchers.EDT) {
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(entry, 0)!!, false)
            }
            debug.assertPausedAt(include, 2)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(include, 3)
            assertEquals(3L, counter.snapshot().total)
            assertEquals(listOf(2L, 0L, 0L, 0L, 1L), process.instructionStatistics.countsSnapshot().counts)
            assertEquals("42", debug.stdout.toString())
            withContext(Dispatchers.EDT) {
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(entry, 2)!!, false)
            }
            debug.assertPausedAt(entry, 2)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals(6L, counter.snapshot().total)
        }
    }

    @Test
    fun `one breakpoint in a macro body stops at each expansion`() = timeoutRunBlocking<Unit> {
        val (entry, definitions) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("macros.s", ".include \"defs.s\"\nemit (42)\nemit (43)\n").virtualFile
            val macros = fixture.addFileToProject("defs.s", """
                .macro emit (%value)
                  li ${'$'}a0, %value
                  li ${'$'}v0, 1
                  syscall
                .end_macro
            """.trimIndent()).virtualFile
            assertTrue(breakpointType.canPutAt(macros, 3, project))
            addBreakpoint(macros, 3)
            main to macros
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(definitions, 3)
            assertEquals("", debug.stdout.toString())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(definitions, 3)
            assertEquals("42", debug.stdout.toString())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals("4243", debug.stdout.toString())
        }
    }

    @Test
    fun `a macro parameter supplied through an alias binds a native breakpoint when used as an instruction`() =
        timeoutRunBlocking<Unit> {
            val entry = withContext(Dispatchers.EDT) {
                val source = fixture.addFileToProject("parameter-operation.asm", """
                    .eqv HEAD %operation
                    .macro emit (%operation, %value)
                      HEAD ${'$'}a0, %value
                      li ${'$'}v0, 1
                      syscall
                    .end_macro
                    emit(li, 42)
                    li ${'$'}v0, 10
                    syscall
                """.trimIndent()).virtualFile
                val outside = fixture.addFileToProject("no-macro.asm", ".eqv HEAD %operation\nHEAD\n").virtualFile
                assertFalse(breakpointType.canPutAt(outside, 1, project))
                assertTrue(breakpointType.canPutAt(source, 2, project))
                addBreakpoint(source, 2)
                source
            }
            withDebug(entry) { debug ->
                debug.assertPausedAt(entry, 2)
                assertEquals("", debug.stdout.toString())
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                assertEquals("42", debug.stdout.toString())
            }
        }

    @Test
    fun `aliased instructions and macro calls bind native breakpoints to their machine code`() = timeoutRunBlocking<Unit> {
        val (entry, definitions) = withContext(Dispatchers.EDT) {
            val definitions = fixture.addFileToProject("aliases.s", """
                .eqv DEFINE .eqv
                DEFINE AREA .data
                DEFINE WORDS .word
                DEFINE VALUE 2
                DEFINE ROW location:
                DEFINE CODE .text
                DEFINE SETUP li ${'$'}a0
                DEFINE PREFIX before:
                DEFINE CALL emit
                .macro emit (%value)
                  li ${'$'}a0, %value
                  li ${'$'}v0, 1
                  syscall
                .end_macro
            """.trimIndent() + "\n").virtualFile
            val entry = fixture.addFileToProject("aliased-breakpoints.asm", """
                .include "aliases.s"
                AREA
                WORDS 7
                VALUE
                ROW
                CODE
                start: SETUP 42
                li ${'$'}v0, 1
                syscall
                PREFIX CALL(43)
            """.trimIndent() + "\n").virtualFile
            for (line in 0..5) assertFalse(breakpointType.canPutAt(entry, line, project), "line $line")
            for (line in listOf(6, 9)) {
                assertTrue(breakpointType.canPutAt(entry, line, project), "line $line")
                XDebuggerUtil.getInstance().toggleLineBreakpoint(project, breakpointType, entry, line)
            }
            entry to definitions
        }
        pollAssertionsAsync(5.seconds, 10.milliseconds) {
            withContext(Dispatchers.EDT) {
                for (line in listOf(6, 9)) {
                    assertEquals(1, debugger.breakpointManager.findBreakpointsAtLine(breakpointType, entry, line).size)
                }
            }
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(entry, 6)
            assertEquals("", debug.stdout.toString())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(definitions, 10)
            assertEquals("42", debug.stdout.toString())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals("4243", debug.stdout.toString())
        }
    }

    @Test
    fun `incomplete or unavailable alias context keeps breakpoint placement provisional`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("provisional.s", """
                .eqv FIRST SECOND
                .eqv SECOND FIRST
                .eqv AREA .data
                FIRST 1
                AREA
                unknown 1
                .eqv SEP :
                empty SEP
                executable SEP nop
                addiu SEP
                syscall SEP nop
            """.trimIndent()).virtualFile
            assertTrue(breakpointType.canPutAt(file, 3, project))
            assertFalse(breakpointType.canPutAt(file, 4, project))
            assertTrue(breakpointType.canPutAt(file, 5, project))
            assertFalse(breakpointType.canPutAt(file, 7, project))
            assertTrue(breakpointType.canPutAt(file, 8, project))
            assertFalse(breakpointType.canPutAt(file, 9, project))
            assertTrue(breakpointType.canPutAt(file, 10, project))
            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                assertTrue(breakpointType.canPutAt(file, 4, project))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `suspension tolerates an instruction pointer without an assembled source line`(invalidAddress: Boolean) = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            val assembly = if (invalidAddress) "jr \$zero\n" else "nop\n"
            fixture.addFileToProject("missing.s", assembly).virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 0)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            assertNull(debug.nextPosition())
            val pause = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
            val edited = CompletableDeferred<String?>()
            pause.machineEdits.submitEdit(MemoryWordEditRequest(0x10010000, "42", object : XModificationCallback {
                override fun valueModified() { edited.complete(null) }
                override fun errorOccurred(errorMessage: String) { edited.complete(errorMessage) }
            }))
            assertNull(edited.await())
            assertNull(pause.frame.sourcePosition)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            assertEquals(if (invalidAddress) 1 else 0, debug.exit.await())
        }
    }

    @Test
    fun `late debugger commands cannot replace an accepted step or advance a later suspension`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("late-commands.asm", "nop\nnop\nnop\n").virtualFile.also {
                addBreakpoint(it, 0)
                addBreakpoint(it, 2)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 0)
            val session = debug.session
            val process = session.debugProcess
            val first = assertInstanceOf(MarsSuspendContext::class.java, session.suspendContext)
            val inspecting = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            first.inspectionRequests.send {
                inspecting.complete(Unit)
                release.await()
            }
            val target = requireNotNull(XDebuggerUtil.getInstance().createPosition(source, 2))
            try {
                inspecting.await()
                withContext(Dispatchers.EDT) {
                    session.stepInto()
                    process.startStepOut(first)
                    process.resume(first)
                    session.resume()
                    val (action, event) = backstepAction(session)
                    assertFalse(event.presentation.isEnabled)
                    action.actionPerformed(event)
                    assertFalse(session.isSuspended)
                }
            } finally {
                release.complete(Unit)
            }
            debug.assertPausedAt(source, 1)
            val second = assertInstanceOf(MarsSuspendContext::class.java, session.suspendContext)
            withContext(Dispatchers.EDT) {
                process.resume(first)
                process.startStepInto(first)
                process.startStepOver(first)
                process.startStepOut(first)
                process.runToPosition(target, first)
                assertSame(second, session.suspendContext)
            }
            // The worker selects commands before inspections, so this acknowledgement requires
            // the later pause to have rejected every callback above.
            val verified = CompletableDeferred<Unit>()
            second.inspectionRequests.send { verified.complete(Unit) }
            verified.await()
            withContext(Dispatchers.EDT) { session.resume() }
            debug.assertPausedAt(source, 2)
            withContext(Dispatchers.EDT) { session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `source steps group pseudo instructions and distinguish consecutive macro expansions`() = timeoutRunBlocking<Unit> {
        val (entry, macros) = withContext(Dispatchers.EDT) {
            val macro = fixture.addFileToProject("step-macros.s", ".macro load (%value)\nli \$t1, %value\n.end_macro\n").virtualFile
            val file = fixture.addFileToProject("steps.asm", """
                .include "step-macros.s"
                li ${'$'}t0, 0x12345678
                load(0x23456789)
                load(0x3456789a)
                nop
            """.trimIndent()).virtualFile
            addBreakpoint(file, 1)
            val finish = addBreakpoint(file, 4)
            WriteAction.run<RuntimeException> { finish.setCondition("0") }
            file to macro
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(entry, 1)
            withContext(Dispatchers.EDT) {
                val (action, event) = toolbarAction(debug.session, "Step Instruction")
                assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
                assertTrue(event.presentation.isEnabled)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(entry, 1)
            withContext(Dispatchers.EDT) {
                assertEquals("0x00400004", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "pc"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(macros, 1)
            withContext(Dispatchers.EDT) {
                assertEquals("0x12345678", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                val (action, event) = toolbarAction(debug.session, "Step Instruction")
                action.actionPerformed(event)
            }
            debug.assertPausedAt(macros, 1)
            withContext(Dispatchers.EDT) {
                assertEquals("0x0040000c", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "pc"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(macros, 1)
            withContext(Dispatchers.EDT) {
                assertEquals("0x23456789", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t1"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(entry, 4)
            withContext(Dispatchers.EDT) {
                assertEquals("0x3456789a", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t1"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `source stepping visits a configured branch delay slot before the target`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("debug-delay.s", "b target\nli \$t0, 7\nli \$t0, 9\ntarget: nop\n")
                .virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file, { delayedBranchingMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(file, 0)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            assertTrue(process.branchHistory.snapshot().recent.isEmpty())
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(file, 1)
            val branch = process.branchHistory.snapshot().recent.single()
            assertTrue(branch.taken)
            assertEquals(branch.pc + 12, branch.target)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(file, 3)
            assertEquals(listOf(branch), process.branchHistory.snapshot().recent)
            withContext(Dispatchers.EDT) {
                assertEquals("0x00000007", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `step over a recursive call stops after all matching returns`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-over-recursion.s", """
                li ${'$'}a0, 2
                jal descend
                done: nop
                li ${'$'}v0, 10
                syscall
                descend:
                beq ${'$'}a0, ${'$'}zero, leaf
                addiu ${'$'}sp, ${'$'}sp, -4
                sw ${'$'}ra, 0(${'$'}sp)
                addiu ${'$'}a0, ${'$'}a0, -1
                jal descend
                return_again: lw ${'$'}ra, 0(${'$'}sp)
                addiu ${'$'}sp, ${'$'}sp, 4
                leaf: addiu ${'$'}t0, ${'$'}t0, 1
                jr ${'$'}ra
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 1)
                val logpoint = addBreakpoint(it, 10)
                WriteAction.run<RuntimeException> { logpoint.setSuspendPolicy(SuspendPolicy.NONE) }
            }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(file, 2)
            withContext(Dispatchers.EDT) {
                assertEquals("0x00000003", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `step over respects delay slots custom links and enabled breakpoints`(breakInsideCall: Boolean) = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-over-link.s", """
                la ${'$'}t0, callee
                jalr ${'$'}s0, ${'$'}t0
                addiu ${'$'}t2, ${'$'}t2, 1
                done: nop
                li ${'$'}v0, 10
                syscall
                callee: j tail
                nop
                tail: addiu ${'$'}t2, ${'$'}t2, 2
                jr ${'$'}s0
                nop
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 1)
                if (breakInsideCall) addBreakpoint(it, 8)
            }
        }
        withDebug(file, { delayedBranchingMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(file, if (breakInsideCall) 8 else 3)
            withContext(Dispatchers.EDT) {
                val result = if (breakInsideCall) "0x00000001" else "0x00000003"
                assertEquals(result, evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t2"))
                if (breakInsideCall) debug.session.stepOut() else debug.session.resume()
            }
            if (breakInsideCall) {
                debug.assertPausedAt(file, 3)
                withContext(Dispatchers.EDT) { debug.session.resume() }
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `step out tracks repeated recursive return addresses and restores a caller on backstep`(wholeSource: Boolean) = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-out-recursion.s", """
                li ${'$'}a0, 2
                jal descend
                done: li ${'$'}v0, 10
                syscall
                descend: beq ${'$'}a0, ${'$'}zero, leaf
                addiu ${'$'}sp, ${'$'}sp, -4
                sw ${'$'}ra, 0(${'$'}sp)
                addiu ${'$'}a0, ${'$'}a0, -1
                jal descend
                return_again: lw ${'$'}ra, 0(${'$'}sp)
                addiu ${'$'}sp, ${'$'}sp, 4
                leaf: jr ${'$'}ra
            """.trimIndent()).virtualFile.also {
                val breakpoint = addBreakpoint(it, 4)
                WriteAction.run<RuntimeException> { breakpoint.setCondition("\$a0 == 0") }
            }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 4)
            val nested = debug.frames()
            assertEquals(listOf(4, 8, 8, 1), nested.map { it.sourcePosition?.line })
            assertEquals(3, nested.drop(1).map { it.equalityObject }.toSet().size)
            assertNull(nested[1].evaluator)
            assertEquals(nested.drop(2).map { it.equalityObject }, debug.frames(2).map { it.equalityObject })
            assertTrue(debug.frames(4).isEmpty())
            for (line in listOf(9, 9, 2)) {
                withContext(Dispatchers.EDT) { debug.session.stepOut() }
                debug.assertPausedAt(file, line)
            }
            assertEquals(listOf(2), debug.frames().map { it.sourcePosition?.line })
            withContext(Dispatchers.EDT) {
                val (action, event) = if (wholeSource) toolbarAction(debug.session, "Backstep Source Statement") else backstepAction(debug.session)
                assertTrue(event.presentation.isEnabled)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(file, 11)
            val rewound = debug.frames(1).single()
            assertEquals(1, rewound.sourcePosition?.line)
            assertEquals(nested.last().equalityObject, rewound.equalityObject)
            withContext(Dispatchers.EDT) { debug.session.stepOut() }
            debug.assertPausedAt(file, 2)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `step out stops at a trap and can still return to the caller after eret`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-out-trap.s", """
                jal callee
                done: li ${'$'}v0, 10
                syscall
                callee: nop
                break
                jr ${'$'}ra
                .ktext 0x80000180
                mfc0 ${'$'}k0, ${'$'}14
                addiu ${'$'}k0, ${'$'}k0, 4
                mtc0 ${'$'}k0, ${'$'}14
                eret
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 3)
                addBreakpoint(it, 10)
            }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 3)
            withContext(Dispatchers.EDT) { debug.session.stepOut() }
            debug.assertPausedAt(file, 7)
            val (interrupted, caller) = debug.frames(1)
            assertEquals(4, interrupted.sourcePosition?.line)
            assertEquals(0, caller.sourcePosition?.line)
            withContext(Dispatchers.EDT) {
                assertEquals("Interrupted at step-out-trap.s:5", frameLabel(interrupted))
                assertEquals("Caller at step-out-trap.s:1", frameLabel(caller))
                assertEquals(mapOf("Interrupted address" to "0x00400010"), callerFields(debug.session, interrupted, 1))
                assertEquals(
                    mapOf("Call address" to "0x00400000", "Return address" to "0x00400004", "Link register" to "\$31"),
                    callerFields(debug.session, caller, 3),
                )
            }
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(file, 10)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(file, 5)
            assertEquals(caller.equalityObject, debug.frames(1).single().equalityObject)
            withContext(Dispatchers.EDT) { debug.session.stepOut() }
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `deep caller stacks deliver multiple batches and keep executable data addresses`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("deep-callers.s", """
                .data
                code: .word 0x0320f809, 0x2402000a, 0x0000000c
                .text
                la ${'$'}t9, descend
                la ${'$'}t0, code
                li ${'$'}a0, 65
                jr ${'$'}t0
                descend: beq ${'$'}a0, ${'$'}zero, leaf
                addiu ${'$'}a0, ${'$'}a0, -1
                jal descend
                leaf: nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 10) }
        }
        withDebug(file, { selfModifyingCodeMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(file, 10)
            val frames = debug.frames()
            assertEquals(67, frames.size)
            assertEquals(66, frames.drop(1).map { it.equalityObject }.toSet().size)
            val tail = debug.frames(64)
            assertEquals(listOf(9, 9, null), tail.map { it.sourcePosition?.line })
            val obsolete = CompletableDeferred<Unit>()
            withContext(Dispatchers.EDT) {
                assertEquals("Caller at 0x10010000", frameLabel(tail.last()))
                requireNotNull(debug.session.suspendContext?.activeExecutionStack).computeStackFrames(0, object : XStackFrameContainer {
                    override fun isObsolete(): Boolean {
                        obsolete.complete(Unit)
                        return true
                    }

                    override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) = fail<Unit>("Received obsolete frames")
                    override fun errorOccurred(errorMessage: String) = fail<Unit>(errorMessage)
                })
            }
            obsolete.await()
            withContext(Dispatchers.EDT) { debug.session.stop() }
            assertEquals(130, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `step over stops at an exception handler or reports an unhandled fault`(hasHandler: Boolean) = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            val handler = if (hasHandler) ".ktext 0x80000180\nhandler: nop\n" else ""
            fixture.addFileToProject("step-over-trap.s", "jal callee\nnop\ncallee: break\n$handler")
                .virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            if (hasHandler) {
                debug.assertPausedAt(file, 4)
                withContext(Dispatchers.EDT) { debug.session.stop() }
            }
            assertEquals(if (hasHandler) 130 else 1, debug.exit.await())
        }
    }

    @Test
    fun `step over groups a pseudo instruction and follows a jump without a call`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-over-pseudo.s", "li \$t0, 0x12345678\nj end\nnop\nend: nop\n")
                .virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) {
                assertEquals("0x12345678", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                debug.session.stepOver(false)
            }
            debug.assertPausedAt(file, 3)
            assertTrue(debug.frames(1).isEmpty())
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `debugging inherits project self modifying code and steps the rewritten instruction without stale source`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            project.service<MarsAssemblySettings>().changeSelfModifyingCode(true)
            fixture.addFileToProject("debug-patch.s", """
                la ${'$'}t0, patch
                li ${'$'}t1, 0x240a002a
                sw ${'$'}t1, 0(${'$'}t0)
                patch: nop
                after_patch: nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 2) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 2)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            assertNull(debug.nextPosition())
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x240a002a", evaluate(debug.session, frame, "word(pc)"))
                assertEquals("0x00000000", evaluate(debug.session, frame, "\$t2"))
                debug.session.stepOver(false)
            }
            debug.assertPausedAt(file, 4)
            withContext(Dispatchers.EDT) {
                assertEquals("0x0000002a", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t2"))
                val (action, event) = toolbarAction(debug.session, "Backstep Source Statement")
                action.actionPerformed(event)
            }
            assertNull(debug.nextPosition())
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000000", evaluate(debug.session, frame, "\$t2"))
                assertEquals("0x240a002a", evaluate(debug.session, frame, "word(pc)"))
                val (action, event) = toolbarAction(debug.session, "Backstep Source Statement")
                action.actionPerformed(event)
            }
            debug.assertPausedAt(file, 2)
            withContext(Dispatchers.EDT) {
                assertEquals("0x00000000", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "word(pc+4)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `source steps keep included statements and repeated macro calls separate`(macro: Boolean) = timeoutRunBlocking<Unit> {
        val bodyLine = if (macro) 1 else 0
        val secondLine = 1
        val afterLine = if (macro) 3 else 1
        val (entry, include) = withContext(Dispatchers.EDT) {
            val instruction = "li \$t0, 0x12345678\n"
            val bodyText = if (macro) ".macro load\n${instruction}.end_macro\n" else instruction.repeat(2)
            val calls = if (macro) "load()\nload()\n" else ""
            val entryText = ".include \"undo-body.s\"\n${calls}after: nop\n"
            val body = fixture.addFileToProject("undo-body.s", bodyText).virtualFile
            val file = fixture.addFileToProject("undo-expansions.asm", entryText).virtualFile
            addBreakpoint(body, bodyLine)
            file to body
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(include, bodyLine)
            val (action, event) = withContext(Dispatchers.EDT) {
                val result = toolbarAction(debug.session, "Backstep Source Statement")
                assertFalse(result.second.presentation.isEnabled)
                result.first.actionPerformed(result.second)
                assertTrue(debug.session.isSuspended)
                debug.session.setBreakpointMuted(true)
                debug.session.stepInto()
                result
            }
            debug.assertPausedAt(include, secondLine)
            withContext(Dispatchers.EDT) {
                assertEquals("0x00400008", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "pc"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(entry, afterLine)
            for ((line, pc, register) in listOf(
                Triple(secondLine, "0x00400008", "0x12345678"),
                Triple(bodyLine, "0x00400000", "0x00000000"),
            )) {
                withContext(Dispatchers.EDT) {
                    action.update(event)
                    assertTrue(event.presentation.isEnabled)
                    action.actionPerformed(event)
                }
                debug.assertPausedAt(include, line)
                withContext(Dispatchers.EDT) {
                    val frame = requireNotNull(debug.session.currentStackFrame)
                    assertEquals(pc, evaluate(debug.session, frame, "pc"))
                    assertEquals(register, evaluate(debug.session, frame, "\$t0"))
                }
            }
            withContext(Dispatchers.EDT) {
                action.update(event)
                assertFalse(event.presentation.isEnabled)
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `source step stops on another execution of a single line loop`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-loop.s", "loop: b loop\n").virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) {
                debug.session.setBreakpointMuted(true)
                debug.session.stepInto()
            }
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) {
                val (action, event) = backstepAction(debug.session)
                assertTrue(event.presentation.isEnabled)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) {
                assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                debug.session.stop()
            }
            assertEquals(130, debug.exit.await())
        }
    }

    @Test
    fun `source boundary treats misaligned and unmapped kernel positions as unknown`() {
        val component = MarsSessionService.createMarsComponent()
        component.context().use { context ->
            val memory = context.memory
            val origin = Origin(Location(Path.of("source.s"), 1), null)
            val step = SourceStep(memory.textBaseAddress, origin)
            assertTrue(step.reached(memory, memory.textBaseAddress + 1))
            assertTrue(step.reached(memory, memory.kernelTextBaseAddress))
        }
    }

    @Test
    fun `native Variables tree renders coherent CPU and coprocessor values from each suspension`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("registers.s", """
                ${'$'}入口: addiu ${'$'}0x8, ${'$'}zero, -2
                addiu ${'$'}t1, ${'$'}zero, 3
                mult ${'$'}t0, ${'$'}t1
                lui ${'$'}t2, 0x3ff8
                mtc1 ${'$'}t2, ${'$'}f1
                c.eq.s ${'$'}f0, ${'$'}f0
                addiu ${'$'}t3, ${'$'}zero, 0x1234
                mtc0 ${'$'}t3, ${'$'}14
                stop: nop
                .globl stop
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 0)
                addBreakpoint(it, 8)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 0)
            val first = withContext(Dispatchers.EDT) { requireNotNull(debug.session.currentStackFrame) }
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 8)
            withContext(Dispatchers.EDT) {
                val second = requireNotNull(debug.session.currentStackFrame)
                assertNotSame(first, second)
                assertEquals(first.equalityObject, second.equalityObject)
                // Open the original frame after running further to prove that its values were captured.
                val initial = registerValues(debug.session, first)
                val current = registerValues(debug.session, second)
                assertEquals("0x00400000", initial["pc"])
                assertEquals("0x00400020", current["pc"])
                assertEquals("0x00000000", initial["\$t0"])
                assertEquals("0xfffffffe", current["\$t0"])
                assertEquals("0xffffffff", current["hi"])
                assertEquals("0xfffffffa", current["lo"])
                assertEquals("0x00000000", initial["\$14 (epc)"])
                assertEquals("0x00001234", current["\$14 (epc)"])
                assertEquals("0x00000000", initial["FCC"])
                assertEquals("0x00000001", current["FCC"])
                assertEquals("1.9375 (0x3ff80000)", current["\$f1"])
                assertEquals("1.5 (0x3ff8000000000000)", current["\$f0/\$f1"])
                assertFalse(requireNotNull(second.evaluator).isCodeFragmentEvaluationSupported)
                assertEquals("0x00000000", evaluate(debug.session, first, "\$0x8"))
                val expressions = mapOf(
                    "\$t0" to "0xfffffffe",
                    "\$8" to "0xfffffffe",
                    "\$0x8" to "0xfffffffe",
                    "\$010" to "0xfffffffe",
                    "\$٨" to "0xfffffffe",
                    "pc" to "0x00400020",
                    "hi" to "0xffffffff",
                    "lo" to "0xfffffffa",
                    "cp0.\$14" to "0x00001234",
                    "FCC" to "0x00000001",
                    "\$f1" to "1.9375 (0x3ff80000)",
                    "\$入口" to "0x00400000",
                    "stop" to "0x00400020",
                    " 'A' " to "0x00000041",
                    "(+0x10 - (2 + 3))" to "0x0000000b",
                    "1 - 2 - 3" to "0xfffffffc",
                    "0xffffffff + 2" to "0x00000001",
                    "0x1e-5" to "0x00000019",
                    "0x1.8p-2" to "0.375",
                    "0X1.8P+2" to "6.0",
                    "1e-2 + 1" to "1.01",
                    "(\$t1 + 0.5) - 1" to "2.5",
                    "\$f1 + 1" to "2.9375",
                    "-1.5" to "-1.5",
                    "\$t0==-2" to "0x00000001",
                    "\$t0==2" to "0x00000000",
                    "\$t0 != 2" to "0x00000001",
                    "\$t0 != -2" to "0x00000000",
                    "\$t0<0" to "0x00000001",
                    "\$t0< -2" to "0x00000000",
                    "\$t0 <= -2" to "0x00000001",
                    "\$t0 <= -3" to "0x00000000",
                    "\$t0 > -3" to "0x00000001",
                    "\$t0 > 0" to "0x00000000",
                    "\$t0 >= -2" to "0x00000001",
                    "\$t0 >= 0" to "0x00000000",
                    "1 == 2 < 3 + 1" to "0x00000001",
                    "(1 + (2 < 3)) == 2" to "0x00000001",
                    "3 > 2 > 0" to "0x00000001",
                    "\$f1 > 1" to "0x00000001",
                    "NaN != NaN" to "0x00000001",
                    "NaN >= 0" to "0x00000000",
                    "-0.0 == 0.0" to "0x00000001",
                    "'<' < '>'" to "0x00000001",
                    "'\\377'" to "0x000000ff",
                    "1 | 2 ^ 3 & 6" to "0x00000001",
                    "4 & 2 == 2" to "0x00000000",
                    "1 << 2 + 1" to "0x00000008",
                    "1 << 2 < 5" to "0x00000001",
                    "0x80000000 >> 1" to "0xc0000000",
                    "0x80000000 >>> 1" to "0x40000000",
                    "8 >> 1 >> 1" to "0x00000002",
                    "1 << 32" to "0x00000001",
                    "~1 + 2" to "0x00000000",
                    "~~1" to "0x00000001",
                )
                for ((expression, expected) in expressions) {
                    assertEquals(expected, evaluate(debug.session, second, expression), expression)
                }
                for (expression in listOf("", "1".repeat(1025), "1\n2", "1\r2", "1,2", "1+", "\"text\"",
                    "\$t0(\$t1)", "1 + unknown", "unknown + 1", "-unknown", "syscall", "\$t0 = 3",
                    "1 <", "1 ==", "!= 1", "1 ! 2", "1 < = 2", "1,==1", "(1,)", "1 # == 2", "1:2",
                    "1 |", "1 ^", "1 &", "1 >>", "~", "1 && 1", "1 <<= 2", "1 & 2.5", "2.5 | 1", "~2.5",
                    "'ab'", "'\\400'", "'\\q'", "0xGG", "\$32", "\$f32")) {
                    assertEquals("Enter a valid MIPS debugger expression using registers, labels, numbers, memory reads, arithmetic, comparisons, or bitwise operators.",
                        evaluate(debug.session, second, expression), expression)
                }
                assertEquals("0xfffffffe", evaluate(debug.session, second, "\$t0"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Quick Evaluate supplies native expression ranges and reads the right register bank`() = timeoutRunBlocking<Unit> {
        val (source, unrelated, unfinished) = withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("quick-evaluate.mars", """
                .data
                pc: .word 42
                message: .asciiz "text"
                .text
                addiu ${'$'}0x8, ${'$'}zero, -2
                mtc0 ${'$'}0x8, ${'$'}14
                la ${'$'}t1, pc
                ori ${'$'}t2, ${'$'}zero, 'A'
                addiu ${'$'}t0, ${'$'}zero, 7
                stop: nop # ignored
                .extern external, 4
                .extern -4, 4
                .extern 'B', 4
                .extern "reserved", 4
                .extern %storage, 4
                .eqv NAME )
                .extern NAME, 4
                .extern -4, 8
            """.trimIndent()).virtualFile
            addBreakpoint(source, 9)
            Triple(source, fixture.addFileToProject("outside.txt", "42").virtualFile,
                fixture.addFileToProject("unfinished.mars", "j absent\nmtc0 \$t0, \$2\nmtc0 \$t0, \$f1\n.word \$t0\n.word -(1)").virtualFile)
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 9)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val evaluator = requireNotNull(frame.evaluator)
                val documents = FileDocumentManager.getInstance()
                val document = requireNotNull(documents.getDocument(source))
                suspend fun assertHint(start: Int, length: Int, expected: String, cursor: Int = start) {
                    val info = requireNotNull(evaluator.getExpressionInfoAtOffsetAsync(project, document, cursor, false).await())
                    val range = TextRange(start, start + length)
                    assertEquals(range, info.textRange)
                    val text = range.substring(document.text)
                    assertEquals(text, info.displayText ?: text)
                    assertEquals(expected, evaluate(debug.session, frame, info.expressionText ?: text))
                }
                val hexRegister = document.text.indexOf("\$0x8")
                assertHint(hexRegister, 4, "0x00000007")
                assertHint(document.text.lastIndexOf("\$0x8"), 4, "0x00000007")
                assertHint(document.text.indexOf("\$14"), 3, "0xfffffffe")
                val signed = document.text.indexOf("-2")
                assertHint(signed, 2, "0xfffffffe")
                assertHint(signed, 2, "0xfffffffe", signed + 1)
                assertHint(document.text.indexOf("42"), 2, "0x0000002a")
                assertHint(document.text.indexOf("'A'"), 3, "0x00000041")
                assertHint(document.text.indexOf("pc"), 2, "0x10010000")
                assertHint(document.text.lastIndexOf("pc"), 2, "0x10010000")
                for ((name, address) in mapOf(
                    "external" to "0x10000000",
                    "-4" to "0x10000004",
                    "'B'" to "0x10000008",
                    "\"reserved\"" to "0x1000000c",
                    "%storage" to "0x10000010",
                    "NAME, 4" to "0x10000014",
                )) {
                    val spelling = name.substringBefore(',')
                    val start = document.text.indexOf(name)
                    assertHint(start, spelling.length, address)
                    assertHint(start, spelling.length, address, start + spelling.lastIndex)
                }
                assertHint(document.text.lastIndexOf("-4"), 2, "0x10000004")
                for (word in listOf(":", "addiu", "\"text\"", "ignored")) {
                    assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, document, document.text.indexOf(word), true).await(), word)
                }
                assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, document, document.textLength, false).await())
                val textDocument = requireNotNull(documents.getDocument(unrelated))
                assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, textDocument, 0, false).await())
                val draft = requireNotNull(documents.getDocument(unfinished))
                for (word in listOf("absent", "\$2", "\$f1", "-")) {
                    assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, draft, draft.text.indexOf(word), false).await(), word)
                }
                val draftRegister = requireNotNull(evaluator.getExpressionInfoAtOffsetAsync(project, draft, draft.text.lastIndexOf("\$t0"), false).await())
                assertEquals("0x00000007", evaluate(debug.session, frame, requireNotNull(draftRegister.expressionText)))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Quick Evaluate follows operands in the loaded macro expansion`() = timeoutRunBlocking<Unit> {
        val (source, library) = withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("expanded.mars", """
                .eqv TARGET ${'$'}t0
                .eqv LIMIT -7
                .eqv ADDRESS 0(${'$'}sp)
                .eqv PTR pc
                .eqv LP (
                .eqv EPC ${'$'}14
                .include "setup.inc"
                .data
                pc: .word 42
                .text
                addiu ${'$'}t0, ${'$'}zero, 17
                addiu ${'$'}t1, ${'$'}zero, 23
                set(TARGET, LIMIT)
                set(${'$'}t1, 0x12345678)
                la ${'$'}t2, PTR
                mtc0 ${'$'}t0, EPC
                lw ${'$'}t3, ADDRESS
                lw ${'$'}t3, 0 LP ${'$'}sp)
                forward(${'$'}t1)
                mixed(${'$'}14)
                nop
            """.trimIndent()).virtualFile
            val library = fixture.addFileToProject("setup.inc", """
                .macro set (%reg, %value)
                li %reg, %value
                .end_macro
                .macro repeat (%item)
                addu %item, %item, ${'$'}zero
                .end_macro
                .macro forward (%passed)
                repeat(%passed)
                .end_macro
                .macro mixed (%bank)
                mtc0 %bank, %bank
                .end_macro
            """.trimIndent()).virtualFile
            for (line in listOf(1, 4, 10)) addBreakpoint(library, line)
            for (line in 14..17) addBreakpoint(source, line)
            source to library
        }
        withDebug(source) { debug ->
            suspend fun hint(file: VirtualFile, text: String, expected: String?) = withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
                val offset = document.text.lastIndexOf(text)
                val info = requireNotNull(frame.evaluator).getExpressionInfoAtOffsetAsync(project, document, offset, false).await()
                if (expected == null) {
                    assertNull(info, text)
                } else {
                    val expression = requireNotNull(info)
                    assertEquals(TextRange(offset, offset + text.length), expression.textRange)
                    assertEquals(text, expression.displayText)
                    assertEquals(expected, evaluate(debug.session, frame, requireNotNull(expression.expressionText)))
                }
            }
            debug.assertPausedAt(library, 1)
            hint(library, "%reg", "0x00000011")
            hint(library, "%value", "0xfffffff9")
            hint(source, "TARGET", "0x00000011")
            hint(source, "LIMIT", "0xfffffff9")
            withContext(Dispatchers.EDT) {
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(source))
                val before = document.text
                WriteCommandAction.runWriteCommandAction(project) { document.insertString(document.textLength, "\n# edited") }
                hint(source, "LIMIT", null)
                WriteCommandAction.runWriteCommandAction(project) { document.setText(before) }
                debug.session.resume()
            }
            debug.assertPausedAt(library, 1)
            hint(library, "%reg", "0x00000017")
            hint(library, "%value", "0x12345678")
            hint(source, "TARGET", null)
            hint(source, "0x12345678", "0x12345678")
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 14)
            hint(source, "PTR", "0x10010000")
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 15)
            hint(source, "EPC", "0x00000000")
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 16)
            hint(source, "ADDRESS", null)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 17)
            hint(source, "LP", null)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(library, 4)
            hint(library, "%item", "0x12345678")
            hint(library, "%passed", "0x12345678")
            hint(source, "\$t1", "0x12345678")
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(library, 10)
            hint(library, "%bank", "0xfffffff9")
            hint(source, "\$14", null)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Quick Evaluate uses the assembled label binding in the editor source`() = timeoutRunBlocking<Unit> {
        val (main, sibling, include) = withContext(Dispatchers.EDT) {
            val main = fixture.addFileToProject("bindings/main.mars", """
                .data
                localValue: .word 41
                .include "shared.inc"
                .eqv OFFSET localValue + 4
                .text
                la ${'$'}t1, localValue
                la ${'$'}t2, OFFSET
                la ${'$'}t3, shared
                jal sibling
                nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile
            val sibling = fixture.addFileToProject("bindings/sibling.s", """
                .data
                localValue: .word 23
                .include "shared.inc"
                .text
                .globl sibling
                sibling: lw ${'$'}t0, localValue
                la ${'$'}t3, shared
                jr ${'$'}ra
                nop
            """.trimIndent()).virtualFile
            val include = fixture.addFileToProject("bindings/shared.inc", "shared: .word 7\n.word shared").virtualFile
            addBreakpoint(sibling, 5)
            Triple(main, sibling, include)
        }
        withDebug(main, { assembleDirectory = true }) { debug ->
            debug.assertPausedAt(sibling, 5)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val evaluator = requireNotNull(frame.evaluator)
                val documents = FileDocumentManager.getInstance()
                for ((file, address) in listOf(main to "0x10010000", sibling to "0x1001000c")) {
                    val document = requireNotNull(documents.getDocument(file))
                    val name = "localValue"
                    for (offset in listOf(document.text.indexOf(name), document.text.lastIndexOf(name))) {
                        val info = requireNotNull(evaluator.getExpressionInfoAtOffsetAsync(project, document, offset, false).await())
                        assertEquals(TextRange(offset, offset + name.length), info.textRange)
                        assertEquals(name, info.displayText)
                        assertEquals(address, evaluate(debug.session, frame, requireNotNull(info.expressionText)))
                    }
                }
                assertEquals("0x1001000c", evaluate(debug.session, frame, "localValue"))
                for ((file, address) in listOf(main to "0x10010004", sibling to "0x10010010")) {
                    val document = requireNotNull(documents.getDocument(file))
                    val offset = document.text.lastIndexOf("shared")
                    val info = requireNotNull(evaluator.getExpressionInfoAtOffsetAsync(project, document, offset, false).await())
                    assertEquals(TextRange(offset, offset + "shared".length), info.textRange)
                    assertEquals(address, evaluate(debug.session, frame, requireNotNull(info.expressionText)))
                }
                val sharedDocument = requireNotNull(documents.getDocument(include))
                assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, sharedDocument, 0, false).await())
                val sharedUse = sharedDocument.text.lastIndexOf("shared")
                assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, sharedDocument, sharedUse, false).await())

                val mainDocument = requireNotNull(documents.getDocument(main))
                val original = mainDocument.text
                val offset = original.lastIndexOf("localValue")
                assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, mainDocument, original.lastIndexOf("OFFSET"), false).await())
                WriteCommandAction.runWriteCommandAction(project) { mainDocument.insertString(mainDocument.textLength, "\n# edited") }
                assertNull(evaluator.getExpressionInfoAtOffsetAsync(project, mainDocument, offset, false).await())
                WriteCommandAction.runWriteCommandAction(project) { mainDocument.setText(original) }
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    private suspend fun evaluate(session: XDebugSession, frame: XStackFrame, expression: String): String {
        val tree = XDebuggerTree(
            project, session.debugProcess.editorsProvider, frame.sourcePosition, XDebuggerActions.VARIABLES_TREE_POPUP_GROUP, null,
        )
        try {
            val evaluated = CompletableDeferred<Any>()
            requireNotNull(frame.evaluator).evaluate(expression, object : XEvaluationCallback {
                override fun evaluated(result: XValue) { evaluated.complete(result) }
                override fun errorOccurred(errorMessage: String) { evaluated.complete(errorMessage) }
            }, frame.sourcePosition)
            return when (val result = evaluated.await()) {
                is String -> result
                else -> {
                    val node = XValueNodeImpl(tree, null, expression, result as XValue)
                    tree.setRoot(node, false)
                    pollAssertionsAsync(3.seconds, 10.milliseconds) { assertTrue(node.isComputed) }
                    requireNotNull(node.rawValue)
                }
            }
        } finally {
            Disposer.dispose(tree)
        }
    }

    @Test
    fun `keyboard and display attach before the first debug instruction and rewind with the session`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("terminal-debug.asm", """
                lui ${'$'}t0, 0xffff
                li ${'$'}t1, 65
                sb ${'$'}t1, 12(${'$'}t0)
                nop
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 2) }
        }
        withDebug(source, { connectKeyboardDisplay = true }) { debug ->
            debug.assertPausedAt(source, 2)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            val terminal = process.keyboardDisplay
            assertTrue(terminal.snapshot().connected)
            terminal.configure(terminal.snapshot().options.copy(instructions = 1))
            terminal.sendKey('K')
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000001", evaluate(debug.session, frame, "word(0xffff0008)"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 3)
            assertEquals("", terminal.snapshot().display.text)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x0000004b", evaluate(debug.session, frame, "word(0xffff0004)"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 4)
            assertEquals("A", terminal.snapshot().display.text)
            withContext(Dispatchers.EDT) {
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 3)
            assertEquals("", terminal.snapshot().display.text)
            assertEquals(1, terminal.snapshot().remaining)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals("A", terminal.snapshot().display.text)
        }
    }

    @Test
    fun `MarsBot position and pen trail rewind through native Debug`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("mars-bot-debug.asm", """
                lui ${'$'}t0, 0xffff
                ori ${'$'}t0, ${'$'}t0, 0x8000
                li ${'$'}t1, 90
                sw ${'$'}t1, 0x10(${'$'}t0)
                li ${'$'}t1, 1
                sw ${'$'}t1, 0x20(${'$'}t0)
                sw ${'$'}t1, 0x50(${'$'}t0)
                nop
                sw ${'$'}zero, 0x50(${'$'}t0)
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 6) }
        }
        withDebug(source, { connectMarsBot = true }) { debug ->
            debug.assertPausedAt(source, 6)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            val robot = process.marsBot
            val before = robot.snapshot()
            assertTrue(before.connected)
            assertEquals(90, before.world.heading)
            assertTrue(before.world.penDown)
            robot.setInstructionsPerTick(1)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(source, 7)
            assertEquals(1.0, robot.snapshot().world.position.x)
            assertEquals(1, robot.snapshot().world.trail?.strokes()?.count())
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000001", evaluate(debug.session, frame, "word(0xffff8030)"))
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 6)
            assertEquals(before.world, robot.snapshot().world)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000000", evaluate(debug.session, frame, "word(0xffff8030)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
            assertEquals(2.0, robot.snapshot().world.position.x)
        }
    }

    @Test
    fun `Scavenger Hunt reset and memory rewind together through Debug`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("hunt-debug.asm", """
                lui ${'$'}t0, 0xffff
                ori ${'$'}t0, ${'$'}t0, 0xe000
                li ${'$'}t1, 1
                sw ${'$'}t1, 8(${'$'}t0)
                sw ${'$'}t1, 0(${'$'}t0)
                sw ${'$'}zero, 4(${'$'}t0)
                sb ${'$'}t1, 0(${'$'}t0)
                nop
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 6) }
        }
        withDebug(source, { connectScavengerHunt = true }) { debug ->
            debug.assertPausedAt(source, 6)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            val started = requireNotNull(process.scavengerHuntSnapshot())
            assertTrue(started.connected)
            assertEquals(0, started.game.playerId)
            withContext(Dispatchers.EDT) {
                process.releaseScavengerHuntView()
                process.setMarsBotConnected(true)
                assertFalse(process.marsBot.snapshot().connected)
                assertTrue(process.scavengerHuntSnapshot()!!.connected)
                process.resetScavengerHunt()
                assertTrue(process.scavengerHuntSnapshot()!!.resetPending)
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 7)
            assertTrue(process.scavengerHuntSnapshot()!!.resetPending)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(source, 8)
            assertEquals(999, process.scavengerHuntSnapshot()!!.game.playerId)
            assertFalse(process.scavengerHuntSnapshot()!!.resetPending)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x000003e7", evaluate(debug.session, frame, "word(0xffffe004)"))
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 7)
            assertEquals(started, process.scavengerHuntSnapshot())
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000000", evaluate(debug.session, frame, "word(0xffffe004)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Digital Lab keypad input reaches the machine and backstep restores the previous latch`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("digital-lab-debug.asm", """
                lui ${'$'}t0, 0xffff
                li ${'$'}t1, 2
                sb ${'$'}t1, 0x12(${'$'}t0)
                lbu ${'$'}t2, 0x14(${'$'}t0)
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 2) }
        }
        withDebug(source, { connectDigitalLab = true }) { debug ->
            debug.assertPausedAt(source, 2)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            assertTrue(process.digitalLab.snapshot().connected)
            withContext(Dispatchers.EDT) {
                process.digitalLab.toggleKey(6)
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000000", evaluate(debug.session, frame, "byte(0xffff0014)"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 3)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000042", evaluate(debug.session, frame, "byte(0xffff0014)"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 4)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000042", evaluate(debug.session, frame, "\$t2"))
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 3)
            assertEquals(6, process.digitalLab.snapshot().key)
            withContext(Dispatchers.EDT) {
                val (action, event) = toolbarAction(debug.session, "Backstep Source Statement")
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 2)
            assertNull(process.digitalLab.snapshot().key)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000000", evaluate(debug.session, frame, "byte(0xffff0014)"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 3)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000000", evaluate(debug.session, frame, "byte(0xffff0014)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `memory watches read the current suspension across a store and backstep`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("watch-memory.asm", """
                .data
                buffer: .word 0x11223344
                pointer: .word buffer
                .text
                la ${'$'}t0, buffer
                li ${'$'}t1, 0x55667788
                sw ${'$'}t1, 0(${'$'}t0)
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 6) }
        }
        withDebug(source, { captureBitmap = true }) { debug ->
            debug.assertPausedAt(source, 6)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            val display = process.bitmapDisplay
            assertNull(display.snapshot().frame.image)
            val cache = process.dataCache
            cache.setLogging(true)
            assertEquals(0L, cache.snapshot().accesses)
            val before = withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val expected = mapOf(
                    "byte(buffer)" to "0x00000044",
                    "byte(buffer+3)" to "0x00000011",
                    "half(\$t0+2)" to "0x00001122",
                    "word(buffer)" to "0x11223344",
                    "word(word(pointer))" to "0x11223344",
                    "word(pc)" to "0xad090000",
                    "word(0xffff0000)" to "0x00000000",
                    "byte(buffer+1) + 1" to "0x00000034",
                    "1 - byte(buffer)" to "0xffffffbd",
                    "-byte(buffer)" to "0xffffffbc",
                    "word(buffer) == 0x11223344" to "0x00000001",
                    "byte(buffer) < half(buffer)" to "0x00000001",
                    "word(buffer + (1 != 1))" to "0x11223344",
                    "~byte(buffer) & 0xff" to "0x000000bb",
                    "word(buffer) >>> 24" to "0x00000011",
                    "-0x080000000" to "0x80000000",
                    "-0X00080000000 + 1" to "0x80000001",
                    "-0xffffffff" to "0x00000001",
                )
                for ((expression, value) in expected) assertEquals(value, evaluate(debug.session, frame, expression), expression)
                for (expression in listOf("word(buffer+1)", "half(buffer+1)", "word(1.5)", "word(0)",
                    "-word(0)", "word(0)+1", "1+word(0)", "word(word(0))", "word(0) != 1", "1 == word(0)", "~word(0)")) {
                    assertEquals("The memory address is invalid, unaligned, or unavailable.",
                        evaluate(debug.session, frame, expression), expression)
                }
                for (expression in listOf("word(unknown)", "byte(buffer) & 0.5", "~(byte(buffer) + 0.5)",
                    "word(buffer + (byte(buffer) & 0.5))", "0x080000000", "- 0x080000000",
                    "-0x080000001", "~0x080000000", "-(0x080000000)")) {
                    assertEquals("Enter a valid MIPS debugger expression using registers, labels, numbers, memory reads, arithmetic, comparisons, or bitwise operators.",
                        evaluate(debug.session, frame, expression), expression)
                }
                debug.session.stepInto()
                frame
            }
            debug.assertPausedAt(source, 7)
            assertEquals(0xff667788.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
            val store = cache.snapshot().recent.single()
            assertTrue(store.write)
            assertEquals(0x10010000, store.address)
            withContext(Dispatchers.EDT) {
                val after = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x55667788", evaluate(debug.session, after, "word(buffer)"))
                assertEquals("Memory is unavailable for this debugger frame.", evaluate(debug.session, before, "word(buffer)"))
                assertEquals("0x10010000", evaluate(debug.session, before, "buffer"))
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 6)
            assertEquals(0xff223344.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
            assertEquals(listOf(store), cache.snapshot().recent)
            withContext(Dispatchers.EDT) {
                assertEquals("0x11223344", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "word(buffer)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `watch reads honor big endian and cancellation completes pending requests`() {
        MarsSessionService.createMarsComponent().context().use { machine ->
            val memory = machine.memory
            memory.setByteOrder(MemoryState.BIG_ENDIAN)
            val address = memory.memoryMapBaseAddress
            memory.setWord(address, 0x11223344)
            val notices = ArrayList<Any>()
            memory.addObserver { notices.add(it) }
            assertEquals(0x11, WatchMemoryWidth.BYTE.inspect(memory, address))
            assertEquals(0x3344, WatchMemoryWidth.HALF.inspect(memory, address + 2))
            assertTrue(notices.isEmpty())
        }
        val requests = watchMemoryRequests()
        var unavailable = false
        assertTrue(requests.trySend(WatchMemoryRequest({ fail<Unit>("Cancelled request ran") }, { unavailable = true })).isSuccess)
        requests.cancel()
        assertTrue(unavailable)
    }

    private suspend fun registerValues(session: XDebugSession, frame: XStackFrame): Map<String, String> =
        inspectRegisterNodes(session, frame) { values ->
            values.associate { requireNotNull(it.name) to requireNotNull(it.rawValue) }
        }

    private suspend fun callerFields(session: XDebugSession, frame: XStackFrame, count: Int): Map<String, String> {
        val tree = XDebuggerTree(
            project, session.debugProcess.editorsProvider, frame.sourcePosition, XDebuggerActions.VARIABLES_TREE_POPUP_GROUP, null,
        )
        try {
            val root = XStackFrameNode(tree, frame, null)
            tree.setRoot(root, true)
            val values = children(root, count).map { assertInstanceOf(XValueNodeImpl::class.java, it) }
            pollAssertionsAsync(3.seconds, 10.milliseconds) { assertTrue(values.all { it.isComputed }) }
            assertTrue(values.all { it.valueContainer.modifier == null })
            return values.associate { requireNotNull(it.name) to requireNotNull(it.rawValue) }
        } finally {
            Disposer.dispose(tree)
        }
    }

    private fun frameLabel(frame: XStackFrame): String =
        SimpleColoredComponent().also(frame::customizePresentation).toString()

    private suspend fun <T> inspectRegisterNodes(
        session: XDebugSession, frame: XStackFrame, inspect: suspend (List<XValueNodeImpl>) -> T,
    ): T {
        val tree = XDebuggerTree(
            project, session.debugProcess.editorsProvider, frame.sourcePosition, XDebuggerActions.VARIABLES_TREE_POPUP_GROUP, null,
        )
        try {
            val root = XStackFrameNode(tree, frame, null)
            tree.setRoot(root, true)
            val (cpu, cp0, cp1) = children(root, 3).map { assertInstanceOf(XValueGroupNodeImpl::class.java, it) }
            assertTrue(cpu.valueContainer.isAutoExpand)
            assertFalse(cp1.valueContainer.isAutoExpand)
            assertTrue(cp0.valueContainer.isRestoreExpansion)
            val floating = children(cp1, 34)
            val pairs = assertInstanceOf(XValueGroupNodeImpl::class.java, floating.last())
            val nodes = children(cpu, 35) + children(cp0, 4) + floating.dropLast(1) + children(pairs, 16)
            val values = nodes.map { assertInstanceOf(XValueNodeImpl::class.java, it) }
            pollAssertionsAsync(3.seconds, 10.milliseconds) { assertTrue(values.all { it.isComputed }) }
            return inspect(values)
        } finally {
            Disposer.dispose(tree)
        }
    }

    private suspend fun children(node: XValueContainerNode<*>, expected: Int): List<XValueContainerNode<*>> {
        node.childCount
        pollAssertionsAsync(3.seconds, 10.milliseconds) { assertEquals(expected, node.loadedChildren.size) }
        return node.loadedChildren
    }

    @Test
    fun `native Variables edits refresh the pause and reject stale register modifiers`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("edit-register.s", """
                .data
                patch: .word 41
                .text
                li ${'$'}t0, 7
                addiu ${'$'}t1, ${'$'}t0, 1
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 4) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 4)
            val modifier = withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val edits = inspectRegisterNodes(debug.session, frame) { nodes ->
                    nodes.associate { requireNotNull(it.name) to it.valueContainer.modifier }
                }
                assertNull(edits.getValue("\$zero"))
                assertNull(edits.getValue("pc"))
                val t0 = requireNotNull(edits.getValue("\$t0"))
                assertEquals("0x00000007", t0.initialValueEditorText)
                assertTrue(backstepAction(debug.session).second.presentation.isEnabled)
                assertNull(editValue(t0, "7"))
                assertTrue(backstepAction(debug.session).second.presentation.isEnabled)
                assertEquals("Enter an expression that evaluates to a 32-bit integer.", editValue(t0, "1.5"))
                assertEquals(
                    "Enter a valid MIPS debugger expression using registers, labels, numbers, memory reads, arithmetic, comparisons, or bitwise operators.",
                    editValue(t0, "unknown"),
                )
                assertEquals("The memory address is invalid, unaligned, or unavailable.", editValue(t0, "word(0)"))
                assertTrue(backstepAction(debug.session).second.presentation.isEnabled)
                assertNull(editValue(t0, "word(patch) + 1"))
                assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                assertSame(frame, debug.session.currentStackFrame)
                assertEquals("0x0000002a", evaluate(debug.session, frame, "\$t0"))
                assertNull(editValue(requireNotNull(edits.getValue("hi")), "-1"))
                assertNull(editValue(requireNotNull(edits.getValue("lo")), "0x80000000"))
                assertNull(editValue(requireNotNull(edits.getValue("\$sp")), "\$sp - 16"))
                val values = registerValues(debug.session, frame)
                assertEquals("0x0000002a", values["\$t0"])
                assertEquals("0xffffffff", values["hi"])
                assertEquals("0x80000000", values["lo"])
                val context = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                assertEquals(values["\$sp"], "0x" + context.memoryRegions.getValue(MemoryRegion.STACK).toString(16))
                debug.session.stepInto()
                assertEquals("This debugger frame no longer accepts register edits.", editValue(t0, "99"))
                t0
            }
            debug.assertPausedAt(source, 5)
            withContext(Dispatchers.EDT) {
                assertEquals("This debugger frame no longer accepts register edits.", editValue(modifier, "99"))
                assertEquals("0x0000002b", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t1"))
                assertTrue(backstepAction(debug.session).second.presentation.isEnabled)
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `native Coprocessor 0 edits update watches and execution without writing CPU registers`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("edit-cp0.s", """
                addiu ${'$'}t0, ${'$'}zero, 7
                mfc0 ${'$'}t1, ${'$'}14
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 1) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 1)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val edits = inspectRegisterNodes(debug.session, frame) { nodes ->
                    nodes.associate { requireNotNull(it.name) to it.valueContainer.modifier }
                }
                val epc = requireNotNull(edits.getValue("\$14 (epc)"))
                assertEquals("0x00000000", epc.initialValueEditorText)
                assertNull(editValue(epc, "cp0.\$14"))
                assertTrue(backstepAction(debug.session).second.presentation.isEnabled)
                assertNull(editValue(epc, "\$t0 + 35"))
                assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                assertNull(editValue(requireNotNull(edits.getValue("\$8 (vaddr)")), "0x80000000"))
                assertNull(editValue(requireNotNull(edits.getValue("\$12 (status)")), "0xff10"))
                assertNull(editValue(requireNotNull(edits.getValue("\$13 (cause)")), "0x10"))
                assertEquals("0x0000002a", evaluate(debug.session, frame, "cp0.\$14"))
                assertEquals("0x80000000", evaluate(debug.session, frame, "cp0.\$8"))
                val values = registerValues(debug.session, frame)
                assertEquals("0x0000002a", values["\$14 (epc)"])
                assertEquals("0x80000000", values["\$8 (vaddr)"])
                assertEquals("0x0000ff10", values["\$12 (status)"])
                assertEquals("0x00000010", values["\$13 (cause)"])
                assertEquals("0x00000007", values["\$t0"])
                assertEquals("0x00000000", values["\$t6"])
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 2)
            withContext(Dispatchers.EDT) {
                assertEquals("0x0000002a", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t1"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `native FPU edits keep single registers pairs and condition flags coherent`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("edit-cp1.s", """
                addiu ${'$'}t0, ${'$'}zero, 7
                mfc1 ${'$'}t1, ${'$'}f31
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 1) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 1)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                val edits = inspectRegisterNodes(debug.session, frame) { nodes ->
                    nodes.associate { requireNotNull(it.name) to it.valueContainer.modifier }
                }
                val f0 = requireNotNull(edits.getValue("\$f0"))
                val f1 = requireNotNull(edits.getValue("\$f1"))
                val f2 = requireNotNull(edits.getValue("\$f2"))
                val pair = requireNotNull(edits.getValue("\$f30/\$f31"))
                val flags = requireNotNull(edits.getValue("FCC"))
                assertEquals("0x00000000", f0.initialValueEditorText)
                assertEquals("0x0000000000000000", pair.initialValueEditorText)
                assertNull(editValue(pair, pair.initialValueEditorText!!))
                assertEquals("Enter a hexadecimal bit pattern that fits in 32 bits.", editValue(f0, "0x100000000"))
                assertEquals("Enter a hexadecimal bit pattern that fits in 64 bits.", editValue(pair, "0x10000000000000000"))
                assertEquals("Enter a condition flag mask between 0 and 255.", editValue(flags, "256"))
                assertEquals("Enter a condition flag mask between 0 and 255.", editValue(flags, "-1"))
                assertEquals("The memory address is invalid, unaligned, or unavailable.", editValue(f0, "word(0)"))
                assertTrue(backstepAction(debug.session).second.presentation.isEnabled)

                assertNull(editValue(f0, "0x7fc00123"))
                assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                assertNull(editValue(f1, "\$f0"))
                assertEquals("NaN (0x7fc00123)", evaluate(debug.session, frame, "\$f1"))
                assertNull(editValue(f0, "-0.0"))
                assertNull(editValue(f2, "\$t0"))
                assertEquals("7.0 (0x40e00000)", evaluate(debug.session, frame, "\$f2"))
                assertNull(editValue(f2, "\$t0 + 0.5"))
                assertNull(editValue(pair, "0Xfff8000000000123"))
                var values = registerValues(debug.session, frame)
                assertEquals("-0.0 (0x80000000)", values["\$f0"])
                assertEquals("7.5 (0x40f00000)", values["\$f2"])
                assertEquals("NaN (0xfff8000000000123)", values["\$f30/\$f31"])
                assertTrue(values.getValue("\$f30").endsWith("(0x00000123)"))
                assertEquals("NaN (0xfff80000)", values["\$f31"])
                assertNull(editValue(pair, "-2.5"))
                assertEquals("-2.5 (0xc004000000000000)", registerValues(debug.session, frame)["\$f30/\$f31"])
                assertNull(editValue(pair, "\$f2 + 0.25"))
                assertNull(editValue(flags, "FCC | 0x81"))
                values = registerValues(debug.session, frame)
                assertEquals("7.75 (0x401f000000000000)", values["\$f30/\$f31"])
                assertEquals("0x00000081", values["FCC"])
                assertEquals("0x00000081", evaluate(debug.session, frame, "FCC"))
                debug.session.stepInto()
            }
            debug.assertPausedAt(source, 2)
            withContext(Dispatchers.EDT) {
                assertEquals("0x401f0000", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t1"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `a full or expired register edit queue reports all undelivered requests`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val requests = machineEditRequests()
            val modifier = RegisterModifier(RegisterBank.CPU, 8, { "0x00000000" }, requests) { true }
            val expression = XDebuggerUtil.getInstance().createExpression("1", null, null, EvaluationMode.EXPRESSION)
            val errors = mutableListOf<String>()
            repeat(17) {
                modifier.setValue(expression, object : XModificationCallback {
                    override fun valueModified() { fail<Unit>("An unprocessed edit was accepted") }
                    override fun errorOccurred(errorMessage: String) { errors.add(errorMessage) }
                })
            }
            assertEquals(listOf("This debugger frame no longer accepts register edits."), errors)
            requests.cancel()
            assertEquals(List(17) { "This debugger frame no longer accepts register edits." }, errors)
        }
    }

    private suspend fun editValue(modifier: XValueModifier, expression: String): String? {
        val input = XDebuggerUtil.getInstance().createExpression(expression, null, null, EvaluationMode.EXPRESSION)
        val result = CompletableDeferred<String?>()
        modifier.setValue(input, object : XModificationCallback {
            override fun valueModified() { result.complete(null) }
            override fun errorOccurred(errorMessage: String) { result.complete(errorMessage) }
        })
        return result.await()
    }

    @Test
    fun `new Debug sessions honor the project backstep history limit`() = timeoutRunBlocking<Unit> {
        val settings = project.service<MarsAssemblySettings>()
        val source = withContext(Dispatchers.EDT) {
            settings.changeBackstepLimit(2)
            val text = "addiu \$t0, \$t0, 1\n".repeat(3) + "nop\n"
            fixture.addFileToProject("backstep-limit.s", text).virtualFile.also { addBreakpoint(it, 3) }
        }
        suspend fun verifyHistory(expectedLines: IntProgression, changeLimitWhilePaused: Boolean) {
            withDebug(source) { debug ->
                debug.assertPausedAt(source, 3)
                withContext(Dispatchers.EDT) {
                    if (changeLimitWhilePaused) settings.changeBackstepLimit(4)
                }
                for (line in expectedLines) {
                    withContext(Dispatchers.EDT) {
                        val (action, event) = backstepAction(debug.session)
                        assertTrue(event.presentation.isEnabled)
                        action.actionPerformed(event)
                    }
                    debug.assertPausedAt(source, line)
                }
                withContext(Dispatchers.EDT) {
                    assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                    val values = registerValues(debug.session, requireNotNull(debug.session.currentStackFrame))
                    assertEquals(if (expectedLines.last == 1) "0x00000001" else "0x00000000", values["\$t0"])
                }
            }
        }
        verifyHistory(2 downTo 1, changeLimitWhilePaused = true)
        verifyHistory(2 downTo 0, changeLimitWhilePaused = false)
    }

    @Test
    fun `native toolbar backstep restores the preceding instruction and refreshes Variables`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("undo.s", "li \$a0, 7\nli \$v0, 1\nsyscall\nnop\n").virtualFile.also {
                addBreakpoint(it, 0)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 0)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            assertTrue(process.xray.snapshot().isEmpty())
            val (action, event) = withContext(Dispatchers.EDT) {
                val result = backstepAction(debug.session)
                val (action, event) = result
                assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
                assertFalse(event.presentation.isEnabled)
                action.actionPerformed(event)
                assertTrue(debug.session.isSuspended)
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(source, 3)!!, false)
                result
            }
            debug.assertPausedAt(source, 3)
            assertEquals("7", debug.stdout.toString())
            assertEquals(listOf(1L, 2L, 3L), process.xray.snapshot().map { it.stepCount() })
            for (line in 2 downTo 0) {
                withContext(Dispatchers.EDT) {
                    action.update(event)
                    assertTrue(event.presentation.isEnabled)
                    action.actionPerformed(event)
                }
                debug.assertPausedAt(source, line)
                assertEquals("7", debug.stdout.toString())
                assertEquals(line, process.xray.snapshot().size)
            }
            withContext(Dispatchers.EDT) {
                action.update(event)
                assertFalse(event.presentation.isEnabled)
                val values = registerValues(debug.session, requireNotNull(debug.session.currentStackFrame))
                assertEquals("0x00000000", values["\$a0"])
                assertEquals("0x00000000", values["\$v0"])
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
            assertEquals("77", debug.stdout.toString())
        }
    }

    private fun backstepAction(session: XDebugSession) = toolbarAction(session, "Backstep Instruction")

    private fun toolbarAction(session: XDebugSession, text: String): Pair<AnAction, AnActionEvent> {
        val toolbar = DefaultActionGroup()
        session.debugProcess.registerAdditionalActions(DefaultActionGroup(), toolbar, DefaultActionGroup())
        val action = toolbar.getChildren(ActionManager.getInstance()).single { it.templatePresentation.text == text }
        val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, ActionPlaces.DEBUGGER_TOOLBAR, ActionUiKind.TOOLBAR, null)
        action.update(event)
        return action to event
    }

    private fun highlightAction(session: XDebugSession, text: String): Pair<ToggleAction, AnActionEvent> {
        val settings = DefaultActionGroup()
        session.debugProcess.registerAdditionalActions(DefaultActionGroup(), DefaultActionGroup(), settings)
        val action = settings.getChildren(ActionManager.getInstance()).filterIsInstance<ToggleAction>()
            .single { it.templatePresentation.text == text }
        val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, ActionPlaces.DEBUGGER_TOOLBAR, ActionUiKind.POPUP, null)
        assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
        return action to event
    }

    @Test
    fun `Debug Memory edits the selected pause and rejects a dialog completed after resume`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("memory-edit.s", """
                .data
                payload: .word 7
                .text
                li ${'$'}t0, 35
                lw ${'$'}a0, payload
                li ${'$'}v0, 1
                syscall
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 4)
                addBreakpoint(it, 5)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 4)
            val lifetime = Disposer.newDisposable()
            try {
                val (view, edits) = withContext(Dispatchers.EDT) {
                    val view = MarsMemoryView(debug.session, source.toNioPath()).also { Disposer.register(lifetime, it) }
                    val edits = Channel<Unit>(Channel.UNLIMITED)
                    debug.session.addSessionListener(object : XDebugSessionListener {
                        override fun settingsChanged() { check(edits.trySend(Unit).isSuccess) }
                    }, lifetime)
                    view to edits
                }
                val button = withContext(Dispatchers.EDT) {
                    UIUtil.findComponentsOfType(view.component, JButton::class.java).single { it.text == "Edit Word…" }
                }
                val table = withContext(Dispatchers.EDT) { requireNotNull(UIUtil.findComponentOfType(view.component, JBTable::class.java)) }
                suspend fun assertStatus(text: String) = pollAssertionsAsync(5.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val labels = UIUtil.findComponentsOfType(view.component, JBLabel::class.java).map { it.text }
                        assertTrue(labels.any { it.orEmpty().contains(text, ignoreCase = true) }, "Expected $text in $labels")
                    }
                }
                suspend fun enter(address: String, expression: String) = withContext(Dispatchers.EDT) {
                    interceptMemoryEdit { finishMemoryEdit(it, address, expression) }
                    button.doClick()
                }
                assertMemory(table, "0x10010000", "07 00 00 00")
                withContext(Dispatchers.EDT) {
                    assertTrue(button.isEnabled)
                    interceptMemoryEdit { dialog ->
                        val (address, value) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
                        assertEquals("0x10010000", address.text)
                        assertSame(value, dialog.getPreferredFocusedComponent())
                        assertSame(value, dialog.performValidateAll().single().component)
                        address.text = "0x10010001"
                        assertTrue(dialog.performValidateAll().single().message.contains("word-aligned"))
                        address.text = "0x100000000"
                        assertSame(address, dialog.performValidateAll().single().component)
                        dialog.doCancelAction()
                    }
                    button.doClick()
                }
                enter("0x10010000", "word(payload)")
                edits.receive()
                withContext(Dispatchers.EDT) { assertTrue(backstepAction(debug.session).second.presentation.isEnabled) }
                enter("0x10010000", "1.5")
                assertStatus("32-bit integer")
                enter("0x10010000", "unknown")
                assertStatus("Enter a valid MIPS debugger expression")
                enter("0x20000000", "99")
                assertStatus("address out of range")
                withContext(Dispatchers.EDT) { assertTrue(backstepAction(debug.session).second.presentation.isEnabled) }

                enter("268500992", "word(payload) + \$t0")
                edits.receive()
                assertMemory(table, "0x10010000", "2a 00 00 00")
                withContext(Dispatchers.EDT) {
                    assertEquals("0x0000002a", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "word(payload)"))
                    assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                    interceptMemoryEdit {
                        debug.session.resume()
                        assertFalse(button.isEnabled)
                        button.actionListeners.single().actionPerformed(ActionEvent(button, ActionEvent.ACTION_PERFORMED, "Edit Word"))
                        finishMemoryEdit(it, "0x10010000", "99")
                    }
                    button.doClick()
                }
                debug.assertPausedAt(source, 5)
                assertMemory(table, "0x10010000", "2a 00 00 00")
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                assertEquals("42", debug.stdout.toString())
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(lifetime)
                    UiInterceptors.clear()
                }
            }
        }
    }

    @Test
    fun `editing executable memory refreshes disassembly and drops stale source for the paused instruction`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("edit-instruction.s", "li \$a0, 0\naddiu \$a0, \$a0, 2\nli \$v0, 1\nsyscall\n")
                .virtualFile.also { addBreakpoint(it, 1) }
        }
        withDebug(source, configure = { selfModifyingCodeMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(source, 1)
            val lifetime = Disposer.newDisposable()
            try {
                val listing = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MemoryPatch", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val memory = requireNotNull(layout.findContent("MarsMipsMemory")).component
                    val edit = UIUtil.findComponentsOfType(memory, JButton::class.java).single { it.text == "Edit Word…" }
                    val context = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                    assertEquals(0x00400000, context.executionMarkers.previous?.address())
                    interceptMemoryEdit { finishMemoryEdit(it, "0x00400004", "0x2404002a") }
                    edit.doClick()
                    val disassembly = requireNotNull(layout.findContent("MarsMipsDisassembly")).component
                    requireNotNull(UIUtil.findComponentOfType(disassembly, JBTable::class.java))
                }
                pollAssertionsAsync(5.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertNull(debug.session.topFramePosition)
                        val context = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                        assertNull(context.sourceStep)
                        assertNull(context.executionMarkers.previous)
                        val row = (0 until listing.rowCount).firstOrNull { listing.getValueAt(it, 1) == "0x00400004" }
                        assertNotNull(row)
                        requireNotNull(row)
                        assertEquals("0x2404002a", listing.getValueAt(row, 2))
                        assertEquals("", listing.getValueAt(row, 4))
                    }
                }
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                assertEquals("42", debug.stdout.toString())
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(lifetime)
                    UiInterceptors.clear()
                }
            }
        }
    }

    private fun interceptMemoryEdit(action: (MarsMemoryEditDialog) -> Unit) =
        UiInterceptors.register(object : UiInterceptors.UiInterceptor<MarsMemoryEditDialog>(MarsMemoryEditDialog::class.java) {
            override fun doIntercept(component: MarsMemoryEditDialog) = action(component)
        })

    private fun finishMemoryEdit(dialog: MarsMemoryEditDialog, address: String, expression: String) {
        val (addressField, valueField) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
        addressField.text = address
        valueField.text = expression
        assertTrue(dialog.performValidateAll().isEmpty())
        dialog.performOKAction()
    }

    @Test
    fun `Debug Memory reads the active suspension and refreshes after a store and backstep`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("memory.s", """
                .data
                payload: .byte 65, 0, 127, 255
                .text
                addiu ${'$'}t1, ${'$'}zero, 66
                la ${'$'}t0, payload
                addiu ${'$'}sp, ${'$'}sp, -32
                sb ${'$'}t1, 0(${'$'}t0)
                nop
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 3)
                addBreakpoint(it, 7)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 3)
            val disposable = Disposer.newDisposable()
            var afterDisposal: () -> Unit = {}
            try {
                val (table, field, panel) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsMemoryTest", "Debug", "MIPS", disposable)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val content = requireNotNull(layout.findContent("MarsMipsMemory"))
                    assertEquals("Memory", content.displayName)
                    assertFalse(content.isCloseable)
                    val panel = content.component
                    val table = requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java))
                    val address = requireNotNull(UIUtil.findComponentOfType(panel, JBTextField::class.java))
                    Triple(table, address, panel)
                }
                val buttons = withContext(Dispatchers.EDT) {
                    UIUtil.findComponentsOfType(panel, JButton::class.java).associateBy { it.text }
                }
                val labels = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JBLabel::class.java) }
                val regions = withContext(Dispatchers.EDT) { requireNotNull(UIUtil.findComponentOfType(panel, ComboBox::class.java)) }
                assertMemory(table, "0x10010000", "41 00 7f ff", "A...")
                for ((region, address) in mapOf(
                    MemoryRegion.PC to "0x00400000",
                    MemoryRegion.TEXT to "0x00400000",
                    MemoryRegion.HEAP to "0x10040000",
                    MemoryRegion.STACK to "0x7fffeff0",
                    MemoryRegion.KERNEL_TEXT to "0x80000000",
                    MemoryRegion.KERNEL_DATA to "0x90000000",
                    MemoryRegion.MMIO to "0xffff0000",
                    MemoryRegion.DATA to "0x10010000",
                )) {
                    withContext(Dispatchers.EDT) {
                        assertTrue(regions.isEnabled)
                        regions.selectedItem = region
                        assertNull(regions.selectedItem)
                    }
                    assertMemory(table, address, "")
                }
                withContext(Dispatchers.EDT) { buttons.getValue("Next Page").doClick(0) }
                assertMemory(table, "0x10010100", "00 00 00 00")
                withContext(Dispatchers.EDT) { buttons.getValue("Previous Page").doClick(0) }
                assertMemory(table, "0x10010000", "41 00 7f ff")
                withContext(Dispatchers.EDT) {
                    field.text = "0X00400000"
                    field.postActionEvent()
                    field.text = "0"
                    buttons.getValue("Go").doClick(0)
                }
                assertMemory(table, "0x00000000", "-- -- -- --")
                withContext(Dispatchers.EDT) {
                    assertFalse(buttons.getValue("Previous Page").isEnabled)
                    field.text = "0x00400000"
                    field.postActionEvent()
                }
                assertMemory(table, "0x00400000", "42 00 09 24")
                withContext(Dispatchers.EDT) {
                    field.text = "0xffffffff"
                    field.postActionEvent()
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertEquals(1, table.rowCount)
                        assertEquals("0xfffffff0", table.getValueAt(0, 0))
                        assertFalse(buttons.getValue("Next Page").isEnabled)
                    }
                }
                withContext(Dispatchers.EDT) {
                    field.text = "0x100000000"
                    field.postActionEvent()
                    assertEquals("0xfffffff0", table.getValueAt(0, 0))
                    assertTrue(labels.any { it.text.contains("Enter a 32-bit address") })
                    field.text = 0x10010003u.toString()
                    field.postActionEvent()
                }
                assertMemory(table, "0x10010000", "41 00 7f ff")
                withContext(Dispatchers.EDT) {
                    assertTrue(labels.any { it.text == "Memory from suspension 1." })
                    // Leave a new page request queued as execution resumes.
                    field.text = "0x00400000"
                    field.postActionEvent()
                    assertTrue(labels.any { it.text == "Reading memory… Showing suspension 1." })
                    debug.session.resume()
                    assertFalse(field.isEnabled)
                    assertFalse(regions.isEnabled)
                    regions.selectedItem = MemoryRegion.HEAP
                    assertNull(regions.selectedItem)
                    assertEquals("0x10010000", table.getValueAt(0, 0))
                    assertTrue(labels.any { it.text == "Last memory snapshot from suspension 1." })
                }
                debug.assertPausedAt(source, 7)
                assertMemory(table, "0x00400000", "42 00 09 24")
                withContext(Dispatchers.EDT) {
                    assertTrue(labels.any { it.text == "Memory from suspension 2." })
                    regions.selectedItem = MemoryRegion.PC
                }
                assertMemory(table, "0x00400010", "")
                withContext(Dispatchers.EDT) { regions.selectedItem = MemoryRegion.STACK }
                assertMemory(table, "0x7fffefd0", "")
                withContext(Dispatchers.EDT) { regions.selectedItem = MemoryRegion.DATA }
                assertMemory(table, "0x10010000", "42 00 7f ff", "B...")
                withContext(Dispatchers.EDT) {
                    val (action, event) = backstepAction(debug.session)
                    action.actionPerformed(event)
                }
                debug.assertPausedAt(source, 6)
                assertMemory(table, "0x10010000", "41 00 7f ff", "A...")
                afterDisposal = {
                    assertTrue(labels.any { it.text == "Memory from suspension 3." })
                    field.text = "0x00400000"
                    field.postActionEvent()
                    assertEquals("0x10010000", table.getValueAt(0, 0))
                }
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(disposable) }
            }
            withContext(Dispatchers.EDT) { afterDisposal() }
            withContext(Dispatchers.EDT) { debug.session.stop() }
            assertEquals(130, debug.exit.await())
        }
    }

    @Test
    fun `Debug Memory highlights the newest recorded write and restores the previous write on backstep`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("last-memory-write.asm", """
                .data
                buffer: .space 0x200
                .text
                la ${'$'}t0, buffer
                li ${'$'}t1, 0x1234
                sw ${'$'}t1, 0(${'$'}t0)
                first: nop
                sh ${'$'}t1, 0x104(${'$'}t0)
                second: nop
                sb ${'$'}t1, 0x105(${'$'}t0)
                third: nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also { file ->
                for (line in listOf(5, 6, 8, 10)) addBreakpoint(file, line)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 5)
            val lifetime = Disposer.newDisposable()
            val display = service<MarsDebuggerDisplaySettings>()
            try {
                val panel = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsLastWriteTest", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    requireNotNull(layout.findContent("MarsMipsMemory")).component
                }
                val (table, address, last) = withContext(Dispatchers.EDT) {
                    Triple(
                        requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java)),
                        requireNotNull(UIUtil.findComponentOfType(panel, JBTextField::class.java)),
                        UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Last Write" },
                    )
                }
                val labels = withContext(Dispatchers.EDT) { UIUtil.findComponentsOfType(panel, JBLabel::class.java) }
                fun bold(column: Int): List<String> {
                    val cell = assertInstanceOf(
                        SimpleColoredComponent::class.java, table.prepareRenderer(table.getCellRenderer(0, column), 0, column),
                    )
                    val fragments = cell.iterator()
                    return buildList {
                        while (fragments.hasNext()) {
                            val fragment = fragments.next()
                            if (fragments.textAttributes.style and STYLE_BOLD != 0) add(fragment)
                        }
                    }
                }
                suspend fun assertWrite(status: String?, bytes: List<String> = emptyList(), words: List<String> = emptyList()) {
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            assertTrue(table.rowCount > 0)
                            assertEquals(status, labels.map { it.text }.firstOrNull { it.startsWith("Last write at ") })
                            assertEquals(status != null, last.isEnabled)
                            assertEquals(bytes, bold(1))
                            assertEquals(words, bold(3))
                        }
                    }
                }
                suspend fun resumeTo(line: Int) {
                    withContext(Dispatchers.EDT) {
                        debug.session.resume()
                        assertFalse(last.isEnabled)
                    }
                    debug.assertPausedAt(source, line)
                }
                assertWrite(null)
                resumeTo(6)
                assertWrite("Last write at 0x10010000 (4 bytes)", listOf("34", "12", "00", "00"), listOf("0x00001234"))
                val (highlight, event) = withContext(Dispatchers.EDT) {
                    highlightAction(debug.session, "Highlight MARS Memory Writes")
                }
                val suspension = debug.session.suspendContext
                withContext(Dispatchers.EDT) {
                    assertTrue(highlight.isSelected(event))
                    highlight.actionPerformed(event)
                    assertFalse(highlight.isSelected(event))
                    assertTrue(display.highlightRegisterWrites)
                    assertSame(suspension, debug.session.suspendContext)
                    val saved = XmlSerializer.serialize(display.getState())
                    assertFalse(XmlSerializer.deserialize(saved, MarsDebuggerDisplaySettings.Settings::class.java).highlightMemoryWrites)
                }
                assertWrite("Last write at 0x10010000 (4 bytes)")
                withContext(Dispatchers.EDT) {
                    highlight.actionPerformed(event)
                    assertTrue(highlight.isSelected(event))
                }
                assertWrite("Last write at 0x10010000 (4 bytes)", listOf("34", "12", "00", "00"), listOf("0x00001234"))
                withContext(Dispatchers.EDT) {
                    val foreground = Color(0x164b67)
                    val background = Color(0xd1e5ff)
                    withHighlightScheme({
                        it.setAttributes(MarsMachineHighlight.WRITE.colorKey, TextAttributes().apply {
                            foregroundColor = foreground
                            backgroundColor = background
                            fontType = Font.ITALIC
                        })
                    }) {
                        val cell = assertInstanceOf(SimpleColoredComponent::class.java,
                            table.getCellRenderer(0, 1).getTableCellRendererComponent(table, table.getValueAt(0, 1), false, false, 0, 1))
                        val fragments = cell.iterator()
                        assertEquals("34", fragments.next())
                        assertEquals(foreground, fragments.textAttributes.fgColor)
                        assertEquals(background, fragments.textAttributes.bgColor)
                        assertEquals(Font.ITALIC, fragments.textAttributes.style)
                    }
                }
                resumeTo(8)
                assertWrite("Last write at 0x10010104 (2 bytes)")
                withContext(Dispatchers.EDT) {
                    last.doClick(0)
                    assertEquals("0x10010104", address.text)
                }
                assertMemory(table, "0x10010100", "00 00 00 00 34 12")
                assertWrite("Last write at 0x10010104 (2 bytes)", listOf("34", "12"), listOf("0x00001234"))
                resumeTo(10)
                assertWrite("Last write at 0x10010105 (1 byte)", listOf("34"), listOf("0x00003434"))
                withContext(Dispatchers.EDT) {
                    val (action, event) = backstepAction(debug.session)
                    action.actionPerformed(event)
                }
                debug.assertPausedAt(source, 9)
                assertWrite("Last write at 0x10010104 (2 bytes)", listOf("34", "12"), listOf("0x00001234"))
                withContext(Dispatchers.EDT) { display.setHexAddresses(false) }
                assertWrite("Last write at 268501252 (2 bytes)", listOf("34", "12"), listOf("0x00001234"))
                withContext(Dispatchers.EDT) {
                    inspectRegisterNodes(debug.session, requireNotNull(debug.session.currentStackFrame)) { nodes ->
                        val modifier = requireNotNull(nodes.single { it.name == "\$t1" }.valueContainer.modifier)
                        assertNull(editValue(modifier, "0x5678"))
                    }
                    debug.session.rebuildViews()
                }
                assertWrite(null)
                withContext(Dispatchers.EDT) { debug.session.resume() }
                debug.assertPausedAt(source, 10)
                assertWrite("Last write at 268501253 (1 byte)", listOf("78"), listOf("0x00007834"))
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                withContext(Dispatchers.EDT) {
                    assertFalse(last.isEnabled)
                    debug.session.rebuildViews()
                    assertEquals(listOf("78"), bold(1))
                }
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(lifetime)
                    display.setHexAddresses(true)
                    display.setHighlightMemoryWrites(true)
                }
            }
        }
    }

    @Test
    fun `Debug terminates at the instruction budget across a breakpoint and backstep`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("limited.asm", "loop: addiu \$t0, \$t0, 1\nj loop\n")
                .virtualFile.also { addBreakpoint(it, 1) }
        }
        withDebug(file, { instructionLimit = 3 }) { debug ->
            debug.assertPausedAt(file, 1)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            withContext(Dispatchers.EDT) {
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(1, debug.exit.await())
            assertEquals(3L, process.instructionCounter.snapshot().total)
        }
    }

    @Test
    fun `Debug stops at global main before executing any instruction from its prepared image`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("debug-main.asm", "nop\nli \$v0, 10\nsyscall\n.globl main\nmain: li \$t0, 42\nli \$v0, 10\nsyscall\n")
                .virtualFile.also {
                    addBreakpoint(it, 0)
                    addBreakpoint(it, 4)
                }
        }
        withDebug(file, { startAtMain = ProjectOptionOverride.ENABLED }, beforeLaunch = true) { debug ->
            debug.assertPausedAt(file, 4)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            assertEquals(0L, process.instructionCounter.snapshot().total)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals(3L, process.instructionCounter.snapshot().total)
        }
    }

    @Test
    fun `Debug writes relative files in the configured working directory after resume`() = timeoutRunBlocking<Unit> {
        val (file, output) = withContext(Dispatchers.EDT) {
            val source = fixture.addFileToProject("debug-working-dir.asm", """
                .data
                filename: .asciiz "created.txt"
                payload: .ascii "debug"
                .text
                la ${'$'}a0, filename
                li ${'$'}a1, 1
                li ${'$'}v0, 13
                syscall
                move ${'$'}a0, ${'$'}v0
                la ${'$'}a1, payload
                li ${'$'}a2, 5
                li ${'$'}v0, 15
                syscall
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 4) }
            val marker = fixture.addFileToProject("debug-io/marker.txt", "").virtualFile
            source to marker.toNioPath().resolveSibling("created.txt")
        }
        withDebug(file, { workingDirectory = "\$PROJECT_DIR\$/debug-io" }) { debug ->
            debug.assertPausedAt(file, 4)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
            assertEquals("debug", Files.readString(output))
            assertFalse(Files.exists(file.toNioPath().resolveSibling("created.txt")))
        }
    }

    @Test
    fun `Debug exposes the launch speed and can change it while paused`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("debug-speed.asm", "nop\nli \$v0, 10\nsyscall\n")
                .virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file, { runSpeed = 0.05f }) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) {
                val (action, event) = toolbarAction(debug.session, "Run speed")
                val speed = assertInstanceOf(MarsRunSpeedAction::class.java, action)
                assertTrue(event.presentation.isEnabled)
                assertEquals("Speed: 0.05 instr/s", event.presentation.text)
                val choices = speed.createPopupActionGroup(JPanel(), DataContext.EMPTY_CONTEXT).getChildren(ActionManager.getInstance())
                val unlimited = choices.single { it.templatePresentation.text == "Unlimited" }
                unlimited.actionPerformed(event)
                action.update(event)
                assertEquals("Speed: Unlimited", event.presentation.text)
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Debug sees the argument frame at the first instruction in compact memory`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            project.service<MarsAssemblySettings>().changeMemoryLayout(MarsMemoryLayout.TEXT_AT_ZERO)
            fixture.addFileToProject("argv.asm", "nop\n").virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file, { programArguments = "\"ab cd\" \"\"" }, beforeLaunch = true) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) {
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x00000002", evaluate(debug.session, frame, "\$a0"))
                assertEquals("0x00000002", evaluate(debug.session, frame, "word(\$sp)"))
                assertEquals("0x00000000", evaluate(debug.session, frame, "word(\$a1 + 8)"))
                assertEquals("0x00000061", evaluate(debug.session, frame, "byte(word(\$a1))"))
                assertEquals("0x00000000", evaluate(debug.session, frame, "byte(word(\$a1 + 4))"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["CompactDataAtZero", "CompactTextAtZero"])
    fun `Debug keeps the captured compact memory layout when project settings change`(identifier: String) = timeoutRunBlocking<Unit> {
        val settings = project.service<MarsAssemblySettings>()
        val layout = MemoryConfigurations.createConfiguration(identifier)
        val file = withContext(Dispatchers.EDT) {
            settings.changeMemoryLayout(MarsMemoryLayout.fromIdentifier(identifier))
            fixture.addFileToProject("compact.asm", ".data\nvalue: .word 42\n.text\nnop\nnop\n").virtualFile
                .also { addBreakpoint(it, 4) }
        }
        withDebug(file, beforeLaunch = identifier == "CompactDataAtZero") { debug ->
            debug.assertPausedAt(file, 4)
            withContext(Dispatchers.EDT) {
                settings.changeMemoryLayout(MarsMemoryLayout.DEFAULT)
                val context = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                assertEquals(layout.textBaseAddress.toLong(), context.memoryRegions.getValue(MemoryRegion.TEXT))
                assertEquals(layout.dataBaseAddress.toLong(), context.memoryRegions.getValue(MemoryRegion.DATA))
                assertEquals(layout.memoryMapBaseAddress.toLong(), context.memoryRegions.getValue(MemoryRegion.MMIO))
                assertEquals(layout.textBaseAddress.toLong() + 4, context.memoryRegions.getValue(MemoryRegion.PC))
                assertEquals(layout.stackPointer.toLong(), context.memoryRegions.getValue(MemoryRegion.STACK))
                val frame = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x0000002a", evaluate(debug.session, frame, "word(value)"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `assembling another opened file leaves the paused machine intact`() = timeoutRunBlocking<Unit> {
        val (entry, other) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("paused.asm", "li \$t0, 42\nnop\n").virtualFile
            val other = fixture.addFileToProject("other.asm", "li \$t0, 99\n") as MipsFile
            addBreakpoint(entry, 1)
            entry to other
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(entry, 1)
            val analysis = project.service<MarsAnalysisService>()
            val suspension = requireNotNull(debug.session.suspendContext)
            readAction { assertNull(analysis.selectedAssembly(other)) }
            withContext(Dispatchers.EDT) {
                project.service<MarsAssemblySettings>().changeAssembleOnOpen(true)
                FileEditorManager.getInstance(project).openFile(other.virtualFile, FileEditorOpenRequest.withFocus(false))
            }
            pollAssertionsAsync(5.seconds, 10.milliseconds) {
                readAction { assertEquals(other.virtualFile.toNioPath(), analysis.selectedAssembly(other)?.entry) }
            }
            withContext(Dispatchers.EDT) {
                assertSame(suspension, debug.session.suspendContext)
                assertEquals("0x0000002a", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Debug Symbols lists the loaded labels and navigates to their original source`() = timeoutRunBlocking<Unit> {
        val includedText = """
            .extern externalSlot, 4
            .data
            .globl payload
            payload: .word 42
            .kdata
            kernelSlot: .word 0
            .text
            .macro emit
            inner: nop
            .end_macro
        """.trimIndent() + "\n"
        val (entry, include, unrelated) = withContext(Dispatchers.EDT) {
            val body = fixture.addFileToProject("symbols-body.s", includedText).virtualFile
            val file = fixture.addFileToProject("symbols.asm", """
                .include "symbols-body.s"
                localEntry: nop
                emit
                nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 1) }
            Triple(file, body, fixture.addFileToProject("symbols-unrelated.s", "nop\n").virtualFile)
        }
        withDebug(entry) { debug ->
            debug.assertPausedAt(entry, 1)
            val lifetime = Disposer.newDisposable()
            try {
                val layout = withContext(Dispatchers.EDT) {
                    RunnerLayoutUi.Factory.getInstance(project).create("MarsSymbolsTest", "Debug", "MIPS", lifetime).also {
                        debug.session.debugProcess.createTabLayouter().registerAdditionalContent(it)
                    }
                }
                val (table, search, status) = withContext(Dispatchers.EDT) {
                    val content = requireNotNull(layout.findContent("MarsMipsSymbols"))
                    assertEquals("Symbols", content.displayName)
                    assertFalse(content.isCloseable)
                    Triple(
                        requireNotNull(UIUtil.findComponentOfType(content.component, JBTable::class.java)),
                        requireNotNull(UIUtil.findComponentOfType(content.component, SearchTextField::class.java)),
                        UIUtil.findComponentsOfType(content.component, JBLabel::class.java).single { it.text.contains("image") },
                    )
                }
                val button = withContext(Dispatchers.EDT) {
                    UIUtil.findComponentsOfType(layout.findContent("MarsMipsSymbols")!!.component, JButton::class.java)
                        .single { it.text == "Open Source" }
                }
                fun row(name: String) = (0 until table.rowCount).first { table.getValueAt(it, 0) == name }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertEquals("Symbols from the loaded image", status.text)
                        assertEquals(5, table.rowCount)
                        assertEquals("0x10010000", table.getValueAt(row("payload"), 1))
                        assertEquals("Data", table.getValueAt(row("payload"), 2))
                        assertEquals("Global", table.getValueAt(row("payload"), 3))
                        assertEquals("", table.getValueAt(row("payload"), 4))
                        assertTrue(table.getValueAt(row("payload"), 5).toString().endsWith("symbols-body.s:4"))
                        assertEquals("External", table.getValueAt(row("externalSlot"), 2))
                        assertEquals("Text", table.getValueAt(row("localEntry"), 2))
                        assertEquals("Local", table.getValueAt(row("localEntry"), 3))
                        assertTrue(table.getValueAt(row("localEntry"), 4).toString().endsWith("symbols.asm"))
                        val macro = (0 until table.rowCount).single { table.getValueAt(it, 0).toString().startsWith("inner") }
                        assertEquals("Macro Expansion", table.getValueAt(macro, 3))
                        assertTrue(table.getValueAt(macro, 5).toString().endsWith("symbols-body.s:9"))
                        assertFalse(button.isEnabled)
                        table.rowSorter.toggleSortOrder(1)
                        assertEquals("localEntry", table.getValueAt(0, 0))
                        assertEquals("kernelSlot", table.getValueAt(table.rowCount - 1, 0))
                        table.rowSorter.toggleSortOrder(1)
                        assertEquals("kernelSlot", table.getValueAt(0, 0))
                        search.text = "10010000"
                        assertEquals(1, table.rowCount)
                        search.text = "SYMBOLS-BODY.S:4"
                        assertEquals(1, table.rowCount)
                        assertEquals("payload", table.getValueAt(0, 0))
                        table.setRowSelectionInterval(0, 0)
                        assertTrue(button.isEnabled)
                        button.doClick(0)
                    }
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                        assertEquals(include, FileDocumentManager.getInstance().getFile(editor.document))
                        assertEquals(3, editor.caretModel.logicalPosition.line)
                    }
                }
                val bodyDocument = withContext(Dispatchers.EDT) {
                    val documents = FileDocumentManager.getInstance()
                    val body = requireNotNull(documents.getDocument(include))
                    val other = requireNotNull(documents.getDocument(unrelated))
                    val detached = EditorFactory.getInstance().createDocument("original")
                    val formattingCopy = LightVirtualFile("symbols-body.s", MipsFileType, includedText)
                    val copy = requireNotNull(documents.getDocument(formattingCopy))
                    assertEquals(formattingCopy, documents.getFile(copy))
                    WriteAction.run<RuntimeException> {
                        detached.setText("edited")
                        copy.setText("# formatting copy\n" + includedText)
                        other.setText("nop\nnop\n")
                        body.setText("# edit\n" + includedText)
                    }
                    body
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertTrue(status.text.startsWith("Source has changed"))
                        assertEquals("payload", table.getValueAt(0, 0))
                        button.doClick(0)
                    }
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                        val captured = requireNotNull(FileDocumentManager.getInstance().getFile(editor.document))
                        assertEquals("symbols-body.s (assembled snapshot)", captured.name)
                        assertFalse(captured.isWritable)
                        assertEquals(includedText, editor.document.text)
                        assertEquals(3, editor.caretModel.logicalPosition.line)
                        WriteAction.run<RuntimeException> { bodyDocument.setText(includedText) }
                    }
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) { assertEquals("Symbols from the loaded image", status.text) }
                }
                withContext(Dispatchers.EDT) {
                    val second = RunnerLayoutUi.Factory.getInstance(project).create("MarsSymbolsSortTest", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(second)
                    val secondTable = requireNotNull(UIUtil.findComponentOfType(second.findContent("MarsMipsSymbols")!!.component, JBTable::class.java))
                    assertEquals(1, secondTable.rowSorter.sortKeys.single().column)
                    assertEquals(SortOrder.DESCENDING, secondTable.rowSorter.sortKeys.single().sortOrder)
                    search.text = ""
                    WriteAction.run<RuntimeException> { include.delete(this) }
                    debug.session.resume()
                }
                assertEquals(0, debug.exit.await())
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertTrue(status.text.startsWith("Source has changed"))
                        assertEquals(5, table.rowCount)
                        table.rowSorter.sortKeys = emptyList()
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
            }
        }
    }

    @Test
    fun `Symbols opens live code and exact data addresses without moving the PC`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("symbol-addresses.asm", """
                ${'\t'}.extern externalSlot, 4
                .data
                padding: .byte 0
                payload: .byte 42
                .text
                entry: nop
                next: nop
                li ${'$'}v0, 10
                syscall
                .text 0x00400100
                far: addiu ${'$'}t0, ${'$'}zero, 42
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 5)
                addBreakpoint(it, 6)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 5)
            val lifetime = Disposer.newDisposable()
            try {
                val (symbols, memory, disassembly) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsSymbolAddressesTest", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    Triple(
                        requireNotNull(layout.findContent("MarsMipsSymbols")),
                        requireNotNull(layout.findContent("MarsMipsMemory")),
                        requireNotNull(layout.findContent("MarsMipsDisassembly")),
                    )
                }
                val (labels, bytes, instructions) = withContext(Dispatchers.EDT) {
                    fun table(panel: JComponent) = requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java))
                    Triple(table(symbols.component), table(memory.component), table(disassembly.component))
                }
                fun button(panel: JComponent, text: String) =
                    UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == text }
                val inspect = withContext(Dispatchers.EDT) { button(symbols.component, "Inspect Address") }
                val follow = withContext(Dispatchers.EDT) { button(disassembly.component, "Follow PC") }
                val address = withContext(Dispatchers.EDT) {
                    requireNotNull(UIUtil.findComponentOfType(memory.component, JBTextField::class.java))
                }
                fun inspect(label: String) {
                    val row = (0 until labels.rowCount).first { labels.getValueAt(it, 0) == label }
                    labels.setRowSelectionInterval(row, row)
                    assertTrue(inspect.isEnabled)
                    inspect.doClick(0)
                }
                suspend fun assertInstruction(address: String, marker: String, canFollow: Boolean) {
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            val row = instructions.selectedRow
                            assertTrue(row >= 0)
                            assertEquals(address, instructions.getValueAt(row, 1))
                            assertEquals(marker, instructions.getValueAt(row, 0))
                            assertEquals(canFollow, follow.isEnabled)
                        }
                    }
                }
                assertInstruction("0x00400000", "▶", false)
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) { assertEquals(6, labels.rowCount) }
                }
                withContext(Dispatchers.EDT) {
                    assertFalse(inspect.isEnabled)
                    inspect("payload")
                    assertEquals("0x10010001", address.text)
                }
                assertMemory(bytes, "0x10010000", "00 2a")
                withContext(Dispatchers.EDT) {
                    inspect("externalSlot")
                    assertEquals("0x10000000", address.text)
                    button(symbols.component, "Open Source").doClick(0)
                }
                val liveDocument = withContext(Dispatchers.EDT) { FileDocumentManager.getInstance().getDocument(source)!! }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
                        assertSame(liveDocument, editor.document)
                        assertEquals(liveDocument.text.indexOf("externalSlot"), editor.caretModel.offset)
                    }
                }
                val loadedText = liveDocument.text
                try {
                    withContext(Dispatchers.EDT) {
                        WriteCommandAction.runWriteCommandAction(project) { liveDocument.insertString(0, "# changed since assembly\n") }
                        button(symbols.component, "Open Source").doClick(0)
                    }
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            val editor = FileEditorManager.getInstance(project).selectedTextEditor!!
                            assertNotSame(liveDocument, editor.document)
                            assertEquals(loadedText, editor.document.text)
                            assertEquals(loadedText.indexOf("externalSlot"), editor.caretModel.offset)
                        }
                    }
                } finally {
                    withContext(Dispatchers.EDT) { WriteCommandAction.runWriteCommandAction(project) { liveDocument.setText(loadedText) } }
                }
                assertMemory(bytes, "0x10000000", "00 00 00 00")
                withContext(Dispatchers.EDT) { inspect("far") }
                assertInstruction("0x00400100", "", true)
                withContext(Dispatchers.EDT) {
                    val row = instructions.selectedRow
                    assertEquals("0x2408002a", instructions.getValueAt(row, 2))
                    assertEquals(5, debug.session.topFramePosition!!.line)
                    val status = requireNotNull(UIUtil.findComponentOfType(disassembly.component, JBLabel::class.java))
                    assertEquals("Inspecting 0x00400100 (program counter 0x00400000)", status.text)
                    follow.doClick(0)
                }
                assertInstruction("0x00400000", "▶", false)
                withContext(Dispatchers.EDT) {
                    inspect("far")
                    debug.session.resume()
                    assertFalse(inspect.isEnabled)
                }
                debug.assertPausedAt(source, 6)
                assertInstruction("0x00400004", "▶", false)
                withContext(Dispatchers.EDT) {
                    assertTrue(inspect.isEnabled)
                    debug.session.resume()
                }
                assertEquals(0, debug.exit.await())
                withContext(Dispatchers.EDT) {
                    assertFalse(inspect.isEnabled)
                    val view = MarsDisassemblyView(debug.session, source.path) { error("No live instruction") }
                    try {
                        view.showAddress(0x00400100)
                        assertEquals(0, view.focus.rowCount)
                    } finally {
                        Disposer.dispose(view)
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
            }
        }
    }

    @Test
    fun `Debugger address display updates existing views and preserves machine state`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("display-addresses.asm", """
                .kdata
                kernelWord: .word 0x41424344
                .text
                entry: nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 3) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 3)
            val lifetime = Disposer.newDisposable()
            try {
                val (layout, action, event) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsAddressDisplayTest", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val settings = DefaultActionGroup()
                    debug.session.debugProcess.registerAdditionalActions(DefaultActionGroup(), DefaultActionGroup(), settings)
                    val action = settings.getChildren(ActionManager.getInstance()).filterIsInstance<MarsHexAddressesAction>().single()
                    val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, ActionPlaces.DEBUGGER_TOOLBAR, ActionUiKind.POPUP, null)
                    assertEquals("Hexadecimal MARS Addresses", action.templatePresentation.text)
                    assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
                    assertTrue(action.isSelected(event))
                    Triple(layout, action, event)
                }
                val (symbols, memory, listing) = withContext(Dispatchers.EDT) {
                    Triple(
                        layout.findContent("MarsMipsSymbols")!!.component,
                        layout.findContent("MarsMipsMemory")!!.component,
                        layout.findContent("MarsMipsDisassembly")!!.component,
                    )
                }
                fun table(panel: JComponent) = requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java))
                val bytes = withContext(Dispatchers.EDT) { table(memory) }
                val instructions = withContext(Dispatchers.EDT) { table(listing) }
                val labels = withContext(Dispatchers.EDT) { table(symbols) }
                val field = withContext(Dispatchers.EDT) { requireNotNull(UIUtil.findComponentOfType(memory, JBTextField::class.java)) }
                val search = withContext(Dispatchers.EDT) { requireNotNull(UIUtil.findComponentOfType(symbols, SearchTextField::class.java)) }
                val status = withContext(Dispatchers.EDT) { requireNotNull(UIUtil.findComponentOfType(listing, JBLabel::class.java)) }
                val context = debug.session.suspendContext
                val display = service<MarsDebuggerDisplaySettings>()
                assertMemory(bytes, "0x10010000", "")
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertTrue(instructions.selectedRow >= 0)
                        assertEquals(2, labels.rowCount)
                    }
                }
                withContext(Dispatchers.EDT) {
                    search.text = "0x90000000"
                    assertEquals(1, labels.rowCount)
                    assertEquals("kernelWord", labels.getValueAt(0, 0))
                    labels.setRowSelectionInterval(0, 0)
                    UIUtil.findComponentsOfType(symbols, JButton::class.java).single { it.text == "Inspect Address" }.doClick(0)
                }
                assertMemory(bytes, "0x90000000", "44 43 42 41", "DCBA")
                withContext(Dispatchers.EDT) {
                    action.actionPerformed(event)
                    assertFalse(action.isSelected(event))
                    assertEquals(0, labels.rowCount)
                    search.text = "2415919104"
                    assertEquals("kernelWord", labels.getValueAt(0, 0))
                    assertEquals("2415919104", field.text)
                    assertEquals("2415919104", bytes.getValueAt(0, 0))
                    assertEquals("44 43 42 41", bytes.getValueAt(0, 1).toString().take(11))
                    assertEquals("Program counter 4194304", status.text)
                    val pc = instructions.selectedRow
                    assertEquals("4194304", instructions.getValueAt(pc, 1))
                    assertEquals("0x00000000", instructions.getValueAt(pc, 2))
                    assertSame(context, debug.session.suspendContext)
                    assertEquals("4294967295", display.formatAddress(-1))
                    val xml = XmlSerializer.serialize(display.getState())
                    assertFalse(XmlSerializer.deserialize(xml, MarsDebuggerDisplaySettings.Settings::class.java).hexAddresses)
                    field.text = "0xffffffff"
                    action.actionPerformed(event)
                    assertTrue(action.isSelected(event))
                    assertEquals("0xffffffff", field.text)
                    assertEquals("0x90000000", bytes.getValueAt(0, 0))
                    display.loadState(XmlSerializer.deserialize(xml, MarsDebuggerDisplaySettings.Settings::class.java))
                    assertEquals("0xffffffff", field.text)
                    assertEquals("2415919104", bytes.getValueAt(0, 0))
                    field.postActionEvent()
                    assertEquals("4294967295", field.text)
                }
                assertMemory(bytes, "4294967280", "")
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                withContext(Dispatchers.EDT) {
                    val unattached = MarsMemoryView(debug.session, file.toNioPath())
                    Disposer.register(lifetime, unattached)
                    display.loadState(XmlSerializer.deserialize(Element("state"), MarsDebuggerDisplaySettings.Settings::class.java))
                    assertTrue(action.isSelected(event))
                    assertEquals("0xffffffff", field.text)
                    assertEquals("0xfffffff0", bytes.getValueAt(0, 0))
                    assertEquals("", unattached.addressField.text)
                }
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(lifetime)
                    service<MarsDebuggerDisplaySettings>().setHexAddresses(true)
                }
            }
        }
    }

    @Test
    fun `Debugger value display reformats captured register and memory words independently of addresses`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("display-values.asm", """
                .data
                words: .word -1, 0x80000000, 65, 0
                .text
                li ${'$'}t0, -2
                li ${'$'}t1, -1
                mtc0 ${'$'}t1, ${'$'}14
                li ${'$'}t2, 0xbff80000
                mtc1 ${'$'}t2, ${'$'}f1
                li ${'$'}t3, 0x7fc01234
                mtc1 ${'$'}t3, ${'$'}f2
                c.eq.s ${'$'}f4, ${'$'}f4
                checkpoint: nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 11) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 11)
            val lifetime = Disposer.newDisposable()
            val display = service<MarsDebuggerDisplaySettings>()
            try {
                val (panel, action, event) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsValueDisplayTest", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val settings = DefaultActionGroup()
                    debug.session.debugProcess.registerAdditionalActions(DefaultActionGroup(), DefaultActionGroup(), settings)
                    val action = settings.getChildren(ActionManager.getInstance()).filterIsInstance<MarsHexValuesAction>().single()
                    val event = AnActionEvent.createEvent(action, DataContext.EMPTY_CONTEXT, null, ActionPlaces.DEBUGGER_TOOLBAR, ActionUiKind.POPUP, null)
                    assertEquals("Hexadecimal MARS Values", action.templatePresentation.text)
                    assertEquals(ActionUpdateThread.BGT, action.actionUpdateThread)
                    assertTrue(action.isSelected(event))
                    Triple(layout.findContent("MarsMipsMemory")!!.component, action, event)
                }
                val (table, field) = withContext(Dispatchers.EDT) {
                    requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java)) to
                        requireNotNull(UIUtil.findComponentOfType(panel, JBTextField::class.java))
                }
                assertMemory(table, "0x10010000", "ff ff ff ff 00 00 00 80")
                val context = debug.session.suspendContext
                val frame = requireNotNull(debug.session.currentStackFrame)
                withContext(Dispatchers.EDT) {
                    val initial = registerValues(debug.session, frame)
                    assertEquals("0xfffffffe", initial["\$t0"])
                    assertEquals("NaN (0x7fc01234)", initial["\$f2"])
                    assertEquals("-1.5 (0xbff8000000000000)", initial["\$f0/\$f1"])
                    assertEquals("Words", table.getColumnName(3))
                    assertEquals("0xffffffff  0x80000000  0x00000041  0x00000000", table.getValueAt(0, 3))

                    action.actionPerformed(event)
                    assertFalse(action.isSelected(event))
                    assertTrue(display.hexAddresses)
                    assertSame(context, debug.session.suspendContext)
                    assertSame(frame, debug.session.currentStackFrame)
                    assertEquals("0x10010000", field.text)
                    assertEquals("-1  -2147483648  65  0", table.getValueAt(0, 3))
                    assertEquals("ff ff ff ff 00 00 00 80 41 00 00 00 00 00 00 00", table.getValueAt(0, 1))
                    assertEquals("........A.......", table.getValueAt(0, 2))
                    assertEquals("4294967295", display.formatUnsignedValue(-1))
                    val decimal = registerValues(debug.session, frame)
                    assertEquals("-2", decimal["\$t0"])
                    assertEquals("0", decimal["\$zero"])
                    assertEquals(parseMemoryAddress(initial.getValue("pc")).toString(), decimal["pc"])
                    assertEquals("-1", decimal["\$14 (epc)"])
                    assertEquals("1", decimal["FCC"])
                    assertEquals("NaN", decimal["\$f2"])
                    assertEquals("-1.5", decimal["\$f0/\$f1"])
                    inspectRegisterNodes(debug.session, frame) { values ->
                        val modifiers = values.associate { it.name to it.valueContainer.modifier }
                        assertEquals("-2", modifiers.getValue("\$t0")!!.initialValueEditorText)
                        assertEquals("0x7fc01234", modifiers.getValue("\$f2")!!.initialValueEditorText)
                    }
                    assertEquals("0xfffffffe", evaluate(debug.session, frame, "\$t0"))
                    val xml = XmlSerializer.serialize(display.getState())
                    val loaded = XmlSerializer.deserialize(xml, MarsDebuggerDisplaySettings.Settings::class.java)
                    assertFalse(loaded.hexValues)
                    assertTrue(loaded.hexAddresses)
                    val addressOnly = Element("state").addContent(
                        Element("option").setAttribute("name", "hexAddresses").setAttribute("value", "false"),
                    )
                    val previous = XmlSerializer.deserialize(addressOnly, MarsDebuggerDisplaySettings.Settings::class.java)
                    assertFalse(previous.hexAddresses)
                    assertTrue(previous.hexValues)
                    field.text = "0"
                    field.postActionEvent()
                }
                assertMemory(table, "0x00000000", "-- -- -- --")
                withContext(Dispatchers.EDT) {
                    assertEquals("—  —  —  —", table.getValueAt(0, 3))
                    field.text = "0x10010000"
                    field.postActionEvent()
                }
                assertMemory(table, "0x10010000", "ff ff ff ff")
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                withContext(Dispatchers.EDT) {
                    action.actionPerformed(event)
                    assertTrue(action.isSelected(event))
                    assertEquals("0xffffffff  0x80000000  0x00000041  0x00000000", table.getValueAt(0, 3))
                }
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(lifetime)
                    display.setHexValues(true)
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `Symbols distinguishes identical names by assembly unit and handles unavailable definitions`(defaultProject: Boolean, @TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val first = Files.writeString(Files.createDirectory(directory.resolve("first")).resolve("same.s"), "nop\n").toRealPath()
            val second = Files.writeString(Files.createDirectory(directory.resolve("second")).resolve("same.s"), "nop\n").toRealPath()
            val files = withContext(Dispatchers.EDT) {
                listOf(first, second).associateWith { requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(it)) }
            }
            val sources = MarsExecutionSources(CapturedAssemblySources(
                AssemblySourceSnapshot(listOf(first, second), mapOf(first to "nop\n", second to "nop\n")), null,
                files.mapValues { it.value.modificationStamp }, mapOf(first to first, second to second),
            ))
            val locals = mapOf(
                first to mapOf("duplicate" to Definition(4, Kind.TEXT, Origin(Location(first, 1), null))),
                second to mapOf("duplicate" to Definition(8, Kind.TEXT, null)),
            )
            val image = MutableStateFlow<LoadedSymbols?>(null)
            val view = withContext(Dispatchers.EDT) {
                MarsSymbolsView(if (defaultProject) ProjectManager.getInstance().defaultProject else project, image)
            }
            try {
                withContext(Dispatchers.EDT) {
                    assertEquals(0, view.focus.rowCount)
                    assertEquals("Waiting for an assembled image…", UIUtil.findComponentsOfType(view.component, JBLabel::class.java)
                        .single { it.text.contains("image") }.text)
                    val detached = EditorFactory.getInstance().createDocument("before")
                    WriteAction.run<RuntimeException> { detached.setText("after") }
                    image.value = LoadedSymbols(AssemblySymbolMap(locals, emptyMap()), sources)
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val table = view.focus
                        assertEquals(2, table.rowCount)
                        table.rowSorter.sortKeys = listOf(SortKey(1, SortOrder.ASCENDING))
                        assertEquals("duplicate", table.getValueAt(0, 0))
                        assertEquals("duplicate", table.getValueAt(1, 0))
                        assertEquals(first.toString(), table.getValueAt(0, 4))
                        assertEquals(second.toString(), table.getValueAt(1, 4))
                        assertEquals("", table.getValueAt(1, 5))
                        val openSource = UIUtil.findComponentsOfType(view.component, JButton::class.java).single { it.text == "Open Source" }
                        if (!defaultProject) {
                            table.setRowSelectionInterval(0, 0)
                            assertTrue(openSource.isEnabled)
                            openSource.doClick(0)
                        }
                        table.setRowSelectionInterval(1, 1)
                        assertFalse(openSource.isEnabled)
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(view) }
            }
        }

    @Test
    fun `captured positions recognize unopened files and reject a snapshot from another image`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val path = Files.writeString(directory.resolve("unopened.asm"), "nop\n").toRealPath()
            val otherPath = Files.writeString(directory.resolve("unrelated.asm"), "nop\n").toRealPath()
            withContext(Dispatchers.EDT) {
                val fs = LocalFileSystem.getInstance()
                val file = requireNotNull(fs.refreshAndFindFileByNioFile(path))
                val other = requireNotNull(fs.refreshAndFindFileByNioFile(otherPath))
                val documents = FileDocumentManager.getInstance()
                assertNull(documents.getCachedDocument(file))
                val capture = CapturedAssemblySources(
                    AssemblySourceSnapshot(listOf(path), mapOf(path to "nop\n")), null,
                    mapOf(path to file.modificationStamp), mapOf(path to path),
                )
                val sources = MarsExecutionSources(capture)
                val location = Location(path, 1)
                assertEquals(file, sources.position(location).file)
                assertEquals(location, sources.location(file, 0))
                assertNull(sources.location(other, 0))
                WriteAction.run<RuntimeException> { file.setBinaryContent("nop\nnop\n".toByteArray()) }
                assertNull(documents.getCachedDocument(file))
                assertNull(sources.location(file, 0))
                val snapshot = sources.position(location).file
                WriteAction.run<RuntimeException> { file.delete(this) }
                assertSame(snapshot, sources.position(location).file)
                assertEquals(location, sources.location(snapshot, 0))
                assertNull(MarsExecutionSources(capture).location(snapshot, 0))
            }
        }

    @Test
    fun `Debug shows the assembled include and runs to a cursor in the captured text`() = timeoutRunBlocking<Unit> {
        val body = "nop\naddiu \$t0, \$zero, 42\nnop\n"
        val (source, include) = withContext(Dispatchers.EDT) {
            val entry = fixture.addFileToProject("captured.asm", ".include \"captured-body.s\"\nnop\n").virtualFile
            val include = fixture.addFileToProject("captured-body.s", body).virtualFile.also { addBreakpoint(it, 0) }
            entry to include
        }
        withDebug(source, beforeLaunch = true) { debug ->
            val initial = requireNotNull(debug.nextPosition())
            assertEquals(include, initial.file)
            assertEquals(0, initial.line)
            val capturedFile = withContext(Dispatchers.EDT) {
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(include))
                WriteAction.run<RuntimeException> { document.setText("# changed\n" + body.replace("42", "99")) }
                val descriptor = assertInstanceOf(OpenFileDescriptor::class.java, initial.createNavigatable(project))
                assertEquals(0, descriptor.line)
                val captured = descriptor.file
                assertNotSame(include, captured)
                assertFalse(captured.isWritable)
                assertSame(MipsFileType, captured.fileType)
                assertEquals(body, FileDocumentManager.getInstance().getDocument(captured)?.text)
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(include, 2)!!, false)
                captured
            }
            debug.assertPausedAt(include, 0)
            val staleBreakpoint = withContext(Dispatchers.EDT) {
                val breakpoint = addBreakpoint(include, 1)
                debug.session.runToPosition(XDebuggerUtil.getInstance().createPosition(capturedFile, 2)!!, false)
                breakpoint
            }
            debug.assertPausedAt(capturedFile, 2)
            withContext(Dispatchers.EDT) {
                assertEquals("0x0000002a", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                val document = requireNotNull(FileDocumentManager.getInstance().getDocument(include))
                WriteAction.run<RuntimeException> {
                    debugger.breakpointManager.removeBreakpoint(staleBreakpoint)
                    document.setText(body)
                }
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(include, 1)
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `Disassembly restores layout and sort without changing selected instruction actions`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("disassembly-layout.asm", "entry: nop\nnext: nop\nli \$v0, 10\nsyscall\n").virtualFile
                .also { addBreakpoint(it, 0) }
        }
        val properties = PropertiesComponent.getInstance()
        val keys = (listOf("sortColumn", "descending", "importedOrder") + (0..6).flatMap { listOf("Order$it", "Width$it") })
            .map { DISASSEMBLY_LAYOUT_PREFIX + it }
        val original = keys.associateWith(properties::getValue)
        try {
            withDebug(source) { debug ->
                debug.assertPausedAt(source, 0)
                val lifetime = Disposer.newDisposable()
                try {
                    fun listing(id: String): JComponent {
                        val layout = RunnerLayoutUi.Factory.getInstance(project).create(id, "Debug", "MIPS", lifetime)
                        debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                        return requireNotNull(layout.findContent("MarsMipsDisassembly")).component
                    }
                    val panel = withContext(Dispatchers.EDT) { listing("MarsDisassemblyLayoutTest") }
                    val table = withContext(Dispatchers.EDT) { requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java)) }
                    fun selectedAddress(listing: JBTable) = listing.getValueAt(listing.selectedRow, listing.convertColumnIndexToView(1))
                    fun gesture(change: () -> Unit) {
                        val header = table.tableHeader
                        header.dispatchEvent(MouseEvent(header, MouseEvent.MOUSE_PRESSED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
                        change()
                        header.dispatchEvent(MouseEvent(header, MouseEvent.MOUSE_RELEASED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1))
                    }
                    fun columnNames(table: JBTable) = (0 until table.columnCount).map(table::getColumnName)
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            assertTrue(table.selectedRow >= 0)
                            assertEquals("0x00400000", selectedAddress(table))
                        }
                    }
                    withContext(Dispatchers.EDT) {
                        gesture {}
                        assertNull(properties.getValue(DISASSEMBLY_LAYOUT_PREFIX + "Order0"))
                        gesture {
                            table.columnModel.moveColumn(4, 0)
                            table.columnModel.getColumn(0).width = 235
                        }
                        assertEquals("Source", table.getColumnName(0))
                        assertEquals("4", properties.getValue(DISASSEMBLY_LAYOUT_PREFIX + "Order0"))
                        assertEquals("235", properties.getValue(DISASSEMBLY_LAYOUT_PREFIX + "Width0"))
                        table.rowSorter.sortKeys = emptyList()
                        table.rowSorter.sortKeys = listOf(SortKey(1, SortOrder.DESCENDING))
                        assertEquals("0x00400000", selectedAddress(table))
                        assertEquals("0x0040002c", table.getValueAt(0, table.convertColumnIndexToView(1)))
                        val row = (0 until table.rowCount).single { table.getValueAt(it, 0) == "disassembly-layout.asm:2" }
                        table.setRowSelectionInterval(row, row)
                        assertEquals("0x00400004", selectedAddress(table))
                        UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Open Source" }.doClick(0)
                        val toggle = UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Toggle Address Breakpoint" }
                        toggle.doClick(0)
                        val type = XDebuggerUtil.getInstance().findBreakpointType(MarsAddressBreakpointType::class.java)
                        val point = debugger.breakpointManager.getBreakpoints(type).single()
                        assertEquals(0x00400004, point.properties?.addressIn(source.toNioPath().toString()))
                        toggle.doClick(0)
                    }
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                            assertEquals(1, editor.caretModel.logicalPosition.line)
                        }
                    }
                    val restored = withContext(Dispatchers.EDT) {
                        requireNotNull(UIUtil.findComponentOfType(listing("MarsRestoredDisassemblyLayoutTest"), JBTable::class.java)).also {
                            assertEquals("Source", it.getColumnName(0))
                            assertEquals(235, it.columnModel.getColumn(0).preferredWidth)
                            assertEquals(SortKey(1, SortOrder.DESCENDING), it.rowSorter.sortKeys.single())
                        }
                    }
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            assertTrue(restored.selectedRow >= 0)
                            assertEquals("0x00400000", selectedAddress(restored))
                        }
                    }
                    withContext(Dispatchers.EDT) {
                        val suspension = debug.session.suspendContext
                        val selected = selectedAddress(table)
                        val sourceColumn = table.columnModel.getColumn(table.convertColumnIndexToView(4))
                        val imported = requireNotNull(venusDisassemblyOrder("2 3 4 1 0"))
                        val expected = listOf("Word", "PC", "Instruction", "Source", "Address", "Breakpoint", "Execution")
                        importDisassemblyOrder(imported)
                        assertEquals(expected, columnNames(table))
                        assertEquals(expected, columnNames(restored))
                        assertSame(sourceColumn, table.columnModel.getColumn(3))
                        assertEquals(235, sourceColumn.width)
                        assertEquals(235, restored.columnModel.getColumn(3).preferredWidth)
                        assertEquals(selected, selectedAddress(table))
                        assertEquals(SortKey(1, SortOrder.DESCENDING), table.rowSorter.sortKeys.single())
                        val reopened = requireNotNull(UIUtil.findComponentOfType(listing("MarsImportedDisassemblyLayoutTest"), JBTable::class.java))
                        assertEquals(expected, columnNames(reopened))
                        assertEquals(235, reopened.columnModel.getColumn(3).preferredWidth)
                        assertEquals("4", properties.getValue(DISASSEMBLY_LAYOUT_PREFIX + "Order0"))

                        gesture { table.columnModel.moveColumn(3, 0) }
                        assertNull(properties.importedDisassemblyOrder())
                        val afterDrag = requireNotNull(UIUtil.findComponentOfType(listing("MarsDraggedDisassemblyLayoutTest"), JBTable::class.java))
                        assertEquals(columnNames(table), columnNames(afterDrag))
                        assertEquals(235, afterDrag.columnModel.getColumn(0).preferredWidth)
                        assertSame(suspension, debug.session.suspendContext)
                        debug.session.resume()
                    }
                    assertEquals(0, debug.exit.await())
                } finally {
                    withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
                }
            }
        } finally {
            withContext(Dispatchers.EDT) { original.forEach(properties::setValue) }
        }
    }

    @Test
    fun `Disassembly marks the pending delay slot and follows the last executed address across backsteps`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("execution-markers.asm", """
                b far
                addiu ${'$'}t0, ${'$'}zero, 7
                nop
                .text 0x00400100
                far: nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(source, { delayedBranchingMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(source, 0)
            val lifetime = Disposer.newDisposable()
            try {
                val (table, last) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsExecutionMarkersTest", "Debug", "MIPS", lifetime)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val panel = requireNotNull(layout.findContent("MarsMipsDisassembly")).component
                    requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java)) to
                        UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Last Executed" }
                }
                suspend fun assertMarkers(selected: String, hasLast: Boolean, expected: Map<String, String>) {
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            val row = table.selectedRow
                            assertTrue(row >= 0)
                            assertEquals(selected, table.getValueAt(row, 1))
                            assertEquals(hasLast, last.isEnabled)
                            val marked = (0 until table.rowCount).mapNotNull {
                                val label = table.getValueAt(it, 6).toString()
                                (table.getValueAt(it, 1).toString() to label).takeIf { label.isNotEmpty() }
                            }.toMap()
                            assertEquals(expected, marked)
                        }
                    }
                }
                val branch = "0x00400000"
                val slot = "0x00400004"
                val target = "0x00400100"
                assertMarkers(branch, false, emptyMap())
                withContext(Dispatchers.EDT) { debug.session.stepInto() }
                debug.assertPausedAt(source, 1)
                val delay = mapOf(branch to "Last executed", slot to "Delay slot next")
                assertMarkers(slot, true, delay)
                withContext(Dispatchers.EDT) {
                    val background = Color(0xd1e5ff)
                    val row = table.selectedRow
                    val column = 1
                    val renderer = table.getCellRenderer(row, column)
                    val value = table.getValueAt(row, column)
                    val normal = renderer.getTableCellRendererComponent(table, value, false, false, row, column).background
                    withHighlightScheme({
                        it.setAttributes(MarsMachineHighlight.DELAY.colorKey, TextAttributes().apply { backgroundColor = background })
                    }) {
                        assertEquals(background, renderer.getTableCellRendererComponent(table, value, false, false, row, column).background)
                        assertFalse(background == renderer.getTableCellRendererComponent(table, value, true, false, row, column).background)
                        val other = (0 until table.rowCount).first { it != row }
                        assertEquals(normal, renderer.getTableCellRendererComponent(table, table.getValueAt(other, column), false, false, other, column).background)
                    }
                }
                withContext(Dispatchers.EDT) { debug.session.stepInto() }
                debug.assertPausedAt(source, 4)
                assertMarkers(target, true, emptyMap())
                withContext(Dispatchers.EDT) { last.doClick(0) }
                assertMarkers(slot, true, mapOf(slot to "Last executed in delay slot"))
                repeat(2) { index ->
                    withContext(Dispatchers.EDT) {
                        val (action, event) = backstepAction(debug.session)
                        action.actionPerformed(event)
                    }
                    debug.assertPausedAt(source, 1 - index)
                    if (index == 0) assertMarkers(slot, true, delay) else assertMarkers(branch, false, emptyMap())
                }
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(lifetime) }
            }
        }
    }

    private fun withHighlightScheme(configure: (EditorColorsScheme) -> Unit, verify: () -> Unit) {
        val manager = EditorColorsManager.getInstance() as EditorColorsManagerImpl
        val previous = manager.globalScheme
        val scheme = previous.clone() as EditorColorsScheme
        scheme.name = "MARS Machine Highlights Test"
        configure(scheme)
        manager.addColorScheme(scheme)
        try {
            manager.setGlobalScheme(scheme)
            verify()
        } finally {
            manager.setGlobalScheme(previous)
            manager.removeScheme(scheme)
        }
    }

    @Test
    fun `Variables highlights written register banks and restores their presentation on backstep`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("register-highlights.asm", """
                addiu ${'$'}t0, ${'$'}zero, 2
                addiu ${'$'}t1, ${'$'}zero, 3
                mult ${'$'}t0, ${'$'}t1
                mtc1 ${'$'}t0, ${'$'}f1
                c.eq.s ${'$'}f0, ${'$'}f0
                mtc0 ${'$'}t0, ${'$'}14
                nop
                add.d ${'$'}f2, ${'$'}f0, ${'$'}f0
                nop
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also {
                addBreakpoint(it, 3)
                addBreakpoint(it, 6)
                addBreakpoint(it, 8)
            }
        }
        withDebug(source) { debug ->
            suspend fun assertHighlighted(vararg names: String) {
                withContext(Dispatchers.EDT) {
                    val frame = requireNotNull(debug.session.currentStackFrame)
                    inspectRegisterNodes(debug.session, frame) { nodes ->
                        val highlighted = nodes.filter { node ->
                            var usedKey: TextAttributesKey? = null
                            requireNotNull(node.valuePresentation).renderValue(object : XValueTextRendererImpl(SimpleColoredText()) {
                                override fun renderRawValue(value: String, key: TextAttributesKey) {
                                    usedKey = key
                                }
                            })
                            usedKey == MarsMachineHighlight.REGISTER.colorKey
                        }.map { it.name }.toSet()
                        assertEquals(names.toSet(), highlighted)
                        assertEquals(names.toSet(), nodes.filter { " (written) = " in it.text.toString() }.map { it.name }.toSet())
                        assertTrue(nodes.none { it.rawValue?.contains("written") == true })
                    }
                }
            }
            debug.assertPausedAt(source, 3)
            assertHighlighted("hi", "lo")
            val display = service<MarsDebuggerDisplaySettings>()
            try {
                val (action, event) = withContext(Dispatchers.EDT) {
                    highlightAction(debug.session, "Highlight MARS Register Writes")
                }
                val suspension = debug.session.suspendContext
                withContext(Dispatchers.EDT) {
                    assertTrue(action.isSelected(event))
                    action.actionPerformed(event)
                    assertFalse(action.isSelected(event))
                    assertTrue(display.highlightMemoryWrites)
                    assertSame(suspension, debug.session.suspendContext)
                    val saved = XmlSerializer.serialize(display.getState())
                    assertFalse(XmlSerializer.deserialize(saved, MarsDebuggerDisplaySettings.Settings::class.java).highlightRegisterWrites)
                }
                assertHighlighted()
                withContext(Dispatchers.EDT) {
                    action.actionPerformed(event)
                    assertTrue(action.isSelected(event))
                }
                assertHighlighted("hi", "lo")
            } finally {
                withContext(Dispatchers.EDT) { display.setHighlightRegisterWrites(true) }
            }
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 6)
            assertHighlighted("hi", "lo", "\$14 (epc)", "FCC", "\$f1", "\$f0/\$f1")
            withContext(Dispatchers.EDT) { debug.session.resume() }
            debug.assertPausedAt(source, 8)
            assertHighlighted("hi", "lo", "\$14 (epc)", "FCC", "\$f2", "\$f3", "\$f2/\$f3")
            withContext(Dispatchers.EDT) {
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 7)
            assertHighlighted("hi", "lo", "\$14 (epc)", "FCC", "\$f1", "\$f0/\$f1")
        }
    }

    @Test
    fun `native Disassembly shows live code follows PC and navigates to source`() = timeoutRunBlocking<Unit> {
        val (source, fragment) = withContext(Dispatchers.EDT) {
            val fragment = fixture.addFileToProject("disassembly-body.s", "li \$t1, 0x240b0043\n").virtualFile
            val entry = fixture.addFileToProject("disassembly.s", """
                .data
                payload: .word 0x240a002a, 0x01400008, 0xffffffff
                .text
                la ${'$'}t0, payload
                .include "disassembly-body.s"
                sw ${'$'}t1, patch
                patch: nop
                jr ${'$'}t0
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 5) }
            entry to fragment
        }
        withDebug(source, { selfModifyingCodeMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(source, 5)
            val disposable = Disposer.newDisposable()
            try {
                val discarded = withContext(Dispatchers.EDT) {
                    MarsDisassemblyView(debug.session, source.path) { error("Disposed view") }.also(Disposer::dispose)
                }
                val (table, open, status) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsDisassemblyTest", "Debug", "MIPS", disposable)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val content = requireNotNull(layout.findContent("MarsMipsDisassembly"))
                    assertEquals("Disassembly", content.displayName)
                    assertFalse(content.isCloseable)
                    val panel = content.component
                    Triple(
                        requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java)),
                        UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Open Source" },
                        requireNotNull(UIUtil.findComponentOfType(panel, JBLabel::class.java)),
                    )
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val pc = table.selectedRow
                        assertTrue(pc >= 0)
                        assertEquals("▶", table.getValueAt(pc, 0))
                        assertEquals("disassembly.s:6", table.getValueAt(pc, 4))
                        val patch = (pc until table.rowCount).first { table.getValueAt(it, 4) == "disassembly.s:7" }
                        assertEquals("0x00000000", table.getValueAt(patch, 2))
                        assertEquals("nop", table.getValueAt(patch, 3))
                        table.setRowSelectionInterval(patch, patch)
                        assertTrue(open.isEnabled)
                        open.doClick(0)
                    }
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                        assertEquals(source, FileDocumentManager.getInstance().getFile(editor.document))
                        assertEquals(6, editor.caretModel.logicalPosition.line)
                        assertEquals(0, discarded.focus.rowCount)
                    }
                }
                withContext(Dispatchers.EDT) {
                    val includeRow = (0 until table.rowCount).first { table.getValueAt(it, 4) == "disassembly-body.s:1" }
                    WriteAction.run<RuntimeException> { fragment.delete(this) }
                    table.setRowSelectionInterval(includeRow, includeRow)
                    assertTrue(open.isEnabled)
                    open.doClick(0)
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val editor = requireNotNull(FileEditorManager.getInstance(project).selectedTextEditor)
                        val captured = requireNotNull(FileDocumentManager.getInstance().getFile(editor.document))
                        assertEquals("disassembly-body.s (assembled snapshot)", captured.name)
                        assertFalse(captured.isWritable)
                        assertEquals("li \$t1, 0x240b0043\n", editor.document.text)
                    }
                }
                withContext(Dispatchers.EDT) { debug.session.stepInto() }
                assertNull(debug.nextPosition())
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val pc = table.selectedRow
                        assertTrue(pc >= 0)
                        assertEquals("▶", table.getValueAt(pc, 0))
                        assertEquals("0x240b0043", table.getValueAt(pc, 2))
                        assertTrue((table.getValueAt(pc, 3) as String).startsWith("addiu "))
                        assertEquals("", table.getValueAt(pc, 4))
                        assertFalse(open.isEnabled)
                    }
                }
                withContext(Dispatchers.EDT) {
                    val (action, event) = backstepAction(debug.session)
                    action.actionPerformed(event)
                }
                debug.assertPausedAt(source, 5)
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val pc = table.selectedRow
                        assertTrue(pc >= 0)
                        assertEquals("disassembly.s:6", table.getValueAt(pc, 4))
                        assertEquals("0x00000000", table.getValueAt(pc + 1, 2))
                    }
                }
                withContext(Dispatchers.EDT) { debug.session.stepInto() }
                assertNull(debug.nextPosition())
                withContext(Dispatchers.EDT) { debug.session.stepInto() }
                debug.assertPausedAt(source, 7)
                withContext(Dispatchers.EDT) { debug.session.stepInto() }
                assertNull(debug.nextPosition())
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val pc = table.selectedRow
                        assertTrue(pc >= 0)
                        assertEquals("0x10010000", table.getValueAt(pc, 1))
                        assertEquals("0x240a002a", table.getValueAt(pc, 2))
                        assertTrue((table.getValueAt(pc, 3) as String).startsWith("addiu "))
                        assertEquals("", table.getValueAt(pc, 4))
                        assertEquals("<INVALID>", table.getValueAt(pc + 2, 3))
                    }
                }
                repeat(2) {
                    withContext(Dispatchers.EDT) { debug.session.stepInto() }
                    assertNull(debug.nextPosition())
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertEquals("Program counter 0x0000002a", status.text)
                        assertEquals(0, table.rowCount)
                        assertFalse(open.isEnabled)
                    }
                }
                // Refresh clears the table before inspecting memory. Drain that inspection before stopping.
                val inspected = CompletableDeferred<Unit>()
                withContext(Dispatchers.EDT) {
                    val context = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                    context.inspectionRequests.send { inspected.complete(Unit) }
                }
                inspected.await()
                withContext(Dispatchers.EDT) { debug.session.stop() }
                assertEquals(130, debug.exit.await())
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertEquals("Pause the program to inspect instructions.", status.text)
                        assertEquals(0, table.rowCount)
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(disposable) }
            }
        }
    }

    @Test
    fun `address breakpoints persist by entry and stop in code without source`() = timeoutRunBlocking<Unit> {
        val type = XDebuggerUtil.getInstance().findBreakpointType(MarsAddressBreakpointType::class.java)
        val source = withContext(Dispatchers.EDT) {
            val other = fixture.addFileToProject("other.s", "nop\n").virtualFile
            val file = fixture.addFileToProject("raw-breakpoints.s", """
                .data
                code: .word 0x25290001, 0x25290002, 0x25290004, 0x2402000a, 0x0000000c
                .text
                la ${'$'}t0, code
                jr ${'$'}t0
            """.trimIndent()).virtualFile
            addBreakpoint(file, 4)
            WriteAction.run<RuntimeException> {
                val manager = debugger.breakpointManager
                manager.addBreakpoint(type, MarsAddressBreakpointProperties(other.path, 0x00400000))
                manager.addBreakpoint(type, MarsAddressBreakpointProperties(file.path, 0x00400002))
                assertEquals("Missing MIPS breakpoint address", type.getDisplayText(manager.addBreakpoint(type, null)))
                val shared = manager.addBreakpoint(type, MarsAddressBreakpointProperties(file.path, 0x00400008))
                shared.suspendPolicy = SuspendPolicy.NONE
                shared.setLogExpression("0x12345678")
            }
            file
        }
        withDebug(source, { selfModifyingCodeMode = ProjectOptionOverride.ENABLED }) { debug ->
            debug.assertPausedAt(source, 4)
            val disposable = Disposer.newDisposable()
            try {
                val (table, toggle) = withContext(Dispatchers.EDT) {
                    val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsAddressTest", "Debug", "MIPS", disposable)
                    debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                    val panel = requireNotNull(layout.findContent("MarsMipsDisassembly")).component
                    val table = requireNotNull(UIUtil.findComponentOfType(panel, JBTable::class.java))
                    assertEquals(Icon::class.java, table.getColumnClass(5))
                    table to UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Toggle Address Breakpoint" }
                }
                fun row(address: String) = (0 until table.rowCount).firstOrNull { table.getValueAt(it, 1) == address } ?: -1
                suspend fun marker(address: String, expected: Icon?) {
                    pollAssertionsAsync(3.seconds, 10.milliseconds) {
                        withContext(Dispatchers.EDT) {
                            val index = row(address)
                            assertTrue(index >= 0, address)
                            assertSame(expected, table.getValueAt(index, 5), address)
                        }
                    }
                }
                marker("0x00400000", null)
                withContext(Dispatchers.EDT) {
                    table.clearSelection()
                    assertFalse(toggle.isEnabled)
                    debug.session.stepInto()
                }
                assertNull(debug.nextPosition())
                val hitAddress = "0x10010004"
                val logAddress = "0x10010008"
                marker(hitAddress, null)
                val point = withContext(Dispatchers.EDT) {
                    val index = row(hitAddress)
                    table.setRowSelectionInterval(index, index)
                    assertTrue(toggle.isEnabled)
                    toggle.doClick(0)
                    val point = debugger.breakpointManager.getBreakpoints(type).single {
                        it.properties?.address == 0x10010004
                    }
                    assertEquals("$hitAddress in ${source.path}", type.getDisplayText(point))
                    assertSame(MarsDebugEditors, type.getEditorsProvider(point, project))
                    val saved = XmlSerializer.serialize(point.properties.state)
                    val restored = type.createProperties().also {
                        it.loadState(XmlSerializer.deserialize(saved, MarsAddressBreakpointProperties::class.java))
                    }
                    assertEquals(source.path, restored.entry)
                    assertEquals(0x10010004, restored.address)
                    WriteAction.run<RuntimeException> {
                        point.setCondition("(word(pc) & 0xff) == 2")
                    }
                    point
                }
                marker(hitAddress, type.enabledIcon)
                withContext(Dispatchers.EDT) { WriteAction.run<RuntimeException> { point.isEnabled = false } }
                marker(hitAddress, type.disabledIcon)
                withContext(Dispatchers.EDT) {
                    WriteAction.run<RuntimeException> { point.isEnabled = true }
                    debug.session.setBreakpointMuted(true)
                }
                marker(hitAddress, type.mutedEnabledIcon)
                withContext(Dispatchers.EDT) {
                    debug.session.setBreakpointMuted(false)
                    val index = row(logAddress)
                    table.setRowSelectionInterval(index, index)
                    toggle.doClick(0)
                    val logpoint = debugger.breakpointManager.getBreakpoints(type).single { it.properties?.address == 0x10010008 }
                    WriteAction.run<RuntimeException> {
                        logpoint.suspendPolicy = SuspendPolicy.NONE
                        logpoint.setLogExpression("\$t1")
                    }
                }
                marker(logAddress, type.suspendNoneIcon)
                val console = withContext(Dispatchers.EDT) {
                    assertInstanceOf(ConsoleViewImpl::class.java, debug.session.consoleView).also { it.component }
                }
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertNull(debug.nextPosition())
                withContext(Dispatchers.EDT) {
                    val frame = requireNotNull(debug.session.currentStackFrame)
                    assertEquals(hitAddress, evaluate(debug.session, frame, "pc"))
                    assertEquals("0x00000001", evaluate(debug.session, frame, "\$t1"))
                }
                marker(hitAddress, type.enabledIcon)
                withContext(Dispatchers.EDT) {
                    val index = row(hitAddress)
                    table.setRowSelectionInterval(index, index)
                    toggle.doClick(0)
                    assertFalse(point in debugger.breakpointManager.getBreakpoints(type))
                }
                marker(hitAddress, null)
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val text = console.editor!!.document.text
                        assertTrue(text.contains("0x12345678"), text)
                        assertTrue(text.contains("0x00000003"), text)
                    }
                }
            } finally {
                withContext(Dispatchers.EDT) { Disposer.dispose(disposable) }
            }
        }
    }

    @Test
    fun `disassembly window stays within the unsigned address space`() {
        MarsSessionService.createMarsComponent().context().use { machine ->
            val first = disassemblyWindow(machine.memory, 0)
            assertEquals(0, first.first().address)
            assertEquals(listOf(first.first()), first.filter(DisassemblyRow::current))
            val last = disassemblyWindow(machine.memory, 0xffff_fffcL)
            assertEquals(0xffff_fffcu.toInt(), last.last().address)
            assertEquals(listOf(last.last()), last.filter(DisassemblyRow::current))
            assertTrue(disassemblyWindow(machine.memory, 0xffff_ffffL).isEmpty())
        }
    }

    @Test
    fun `memory page decodes big endian bytes in address order`() {
        MarsSessionService.createMarsComponent().context().use { machine ->
            val memory = machine.memory
            memory.byteOrder = MemoryState.BIG_ENDIAN
            memory.setWord(memory.dataBaseAddress, 0x41424300)
            val row = memoryPage(memory, memory.dataBaseAddress.toLong()).first()
            assertTrue(row.bytes.startsWith("41 42 43 00"), row.bytes)
            assertTrue(row.characters.startsWith("ABC."), row.characters)
        }
    }

    @Test
    fun `Debug Memory exports each pause and rejects a dialog completed after resume`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val file = exportDebugProgram()
            val output = directory.resolve("paused.hex")
            val finalDump = directory.resolve("finished.hex")
            val notifications = Channel<Notification>(Channel.UNLIMITED)
            val disposable = Disposer.newDisposable()
            try {
                withDebug(file, {
                    memoryDumps.add(RunMemoryDumpState().apply {
                        outputPath = finalDump.toString()
                        formatName = "HexText"
                        region = MemoryExportRegion.RANGE
                        firstAddress = 0x10010000L
                        lastAddress = firstAddress
                    })
                }) { debug ->
                    debug.assertPausedAt(file, 3)
                    val button = withContext(Dispatchers.EDT) {
                        project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
                            override fun notify(notification: Notification) {
                                if (notification.groupId == "MARS Memory Export") notifications.trySend(notification)
                            }
                        })
                        replaceMemoryExportSaveDialog(disposable) { _, base, name ->
                            assertEquals(file.toNioPath().parent, base)
                            assertEquals("export-debug-memory", name)
                            output
                        }
                        val layout = RunnerLayoutUi.Factory.getInstance(project).create("MarsExportTest", "Debug", "MIPS", disposable)
                        debug.session.debugProcess.createTabLayouter().registerAdditionalContent(layout)
                        val panel = requireNotNull(layout.findContent("MarsMipsMemory")).component
                        UIUtil.findComponentsOfType(panel, JButton::class.java).single { it.text == "Export Memory…" }
                    }
                    withContext(Dispatchers.EDT) {
                        assertTrue(button.isEnabled)
                        interceptMemoryExportDialog { it.doCancelAction() }
                        button.doClick(0)
                        assertFalse(Files.exists(output))
                        interceptMemoryExportDialog(::selectExportDataWord)
                        button.doClick(0)
                    }
                    assertEquals(NotificationType.INFORMATION, notifications.receive().type)
                    assertEquals(listOf("00000007"), Files.readAllLines(output))
                    withContext(Dispatchers.EDT) {
                        interceptMemoryExportDialog { dialog ->
                            selectExportDataWord(dialog)
                            debug.session.resume()
                            assertFalse(button.isEnabled)
                            val event = ActionEvent(button, ActionEvent.ACTION_PERFORMED, "stale")
                            button.actionListeners.forEach { it.actionPerformed(event) }
                        }
                        button.doClick(0)
                    }
                    debug.assertPausedAt(file, 5)
                    val failure = notifications.receive()
                    assertEquals(NotificationType.ERROR, failure.type)
                    assertTrue(failure.content.contains("resumed or stopped"))
                    assertEquals(listOf("00000007"), Files.readAllLines(output))
                    withContext(Dispatchers.EDT) {
                        assertTrue(button.isEnabled)
                        interceptMemoryExportDialog(::selectExportDataWord)
                        button.doClick(0)
                    }
                    assertEquals(NotificationType.INFORMATION, notifications.receive().type)
                    assertEquals(listOf("00000009"), Files.readAllLines(output))
                    assertFalse(Files.exists(finalDump))
                    withContext(Dispatchers.EDT) { debug.session.resume() }
                    assertEquals(0, debug.exit.await())
                    assertEquals(listOf("00000009"), Files.readAllLines(finalDump))
                }
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(disposable)
                    UiInterceptors.clear()
                }
            }
        }

    @Test
    fun `the debugger can resume during export IO and discards requests queued for an old pause`(@TempDir directory: Path) =
        timeoutRunBlocking<Unit> {
            val file = exportDebugProgram()
            val releaseWriter = CountDownLatch(1)
            val writing = CompletableDeferred<Unit>()
            val hex = DumpFormatLoader.FORMATS.single { it.commandDescriptor == "HexText" }
            val format = object : DumpFormat by hex {
                override fun write(file: File, snapshot: MemoryDumpSnapshot) {
                    writing.complete(Unit)
                    check(releaseWriter.await(10, TimeUnit.SECONDS))
                    hex.write(file, snapshot)
                }
            }
            val options = MemoryExportOptions(format, MemoryExportRegion.RANGE, 0x10010000u..0x10010000u)
            withDebug(file) { debug ->
                debug.assertPausedAt(file, 3)
                val pause = assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                val output = directory.resolve("detached")
                val job = project.service<MarsMemoryExportService>().exportCapturedInBackground(options, output) {
                    pause.captureForExport(options)
                }
                try {
                    writing.await()
                    val inspecting = CompletableDeferred<Unit>()
                    val releaseInspection = CompletableDeferred<Unit>()
                    pause.inspectionRequests.send {
                        inspecting.complete(Unit)
                        releaseInspection.await()
                    }
                    inspecting.await()
                    val queued = async(start = CoroutineStart.UNDISPATCHED) { pause.captureForExport(options) }
                    try {
                        withContext(Dispatchers.EDT) { debug.session.resume() }
                    } finally {
                        releaseInspection.complete(Unit)
                    }
                    debug.assertPausedAt(file, 5)
                    val stale = assertInstanceOf(MemoryExportCapture.Failed::class.java, queued.await())
                    assertTrue(stale.message.contains("resumed or stopped"))
                    val late = assertInstanceOf(MemoryExportCapture.Failed::class.java, pause.captureForExport(options))
                    assertEquals(stale.message, late.message)
                    assertFalse(Files.exists(output))
                } finally {
                    releaseWriter.countDown()
                }
                job.join()
                assertEquals(listOf("00000007"), Files.readAllLines(output))
            }

            val cancelled = CompletableDeferred<MemoryExportCapture>().also { it.cancel() }
            MarsSessionService.createMarsComponent().context().use { machine ->
                MemoryExportRequest(options, cancelled).capture(machine)
                assertTrue(cancelled.isCancelled)
            }
        }

    private suspend fun exportDebugProgram(): VirtualFile = withContext(Dispatchers.EDT) {
        fixture.addFileToProject("export-debug.s", """
            .data
            payload: .word 7
            .text
            addiu ${'$'}t0, ${'$'}zero, 9
            sw ${'$'}t0, payload
            nop
        """.trimIndent()).virtualFile.also {
            addBreakpoint(it, 3)
            addBreakpoint(it, 5)
        }
    }

    private fun selectExportDataWord(dialog: MarsMemoryExportDialog) {
        val (region, format) = UIUtil.findComponentsOfType(dialog.fields, JComboBox::class.java)
        region.selectedItem = MemoryExportRegion.RANGE
        format.selectedItem = DumpFormatLoader.FORMATS.single { it.commandDescriptor == "HexText" }
        val (first, last) = UIUtil.findComponentsOfType(dialog.fields, JTextField::class.java)
        first.text = "0x10010000"
        last.text = first.text
        assertTrue(dialog.performValidateAll().isEmpty())
        dialog.performOKAction()
    }

    private suspend fun assertMemory(table: JBTable, address: String, bytes: String, ascii: String = "") {
        pollAssertionsAsync(3.seconds, 10.milliseconds) {
            withContext(Dispatchers.EDT) {
                assertTrue(table.rowCount > 0)
                assertEquals(address, table.getValueAt(0, 0))
                assertTrue((table.getValueAt(0, 1) as String).startsWith(bytes))
                assertTrue((table.getValueAt(0, 2) as String).startsWith(ascii))
            }
        }
    }

    @Test
    fun `debugger editor highlights comparison and bit operators with the expression lexer`() = timeoutRunBlocking<Unit> {
        withContext(Dispatchers.EDT) {
            val source = "(~\$t0 >>> 7 & 8 ^ 1 | 2) != (3 < 4)"
            val expression = XDebuggerUtil.getInstance().createExpression(source, null, null, EvaluationMode.EXPRESSION)
            val document = MarsDebugEditors.createDocument(project, expression, null, EvaluationMode.EXPRESSION, "breakpoint")
            val factory = EditorFactory.getInstance()
            val editor = assertInstanceOf(EditorEx::class.java, factory.createEditor(document, project, MipsFileType, false))
            try {
                assertEquals(MipsTypes.psi("BAD_CHARACTER"), editor.highlighter.createIterator(source.indexOf('!')).tokenType)
                assertDoesNotThrow { MarsDebugEditors.afterEditorCreated(null) }
                MarsDebugEditors.afterEditorCreated(editor)
                val expected = editor.colorsScheme.getAttributes(DefaultLanguageHighlighterColors.OPERATION_SIGN)
                for ((spelling, name) in mapOf("~" to "BIT_NOT", ">>>" to "SHIFT", "&" to "BIT_AND",
                    "^" to "BIT_XOR", "|" to "BIT_OR", "!=" to "EQUALITY", "<" to "RELATION")) {
                    val operator = editor.highlighter.createIterator(source.indexOf(spelling))
                    assertEquals(MipsTypes.psi(name), operator.tokenType, spelling)
                    assertEquals(spelling.length, operator.end - operator.start, spelling)
                    assertEquals(expected, operator.textAttributes, spelling)
                }
            } finally {
                factory.releaseEditor(editor)
            }
        }
    }

    @Test
    fun `native conditions and log expressions use the machine state before the instruction`() = timeoutRunBlocking<Unit> {
        val source = """
            .data
            flag: .word 0
            .text
            li ${'$'}t0, 1
            false_hit: nop
            bad_condition: nop
            sw ${'$'}t0, flag
            log_value: nop
            bad_log: nop
            true_hit: addiu ${'$'}t0, ${'$'}t0, 1
            li ${'$'}v0, 10
            syscall
        """.trimIndent()
        val disposable = Disposer.newDisposable()
        val errors = Channel<String>(Channel.UNLIMITED)
        try {
            val file = withContext(Dispatchers.EDT) {
                project.messageBus.connect(disposable).subscribe(Notifications.TOPIC, object : Notifications {
                    override fun notify(notification: Notification) {
                        if (notification.content.startsWith("Could not evaluate MIPS breakpoint")) {
                            errors.trySend(notification.content)
                        }
                    }
                })
                val file = fixture.addFileToProject("conditions.asm", source).virtualFile
                val falseHit = addBreakpoint(file, 4)
                val badCondition = addBreakpoint(file, 5)
                val logValue = addBreakpoint(file, 7)
                val badLog = addBreakpoint(file, 8)
                val trueHit = addBreakpoint(file, 9)
                assertSame(MarsDebugEditors, breakpointType.getEditorsProvider(trueHit, project))
                WriteAction.run<RuntimeException> {
                    falseHit.setCondition("(word(flag) & 1) != 0")
                    falseHit.setLogExpression("0x11111111")
                    badCondition.setCondition("unknown")
                    badCondition.setLogExpression("0x22222222")
                    logValue.setCondition("word(flag)")
                    logValue.setLogExpression("(word(flag) | \$t0) + 0x1235")
                    logValue.suspendPolicy = SuspendPolicy.NONE
                    badLog.setLogExpression("word(flag+1)")
                    badLog.isLogMessage = true
                    badLog.suspendPolicy = SuspendPolicy.NONE
                    trueHit.setCondition("((word(flag) << 1) >>> 1) == \$t0")
                    trueHit.setLogExpression("\$t0")
                }
                file
            }
            withDebug(file) { debug ->
                debug.assertPausedAt(file, 9)
                withContext(Dispatchers.EDT) {
                    assertEquals("0x00000001", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                }
                assertEquals("Could not evaluate MIPS breakpoint condition. Enter a valid MIPS debugger expression using registers, labels, numbers, memory reads, arithmetic, comparisons, or bitwise operators.", errors.receive())
                assertEquals("Could not evaluate MIPS breakpoint log expression. The memory address is invalid, unaligned, or unavailable.", errors.receive())
                val console = withContext(Dispatchers.EDT) {
                    assertInstanceOf(ConsoleViewImpl::class.java, debug.session.consoleView).also { it.component }
                }
                pollAssertionsAsync(3.seconds, 10.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        val text = console.editor!!.document.text
                        assertTrue(text.contains("0x00001236"), text)
                        assertTrue(text.contains("0x00000001"), text)
                        assertTrue(text.contains("conditions.asm"), text)
                        assertFalse(text.contains("0x11111111"), text)
                        assertFalse(text.contains("0x22222222"), text)
                    }
                }
                withContext(Dispatchers.EDT) { debug.session.resume() }
                assertEquals(0, debug.exit.await())
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    @Test
    fun `ClearScreen removes earlier output from the native Debug console`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("debug-clear.asm", """
                .data
                before: .asciiz "old debug output"
                after: .asciiz "new debug output"
                .text
                la ${'$'}a0, before
                li ${'$'}v0, 4
                syscall
                li ${'$'}v0, 60
                syscall
                la ${'$'}a0, after
                li ${'$'}v0, 4
                syscall
                li ${'$'}v0, 10
                syscall
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 12) }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 12)
            val console = withContext(Dispatchers.EDT) {
                assertInstanceOf(ConsoleViewImpl::class.java, debug.session.consoleView)
            }
            pollAssertionsAsync(5.seconds, 10.milliseconds) {
                withContext(Dispatchers.EDT) { assertEquals("new debug output", console.editor!!.document.text) }
            }
            withContext(Dispatchers.EDT) { debug.session.resume() }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `native logpoint continues and a disabled breakpoint can be enabled again`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            val file = fixture.addFileToProject("loop.s", "nop\nli \$v0, 1\nsyscall\nloop: b loop\nnop\n").virtualFile
            val log = addBreakpoint(file, 0)
            val stop = addBreakpoint(file, 3)
            WriteAction.run<RuntimeException> {
                log.suspendPolicy = SuspendPolicy.NONE
                log.isLogMessage = true
                stop.isEnabled = false
            }
            file to stop
        }
        withDebug(source.first) { debug ->
            debug.output.await()
            val console = withContext(Dispatchers.EDT) {
                assertInstanceOf(ConsoleViewImpl::class.java, debug.session.consoleView).also { it.component }
            }
            pollAssertionsAsync(3.seconds, 10.milliseconds) {
                withContext(Dispatchers.EDT) { assertTrue(console.editor!!.document.text.contains("loop.s")) }
            }
            withContext(Dispatchers.EDT) {
                assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                val (action, event) = toolbarAction(debug.session, "Step Instruction")
                assertFalse(event.presentation.isEnabled)
                action.actionPerformed(event)
            }
            withContext(Dispatchers.EDT) { debug.session.pause() }
            debug.assertPausedAt(source.first, 3)
            withContext(Dispatchers.EDT) {
                debug.session.setBreakpointMuted(true)
                WriteAction.run<RuntimeException> { source.second.isEnabled = true }
                debug.session.setBreakpointMuted(false)
                debug.session.resume()
            }
            debug.assertPausedAt(source.first, 3)
            withContext(Dispatchers.EDT) { debug.session.stop() }
            assertEquals(130, debug.exit.await())
        }
    }

    private fun addBreakpoint(file: VirtualFile, line: Int) =
        WriteAction.compute<_, RuntimeException> { debugger.breakpointManager.addLineBreakpoint(breakpointType, file.url, line, null) }

    @Test
    fun `floating point tool edits only the selected paused register and refreshes the debugger`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("float-tool.asm",
                "lui \$t0, 0x3fc0\nmtc1 \$t0, \$f0\nmfc1 \$t1, \$f0\nli \$v0, 5\nsyscall\n")
                .virtualFile.also { addBreakpoint(it, 2) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 2)
            val process = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler)
            val parent = JPanel()
            val (view, content, other) = withContext(Dispatchers.EDT) {
                val manager = ToolWindowManager.getInstance(project)
                val id = DefaultDebugExecutor.getDebugExecutorInstance().toolWindowId
                val window = manager.getToolWindow(id) ?: manager.registerToolWindow(RegisterToolWindowTask(id))
                fun attach(handler: MarsProcessHandler, number: Long) =
                    RunContentDescriptor(null, handler, JPanel(), "Floating point test").let { descriptor ->
                        descriptor.executionId = number
                        ContentFactory.getInstance().createContent(descriptor.component, descriptor.displayName, false).also {
                            it.putUserData(RunContentDescriptor.DESCRIPTOR_KEY, descriptor)
                            it.setDisposer(descriptor)
                            window.contentManager.addContent(it)
                        }
                    }
                val content = attach(process, 1)
                val runFile = fixture.addFileToProject("float-read-only.asm", "nop\n").virtualFile
                val running = project.service<MarsRunService>().createProcess(runFile.toNioPath(), memoryDumps = emptyList())
                val other = attach(running, 2)
                running.startNotify()
                val view = FloatingPointView(project)
                ComponentUtil.forceMarkAsShowing(parent, true)
                withForcedRespectIsShowingClientProperty {
                    withShowingChanged { parent.add(view.component) }
                    UIUtil.dispatchAllInvocationEvents()
                }
                Triple(view, content, other)
            }
            fun hex() = UIUtil.findComponentsOfType(view.component, JBTextField::class.java).single { it.columns == 12 }
            fun register() = UIUtil.findComponentsOfType(view.component, JComboBox::class.java).single { it.itemCount == 33 }
            fun write(bits: String) {
                hex().text = bits
                hex().postActionEvent()
            }
            try {
                val frame = withContext(Dispatchers.EDT) {
                    register().selectedIndex = 1
                    assertFalse(hex().isEditable)
                    UIUtil.findComponentsOfType(view.component, JComboBox::class.java).single { it.itemCount == 2 }.selectedIndex = 1
                    assertEquals("0x3fc00000", hex().text)
                    assertTrue(hex().isEditable)
                    assertTrue(backstepAction(debug.session).second.presentation.isEnabled)
                    write("80000000")
                    write("7fc00123")
                    requireNotNull(debug.session.currentStackFrame)
                }
                pollAssertionsAsync(5.seconds, 20.milliseconds) {
                    withContext(Dispatchers.EDT) {
                        assertEquals("NaN (0x7fc00123)", evaluate(debug.session, frame, "\$f0"))
                        assertEquals("0x7fc00123", hex().text)
                        assertFalse(backstepAction(debug.session).second.presentation.isEnabled)
                    }
                }
                withContext(Dispatchers.EDT) {
                    UIUtil.findComponentsOfType(view.component, JButton::class.java).single { it.text == "Reset" }.doClick()
                }
                pollAssertionsAsync(5.seconds, 20.milliseconds) {
                    withContext(Dispatchers.EDT) { assertEquals("0x00000000", hex().text) }
                }
                val occupied = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val suspension = withContext(Dispatchers.EDT) {
                    assertInstanceOf(MarsSuspendContext::class.java, debug.session.suspendContext)
                }
                assertTrue(suspension.inspectionRequests.trySend {
                    occupied.complete(Unit)
                    release.await()
                }.isSuccess)
                try {
                    occupied.await()
                    withContext(Dispatchers.EDT) {
                        repeat(17) { write("3f800000") }
                        val messages = UIUtil.findComponentsOfType(view.component, JEditorPane::class.java)
                            .map { it.document.getText(0, it.document.length).trim() }
                        assertTrue(runMessages.getMessage("debug.register.edit.unavailable") in messages, messages.toString())
                        debug.session.resume()
                    }
                } finally {
                    release.complete(Unit)
                }
                pollAssertionsAsync(5.seconds, 20.milliseconds) {
                    withContext(Dispatchers.EDT) { assertFalse(hex().isEditable) }
                }
                withContext(Dispatchers.IO) { process.processInput.write("0\n".toByteArray()) }
                assertEquals(0, debug.exit.await())
                assertEquals(0, process.floatingRegisterBits(0))
            } finally {
                withContext(Dispatchers.EDT) {
                    Disposer.dispose(view)
                    content.manager?.removeContent(content, true)
                    other.manager?.removeContent(other, true)
                    withForcedRespectIsShowingClientProperty {
                        withShowingChanged { parent.remove(view.component) }
                    }
                }
            }
        }
    }

    private suspend fun withDebug(
        file: VirtualFile, configure: MarsRunOptions.() -> Unit = {}, beforeLaunch: Boolean = false, action: suspend (DebugLaunch) -> Unit,
    ) {
        val disposable = Disposer.newDisposable()
        val started = CompletableDeferred<DebugLaunch>()
        val manager = ExecutionManager.getInstance(project) as ExecutionManagerImpl
        val previous = manager.forceCompilationInTests
        try {
            withContext(Dispatchers.EDT) {
                val config = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project)
                config.options.sourcePath = file.path
                config.options.configure()
                if (beforeLaunch) {
                    manager.forceCompilationInTests = true
                    config.beforeRunTasks = listOf(MarsBeforeLaunchTask().apply { isEnabled = true })
                }
                val environment = ExecutionEnvironmentBuilder.create(DefaultDebugExecutor.getDebugExecutorInstance(), config).build()
                val runner = assertInstanceOf(MarsDebugRunner::class.java, environment.runner)
                assertFalse(runner.canRun(DefaultRunExecutor.EXECUTOR_ID, config))
                assertFalse(runner.canRun(environment.executor.id, object : RunProfile {
                    override fun getName() = "Other"
                    override fun getIcon() = null
                    override fun getState(executor: Executor, environment: ExecutionEnvironment) = null
                }))
                project.messageBus.connect(disposable).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
                    override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                        if (env !== environment) return
                        val session = debugger.debugSessions.single { it.debugProcess.processHandler === handler }
                        session.debugProcess.startPausing()
                        session.debugProcess.startStepOver(null)
                        session.debugProcess.startStepOut(null)
                        val editors = session.debugProcess.editorsProvider
                        val expression = XDebuggerUtil.getInstance().createExpression("1", null, null, EvaluationMode.EXPRESSION)
                        assertEquals("1", editors.createDocument(project, expression, null, EvaluationMode.EXPRESSION).text)
                        val debug = DebugLaunch(session)
                        session.addSessionListener(debug, disposable)
                        handler.addProcessListener(debug)
                        started.complete(debug)
                    }
                })
                runner.execute(environment)
            }
            action(started.await())
        } finally {
            withContext(Dispatchers.EDT) {
                manager.forceCompilationInTests = previous
                debugger.debugSessions.forEach { it.stop() }
                Disposer.dispose(disposable)
            }
        }
    }

    private class DebugLaunch(val session: XDebugSession) : ProcessListener, XDebugSessionListener {
        val stdout = StringBuilder()
        val exit = CompletableDeferred<Int>()
        val output = CompletableDeferred<Unit>()
        private val positions = Channel<XSourcePosition?>(Channel.UNLIMITED)

        override fun sessionPaused() { positions.trySend(session.topFramePosition) }

        suspend fun nextPosition() = positions.receive()

        suspend fun frames(first: Int = 0): List<XStackFrame> {
            val result = CompletableDeferred<List<XStackFrame>>()
            withContext(Dispatchers.EDT) {
                val stack = requireNotNull(session.suspendContext?.activeExecutionStack)
                val collected = ArrayList<XStackFrame>()
                stack.computeStackFrames(first, object : XStackFrameContainer {
                    override fun addStackFrames(stackFrames: List<XStackFrame>, last: Boolean) {
                        collected.addAll(stackFrames)
                        if (last) result.complete(collected)
                    }

                    override fun errorOccurred(errorMessage: String) {
                        result.completeExceptionally(AssertionError(errorMessage))
                    }
                })
            }
            return result.await()
        }

        suspend fun assertPausedAt(file: VirtualFile, line: Int) {
            val position = requireNotNull(nextPosition())
            assertEquals(file, position.file)
            assertEquals(line, position.line)
        }

        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (outputType === ProcessOutputTypes.STDOUT) {
                stdout.append(event.text)
                output.complete(Unit)
            }
        }

        override fun processTerminated(event: ProcessEvent) { exit.complete(event.exitCode) }
    }
}
