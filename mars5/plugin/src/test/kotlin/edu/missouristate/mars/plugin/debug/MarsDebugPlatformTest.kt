package edu.missouristate.mars.plugin.debug

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
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
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.DefaultLanguageHighlighterColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
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
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.UIUtil
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
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueModifier
import com.intellij.xdebugger.frame.XValueModifier.XModificationCallback
import com.intellij.xdebugger.impl.actions.XDebuggerActions
import com.intellij.xdebugger.impl.ui.tree.XDebuggerTree
import com.intellij.xdebugger.impl.ui.tree.nodes.XStackFrameNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueContainerNode
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueGroupNodeImpl
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.mips.dump.DumpFormat
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.dump.MemoryDumpSnapshot
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.build.MarsMemoryExportDialog
import edu.missouristate.mars.plugin.build.MarsMemoryExportService
import edu.missouristate.mars.plugin.build.MemoryExportCapture
import edu.missouristate.mars.plugin.build.MemoryExportOptions
import edu.missouristate.mars.plugin.build.MemoryExportRegion
import edu.missouristate.mars.plugin.build.interceptMemoryExportDialog
import edu.missouristate.mars.plugin.build.replaceMemoryExportSaveDialog
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsTypes
import edu.missouristate.mars.plugin.run.MarsRunType
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunOptions
import edu.missouristate.mars.plugin.run.RunMemoryDumpState
import java.awt.event.ActionEvent
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JTextField
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

@TestApplication
class MarsDebugPlatformTest {
    private val projectFixture = codeInsightProjectFixture()
    private val fixture by codeInsightFixture(projectFixture)
    private val project get() = projectFixture.get()
    private val debugger get() = XDebuggerManager.getInstance(project)
    private val breakpointType get() = XDebuggerUtil.getInstance().findBreakpointType(MarsLineBreakpointType::class.java)

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
            val counter = assertInstanceOf(MarsProcessHandler::class.java, debug.session.debugProcess.processHandler).instructionCounter
            assertEquals(2L, counter.snapshot().total)
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
            """.trimIndent()).virtualFile
            assertTrue(breakpointType.canPutAt(file, 3, project))
            assertFalse(breakpointType.canPutAt(file, 4, project))
            assertTrue(breakpointType.canPutAt(file, 5, project))
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
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            assertEquals(if (invalidAddress) 1 else 0, debug.exit.await())
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
                debug.session.sessionResumed()
                debug.session.debugProcess.startStepInto(null)
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
        withDebug(file, { delayedBranching = true }) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) { debug.session.stepInto() }
            debug.assertPausedAt(file, 3)
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
        withDebug(file, { delayedBranching = true }) { debug ->
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(file, if (breakInsideCall) 8 else 3)
            withContext(Dispatchers.EDT) {
                val result = if (breakInsideCall) "0x00000001" else "0x00000003"
                assertEquals(result, evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t2"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
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
    fun `step over groups a pseudo instruction without a call`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("step-over-pseudo.s", "li \$t0, 0x12345678\nnop\n")
                .virtualFile.also { addBreakpoint(it, 0) }
        }
        withDebug(file) { debug ->
            debug.assertPausedAt(file, 0)
            withContext(Dispatchers.EDT) { debug.session.stepOver(false) }
            debug.assertPausedAt(file, 1)
            withContext(Dispatchers.EDT) {
                assertEquals("0x12345678", evaluate(debug.session, requireNotNull(debug.session.currentStackFrame), "\$t0"))
                debug.session.resume()
            }
            assertEquals(0, debug.exit.await())
        }
    }

    @Test
    fun `debugging configured self modifying code steps the rewritten instruction without stale source`() = timeoutRunBlocking<Unit> {
        val file = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("debug-patch.s", """
                la ${'$'}t0, patch
                li ${'$'}t1, 0x240a002a
                sw ${'$'}t1, 0(${'$'}t0)
                patch: nop
                after_patch: nop
            """.trimIndent()).virtualFile.also { addBreakpoint(it, 2) }
        }
        withDebug(file, { selfModifyingCode = true }) { debug ->
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
                initial: addiu ${'$'}t0, ${'$'}zero, -2
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
                assertEquals("0x00000000", evaluate(debug.session, first, "\$t0"))
                val expressions = mapOf(
                    "\$t0" to "0xfffffffe",
                    "\$8" to "0xfffffffe",
                    "pc" to "0x00400020",
                    "hi" to "0xffffffff",
                    "lo" to "0xfffffffa",
                    "cp0.\$14" to "0x00001234",
                    "FCC" to "0x00000001",
                    "\$f1" to "1.9375 (0x3ff80000)",
                    "initial" to "0x00400000",
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
                    "1 |", "1 ^", "1 &", "1 >>", "~", "1 && 1", "1 <<= 2", "1 & 2.5", "2.5 | 1", "~2.5")) {
                    assertEquals("Enter a valid MIPS debugger expression using registers, labels, numbers, memory reads, arithmetic, comparisons, or bitwise operators.",
                        evaluate(debug.session, second, expression), expression)
                }
                assertEquals("0xfffffffe", evaluate(debug.session, second, "\$t0"))
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
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 6)
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
                )
                for ((expression, value) in expected) assertEquals(value, evaluate(debug.session, frame, expression), expression)
                for (expression in listOf("word(buffer+1)", "half(buffer+1)", "word(1.5)", "word(0)",
                    "-word(0)", "word(0)+1", "1+word(0)", "word(word(0))", "word(0) != 1", "1 == word(0)", "~word(0)")) {
                    assertEquals("The memory address is invalid, unaligned, or unavailable.",
                        evaluate(debug.session, frame, expression), expression)
                }
                for (expression in listOf("word(unknown)", "byte(buffer) & 0.5", "~(byte(buffer) + 0.5)",
                    "word(buffer + (byte(buffer) & 0.5))")) {
                    assertEquals("Enter a valid MIPS debugger expression using registers, labels, numbers, memory reads, arithmetic, comparisons, or bitwise operators.",
                        evaluate(debug.session, frame, expression), expression)
                }
                debug.session.stepInto()
                frame
            }
            debug.assertPausedAt(source, 7)
            withContext(Dispatchers.EDT) {
                val after = requireNotNull(debug.session.currentStackFrame)
                assertEquals("0x55667788", evaluate(debug.session, after, "word(buffer)"))
                assertEquals("Memory is unavailable for this debugger frame.", evaluate(debug.session, before, "word(buffer)"))
                assertEquals("0x10010000", evaluate(debug.session, before, "buffer"))
                val (action, event) = backstepAction(debug.session)
                action.actionPerformed(event)
            }
            debug.assertPausedAt(source, 6)
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
            val requests = registerEditRequests()
            val modifier = RegisterModifier(RegisterBank.CPU, 8, "0x00000000", requests) { true }
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
    fun `native toolbar backstep restores the preceding instruction and refreshes Variables`() = timeoutRunBlocking<Unit> {
        val source = withContext(Dispatchers.EDT) {
            fixture.addFileToProject("undo.s", "li \$a0, 7\nli \$v0, 1\nsyscall\nnop\n").virtualFile.also {
                addBreakpoint(it, 0)
            }
        }
        withDebug(source) { debug ->
            debug.assertPausedAt(source, 0)
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
            for (line in 2 downTo 0) {
                withContext(Dispatchers.EDT) {
                    action.update(event)
                    assertTrue(event.presentation.isEnabled)
                    action.actionPerformed(event)
                }
                debug.assertPausedAt(source, line)
                assertEquals("7", debug.stdout.toString())
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
        withDebug(source, { selfModifyingCode = true }) { debug ->
            debug.assertPausedAt(source, 5)
            val disposable = Disposer.newDisposable()
            try {
                val discarded = withContext(Dispatchers.EDT) {
                    MarsDisassemblyView(debug.session, source.path).also(Disposer::dispose)
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
                    assertFalse(open.isEnabled)
                    debug.session.stepInto()
                }
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
        withDebug(source, { selfModifyingCode = true }) { debug ->
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

    private suspend fun withDebug(file: VirtualFile, configure: MarsRunOptions.() -> Unit = {}, action: suspend (DebugLaunch) -> Unit) {
        val disposable = Disposer.newDisposable()
        val started = CompletableDeferred<DebugLaunch>()
        try {
            withContext(Dispatchers.EDT) {
                val config = ConfigurationTypeUtil.findConfigurationType(MarsRunType::class.java).createTemplateConfiguration(project)
                config.options.sourcePath = file.path
                config.options.configure()
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
                        val session = debugger.debugSessions.single()
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
