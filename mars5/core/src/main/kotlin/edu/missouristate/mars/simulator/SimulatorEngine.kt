package edu.missouristate.mars.simulator

import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.MarsSimulator
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.SimulatorState
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.api.StopEvent.Reason
import edu.missouristate.mars.core.MarsContext
import java.lang.Integer.compareUnsigned
import java.lang.System.Logger.Level.WARNING
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.locks.LockSupport
import java.util.function.DoubleSupplier
import java.util.function.IntPredicate

/** Runs instructions, undo, and callbacks for one session on a dedicated worker. */
class SimulatorEngine(private val session: MarsContext) : MarsSimulator, AutoCloseable {
    private enum class Request { NONE, PAUSE, STOP }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mars-simulator").apply { isDaemon = true }
    }
    private val listeners = CopyOnWriteArrayList<SimulatorListener>()
    @Volatile private var state = SimulatorState.IDLE
    @Volatile private var request = Request.NONE
    @Volatile private var worker: Thread? = null
    @Volatile private var runSpeed = DoubleSupplier { Double.POSITIVE_INFINITY }
    private var closed = false
    private var program: MIPSprogram? = null
    private var stepCount = 0L
    private var instructionLimit = 0L
    private var instructionsRemaining = 0L

    override fun run() {
        runAsync(0, null)
    }

    override fun step() {
        runAsync(1, null)
    }

    /** Set the instruction budget before execution starts. Zero means unlimited. */
    @Synchronized
    fun setInstructionLimit(limit: Long) {
        require(limit >= 0) { "Instruction limit cannot be negative" }
        check(program == null) { "Set the instruction limit before starting the simulator" }
        instructionLimit = limit
    }

    /** Supply instructions per second. Nonpositive and infinite rates run without throttling. */
    fun setRunSpeedSource(source: DoubleSupplier) {
        runSpeed = source
    }

    private fun instructionLimitReached(): Boolean = instructionLimit > 0 && instructionsRemaining == 0L

    private fun instructionDelayNanos(): Long {
        val rate = runSpeed.asDouble
        if (rate <= 0 || !rate.isFinite()) return 0
        return minOf(1_000_000_000.0 / rate, Long.MAX_VALUE / 2.0).toLong()
    }

    private fun awaitNextInstruction() {
        var delay = instructionDelayNanos()
        if (delay == 0L) return
        val start = System.nanoTime()
        while (request == Request.NONE) {
            val remaining = delay - (System.nanoTime() - start)
            if (remaining <= 0) return
            LockSupport.parkNanos(minOf(10_000_000L, remaining))
            delay = instructionDelayNanos()
        }
    }

    /** The future completes after stop callbacks. Nonpositive step limits mean unlimited. */
    fun runAsync(maximumSteps: Long, breakpoints: IntArray?): CompletableFuture<StopEvent> {
        val points = breakpoints?.copyOf() ?: IntArray(0)
        points.sort()
        return startAsync(maximumSteps, points, null)
    }

    /** Check the predicate on the worker before each instruction, including the first. */
    fun runUntilAsync(maximumSteps: Long, shouldBreak: IntPredicate): CompletableFuture<StopEvent> =
        startAsync(maximumSteps, null, shouldBreak)

    @Synchronized
    private fun startAsync(maximumSteps: Long, points: IntArray?, shouldBreak: IntPredicate?): CompletableFuture<StopEvent> {
        checkReady()
        val current = checkNotNull(session.program) { "No program assembled" }
        if (current !== program) {
            selectProgram(current)
            state = SimulatorState.IDLE
        }
        check(state != SimulatorState.STOPPED && !instructionLimitReached()) { "Program has stopped" }
        request = Request.NONE
        state = SimulatorState.RUNNING
        val completion = CompletableFuture<StopEvent>()
        executor.execute { execute(current, maximumSteps, points, shouldBreak, completion) }
        return completion
    }

    private fun checkReady() {
        check(!closed && !session.isClosing) { "Simulator is closed" }
        check(state != SimulatorState.RUNNING) { "Simulator is running" }
    }

    private fun selectProgram(current: MIPSprogram) {
        program = current
        stepCount = 0
        instructionsRemaining = instructionLimit
    }

    private fun execute(
        current: MIPSprogram, maximumSteps: Long, points: IntArray?, shouldBreak: IntPredicate?,
        completion: CompletableFuture<StopEvent>,
    ) {
        worker = Thread.currentThread()
        var acquired = false
        val reason = try {
            session.claimExecution().use {
                acquired = true
                check(current === session.program) { "Program was replaced" }
                val stop = executeInstructions(current, maximumSteps, points, shouldBreak)
                if (stop != Reason.PAUSE && stop != Reason.BREAKPOINT && stop != Reason.STEP_LIMIT) {
                    session.syscallEnvironment.close()
                }
                stop
            }
        } catch (error: Exception) {
            if (acquired) session.syscallEnvironment.close()
            reportError(error)
            Reason.EXCEPTION
        }
        val event = StopEvent(reason, current.exitCode)
        synchronized(this) {
            state = when (reason) {
                Reason.PAUSE, Reason.BREAKPOINT, Reason.STEP_LIMIT, Reason.BACKSTEP -> SimulatorState.PAUSED
                else -> SimulatorState.STOPPED
            }
        }
        notifyListeners { it.onStop(event) }
        completion.complete(event)
    }

    private fun executeInstructions(
        current: MIPSprogram, maximumSteps: Long, points: IntArray?, shouldBreak: IntPredicate?,
    ): Reason {
        var steps = 0L
        while (true) {
            if (request != Request.NONE) return if (request == Request.STOP) Reason.STOP_REQUEST else Reason.PAUSE
            if (shouldBreak != null) {
                val hit = shouldBreak.test(session.registerFile.getPC())
                if (request != Request.NONE) continue
                if (hit) return Reason.BREAKPOINT
            }
            val result = current.executeSessionInstruction()
            if (result.executed) {
                steps++
                stepCount++
                if (instructionLimit > 0) instructionsRemaining--
                val event = StepEvent(result.pc, result.instruction, stepCount, result.transfer, result.branchOutcome)
                notifyListeners { it.onStep(event) }
            }
            val stop = result.stopReason
            if (stop != null) return stop
            if (instructionLimitReached()) return Reason.INSTRUCTION_LIMIT
            if (request != Request.NONE) continue
            // Address-list breakpoints are checked after execution so resuming can leave a breakpoint.
            if (points != null && points.binarySearch(session.registerFile.getPC()) >= 0) return Reason.BREAKPOINT
            if (maximumSteps > 0 && steps >= maximumSteps) return Reason.STEP_LIMIT
            awaitNextInstruction()
        }
    }

    @Synchronized
    override fun pause() {
        if (state == SimulatorState.RUNNING && request != Request.STOP) request = Request.PAUSE
    }

    @Synchronized
    override fun stop() {
        if (closed) return
        if (state == SimulatorState.RUNNING) {
            request = Request.STOP
        } else {
            state = SimulatorState.RUNNING
            executor.execute(::stopOnWorker)
        }
    }

    private fun stopOnWorker() {
        worker = Thread.currentThread()
        val reason = try {
            session.claimExecution().use { session.syscallEnvironment.close() }
            Reason.STOP_REQUEST
        } catch (error: Exception) {
            reportError(error)
            Reason.EXCEPTION
        }
        state = SimulatorState.STOPPED
        val event = StopEvent(reason, program?.exitCode ?: 0)
        notifyListeners { it.onStop(event) }
    }

    override fun backstep() {
        backstepAsync()
    }

    fun backstepAsync(): CompletableFuture<Void> = backstepAsync(false)

    /** Undo one source statement, or one instruction if no source is available. */
    fun backstepSourceAsync(): CompletableFuture<Void> = backstepAsync(true)

    @Synchronized
    private fun backstepAsync(wholeSource: Boolean): CompletableFuture<Void> {
        checkReady()
        val current = session.program ?: return CompletableFuture.completedFuture(null)
        if (program !== current) selectProgram(current)
        val completion = CompletableFuture<Void>()
        request = Request.NONE
        state = SimulatorState.RUNNING
        executor.execute { undoOnWorker(current, wholeSource, completion) }
        return completion
    }

    private fun undoOnWorker(current: MIPSprogram, wholeSource: Boolean, completion: CompletableFuture<Void>) {
        worker = Thread.currentThread()
        try {
            session.claimExecution().use {
                check(program === session.program) { "Program was replaced" }
                val previousStepCount = stepCount
                undo(wholeSource)
                if (stepCount != previousStepCount) notifyListeners { it.onBackstep(stepCount) }
            }
        } catch (error: Exception) {
            state = SimulatorState.STOPPED
            reportError(error)
            notifyListeners { it.onStop(StopEvent(Reason.EXCEPTION, program?.exitCode ?: 0)) }
            completion.completeExceptionally(error)
            return
        }
        state = SimulatorState.PAUSED
        notifyListeners { it.onStop(StopEvent(Reason.BACKSTEP, current.exitCode)) }
        completion.complete(null)
    }

    private fun undo(wholeSource: Boolean) {
        val history = session.backStepper
        var instruction = if (wholeSource) history.previousInstruction() else null
        while (history.canBackstep() && request == Request.NONE) {
            history.backStep()
            if (stepCount > 0) stepCount--
            if (!wholeSource) return
            val earlier = history.previousInstruction()
            if (instruction == null || instruction.instructionOrigin == null || earlier == null ||
                earlier.instructionOrigin !== instruction.instructionOrigin ||
                compareUnsigned(earlier.address, instruction.address) >= 0
            ) return
            // Lower addresses with the same origin can belong to the current source statement.
            instruction = earlier
        }
    }

    fun executingOnAnotherThread(): Boolean = state == SimulatorState.RUNNING && worker !== Thread.currentThread()

    override fun state(): SimulatorState = state

    override fun addListener(listener: SimulatorListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: SimulatorListener) {
        listeners.remove(listener)
    }

    private fun reportError(error: Exception) {
        notifyListeners { it.onError(ErrorEvent(error.message, error)) }
    }

    private inline fun notifyListeners(callback: (SimulatorListener) -> Unit) {
        for (listener in listeners) {
            try {
                callback(listener)
            } catch (error: RuntimeException) {
                logger.log(WARNING, "Simulator listener failed", error)
            }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        request = Request.STOP
        executor.execute {
            session.releaseDefaultResources()
            state = SimulatorState.STOPPED
        }
        executor.shutdown()
    }

    private companion object {
        val logger: System.Logger = System.getLogger(SimulatorEngine::class.java.name)
    }
}
