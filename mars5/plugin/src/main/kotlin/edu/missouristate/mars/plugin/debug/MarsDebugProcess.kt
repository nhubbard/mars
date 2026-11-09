package edu.missouristate.mars.plugin.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
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
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.ui.XDebugTabLayouter
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.Call
import edu.missouristate.mars.api.StepEvent.ExceptionEntry
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsSyntaxHighlighter
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
private const val SOURCE_STEP_LIMIT = 256L
private const val CALL_STEP_LIMIT = 100_000L

private enum class ReverseStep(val messageKey: String) {
    INSTRUCTION("debug.backstep"),
    SOURCE("debug.backstep.source"),
}

internal class MarsDebugProcess(session: XDebugSession, state: MarsRunState) : XDebugProcess(session) {
    private data class Command(
        val steps: Long = 0,
        val target: Location? = null,
        val backstep: ReverseStep? = null,
        val sourceStep: SourceStep? = null,
        val stepOver: StepOver? = null,
    )

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
            runMessages.getMessage("debug.step.instruction"),
            runMessages.getMessage("debug.step.instruction.description"),
            AllIcons.Actions.TraceInto,
        ) {
            override fun getActionUpdateThread() = ActionUpdateThread.BGT
            override fun update(event: AnActionEvent) { event.presentation.isEnabled = session.isSuspended }
            override fun actionPerformed(event: AnActionEvent) {
                if (!session.isSuspended) return
                session.sessionResumed()
                commands.trySend(Command(steps = 1))
            }
        })
        for (step in ReverseStep.entries) {
            topToolbar.add(object : DumbAwareAction(
                runMessages.getMessage(step.messageKey),
                runMessages.getMessage("${step.messageKey}.description"),
                if (step == ReverseStep.SOURCE) AllIcons.Actions.Undo else AllIcons.Actions.Rollback,
            ) {
                override fun getActionUpdateThread() = ActionUpdateThread.BGT
                override fun update(event: AnActionEvent) { event.presentation.isEnabled = canBackstep() }
                override fun actionPerformed(event: AnActionEvent) {
                    if (!canBackstep()) return
                    session.sessionResumed()
                    commands.trySend(Command(backstep = step))
                }
            })
        }
    }

    private fun canBackstep() = (session.suspendContext as? MarsSuspendContext)?.canBackstep == true

    override fun stop() = handler.destroyProcess()
    override fun resume(context: XSuspendContext?) { commands.trySend(Command()) }
    override fun startPausing() { simulator?.pause() }
    override fun startStepInto(context: XSuspendContext?) {
        val sourceStep = (context as? MarsSuspendContext)?.sourceStep
        commands.trySend(Command(steps = if (sourceStep == null) 1 else SOURCE_STEP_LIMIT, sourceStep = sourceStep))
    }
    override fun startStepOver(context: XSuspendContext?) {
        val stopped = context as? MarsSuspendContext ?: return
        commands.trySend(Command(steps = CALL_STEP_LIMIT, stepOver = StepOver(stopped.sourceStep)))
    }
    override fun startStepOut(context: XSuspendContext?) = rejectCommand("debug.step.out.unavailable", context)
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
            if (points.isEmpty()) {
                session.setBreakpointInvalid(breakpoint, runMessages.getMessage("debug.breakpoint.no.code"))
            } else {
                points.forEach { next.getOrPut(it) { mutableListOf() }.add(breakpoint) }
                session.setBreakpointVerified(breakpoint)
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
                val backstep = command.backstep
                if (backstep != null) {
                    process.backstepSlice(engine, wholeSource = backstep == ReverseStep.SOURCE)
                    withSuspension(machine) { context ->
                        withContext(Dispatchers.EDT) { session.positionReached(context) }
                        command = awaitCommand(context, machine.memory)
                    }
                    continue
                }
                val target = command.target
                val targets = target?.let { map.breakpoints()[it] }.orEmpty()
                var skip = skipFirst
                var hits = emptyList<LineBreakpoint>()
                var stepOrTargetReached = false
                if (target != null && targets.isEmpty()) {
                    withSuspension(machine) { context ->
                        withContext(Dispatchers.EDT) {
                            session.reportError(runMessages.getMessage("debug.breakpoint.no.code"))
                            session.positionReached(context)
                        }
                        command = awaitCommand(context, machine.memory)
                    }
                    continue
                }
                val stepOver = command.stepOver
                stepOver?.let(engine::addListener)
                val event = try {
                    process.runSlice(engine, command.steps) { pc ->
                        if (skip) {
                            skip = false
                            false
                        } else {
                            hits = addresses[pc].orEmpty()
                            val stepReached = stepOver?.reached(machine.memory, pc)
                                ?: command.sourceStep?.reached(machine.memory, pc)
                            stepOrTargetReached = pc in targets || stepReached == true
                            hits.isNotEmpty() || stepOrTargetReached
                        }
                    }
                } finally {
                    stepOver?.let(engine::removeListener)
                }
                val reason: StopEvent.Reason = event.reason()
                when (reason) {
                    StopEvent.Reason.BREAKPOINT, StopEvent.Reason.PAUSE, StopEvent.Reason.STEP_LIMIT -> {
                        withSuspension(machine) { context ->
                            val breakpointSuspended = reason == StopEvent.Reason.BREAKPOINT &&
                                hits.any { handleBreakpoint(it, context, machine.memory) }
                            val positionReached = reason != StopEvent.Reason.BREAKPOINT || stepOrTargetReached
                            if (!breakpointSuspended && positionReached) {
                                withContext(Dispatchers.EDT) { session.positionReached(context) }
                            }
                            if (breakpointSuspended || positionReached) command = awaitCommand(context, machine.memory)
                        }
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

    private suspend fun handleBreakpoint(breakpoint: LineBreakpoint, context: MarsSuspendContext, memory: MemoryState): Boolean {
        val evaluator = context.frame.evaluator
        breakpoint.conditionExpression?.let { expression ->
            val condition = evaluator.evaluateAtBreakpoint(expression.expression, memory).getOrElse { error ->
                withContext(Dispatchers.EDT) {
                    session.reportError(runMessages.getMessage("debug.breakpoint.condition.error", error.message))
                }
                return false
            }
            if (condition.number == 0.0) return false
        }

        val logged = breakpoint.logExpressionObject?.let { evaluator.evaluateAtBreakpoint(it.expression, memory) }
        return withContext(Dispatchers.EDT) {
            logged?.exceptionOrNull()?.let { session.reportError(runMessages.getMessage("debug.breakpoint.log.error", it.message)) }
            session.breakpointReached(breakpoint, logged?.getOrNull()?.display(), context)
        }
    }

    private suspend fun awaitCommand(context: MarsSuspendContext, memory: MemoryState): Command {
        while (true) {
            val command = select<Command?> {
                commands.onReceive { it }
                context.memoryRequests.onReceive { request ->
                    val rows = memoryPage(memory, request.address)
                    withContext(Dispatchers.EDT) { request.show(rows) }
                    null
                }
                context.watchRequests.onReceive { request ->
                    request.inspect(memory)
                    null
                }
            }
            if (command != null) return command
        }
    }

    private suspend fun withSuspension(machine: MarsContext, action: suspend (MarsSuspendContext) -> Unit) {
        val context = suspendContext(machine)
        try {
            action(context)
        } finally {
            context.memoryRequests.cancel()
            context.watchRequests.cancel()
        }
    }

    private fun suspendContext(machine: MarsContext): MarsSuspendContext = machine.claimStoppedSession().use {
        val pc = machine.registerFile.programCounterRegister.valueNoNotify
        val statement = machine.memory.sourceStatement(pc)
        val origin = statement?.instructionOrigin
        val program = machine.program
        val symbols = program.symbolMap()
        val scope = statement?.sourceMIPSprogram ?: program
        val localSymbols = symbols.locals().getValue(Path.of(scope.filename).toAbsolutePath().normalize())
        val watchRequests = watchMemoryRequests()
        MarsSuspendContext(
            MarsStackFrame(
                sourcePosition(origin), machine, session.project, localSymbols, symbols.globals(), watchRequests,
            ),
            machine.backStepper.canBackstep(),
            MemoryRegion.entries.associateWith { it.address(machine).toUInt().toLong() },
            ++suspensionNumber,
            watchRequests,
            origin?.let { SourceStep(pc, it) },
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

internal object MarsDebugEditors : XDebuggerEditorsProvider() {
    override fun getFileType() = MipsFileType
    override fun createDocument(
        project: Project, expression: XExpression, sourcePosition: XSourcePosition?, mode: EvaluationMode, purpose: String?,
    ) = EditorFactory.getInstance().createDocument(expression.expression)

    override fun afterEditorCreated(editor: Editor?) {
        if (editor !is EditorEx) return
        editor.highlighter = EditorHighlighterFactory.getInstance().createEditorHighlighter(
            MipsSyntaxHighlighter(debuggerExpression = true), editor.colorsScheme,
        )
    }
}

internal class MarsSuspendContext(
    val frame: MarsStackFrame,
    val canBackstep: Boolean,
    val memoryRegions: Map<MemoryRegion, Long>,
    val number: Long,
    val watchRequests: Channel<WatchMemoryRequest>,
    val sourceStep: SourceStep?,
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

internal data class SourceStep(private val start: Int, private val origin: Origin) {
    // A loop back to the same address is a new execution of the source statement.
    fun reached(memory: MemoryState, pc: Int): Boolean = pc == start || memory.sourceStatement(pc)?.instructionOrigin !== origin
}

// The simulator worker owns this state for one Step Over command, including across logpoints.
private class StepOver(private val source: SourceStep?) : SimulatorListener {
    private val returnAddresses = ArrayDeque<Int>()
    private var enteredHandler = false

    override fun onStep(event: StepEvent) {
        when (val transfer = event.transfer() ?: return) {
            is Call -> returnAddresses.addLast(transfer.returnAddress())
            is ExceptionEntry -> enteredHandler = true
        }
    }

    override fun onStop(event: StopEvent) = Unit
    override fun onError(event: ErrorEvent) = Unit

    fun reached(memory: MemoryState, pc: Int): Boolean {
        if (enteredHandler) return true
        if (returnAddresses.isEmpty()) return source?.reached(memory, pc) != false
        if (returnAddresses.last() != pc) return false
        returnAddresses.removeLast()
        return returnAddresses.isEmpty()
    }
}

private fun MemoryState.sourceStatement(pc: Int): ProgramStatement? {
    // Data-segment execution and rewritten instructions have no assembled source position.
    if (!MemoryState.wordAligned(pc) || !(inTextSegment(pc) || inKernelTextSegment(pc))) return null
    return getStatementNoNotify(pc)
}
