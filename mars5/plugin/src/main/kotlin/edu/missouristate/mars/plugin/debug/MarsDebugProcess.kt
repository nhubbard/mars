package edu.missouristate.mars.plugin.debug

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.EditorFactory
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
import edu.missouristate.mars.api.InstructionSourceMap
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.plugin.language.MipsFileType
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import edu.missouristate.mars.plugin.run.MarsRunState
import edu.missouristate.mars.plugin.run.runMessages
import edu.missouristate.mars.simulator.SimulatorEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext

private typealias LineBreakpoint = XLineBreakpoint<XBreakpointProperties<*>>

internal class MarsDebugProcess(session: XDebugSession, state: MarsRunState) : XDebugProcess(session) {
    private data class Command(val steps: Long = 0, val target: Location? = null)

    private val commands = Channel<Command>(Channel.CONFLATED)
    @Volatile private var simulator: SimulatorEngine? = null
    private val bindings = Any()
    private val registered = linkedSetOf<LineBreakpoint>()
    private var sourceMap: InstructionSourceMap? = null
    @Volatile private var addresses = emptyMap<Int, List<LineBreakpoint>>()
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
                    command = commands.receive()
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
                        if (suspended) command = commands.receive()
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

    private fun suspendContext(machine: MarsContext): XSuspendContext {
        val origin = try {
            machine.memory.getStatementNoNotify(machine.registerFile.pc)?.instructionOrigin
        } catch (_: AddressErrorException) {
            null
        }
        if (origin == null) return MarsSuspendContext(null)
        val location = origin.location()
        val file = LocalFileSystem.getInstance().findFileByNioFile(location.file())
        val position = XDebuggerUtil.getInstance().createPosition(file, location.line() - 1)
        return MarsSuspendContext(position)
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

private class MarsSuspendContext(position: XSourcePosition?) : XSuspendContext() {
    private val frame = object : XStackFrame() {
        override fun getSourcePosition() = position
    }
    private val stack = object : XExecutionStack(runMessages.getMessage("debug.cpu")) {
        override fun getTopFrame() = frame
        override fun computeStackFrames(firstFrameIndex: Int, container: XStackFrameContainer) {
            container.addStackFrames(if (firstFrameIndex == 0) listOf(frame) else emptyList(), true)
        }
    }
    override fun getActiveExecutionStack(): XExecutionStack = stack
}
