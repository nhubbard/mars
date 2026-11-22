package edu.missouristate.mars.plugin.learning

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.plugin.debug.MarsDebugProcess
import edu.missouristate.mars.plugin.debug.MarsSuspendContext
import edu.missouristate.mars.plugin.run.MarsProcessHandler
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Observes ordinary Debug pauses. Breakpoints, stepping and input remain the student's actions. */
internal class MarsDownloadedDebugCheckpoint(
    private val project: Project, private val lifetime: Disposable, private val entry: Path,
    private val editor: Editor, identity: MarsLessonIdentity, private val step: MarsNativeDebugStep,
    private val feedback: (Outcome) -> Unit,
) {
    private val source = MarsLearningSource(project, entry, editor.document, identity)
    private class Observation(val attempt: MarsLearningSource.Attempt, val owned: Disposable,
                              val trace: Trace, val input: MarsIntegerInputObservation) {
        var lastFeedback: Outcome? = null
    }
    private val observation = AtomicReference<Observation?>()
    @Volatile private var closed = false

    init {
        Disposer.register(lifetime) { closed = true }
        project.messageBus.connect(lifetime).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                starting(executorId, handler)
            }
        })
    }

    fun passed() = source.passed(step.id)

    val phase: Int
        get() {
            if (passed()) return step.stops.size
            val current = observation.get() ?: return 0
            return if (source.current(current.attempt)) current.trace.phase else 0
        }

    @Synchronized
    fun starting(executorId: String, handler: ProcessHandler) {
        if (closed || executorId != DefaultDebugExecutor.EXECUTOR_ID || handler !is MarsProcessHandler) return
        if (!source.supports(handler) || passed()) return
        val session = XDebuggerManager.getInstance(project).debugSessions.singleOrNull {
            it.debugProcess is MarsDebugProcess && it.debugProcess.processHandler === handler
        } ?: return
        observation.get()?.let { Disposer.dispose(it.owned) }
        val owned = Disposer.newDisposable("Downloaded MARS Debug observation")
        val state = Observation(source.begin(step.id), owned, Trace(step.stops), MarsIntegerInputObservation(step.integerInputs))
        observation.set(state)
        Disposer.register(lifetime, owned)
        Disposer.register(owned) {
            source.finish(state.attempt, false)
            observation.compareAndSet(state, null)
        }
        handler.addInstructionListener({ event -> state.input.instruction(event, handler.integerRegisterBits(2)!!) }, owned)
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = runReadActionBlocking {
                if (closed || observation.get() !== state) return@runReadActionBlocking
                if (!source.current(state.attempt) || !source.matches(state.attempt, handler)) {
                    report(state, Outcome.SOURCE_CHANGED)
                    Disposer.dispose(owned)
                    return@runReadActionBlocking
                }
                // The native callback follows installation of this MARS session's context.
                val context = session.suspendContext as MarsSuspendContext
                val stop = step.stops[state.trace.phase]
                val evaluator = context.frame.evaluator
                val range = MarsLessonAnchors.range(editor, stop.anchor)
                val address = range?.let {
                    val line = editor.document.getLineNumber(it.startOffset) + 1
                    evaluator.instructionSources.breakpointsByAddress[Location(entry, line)]?.singleOrNull()
                }
                val values = stop.registers.mapValues { (name, _) -> evaluator.integerRegisterBits(name) }
                val outcome = state.trace.observe(Pause(context.number, context.executionRevision, state.input.instructions,
                    evaluator.integerRegisterBits("pc"), address, values, state.input.matches())) ?: return@runReadActionBlocking
                when (outcome) {
                    Outcome.PASSED -> {
                        // The ticket decides acceptance atomically. The native task reads
                        // passed()/phase; a reset racing this commit cannot advance it.
                        source.finish(state.attempt, true)
                        Disposer.dispose(owned)
                    }
                    Outcome.ADVANCED -> Unit
                    Outcome.HISTORY, Outcome.INPUT -> { report(state, outcome); Disposer.dispose(owned) }
                    else -> report(state, outcome)
                }
            }

            override fun sessionStopped() { Disposer.dispose(owned) }
        }, owned)
    }

    private fun report(state: Observation, outcome: Outcome) {
        if (state.lastFeedback == outcome) return
        state.lastFeedback = outcome
        feedback(outcome)
    }

    internal enum class Outcome { ADVANCED, PASSED, ANCHOR, BOUNDARY, VALUES, INPUT, HISTORY, SOURCE_CHANGED }
    internal data class Pause(val number: Long, val revision: Long, val instructions: Long, val pc: Long,
                             val address: Long?, val registers: Map<String, Long>, val inputsMatch: Boolean)

    /** Accept ordered source-operation starts, with full-width values and actual forward execution between stops. */
    internal class Trace(private val stops: List<MarsNativeDebugStop>) {
        @Volatile var phase = 0
            private set
        private var lastPause = 0L
        private var lastInstructions = -1L

        fun observe(pause: Pause): Outcome? {
            if (phase == stops.size || pause.number <= lastPause) return null
            lastPause = pause.number
            if (pause.revision != 0L) return Outcome.HISTORY
            val address = pause.address ?: return Outcome.ANCHOR
            if (pause.pc != address || pause.instructions <= lastInstructions) return Outcome.BOUNDARY
            if (!pause.inputsMatch) return Outcome.INPUT
            if (pause.registers != stops[phase].registers) return Outcome.VALUES
            lastInstructions = pause.instructions
            phase++
            return if (phase == stops.size) Outcome.PASSED else Outcome.ADVANCED
        }
    }
}
