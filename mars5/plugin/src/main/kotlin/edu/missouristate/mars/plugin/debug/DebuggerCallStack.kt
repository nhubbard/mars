package edu.missouristate.mars.plugin.debug

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.Call
import edu.missouristate.mars.api.StepEvent.ExceptionEntry
import edu.missouristate.mars.api.StepEvent.ExceptionReturn
import edu.missouristate.mars.api.StepEvent.Jump
import edu.missouristate.mars.api.StopEvent

/** Tracks observed calls on the simulator worker and is read after the worker has suspended. */
internal class DebuggerCallStack(
    private val undoCapacity: Int, private val sourceAt: (Int) -> Origin?,
) : SimulatorListener {
    data class StepOutTarget(val depth: Int, val handlerDepth: Int)
    data class CallerSite(val id: Long, val pc: Int, val origin: Origin?, val call: Call?)

    private class Frame(val parent: Frame?, val site: CallerSite) {
        val returnAddress = site.call?.returnAddress()
        val depth: Int = (parent?.depth ?: 0) + 1
        val handlerDepth: Int = if (returnAddress == null) depth else parent?.handlerDepth ?: 0
    }

    private data class Change(val step: Long, val previous: Frame?)

    private var head: Frame? = null
    private var nextId = 0L
    private val history = ArrayDeque<Change>()

    fun stepOutTarget(): StepOutTarget? {
        val frame = head ?: return null
        if (frame.returnAddress == null) return null
        return StepOutTarget(frame.depth, frame.handlerDepth)
    }

    fun callers(): Sequence<CallerSite> = generateSequence(head, Frame::parent).map(Frame::site)

    fun reached(target: StepOutTarget): Boolean {
        val frame = head ?: return true
        return frame.depth < target.depth || frame.handlerDepth != target.handlerDepth
    }

    override fun onStep(event: StepEvent) {
        val before = head
        head = when (val transfer = event.transfer()) {
            is Call -> Frame(before, CallerSite(++nextId, event.pc(), sourceAt(event.pc()), transfer))
            ExceptionEntry.INSTANCE -> Frame(before, CallerSite(++nextId, event.pc(), sourceAt(event.pc()), null))
            ExceptionReturn.INSTANCE -> generateSequence(before, Frame::parent).firstOrNull { it.returnAddress == null }?.parent
            is Jump -> if (before != null && before.returnAddress == transfer.target()) before.parent else before
            else -> before
        }
        if (head === before) return
        if (history.size == undoCapacity) history.removeFirst()
        history.addLast(Change(event.stepCount(), before))
    }

    override fun onBackstep(stepCount: Long) {
        while ((history.lastOrNull()?.step ?: 0) > stepCount) {
            head = history.removeLast().previous
        }
    }

    override fun onStop(event: StopEvent) = Unit
    override fun onError(event: ErrorEvent) = Unit
}
