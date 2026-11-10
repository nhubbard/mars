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
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
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
import edu.missouristate.mars.api.StepEvent.ExceptionReturn
import edu.missouristate.mars.api.StepEvent.Jump
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.debug.DebuggerCallStack.CallerSite
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.language.MipsSyntaxHighlighter
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsExecutionSources
import edu.missouristate.mars.plugin.run.MarsRunSpeedAction
import edu.missouristate.mars.plugin.run.MarsRunState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.simulator.SimulatorEngine
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

private typealias LineBreakpoint = XLineBreakpoint<XBreakpointProperties<*>>
private const val SOURCE_STEP_LIMIT = 256L
private const val CALL_STEP_LIMIT = 100_000L

private enum class ReverseStep(val messageKey: String) {
    INSTRUCTION("debug.backstep"),
    SOURCE("debug.backstep.source"),
}

internal class MarsDebugProcess(session: XDebugSession, private val state: MarsRunState) : XDebugProcess(session) {
    private data class Command(
        val steps: Long = 0,
        val target: Location? = null,
        val backstep: ReverseStep? = null,
        val sourceStep: SourceStep? = null,
        val stepOver: StepOver? = null,
        val stepOut: DebuggerCallStack.StepOutTarget? = null,
    )

    private val commands = Channel<Command>(Channel.CONFLATED)
    @Volatile private var simulator: SimulatorEngine? = null
    private val bindings = Any()
    private val entryPath = state.entry.normalize()
    private val entry = entryPath.toString()
    private val registeredLines = linkedSetOf<LineBreakpoint>()
    private val registeredAddresses = linkedSetOf<AddressBreakpoint>()
    private var sourceMap: InstructionSourceMap? = null
    @Volatile private var addresses = emptyMap<Int, List<XBreakpoint<*>>>()
    private var suspensionNumber = 0L
    @Volatile private lateinit var sources: MarsExecutionSources
    private val handler = state.createProcess { machine, executionSources ->
        sources = executionSources
        debug(this, machine)
    }

    private val lineHandler = bindingHandler(MarsLineBreakpointType::class.java, registeredLines)
    private val addressHandler = bindingHandler(MarsAddressBreakpointType::class.java, registeredAddresses)

    private fun <B : XBreakpoint<*>> bindingHandler(type: Class<out XBreakpointType<B, *>>, registered: MutableSet<B>) =
        object : XBreakpointHandler<B>(type) {
            override fun registerBreakpoint(breakpoint: B) {
                synchronized(bindings) {
                    registered.add(breakpoint)
                    updateBindings()
                }
            }

            override fun unregisterBreakpoint(breakpoint: B, temporary: Boolean) {
                synchronized(bindings) {
                    registered.remove(breakpoint)
                    updateBindings()
                }
            }
        }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(lineHandler, addressHandler)
    override fun doGetProcessHandler() = handler
    override fun createConsole() = TextConsoleBuilderFactory.getInstance().createBuilder(session.project).console.also {
        it.attachToProcess(handler)
        handler.bindConsole(it)
    }

    override fun getEditorsProvider(): XDebuggerEditorsProvider = MarsDebugEditors

    override fun createTabLayouter() = object : XDebugTabLayouter() {
        override fun registerAdditionalContent(ui: RunnerLayoutUi) {
            val view = MarsMemoryView(session, entryPath)
            val content = ui.createContent("MarsMipsMemory", view.component, runMessages.getMessage("debug.memory"), null, view.addressField)
            content.isCloseable = false
            content.setDisposer(view)
            ui.addContent(content, 0, PlaceInGrid.right, true)
            val disassembly = MarsDisassemblyView(session, entry, ::sourcePosition)
            val listing = ui.createContent("MarsMipsDisassembly", disassembly.component, runMessages.getMessage("debug.disassembly"), null, disassembly.focus)
            listing.isCloseable = false
            listing.setDisposer(disassembly)
            ui.addContent(listing, 0, PlaceInGrid.right, true)
        }
    }

    override fun registerAdditionalActions(leftToolbar: DefaultActionGroup, topToolbar: DefaultActionGroup, settings: DefaultActionGroup) {
        topToolbar.add(MarsRunSpeedAction(handler))
        topToolbar.add(state.resetAction(handler))
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
    override fun startStepOut(context: XSuspendContext?) {
        val target = (context as? MarsSuspendContext)?.stepOutTarget
        if (target == null) rejectCommand("debug.step.out.unavailable", context)
        else commands.trySend(Command(steps = CALL_STEP_LIMIT, stepOut = target))
    }
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val target = sources.location(position.file, position.line)
        if (target == null) rejectCommand("debug.source.not.in.image", context)
        else commands.trySend(Command(target = target))
    }

    private fun rejectCommand(message: String, context: XSuspendContext?) {
        session.reportError(runMessages.getMessage(message))
        context?.let(session::positionReached)
    }

    private fun updateBindings() {
        val map = sourceMap ?: return
        val next = mutableMapOf<Int, MutableList<XBreakpoint<*>>>()
        for (breakpoint in registeredLines) {
            val file = VirtualFileManager.getInstance().findFileByUrl(breakpoint.fileUrl)
            val location = file?.let { sources.location(it, breakpoint.line) }
            val points = location?.let { map.breakpoints()[it] }.orEmpty()
            if (points.isEmpty()) {
                session.setBreakpointInvalid(breakpoint, runMessages.getMessage("debug.breakpoint.no.code"))
            } else {
                points.forEach { next.getOrPut(it) { mutableListOf() }.add(breakpoint) }
                session.setBreakpointVerified(breakpoint)
            }
        }
        for (breakpoint in registeredAddresses) {
            val address = breakpoint.properties?.addressIn(entry) ?: continue
            next.getOrPut(address) { mutableListOf() }.add(breakpoint)
        }
        addresses = next
    }

    private suspend fun debug(process: MarsProcessHandler, machine: MarsContext): StopEvent {
        val engine = machine.simulator()
        val calls = DebuggerCallStack(machine.backStepper.capacity()) { machine.memory.sourceStatement(it)?.instructionOrigin }
        engine.addListener(calls)
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
                    withSuspension(machine, calls) { context ->
                        withContext(Dispatchers.EDT) { session.positionReached(context) }
                        command = awaitCommand(context, machine)
                    }
                    continue
                }
                val target = command.target
                val targets = target?.let { map.breakpoints()[it] }.orEmpty()
                var skip = skipFirst
                var hits = emptyList<XBreakpoint<*>>()
                var stepOrTargetReached = false
                if (target != null && targets.isEmpty()) {
                    withSuspension(machine, calls) { context ->
                        withContext(Dispatchers.EDT) {
                            session.reportError(runMessages.getMessage("debug.breakpoint.no.code"))
                            session.positionReached(context)
                        }
                        command = awaitCommand(context, machine)
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
                            val stepOut = command.stepOut
                            val stepReached = when {
                                stepOver != null -> stepOver.reached(machine.memory, pc)
                                stepOut != null -> calls.reached(stepOut)
                                else -> command.sourceStep?.reached(machine.memory, pc)
                            }
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
                        withSuspension(machine, calls) { context ->
                            val breakpointSuspended = reason == StopEvent.Reason.BREAKPOINT &&
                                hits.sortedBy { it.suspendPolicy != SuspendPolicy.NONE }
                                    .any { handleBreakpoint(it, context, machine.memory) }
                            val positionReached = reason != StopEvent.Reason.BREAKPOINT || stepOrTargetReached
                            if (!breakpointSuspended && positionReached) {
                                withContext(Dispatchers.EDT) { session.positionReached(context) }
                            }
                            if (breakpointSuspended || positionReached) command = awaitCommand(context, machine)
                        }
                        skipFirst = true
                    }
                    else -> return event
                }
            }
        } finally {
            engine.removeListener(calls)
            simulator = null
            commands.cancel()
        }
    }

    private suspend fun handleBreakpoint(breakpoint: XBreakpoint<*>, context: MarsSuspendContext, memory: MemoryState): Boolean {
        val evaluator = context.frame.evaluator
        breakpoint.conditionExpression?.let { expression ->
            val condition = evaluator.evaluateStopped(expression.expression, memory).getOrElse { error ->
                withContext(Dispatchers.EDT) {
                    session.reportError(runMessages.getMessage("debug.breakpoint.condition.error", error.message))
                }
                return false
            }
            if (condition.number == 0.0) return false
        }

        val logged = breakpoint.logExpressionObject?.let { evaluator.evaluateStopped(it.expression, memory) }
        return withContext(Dispatchers.EDT) {
            logged?.exceptionOrNull()?.let { session.reportError(runMessages.getMessage("debug.breakpoint.log.error", it.message)) }
            session.breakpointReached(breakpoint, logged?.getOrNull()?.display(), context)
        }
    }

    private suspend fun awaitCommand(context: MarsSuspendContext, machine: MarsContext): Command {
        while (true) {
            val command = select<Command?> {
                commands.onReceive { it }
                context.inspectionRequests.onReceive { inspect ->
                    inspect(machine.memory)
                    null
                }
                context.watchRequests.onReceive { request ->
                    request.inspect(machine.memory)
                    null
                }
                context.memoryExports.onReceive { request ->
                    request.capture(machine)
                    null
                }
                context.registerEdits.onReceive { request ->
                    // A selected edit completes before the worker can start another command.
                    withContext(NonCancellable) { editRegister(machine, context, request) }
                    null
                }
            }
            if (command != null) return command
        }
    }

    private suspend fun editRegister(machine: MarsContext, context: MarsSuspendContext, request: RegisterEditRequest) {
        val changed = request.apply(machine, context.frame.evaluator).getOrElse { error ->
            withContext(Dispatchers.EDT) { request.callback.errorOccurred(error.message!!) }
            return
        }
        if (changed) {
            machine.claimStoppedSession().use { context.refresh(machine) }
        }
        withContext(Dispatchers.EDT) { request.callback.valueModified() }
    }

    private suspend fun withSuspension(
        machine: MarsContext, calls: DebuggerCallStack, action: suspend (MarsSuspendContext) -> Unit,
    ) = coroutineScope {
        val context = suspendContext(machine, calls.stepOutTarget(), calls.callers(), this)
        try {
            action(context)
        } finally {
            context.inspectionRequests.cancel()
            context.watchRequests.cancel()
            context.registerEdits.cancel()
            context.memoryExports.cancel()
            coroutineContext.cancelChildren()
        }
    }

    private fun suspendContext(
        machine: MarsContext, stepOutTarget: DebuggerCallStack.StepOutTarget?,
        callers: Sequence<CallerSite>, frameScope: CoroutineScope,
    ): MarsSuspendContext = machine.claimStoppedSession().use {
        val pc = machine.registerFile.programCounterRegister.valueNoNotify
        val statement = machine.memory.sourceStatement(pc)
        val origin = statement?.instructionOrigin
        val program = machine.program
        val symbols = program.symbolMap()
        val assemblyEntry = statement?.sourceFile?.ifEmpty { program.filename } ?: program.filename
        val localSymbols = symbols.locals().getValue(Path.of(assemblyEntry).toAbsolutePath().normalize())
        val watchRequests = watchMemoryRequests()
        val registerEdits = registerEditRequests()
        val number = ++suspensionNumber
        val frame = MarsStackFrame(
            sourcePosition(origin), machine, session.project, localSymbols, symbols.globals(), watchRequests, registerEdits,
        ) {
            val current = session.suspendContext
            current is MarsSuspendContext && current.registerEdits === registerEdits
        }
        MarsSuspendContext(
            frame,
            machine.backStepper.canBackstep(),
            MemoryRegion.entries.associateWith { it.address(machine).toUInt().toLong() },
            number,
            watchRequests,
            registerEdits,
            origin?.let { SourceStep(pc, it) },
            stepOutTarget,
            MarsExecutionStack(frame, callers, ::sourcePosition, frameScope),
        )
    }

    private fun sourcePosition(origin: Origin?): XSourcePosition? =
        origin?.let { sourcePosition(it.location()) }

    private fun sourcePosition(location: Location): XSourcePosition = sources.position(location)

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
    canBackstep: Boolean,
    memoryRegions: Map<MemoryRegion, Long>,
    val number: Long,
    val watchRequests: Channel<WatchMemoryRequest>,
    val registerEdits: Channel<RegisterEditRequest>,
    val sourceStep: SourceStep?,
    val stepOutTarget: DebuggerCallStack.StepOutTarget?,
    private val stack: MarsExecutionStack,
) : XSuspendContext() {
    @Volatile var canBackstep = canBackstep
        private set
    @Volatile var memoryRegions = memoryRegions
        private set

    fun refresh(machine: MarsContext) {
        frame.refresh(machine)
        canBackstep = machine.backStepper.canBackstep()
        memoryRegions = MemoryRegion.entries.associateWith { it.address(machine).toUInt().toLong() }
    }

    val memoryExports = Channel<MemoryExportRequest>(16, onUndeliveredElement = MemoryExportRequest::unavailable)
    val inspectionRequests = Channel<suspend (MemoryState) -> Unit>(16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override fun getActiveExecutionStack() = stack
}

internal data class SourceStep(private val start: Int, private val origin: Origin) {
    // A loop back to the same address is a new execution of the source statement.
    fun reached(memory: MemoryState, pc: Int): Boolean = pc == start || memory.sourceStatement(pc)?.instructionOrigin !== origin
}

// The simulator worker owns this state for one Step Over command, including across logpoints.
private class StepOver(private val source: SourceStep?) : SimulatorListener {
    private val returnAddresses = ArrayDeque<Int>()
    private var enteredHandler = false
    private var returned = false

    override fun onStep(event: StepEvent) {
        when (val transfer = event.transfer() ?: return) {
            is Call -> returnAddresses.addLast(transfer.returnAddress())
            is ExceptionEntry -> enteredHandler = true
            is ExceptionReturn -> Unit
            is Jump -> if (returnAddresses.lastOrNull() == transfer.target()) {
                returnAddresses.removeLast()
                returned = returnAddresses.isEmpty()
            }
        }
    }

    override fun onStop(event: StopEvent) = Unit
    override fun onError(event: ErrorEvent) = Unit

    fun reached(memory: MemoryState, pc: Int): Boolean {
        return enteredHandler || returned || (returnAddresses.isEmpty() && source?.reached(memory, pc) != false)
    }
}

private fun MemoryState.sourceStatement(pc: Int): ProgramStatement? {
    // Data-segment execution and rewritten instructions have no assembled source position.
    if (!MemoryState.wordAligned(pc) || !(inTextSegment(pc) || inKernelTextSegment(pc))) return null
    return getStatementNoNotify(pc)
}
