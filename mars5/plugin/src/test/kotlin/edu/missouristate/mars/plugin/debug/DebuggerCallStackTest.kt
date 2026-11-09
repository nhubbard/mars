package edu.missouristate.mars.plugin.debug

import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StepEvent.Call
import edu.missouristate.mars.api.StepEvent.ExceptionEntry
import edu.missouristate.mars.api.StepEvent.ExceptionReturn
import edu.missouristate.mars.api.StepEvent.Jump
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DebuggerCallStackTest {
    @Test
    fun `undo retains bounded frame changes and restores exception boundaries`() {
        val calls = DebuggerCallStack(2) { null }
        calls.onStep(StepEvent(8, 0, 1))
        assertNull(calls.stepOutTarget())
        calls.onStep(StepEvent(12, 0, 2, Call(31, 16)))
        val caller = checkNotNull(calls.stepOutTarget())
        calls.onStep(StepEvent(20, 0, 3, ExceptionEntry.INSTANCE))
        calls.onStep(StepEvent(36, 0, 4, Jump(40)))
        assertNull(calls.stepOutTarget())
        assertTrue(calls.reached(caller))
        calls.onStep(StepEvent(40, 0, 5, Call(16, 44)))
        calls.onStep(StepEvent(48, 0, 6, ExceptionReturn.INSTANCE))
        assertEquals(caller, calls.stepOutTarget())
        assertFalse(calls.reached(caller))
        calls.onBackstep(3)
        assertNull(calls.stepOutTarget())
        calls.onStep(StepEvent(40, 0, 4, ExceptionReturn.INSTANCE))
        calls.onStep(StepEvent(20, 0, 5, Jump(16)))
        assertTrue(calls.reached(caller))
        calls.onStep(StepEvent(16, 0, 6, ExceptionReturn.INSTANCE))
        assertNull(calls.stepOutTarget())
    }
}
