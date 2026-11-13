package edu.missouristate.mars.plugin.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.RunnerLayoutUi
import com.intellij.execution.ui.layout.PlaceInGrid
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XExpression
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointHandler
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.evaluation.XDebuggerEditorsProvider
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.ui.XDebugTabLayouter
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.AssemblySymbolMap.Kind
import edu.missouristate.mars.api.ErrorEvent
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
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
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
import edu.missouristate.mars.simulator.BackStepper.MemoryWrite
import java.nio.file.Path
import java.util.function.Consumer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlin.io.path.invariantSeparatorsPathString

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

    private class Suspension(val context: MarsSuspendContext) {
        val command = CompletableDeferred<Command>()
    }

    @Volatile private var suspension: Suspension? = null
    @Volatile private var simulator: SimulatorEngine? = null
    private val bindings = Any()
    private val entryPath = state.entry.normalize()
    private val entry = entryPath.invariantSeparatorsPathString
    private val registeredLines = linkedSetOf<LineBreakpoint>()
    private val registeredAddresses = linkedSetOf<AddressBreakpoint>()
    private val registeredWatchpoints = linkedSetOf<MemoryWatchpoint>()
    private data class HitProgress(val interval: Int, val hits: Int)
    private val hitProgress = mutableMapOf<XBreakpoint<*>, HitProgress>()
    private var boundMachine: MarsContext? = null
    @Volatile private var addresses = emptyMap<Int, List<XBreakpoint<*>>>()
    @Volatile private var watchpoints = emptyMap<MemoryWatchpoint, MemoryWatchRange>()
    // Written on the simulator worker and drained when its slice completes.
    private val watchpointHits = linkedSetOf<MemoryWatchpoint>()
    private val watchMemory = Consumer<MemoryAccessNotice> { access ->
        if (access.origin() == AccessOrigin.EXECUTION && !access.isInstructionFetch) {
            for ((breakpoint, range) in watchpoints) {
                if (range.overlaps(access)) watchpointHits.add(breakpoint)
            }
        }
    }
    private var suspensionNumber = 0L
    @Volatile private lateinit var sources: MarsExecutionSources
    private val loadedSymbols = MutableStateFlow<LoadedSymbols?>(null)
    private val handler = state.createProcess { machine, executionSources ->
        sources = executionSources
        loadedSymbols.value = LoadedSymbols(machine.program!!.symbolMap(), executionSources)
        debug(this, machine)
    }

    private val lineHandler = bindingHandler(MarsLineBreakpointType::class.java, registeredLines)
    private val addressHandler = bindingHandler(MarsAddressBreakpointType::class.java, registeredAddresses)
    private val watchpointHandler = bindingHandler(MarsMemoryWatchpointType::class.java, registeredWatchpoints)

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
                    if (!temporary) hitProgress.remove(breakpoint)
                    updateBindings()
                }
            }
        }

    override fun getBreakpointHandlers(): Array<XBreakpointHandler<*>> = arrayOf(lineHandler, addressHandler, watchpointHandler)
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
            // Kotlin exposes this Java accessor as a read-only property.
            @Suppress("UsePropertyAccessSyntax")
            content.setDisposer(view)
            ui.addContent(content, 2, PlaceInGrid.center, false)
            val disassembly = MarsDisassemblyView(session, entry, ::sourcePosition)
            val listing = ui.createContent("MarsMipsDisassembly", disassembly.component, runMessages.getMessage("debug.disassembly"), null, disassembly.focus)
            listing.isCloseable = false
            // Kotlin exposes this Java accessor as a read-only property.
            @Suppress("UsePropertyAccessSyntax")
            listing.setDisposer(disassembly)
            ui.addContent(listing, 3, PlaceInGrid.center, false)
            val symbols = MarsSymbolsView(session.project, loadedSymbols)
            symbols.bindAddressInspection(session) { definition ->
                when (definition.kind) {
                    Kind.TEXT -> {
                        disassembly.showAddress(definition.address)
                        ui.selectAndFocus(listing, true, true)
                    }
                    Kind.DATA, Kind.EXTERNAL -> {
                        view.showAddress(definition.address)
                        ui.selectAndFocus(content, true, true)
                    }
                }
            }
            val labels = ui.createContent("MarsMipsSymbols", symbols.component, runMessages.getMessage("debug.symbols"), null, symbols.focus)
            labels.isCloseable = false
            // Kotlin exposes this Java accessor as a read-only property.
            @Suppress("UsePropertyAccessSyntax")
            labels.setDisposer(symbols)
            ui.addContent(labels, 4, PlaceInGrid.center, false)
            val refreshVariables = {
                if (session.isSuspended) session.rebuildViews()
            }
            service<MarsDebuggerDisplaySettings>().addVariablesListener(labels, refreshVariables)
            onMachineHighlightSchemeChange(labels, refreshVariables)
        }
    }

    override fun registerAdditionalActions(leftToolbar: DefaultActionGroup, topToolbar: DefaultActionGroup, settings: DefaultActionGroup) {
        val display = service<MarsDebuggerDisplaySettings>()
        settings.add(MarsHexAddressesAction(display))
        settings.add(MarsHexValuesAction(display))
        settings.add(MarsHighlightMemoryWritesAction(display))
        settings.add(MarsHighlightRegisterWritesAction(display))
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
                val pause = pendingSuspension(session.suspendContext) ?: return
                pause.command.complete(Command(steps = 1))
                session.sessionResumed()
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
                    val pause = pendingSuspension(session.suspendContext) ?: return
                    if (!pause.context.canBackstep) return
                    pause.command.complete(Command(backstep = step))
                    session.sessionResumed()
                }
            })
        }
    }

    private fun canBackstep() = (session.suspendContext as? MarsSuspendContext)?.canBackstep == true

    // IntelliJ clears its current context before invoking native resume and step callbacks.
    private fun pendingSuspension(context: XSuspendContext?): Suspension? =
        suspension?.takeIf { it.context === context && it.command.isActive }

    override fun stop() = handler.destroyProcess()
    override fun resume(context: XSuspendContext?) {
        val pause = pendingSuspension(context) ?: return
        pause.command.complete(Command())
    }
    override fun startPausing() { simulator?.pause() }
    override fun startStepInto(context: XSuspendContext?) {
        val pause = pendingSuspension(context) ?: return
        val sourceStep = pause.context.sourceStep
        pause.command.complete(Command(steps = if (sourceStep == null) 1 else SOURCE_STEP_LIMIT, sourceStep = sourceStep))
    }
    override fun startStepOver(context: XSuspendContext?) {
        val pause = pendingSuspension(context) ?: return
        pause.command.complete(Command(steps = CALL_STEP_LIMIT, stepOver = StepOver(pause.context.sourceStep)))
    }
    override fun startStepOut(context: XSuspendContext?) {
        val pause = pendingSuspension(context) ?: return
        val target = pause.context.stepOutTarget
        if (target == null) rejectCommand("debug.step.out.unavailable", pause.context)
        else pause.command.complete(Command(steps = CALL_STEP_LIMIT, stepOut = target))
    }
    override fun runToPosition(position: XSourcePosition, context: XSuspendContext?) {
        val pause = pendingSuspension(context) ?: return
        val target = sources.location(position.file, position.line)
        if (target == null) rejectCommand("debug.source.not.in.image", pause.context)
        else pause.command.complete(Command(target = target))
    }

    internal fun originalBreakpointLine(file: VirtualFile, line: Int): Pair<VirtualFile, Location>? = synchronized(bindings) {
        val machine = boundMachine ?: return@synchronized null
        val target = sources.originalLine(file, line) ?: return@synchronized null
        target.takeIf { machine.program!!.sourceMap().breakpoints().containsKey(target.second) }
    }

    private fun rejectCommand(message: String, context: MarsSuspendContext) {
        session.reportError(runMessages.getMessage(message))
        session.positionReached(context)
    }

    private fun updateBindings() {
        val machine = boundMachine ?: return
        val map = machine.program!!.sourceMap()
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
        val nextWatchpoints = buildMap {
            for (breakpoint in registeredWatchpoints) {
                val range = breakpoint.properties?.range()
                if (range == null) {
                    session.updateBreakpointPresentation(breakpoint, AllIcons.Debugger.Db_invalid_breakpoint, runMessages.getMessage("debug.watchpoint.invalid"))
                } else {
                    put(breakpoint, range)
                    session.updateBreakpointPresentation(breakpoint, null, null)
                }
            }
        }
        bindWatchpoints(machine.memory, nextWatchpoints)
    }

    private fun bindWatchpoints(memory: MemoryState, next: Map<MemoryWatchpoint, MemoryWatchRange>) {
        val wasObserving = watchpoints.isNotEmpty()
        watchpoints = next
        if (wasObserving == next.isNotEmpty()) return
        if (wasObserving) memory.deleteObserver(watchMemory) else memory.addObserver(watchMemory)
    }

    private suspend fun debug(process: MarsProcessHandler, machine: MarsContext): StopEvent {
        val engine = machine.simulator()
        val calls = DebuggerCallStack(machine.backStepper.capacity()) { machine.memory.sourceStatement(it)?.instructionOrigin }
        engine.addListener(calls)
        simulator = engine
        val map = machine.program!!.sourceMap()
        try {
            synchronized(bindings) {
                boundMachine = machine
                updateBindings()
            }
            var command = Command()
            var skipFirst = false
            while (true) {
                val backstep = command.backstep
                if (backstep != null) {
                    process.backstepSlice(engine, wholeSource = backstep == ReverseStep.SOURCE)
                    withSuspension(machine, calls) { pause ->
                        withContext(Dispatchers.EDT) { session.positionReached(pause.context) }
                        command = awaitCommand(pause, machine)
                    }
                    continue
                }
                val target = command.target
                val targets = target?.let { map.breakpoints()[it] }.orEmpty()
                var skip = skipFirst
                var hits = emptyList<XBreakpoint<*>>()
                var stepOrTargetReached = false
                if (target != null && targets.isEmpty()) {
                    withSuspension(machine, calls) { pause ->
                        withContext(Dispatchers.EDT) {
                            session.reportError(runMessages.getMessage("debug.breakpoint.no.code"))
                            session.positionReached(pause.context)
                        }
                        command = awaitCommand(pause, machine)
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
                            hits.isNotEmpty() || stepOrTargetReached || watchpointHits.isNotEmpty()
                        }
                    }
                } finally {
                    stepOver?.let(engine::removeListener)
                }
                when (val reason: StopEvent.Reason = event.reason) {
                    StopEvent.Reason.BREAKPOINT, StopEvent.Reason.PAUSE, StopEvent.Reason.STEP_LIMIT -> {
                        val reached = if (reason == StopEvent.Reason.BREAKPOINT) hits + watchpointHits else watchpointHits.toList()
                        watchpointHits.clear()
                        withSuspension(machine, calls) { pause ->
                            val context = pause.context
                            val breakpointSuspended = reached.sortedBy { it.suspendPolicy != SuspendPolicy.NONE }
                                .filter { matchesBreakpoint(it, context, machine.memory) }
                                .any { handleBreakpoint(it, context, machine.memory) }
                            val positionReached = reason != StopEvent.Reason.BREAKPOINT || stepOrTargetReached
                            if (!breakpointSuspended && positionReached) {
                                withContext(Dispatchers.EDT) { session.positionReached(context) }
                            }
                            if (breakpointSuspended || positionReached) command = awaitCommand(pause, machine)
                        }
                        skipFirst = true
                    }
                    else -> return event
                }
            }
        } finally {
            synchronized(bindings) {
                boundMachine = null
                bindWatchpoints(machine.memory, emptyMap())
            }
            watchpointHits.clear()
            engine.removeListener(calls)
            simulator = null
        }
    }

    private suspend fun matchesBreakpoint(breakpoint: XBreakpoint<*>, context: MarsSuspendContext, memory: MemoryState): Boolean {
        breakpoint.conditionExpression?.let { expression ->
            val condition = context.frame.evaluator.evaluateStopped(expression.expression, memory).getOrElse { error ->
                withContext(Dispatchers.EDT) {
                    session.reportError(runMessages.getMessage("debug.breakpoint.condition.error", error.message))
                }
                return false
            }
            if (condition.number == 0.0) return false
        }
        return matchesHitInterval(breakpoint)
    }

    private suspend fun handleBreakpoint(breakpoint: XBreakpoint<*>, context: MarsSuspendContext, memory: MemoryState): Boolean {
        val logged = breakpoint.logExpressionObject?.let { context.frame.evaluator.evaluateStopped(it.expression, memory) }
        return withContext(Dispatchers.EDT) {
            logged?.exceptionOrNull()?.let { session.reportError(runMessages.getMessage("debug.breakpoint.log.error", it.message)) }
            session.breakpointReached(breakpoint, logged?.getOrNull()?.display(), context)
        }
    }

    private fun matchesHitInterval(breakpoint: XBreakpoint<*>): Boolean {
        val properties = breakpoint.properties as? MarsBreakpointHitCount ?: return true
        return synchronized(bindings) {
            val interval = properties.hitInterval
            val previous = hitProgress[breakpoint]
            val hits = if (previous?.interval == interval) previous.hits + 1 else 1
            if (hits >= interval) {
                hitProgress.remove(breakpoint)
                true
            } else {
                hitProgress[breakpoint] = HitProgress(interval, hits)
                false
            }
        }
    }

    private suspend fun awaitCommand(pause: Suspension, machine: MarsContext): Command {
        val context = pause.context
        while (true) {
            val command = select {
                pause.command.onAwait { it }
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
                context.machineEdits.onReceive { request ->
                    // A selected edit completes before the worker can start another command.
                    withContext(NonCancellable) { editMachine(machine, context, request) }
                    null
                }
            }
            if (command != null) return command
        }
    }

    private suspend fun editMachine(machine: MarsContext, context: MarsSuspendContext, request: MachineEditRequest) {
        val changed = request.apply(machine, context.frame.evaluator).getOrElse { error ->
            withContext(Dispatchers.EDT) { request.callback.errorOccurred(error.message!!) }
            return
        }
        if (changed) {
            machine.claimStoppedSession().use {
                val pc = machine.registerFile.getPC()
                val origin = machine.memory.sourceStatement(pc)?.instructionOrigin
                context.refresh(machine, sourcePosition(origin), origin?.let { SourceStep(pc, it) })
            }
        }
        withContext(Dispatchers.EDT) { request.callback.valueModified() }
    }

    private suspend fun withSuspension(
        machine: MarsContext, calls: DebuggerCallStack, action: suspend (Suspension) -> Unit,
    ) = coroutineScope {
        val context = suspendContext(machine, calls.stepOutTarget(), calls.callers(), this)
        val pause = Suspension(context)
        suspension = pause
        try {
            action(pause)
        } finally {
            suspension = null
            pause.command.cancel()
            context.inspectionRequests.cancel()
            context.watchRequests.cancel()
            context.machineEdits.cancel()
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
        val program = machine.program!!
        val symbols = program.symbolMap()
        val assemblyEntry = statement?.sourceFile?.ifEmpty { program.filename } ?: program.filename
        val localSymbols = symbols.locals().getValue(Path.of(assemblyEntry).toAbsolutePath().normalize())
        val watchRequests = watchMemoryRequests()
        val machineEdits = machineEditRequests()
        val number = ++suspensionNumber
        val frame = MarsStackFrame(
            sourcePosition(origin), machine, session.project, localSymbols, symbols.globals(), loadedSymbols.value!!, watchRequests, machineEdits,
        ) {
            val current = session.suspendContext
            current is MarsSuspendContext && current.machineEdits === machineEdits
        }
        MarsSuspendContext(
            frame,
            machine.backStepper.canBackstep(),
            MemoryRegion.entries.associateWith { it.address(machine).toUInt().toLong() },
            ExecutionMarkers.capture(machine),
            machine.backStepper.previousMemoryWrite(),
            number,
            watchRequests,
            machineEdits,
            origin?.let { SourceStep(pc, it) },
            stepOutTarget,
            MarsExecutionStack(frame, callers, ::sourcePosition, frameScope),
        )
    }

    private fun sourcePosition(origin: Origin?): XSourcePosition? =
        origin?.let { sourcePosition(it.location) }

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
    executionMarkers: ExecutionMarkers,
    lastMemoryWrite: MemoryWrite?,
    val number: Long,
    val watchRequests: Channel<WatchMemoryRequest>,
    val machineEdits: Channel<MachineEditRequest>,
    sourceStep: SourceStep?,
    val stepOutTarget: DebuggerCallStack.StepOutTarget?,
    private val stack: MarsExecutionStack,
) : XSuspendContext() {
    @Volatile var canBackstep = canBackstep
        private set
    @Volatile var memoryRegions = memoryRegions
        private set
    @Volatile var executionMarkers = executionMarkers
        private set
    @Volatile var lastMemoryWrite = lastMemoryWrite
        private set

    @Volatile var sourceStep = sourceStep
        private set

    fun refresh(machine: MarsContext, position: XSourcePosition?, step: SourceStep?) {
        frame.refresh(machine, position)
        sourceStep = step
        canBackstep = machine.backStepper.canBackstep()
        memoryRegions = MemoryRegion.entries.associateWith { it.address(machine).toUInt().toLong() }
        executionMarkers = ExecutionMarkers.capture(machine)
        lastMemoryWrite = machine.backStepper.previousMemoryWrite()
    }

    val memoryExports = Channel(16, onUndeliveredElement = MemoryExportRequest::unavailable)
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
        when (val transfer = event.transfer ?: return) {
            is Call -> returnAddresses.addLast(transfer.returnAddress)
            is ExceptionEntry -> enteredHandler = true
            is ExceptionReturn -> Unit
            is Jump -> if (returnAddresses.lastOrNull() == transfer.target) {
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

internal fun MemoryState.sourceStatement(pc: Int): ProgramStatement? {
    // Data-segment execution and rewritten instructions have no assembled source position.
    if (!MemoryState.wordAligned(pc) || !(inTextSegment(pc) || inKernelTextSegment(pc))) return null
    return getStatementNoNotify(pc)
}
