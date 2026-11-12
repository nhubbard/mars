package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.ProcessingException
import edu.missouristate.mars.mips.hardware.AccessOrigin.Companion.duringExecution
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.MarsSessionService
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DigitalLabDeviceTest {
    @Test
    fun `only connected instruction writes drive the display and hardware ports`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            session.setMemoryConfiguration("CompactDataAtZero")
            val memory = session.memory
            val start = memory.memoryMapBaseAddress + 0x10
            val lab = DigitalLabDevice()
            lab.setConnected(true)
            assertFalse(lab.snapshot().available)
            lab.observe(session).use {
                assertEquals(0, memory.countObservers())
                lab.setConnected(true)
                assertEquals(1, memory.countObservers())
                memory.setWord(start, 0x0181063f)
                assertEquals(0, lab.snapshot().right)
                duringExecution<Unit, Exception> { memory.getWord(start) }
                assertEquals(0, lab.snapshot().left)
                duringExecution<Unit, Exception> { memory.setWord(start, 0x0181063f) }
                assertEquals(0x3f, lab.snapshot().right)
                assertEquals(0x06, lab.snapshot().left)
                assertEquals(30, lab.snapshot().timerRemaining)
                duringExecution<Unit, Exception> {
                    memory.setHalf(start, 0x4f5b)
                    memory.setByte(start + 3, 0)
                }
                assertEquals(0x5b, lab.snapshot().right)
                assertEquals(0x4f, lab.snapshot().left)
                assertEquals(0, lab.snapshot().timerRemaining)
                lab.releaseView()
                assertFalse(lab.snapshot().connected)
                assertEquals(0, memory.countObservers())
                lab.toggleKey(0)
                lab.instructionCompleted(session)
                assertNull(lab.snapshot().key)
                lab.reset()
                assertEquals(0, lab.snapshot().right)
            }
            assertFalse(lab.snapshot().available)
            assertEquals(0, memory.countObservers())
        }
    }

    @Test
    fun `latched keys return row and column codes and release at a worker boundary`() {
        MarsSessionService.createMarsComponent().context().use { first ->
            MarsSessionService.createMarsComponent().context().use { second ->
                val memory = first.memory
                val start = memory.memoryMapBaseAddress + 0x10
                val lab = DigitalLabDevice(true)
                val other = DigitalLabDevice(true)
                lab.toggleKey(6)
                assertNull(lab.snapshot().key)
                lab.observe(first).use {
                    other.observe(second).use {
                        lab.releaseView()
                        assertTrue(lab.snapshot().connected)
                        duringExecution<Unit, Exception> { memory.setByte(start + 2, 1) }
                        lab.toggleKey(6)
                        lab.instructionCompleted(first)
                        assertEquals(6, lab.snapshot().key)
                        assertEquals(0, memory.getByte(start + 4))
                        duringExecution<Unit, Exception> { memory.setByte(start + 2, 2) }
                        assertEquals(0, memory.getByte(start + 4))
                        lab.instructionCompleted(first)
                        assertEquals(0x42, memory.getByte(start + 4))
                        assertNull(other.snapshot().key)
                        assertEquals(0, second.memory.getByte(start + 4))
                        lab.toggleKey(8)
                        assertNull(lab.snapshot().key)
                        assertEquals(0x42, memory.getByte(start + 4))
                        lab.instructionCompleted(first)
                        assertEquals(0, memory.getByte(start + 4))
                    }
                }
            }
        }
    }

    @Test
    fun `timer and keypad interrupt together at the next boundary when EXL is clear`(@TempDir directory: Path) {
        val path = directory.resolve("irq.asm").also { it.writeText("nop\nnop\n") }
        for (masked in listOf(false, true)) {
            MarsSessionService.createMarsComponent().context().use { session ->
                session.assemble(listOf(path))
                val memory = session.memory
                val start = memory.memoryMapBaseAddress + 0x10
                val lab = DigitalLabDevice(true)
                lab.observe(session).use {
                    if (masked) session.coprocessor0.getRegister(STATUS)!!.setValue(2)
                    duringExecution<Unit, Exception> { memory.setHalf(start + 2, 0x0181) }
                    repeat(29) { lab.instructionCompleted(session) }
                    assertEquals(1, lab.snapshot().timerRemaining)
                    lab.toggleKey(2)
                    lab.instructionCompleted(session)
                    assertEquals(0x41, memory.getByte(start + 4))
                    assertEquals(30, lab.snapshot().timerRemaining)
                    val stop = session.simulator().runAsync(1, null).get(5, TimeUnit.SECONDS)
                    assertEquals(if (masked) StopEvent.Reason.STEP_LIMIT else StopEvent.Reason.EXCEPTION, stop.reason)
                    assertEquals(if (masked) 0 else 0xc00, session.coprocessor0.getRegister(CAUSE)!!.valueNoNotify)
                }
            }
        }
    }

    @Test
    fun `backstep restores the timer and only this device's pending interrupts`(@TempDir directory: Path) {
        val path = directory.resolve("timer.asm").also { it.writeText("nop\n".repeat(31)) }
        MarsSessionService.createMarsComponent().context().use { session ->
            val program = session.assemble(listOf(path))
            val memory = session.memory
            val timer = memory.memoryMapBaseAddress + 0x13
            val lab = DigitalLabDevice(true)
            lab.observe(session).use {
                duringExecution<Unit, Exception> { memory.setByte(timer, 1) }
                fun step() {
                    program.executeSessionInstruction()
                    lab.instructionCompleted(session)
                }
                repeat(30) { step() }
                assertEquals(30, lab.snapshot().timerRemaining)
                assertEquals(0x100, session.pendingDeviceInterrupts())

                session.setDeviceInterrupts(0x40, 0x40)
                session.backStepper.backStep()
                assertEquals(1, lab.snapshot().timerRemaining)
                assertEquals(0x40, session.pendingDeviceInterrupts())
                session.setDeviceInterrupts(0x40, 0)
                step()
                assertThrows(ProcessingException::class.java) { program.executeSessionInstruction() }
                assertEquals(0, session.pendingDeviceInterrupts())
                assertEquals(0x400, session.coprocessor0.getRegister(CAUSE)!!.valueNoNotify)
                session.backStepper.backStep()
                assertEquals(0x100, session.pendingDeviceInterrupts())
                assertEquals(0, session.coprocessor0.getRegister(CAUSE)!!.valueNoNotify)
            }
        }
    }

    @Test
    fun `backstep across connection restores the old display and leaves unrelated port data alone`(@TempDir directory: Path) {
        val path = directory.resolve("display.asm").also {
            it.writeText($$"lui $t0, 0xffff\naddiu $t1, $zero, 0x3f\nsb $t1, 0x10($t0)\nnop\n")
        }
        MarsSessionService.createMarsComponent().context().use { session ->
            val program = session.assemble(listOf(path))
            val memory = session.memory
            val output = memory.memoryMapBaseAddress + 0x14
            val lab = DigitalLabDevice()
            lab.observe(session).use {
                program.executeSessionInstruction()
                lab.setConnected(true)
                program.executeSessionInstruction()
                program.executeSessionInstruction()
                lab.instructionCompleted(session)
                assertEquals(0x3f, lab.snapshot().right)
                session.backStepper.backStep()
                assertEquals(0, lab.snapshot().right)
                session.backStepper.backStep()
                memory.setByte(output, 0xab)
                session.backStepper.backStep()
                assertEquals(0xab, memory.getByte(output))
                assertTrue(lab.snapshot().connected)
            }
        }
    }

    @Test
    fun `a disconnected device ignores an in-flight notice from the previous observer list`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val start = memory.memoryMapBaseAddress + 0x10
            val lab = DigitalLabDevice(true)
            val disconnect = Consumer<MemoryAccessNotice> { lab.setConnected(false) }
            memory.addObserver(disconnect, start)
            lab.observe(session).use {
                duringExecution<Unit, Exception> { memory.setByte(start, 0xff) }
                assertEquals(0, lab.snapshot().right)
                assertEquals(1, memory.countObservers())
            }
            memory.deleteObserver(disconnect)
        }
    }
}
