package edu.missouristate.mars

import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.simulator.DelayedBranchState
import edu.missouristate.mars.simulator.ProgramArgumentList
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class SimulatorLaunchStateTest {
    @Test
    fun `registration in a delay slot extends the delay and preserves the first jump`() {
        val branch = DelayedBranchState()
        val cleared = branch.snapshot()
        branch.trigger()
        assertEquals(cleared, branch.snapshot())

        branch.register(0x00401000, true)
        val registered = branch.snapshot()
        branch.register(0x00402000)
        assertEquals(registered, branch.snapshot())
        branch.trigger()
        val triggered = branch.snapshot()
        assertTrue(branch.isTriggered)

        branch.register(0x00403000)
        assertTrue(branch.isRegistered)
        assertFalse(branch.isTriggered)
        assertTrue(branch.isJump)
        assertEquals(0x00401000, branch.branchTargetAddress)

        branch.clear()
        assertEquals(cleared, branch.snapshot())
        branch.restore(triggered)
        assertEquals(triggered, branch.snapshot())
        assertThrows(IllegalArgumentException::class.java) {
            branch.restore(DelayedBranchState.Snapshot(-1, 0, false))
        }
        assertEquals(triggered, branch.snapshot())
        branch.restore(DelayedBranchState.Snapshot(cleared.state, 0x00401000, true))
        assertEquals(cleared, branch.snapshot())
    }

    @Test
    fun `launch argument sources preserve their boundaries when copied to the stack`() {
        val list = arrayListOf("skip", "one two", "")
        val copied = ProgramArgumentList(list, 1)
        list.clear()

        for ((arguments, expected) in listOf(
            ProgramArgumentList("one\ttwo\nthree\rfour\u000Cfive") to listOf("one", "two", "three", "four", "five"),
            ProgramArgumentList(arrayOf("skip", "one two", ""), 1) to listOf("one two", ""),
            copied to listOf("one two", ""),
        )) {
            TestSessions.create().use { session ->
                arguments.storeProgramArguments(session)
                val word = MemoryState.WORD_LENGTH_BYTES
                val memory = session.memory
                val argv = session.registerFile.getUserRegister("\$a1").getValueNoNotify()
                val stack = session.registerFile.getUserRegister("\$sp").getValueNoNotify()
                assertEquals(stack + word, argv)
                assertEquals(expected.size, memory.getWord(stack))
                assertEquals(expected.size, session.registerFile.getUserRegister("\$a0").getValueNoNotify())
                for ((index, argument) in expected.withIndex()) {
                    val start = memory.getWord(argv + index * word)
                    assertEquals(argument.map(Char::code) + 0, (0..argument.length).map { memory.getByte(start + it) })
                }
                assertEquals(0, memory.getWord(argv + expected.size * word))
            }
        }
    }

    @Test
    fun `empty arguments and cancellation before layout leave memory and stack registers alone`() {
        TestSessions.create().use { session ->
            val memory = session.memory
            memory.set(memory.stackBaseAddress, 0x5a, 1)
            val stack = session.registerFile.getUserRegister("\$sp").getValueNoNotify()
            ProgramArgumentList(arrayListOf("ignored"), 2).storeProgramArguments(session) {
                error("Empty arguments must return before checking cancellation")
            }
            assertThrows(CancellationException::class.java) {
                ProgramArgumentList(arrayOf("argument")).storeProgramArguments(session) {
                    throw CancellationException()
                }
            }
            assertEquals(0x5a, memory.getByte(memory.stackBaseAddress))
            assertEquals(stack, session.registerFile.getUserRegister("\$sp").getValueNoNotify())
            assertEquals(0, session.registerFile.getUserRegister("\$a0").getValueNoNotify())
        }
    }
}
