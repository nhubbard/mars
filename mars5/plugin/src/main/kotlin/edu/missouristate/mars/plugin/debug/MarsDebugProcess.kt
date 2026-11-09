package edu.missouristate.mars.plugin.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.ui.XDebugTabLayouter
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.simulator.SimulatorEngine
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

private typealias LineBreakpoint = XLineBreakpoint<XBreakpointProperties<*>>

internal class MarsDebugProcess(session: XDebugSession, state: MarsRunState) : XDebugProcess(session) {
    private data class Command(val steps: Long = 0, val target: Location? = null, val backstep: Boolean = false)

    private val commands = Channel<Command>(Channel.CONFLATED)
    @Volatile private var simulator: SimulatorEngine? = null
    private val bindings = Any()
    private val registered = linkedSetOf<LineBreakpoint>()
    private var sourceMap: InstructionSourceMap? = null
    @Volatile private var addresses = emptyMap<Int, List<LineBreakpoint>>()
    private var suspensionNumber = 0L
    private val handler = state.debugProcess { debug(this, it) }

    private val lineHandler = object : XBreakpointHandler<LineBreakpoint>(MarsLineBreakpointType::class.java) {
        override fun registerBreakpoint(breakpoint: LineBreakpoint) {
            synchronized(bindings) {
                registered.add(breakpoint)
                updateBindings()
            }
        }

        override fun unregisterBreakpoint(breakpoint: LineBreakpoint, temporary: Boolean) {
            synchronized(bindings) {
                registered.remove(breakpoint)
                updateBindings()
            }
        }
    }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(lineHandler)
    override fun doGetProcessHandler() = handler
    override fun createConsole() = TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console.also {
        it.attachToProcess(handler)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = MarsDebugEditors

    override fun createTabLayouter() = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            val view = MarsMemoryView(session)
            val content = ui.createContent("MarsMipsMemory", view.component, runMessages.getMessage("debug.memory"), null, view.addressField)
            content.isCloseable = false
            content.setDisposer(view)
            ui.addContent(content, 0, PlaceInGrid.right, true)
        }
    }

    override fun registerAdditionalActions(leftToolbar: DefaultActionGroup, topToolbar: DefaultActionGroup, settings: DefaultActionGroup) {
        topToolbar.add(object : DumbAwareAction(
            runMessages.getMessage("debug.backstep"),
            runMessages.getMessage("debug.backstep.description"),
            AllIcons.Actions.Rollback,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(event: AnActionEvent) { event.presentation.isEnabled = canBackstep() }
            override fun actionPerformed(event: AnActionEvent) {
                if (!canBackstep()) return
                session.sessionResumed()
                commands.trySend(Command(backstep = true))
            }
        })
    }

    private fun canBackstep() = (session.suspendContext as? MarsSuspendContext)?.canBackstep == true

    override fun stop() = handler.destroyProcess()
    override fun resume(context: XSuspendContext?) { commands.trySend(Command()) }
    override fun startPausing() { simulator?.pause() }
    override fun startStepInto(context: XSuspendContext?) { commands.trySend(Command(steps = 1)) }
    override fun startStepOver(context: XSuspendContext?) = rejectCommand("debug.call.step.unavailable", context)
    override fun startStepOut(context: XSuspendContext?) = rejectCommand("debug.call.step.unavailable", context)
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val target = position.file.location(position.line)
        if (target == null) rejectCommand("debug.breakpoint.no.code", context)
        else commands.trySend(Command(target = target))
    }

    private fun rejectCommand(message: String, context: XSuspendContext?) {
        session.reportError(runMessages.getMessage(message))
        context?.let(session::positionReached)
    }

    private fun updateBindings() {
        val map = sourceMap ?: return
        val next = mutableMapOf<Int, MutableList<LineBreakpoint>>()
        for (breakpoint in registered) {
            val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl)
            val points = file?.location(breakpoint.line)?.let { map.breakpoints()[it] }.orEmpty()
            when {
                points.isEmpty() -> session.setBreakpointInvalid(breakpoint, runMessages.getMessage("debug.breakpoint.no.code"))
                breakpoint.conditionExpression != null || breakpoint.logExpressionObject != null ->
                    session.setBreakpointInvalid(breakpoint, runMessages.getMessage("debug.breakpoint.expression.unavailable"))
                else -> {
                    points.forEach { next.getOrPut(it) { mutableListOf() }.add(breakpoint) }
                    session.setBreakpointVerified(breakpoint)
                }
            }
        }
        addresses = next
    }

    private suspend fun debug(process: MarsProcessHandler, machine: MarsContext): StopEvent {
        val engine = machine.simulator()
        simulator = engine
        val map = machine.program.sourceMap()
        try {
            synchronized(bindings) {
                sourceMap = map
                updateBindings()
            }
            var command = Command()
            var skipFirst = false
            while (true) {
                if (command.backstep) {
                    process.backstepSlice(engine)
                    val context = suspendContext(machine)
                    withContext(Dispatchers.EDT) { session.positionReached(context) }
                    command = awaitCommand(context, machine.memory)
                    continue
                }
                val target = command.target
                val targets = target?.let { map.breakpoints()[it] }.orEmpty()
                var skip = skipFirst
                var hits = emptyList<LineBreakpoint>()
                var targetHit = false
                if (target != null && targets.isEmpty()) {
                    val context = suspendContext(machine)
                    withContext(Dispatchers.EDT) {
                        session.reportError(runMessages.getMessage("debug.breakpoint.no.code"))
                        session.positionReached(context)
                    }
                    command = awaitCommand(context, machine.memory)
                    continue
                }
                val event = process.runSlice(engine, command.steps) { pc ->
                    if (skip) {
                        skip = false
                        false
                    } else {
                        hits = addresses[pc].orEmpty()
                        targetHit = pc in targets
                        hits.isNotEmpty() || targetHit
                    }
                }
                val reason: StopEvent.Reason = event.reason()
                when (reason) {
                    StopEvent.Reason.BREAKPOINT, StopEvent.Reason.PAUSE, StopEvent.Reason.STEP_LIMIT -> {
                        val context = suspendContext(machine)
                        val suspended = withContext(Dispatchers.EDT) {
                            when (reason) {
                                StopEvent.Reason.BREAKPOINT -> {
                                    if (hits.any { session.breakpointReached(it, null, context) }) true
                                    else if (targetHit) {
                                        session.positionReached(context)
                                        true
                                    } else false
                                }
                                else -> {
                                    session.positionReached(context)
                                    true
                                }
                            }
                        }
                        if (suspended) command = awaitCommand(context, machine.memory)
                        skipFirst = true
                    }
                    else -> return event
                }
            }
        } finally {
            simulator = null
            commands.cancel()
        }
    }

    private suspend fun awaitCommand(context: MarsSuspendContext, memory: MemoryState): Command {
        try {
            while (true) {
                val command = select<Command?> {
                    commands.onReceive { it }
                    context.memoryRequests.onReceive { request ->
                        val rows = memoryPage(memory, request.address)
                        withContext(Dispatchers.EDT) { request.show(rows) }
                        null
                    }
                }
                if (command != null) return command
            }
        } finally {
            context.memoryRequests.cancel()
        }
    }

    private fun suspendContext(machine: MarsContext): MarsSuspendContext = machine.claimStoppedSession().use {
        val pc = machine.registerFile.programCounterRegister.valueNoNotify
        val statement = try {
            machine.memory.getStatementNoNotify(pc)
        } catch (_: AddressErrorException) {
            null
        }
        val program = machine.program
        val symbols = program.symbolMap()
        val scope = statement?.sourceMIPSprogram ?: program
        val localSymbols = symbols.locals().getValue(Path.of(scope.filename).toAbsolutePath().normalize())
        MarsSuspendContext(
            MarsStackFrame(sourcePosition(statement?.instructionOrigin), machine, session.project, localSymbols, symbols.globals()),
            machine.backStepper.canBackstep(),
            MemoryRegion.entries.associateWith { it.address(machine).toUInt().toLong() },
            ++suspensionNumber,
        )
    }

    private fun sourcePosition(origin: Origin?): XSourcePosition? {
        if (origin == null) return null
        val location = origin.location()
        val file = LocalFileSystem.getInstance().findFileByNioFile(location.file())
        return XDebuggerUtil.getInstance().createPosition(file, location.line() - 1)
    }

    private fun VirtualFile.location(line: Int): Location? {
        if (line !in 0 until Int.MAX_VALUE) return null
        return fileSystem.getNioPath(this)?.let { Location(it, line + 1) }
    }
}

private object MarsDebugEditors : XDebuggerEditorsProvider() {
    override fun getFileType() = MipsFileType
    override fun createDocument(project: Project, expression: XExpression, sourcePosition: XSourcePosition?, mode: EvaluationMode) =
        EditorFactory.getInstance().createDocument(expression.expression)
}

internal class MarsSuspendContext(
    frame: XStackFrame,
    val canBackstep: Boolean,
    val memoryRegions: Map<MemoryRegion, Long>,
    val number: Long,
) : XSuspendContext() {
    val memoryRequests = Channel<MemoryRequest>(16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val stack = object : XExecutionStack(runMessages.getMessage("debug.cpu")) {
        override fun getTopFrame() = frame
        override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
            container.addStackFrames(if (firstFrameIndex == 0) listOf(frame) else emptyList(), true)
        }
    }
    override fun getActiveExecutionStack(): XExecutionStack = stack
}
