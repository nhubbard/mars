package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessOrigin.Companion.duringExecution
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice.Delay
import edu.missouristate.mars.plugin.tools.KeyboardDisplayDevice.Options
import edu.missouristate.mars.plugin.tools.MmioTerminalDisplay.Geometry
import java.nio.file.Path
import java.util.function.Consumer
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KeyboardDisplayDeviceTest {
    @Test
    fun `receiver applies one byte at a boundary and acknowledges only a CPU data read`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            session.setMemoryConfiguration("CompactDataAtZero")
            val memory = session.memory
            val start = memory.memoryMapBaseAddress
            val device = KeyboardDisplayDevice(true, 10)
            device.sendKey('X')
            assertNull(device.snapshot().base)
            memory.setWord(start, 0x82)
            memory.setWord(start + 8, 0x40)
            device.observe(session).use {
                assertEquals(start, device.snapshot().base)
                assertEquals(0x41, memory.getWord(start + 8))
                device.releaseView()
                assertTrue(device.snapshot().connected)
                device.sendKey('A')
                device.sendKey('\u0142')
                assertEquals(0, memory.getWord(start + 4))
                device.instructionCompleted(session)
                assertEquals(0x83, memory.getWord(start))
                assertEquals(0x42, memory.getWord(start + 4))
                assertEquals(0x40, session.pendingDeviceInterrupts())
                device.instructionCompleted(session)
                assertEquals(0x83, memory.getWord(start))
                duringExecution<Unit, Exception> {
                    memory.getByte(start + 5)
                    memory.setWord(start + 4, 0x43)
                }
                device.instructionCompleted(session)
                assertEquals(0x83, memory.getWord(start))
                duringExecution<Unit, Exception> { memory.getByte(start + 4) }
                device.instructionCompleted(session)
                assertEquals(0x82, memory.getWord(start))
                assertEquals(0x43, memory.getWord(start + 4))
                session.setDeviceInterrupts(0x40, 0)
                memory.setWord(start, 0)
                device.sendKey('D')
                device.instructionCompleted(session)
                assertEquals(1, memory.getWord(start))
                assertEquals(0, session.pendingDeviceInterrupts())
            }
            assertFalse(device.snapshot().available)
            assertEquals(0, memory.countObservers())
        }
    }

    @Test
    fun `transmitter ignores writes while busy and releases ready after the configured instruction count`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val start = memory.memoryMapBaseAddress
            val tx = start + 12
            val device = KeyboardDisplayDevice(true, 1)
            device.configure(Options(instructions = 2))
            device.observe(session).use {
                memory.setWord(start + 8, 3)
                memory.setWord(tx, 'Z'.code)
                duringExecution<Unit, Exception> { memory.getWord(tx) }
                device.instructionCompleted(session)
                assertEquals(0, device.snapshot().remaining)
                duringExecution<Unit, Exception> {
                    memory.setByte(tx + 1, 'Y'.code)
                    memory.setWord(tx, 'A'.code)
                    memory.setWord(tx, 'B'.code)
                }
                device.instructionCompleted(session)
                assertEquals(2, device.snapshot().remaining)
                assertEquals("", device.snapshot().display.text)
                assertEquals(2, memory.getWord(start + 8))
                duringExecution<Unit, Exception> { memory.setWord(tx, 'C'.code) }
                device.instructionCompleted(session)
                assertEquals(1, device.snapshot().remaining)
                device.instructionCompleted(session)
                assertEquals("A", device.snapshot().display.text)
                assertEquals(3, memory.getWord(start + 8))
                assertEquals(0x80, session.pendingDeviceInterrupts())
                session.setDeviceInterrupts(0x80, 0)
                session.coprocessor0.getRegister(STATUS)!!.setValue(2)
                device.configure(Options(instructions = 1, displayAfterDelay = false))
                duringExecution<Unit, Exception> { memory.setByte(tx, 'D'.code) }
                device.instructionCompleted(session)
                assertEquals("AD", device.snapshot().display.text)
                device.instructionCompleted(session)
                assertEquals("AD", device.snapshot().display.text)
                assertEquals(0, session.pendingDeviceInterrupts())
            }
        }
    }

    @Test
    fun `receiver and transmitter can raise interrupts on the same boundary as another device`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val start = memory.memoryMapBaseAddress
            val device = KeyboardDisplayDevice(true, 1)
            device.configure(Options(instructions = 1))
            device.observe(session).use {
                memory.setWord(start, 2)
                memory.setWord(start + 8, 3)
                duringExecution<Unit, Exception> { memory.setWord(start + 12, 'A'.code) }
                device.instructionCompleted(session)
                device.sendKey('K')
                session.setDeviceInterrupts(0x100, 0x100)
                device.instructionCompleted(session)
                assertEquals(0x1c0, session.pendingDeviceInterrupts())
                assertEquals('K'.code, memory.getWord(start + 4))
                assertEquals("A", device.snapshot().display.text)
            }
        }
    }

    @Test
    fun `reset and connection changes take effect on the worker and release subscriptions`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val start = memory.memoryMapBaseAddress
            val device = KeyboardDisplayDevice()
            device.setConnected(true)
            assertFalse(device.snapshot().connected)
            device.observe(session).use {
                assertEquals(0, memory.countObservers())
                device.instructionCompleted(session)
                device.sendKey('X')
                device.setConnected(true)
                assertEquals(2, memory.countObservers())
                duringExecution<Unit, Exception> { memory.setWord(start + 12, 'X'.code) }
                device.instructionCompleted(session)
                assertEquals("", device.snapshot().display.text)
                assertEquals(0, memory.getWord(start + 4))
                memory.setWord(start + 8, 0)
                duringExecution<Unit, Exception> { memory.setWord(start + 12, 'X'.code) }
                device.instructionCompleted(session)
                assertEquals(0, device.snapshot().remaining)
                device.reset()
                device.instructionCompleted(session)
                assertEquals(1, memory.getWord(start + 8))
                device.configure(Options(instructions = 1))
                duringExecution<Unit, Exception> { memory.setWord(start + 12, 'Q'.code) }
                device.instructionCompleted(session)
                device.instructionCompleted(session)
                assertEquals("Q", device.snapshot().display.text)
                assertEquals(0, session.pendingDeviceInterrupts())
                val disconnect = Consumer<MemoryAccessNotice> { device.releaseView() }
                device.setConnected(false)
                memory.addObserver(disconnect, start + 12)
                device.setConnected(true)
                device.instructionCompleted(session)
                duringExecution<Unit, Exception> { memory.setWord(start + 12, 'X'.code) }
                assertFalse(device.snapshot().connected)
                assertEquals("Q", device.snapshot().display.text)
                assertEquals(1, memory.countObservers())
                memory.deleteObserver(disconnect)
            }
        }
    }

    @Test
    fun `rewinding real instructions restores the ready bits output and random sequence without replaying host input`(
        @TempDir directory: Path,
    ) {
        val entry = directory.resolve("terminal.asm").also {
            it.writeText("nop\nlui \$t0, 0xffff\naddiu \$t1, \$zero, 65\nnop\nsw \$t1, 12(\$t0)\n" +
                "nop\n".repeat(12))
        }
        for (delay in listOf(Delay.UNIFORM, Delay.NORMAL)) {
            MarsSessionService.createMarsComponent().context().use { session ->
                val program = session.assemble(listOf(entry))
                val device = KeyboardDisplayDevice(seed = 42)
                val memory = session.memory
                val start = memory.memoryMapBaseAddress
                device.configure(Options(delay, 2))
                device.observe(session).use {
                    fun step() {
                        program.executeSessionInstruction()
                        device.instructionCompleted(session)
                    }
                    step()
                    val resized = Geometry(12, 4)
                    device.reset(resized)
                    step()
                    session.backStepper.backStep()
                    assertEquals(resized, device.snapshot().display.geometry)
                    session.backStepper.backStep()
                    assertEquals(Geometry(), device.snapshot().display.geometry)
                    repeat(2) { step() }
                    device.setConnected(true)
                    repeat(2) { step() }
                    device.sendKey('K')
                    step()
                    assertEquals(1, memory.getWord(start))
                    assertEquals('K'.code, memory.getWord(start + 4))
                    val duration = device.snapshot().remaining
                    assertTrue(duration in 1..12)
                    repeat(duration) { step() }
                    assertEquals("A", device.snapshot().display.text)
                    session.backStepper.backStep()
                    assertEquals("", device.snapshot().display.text)
                    assertEquals(1, device.snapshot().remaining)
                    assertEquals(0, memory.getWord(start + 8))
                    repeat(duration) { session.backStepper.backStep() }
                    assertEquals(0, memory.getWord(start + 4))
                    assertEquals(0, memory.getWord(start))
                    assertEquals(1, memory.getWord(start + 8))
                    step()
                    assertEquals(duration, device.snapshot().remaining)
                    assertEquals(0, memory.getWord(start))
                    repeat(5) { session.backStepper.backStep() }
                    assertEquals("", device.snapshot().display.text)
                    assertEquals(0, device.snapshot().remaining)
                }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { Options(instructions = 0) }
        assertThrows(IllegalArgumentException::class.java) { Options(instructions = 1_000_001) }
    }

    @Test
    fun `display supports clear cursor addressing line wrap and shared sequential output`() {
        assertEquals("", MmioTerminalDisplay().text)
        val plain = MmioTerminalDisplay().transmit('x'.code).transmit('\n'.code)
        assertEquals("x\n", plain.text)
        assertEquals("", plain.transmit(12).text)
        val grid = plain.transmit((79 shl 20) or 7).transmit('A'.code).transmit('B'.code)
        assertEquals('A', grid.text.lines()[0].last())
        assertEquals('B', grid.text.lines()[1].first())
        assertEquals('C', grid.transmit('\r'.code).transmit('C'.code).text.lines()[1].first())
        assertEquals('D', grid.transmit('\n'.code).transmit('D'.code).text.lines()[2].first())
        val corner = grid.transmit(-249).transmit('C'.code).transmit('D'.code)
        assertEquals('D', corner.text.lines().last().last())
        val cleared = corner.transmit(12)
        assertEquals(0, cleared.cursor)
        assertEquals(" ".repeat(80), cleared.text.lines().last())
        var full = MmioTerminalDisplay()
        repeat(9000) { full = full.transmit('A'.code) }
        assertEquals("A".repeat(9000), full.text)
        assertEquals("A".repeat(9000) + 'B', full.transmit('B'.code).text)
    }

    @Test
    fun `custom geometry defines the grid wrap clamp and caret without changing sequential output`() {
        assertThrows(IllegalArgumentException::class.java) { Geometry(columns = 0) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(columns = 257) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(rows = 0) }
        assertThrows(IllegalArgumentException::class.java) { Geometry(rows = 129) }
        val dimensions = Geometry(3, 2)
        val sequential = MmioTerminalDisplay(dimensions).transmit('x'.code)
        assertEquals("x", sequential.text)
        assertEquals(dimensions, sequential.transmit(12).geometry)
        val grid = sequential.transmit((2 shl 20) or 7).transmit('A'.code).transmit('B'.code)
        assertEquals("  A\nB  ", grid.text)
        assertEquals(5, grid.caretOffset)
        assertEquals("  A\nB Z", grid.transmit(-249).transmit('Z'.code).text)
        assertEquals("   \n   ", grid.transmit(12).text)
    }
}
