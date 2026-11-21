package edu.missouristate.mars.plugin.learning

import edu.missouristate.mars.plugin.learning.MarsConsoleDebugCheckpoint.Companion.ADDIU_T0_T0_ONE
import edu.missouristate.mars.plugin.learning.MarsConsoleDebugCheckpoint.Companion.ADDU_T0_V0_V0
import edu.missouristate.mars.plugin.learning.MarsConsoleDebugCheckpoint.Pause
import edu.missouristate.mars.plugin.learning.MarsConsoleDebugCheckpoint.Trace
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MarsDebugTraceTest {
    @Test
    fun `three stopped boundaries require exact instructions values and ordered fresh suspensions`() {
        val trace = Trace()
        val first = Pause(1, 0x400020, ADDU_T0_V0_V0, 20, 0)
        trace.observe(first.copy(instruction = null))
        trace.observe(first.copy(number = 2, instruction = 0))
        trace.observe(first.copy(number = 3, input = 19))
        assertEquals(0, trace.phase)
        trace.observe(first.copy(number = 4))
        assertEquals(1, trace.phase)
        val second = Pause(4, first.pc + 4, ADDIU_T0_T0_ONE, 20, 40)
        for ((index, wrong) in listOf(second.copy(pc = first.pc + 8), second.copy(instruction = 0), second.copy(instruction = null),
            second.copy(input = 21), second.copy(result = 41)).withIndex()) {
            trace.observe(wrong.copy(number = 10L + index))
        }
        // An older context cannot advance the trace, even if its displayed values match.
        trace.observe(second)
        assertEquals(1, trace.phase)
        trace.observe(second.copy(number = 100000))
        assertEquals(2, trace.phase)
        val third = Pause(100001, first.pc + 8, 0, 20, 41)
        trace.observe(third.copy(pc = first.pc + 12))
        trace.observe(third.copy(number = 100002, input = 19))
        trace.observe(third.copy(number = 100003, result = 40))
        assertEquals(2, trace.phase)
        trace.observe(third.copy(number = 100004))
        assertEquals(3, trace.phase)
        trace.observe(third.copy(number = 100005, result = 0))
        assertEquals(3, trace.phase)
    }
    @Test
    fun `output trace rejects incorrect copies selectors boundaries and stale pauses`() {
        val trace = Trace(MarsConsoleDebugCheckpoint.Segment.OUTPUT)
        var number = 1L
        fun observe(pause: Pause) { trace.observe(pause.copy(number = number++)) }
        val first = Pause(0, 0x400040, MarsConsoleDebugCheckpoint.MOVE_A0_T0, 4, 41, 0x10010000)
        for (wrong in listOf(first.copy(instruction = null), first.copy(instruction = 0),
            first.copy(input = 20), first.copy(result = 40))) observe(wrong)
        assertEquals(0, trace.phase)
        observe(first)
        assertEquals(1, trace.phase)
        val second = first.copy(pc = first.pc + 4, instruction = MarsConsoleDebugCheckpoint.LI_V0_ONE, argument = 41)
        for (wrong in listOf(second.copy(pc = first.pc + 8), second.copy(instruction = null),
            second.copy(instruction = 0), second.copy(input = 1), second.copy(result = 40), second.copy(argument = 4))) observe(wrong)
        trace.observe(second.copy(number = 1))
        assertEquals(1, trace.phase)
        observe(second)
        assertEquals(2, trace.phase)
        val third = second.copy(pc = first.pc + 8, instruction = MarsConsoleDebugCheckpoint.SYSCALL, input = 1)
        for (wrong in listOf(third.copy(pc = first.pc + 12), third.copy(instruction = null),
            third.copy(instruction = 0), third.copy(input = 4), third.copy(result = 40), third.copy(argument = 4))) observe(wrong)
        assertEquals(2, trace.phase)
        observe(third)
        assertEquals(3, trace.phase)
        observe(third.copy(argument = 0))
        assertEquals(3, trace.phase)
    }

}
