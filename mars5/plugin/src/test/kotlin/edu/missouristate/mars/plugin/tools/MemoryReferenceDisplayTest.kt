package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Options
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.ReferenceCell
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class MemoryReferenceDisplayTest {
    @Test
    fun `counts execution accesses by word group and recolors existing cells`() {
        assertThrows(IllegalArgumentException::class.java) { Options(wordsPerCell = 3) }
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val display = MemoryGridDisplay(captureAtLaunch = true, mode = Mode.REFERENCES)
            val base = memory.dataBaseAddress
            assertNull(display.referenceCell(0))
            display.configure(Options(64, 64, 32, 32, wordsPerCell = 2))
            display.observe(memory).use {
                memory.setWord(base, 7)
                memory.getWord(base)
                assertEquals(ReferenceCell(base, 0), display.referenceCell(0))
                AccessOrigin.duringExecution<Int, Exception> {
                    memory.getByte(base + 1)
                    memory.setHalf(base + 4, 7)
                    memory.getWord(base + 8)
                    memory.getWord(base + 32)
                }
                assertEquals(ReferenceCell(base, 2), display.referenceCell(0))
                assertEquals(ReferenceCell(base + 8, 1), display.referenceCell(1))
                val before = display.snapshot().frame.image!!
                assertEquals(0xff00ff00.toInt(), before.getRGB(0, 0))
                assertEquals(0xff0000ff.toInt(), before.getRGB(1, 0))
                display.setColor(1, 0x00ff00ff)
                val after = display.snapshot().frame.image!!
                assertEquals(0xffff00ff.toInt(), after.getRGB(1, 0))
                assertEquals(0xff0000ff.toInt(), before.getRGB(1, 0))
                display.setColor(0, 0x00010203)
                display.reset()
                assertEquals(ReferenceCell(base, 0), display.referenceCell(0))
                assertEquals(0xff010203.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                display.setColor(0, 0x00020304)
                assertEquals(0xff020304.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                display.configure(Options(1024, 64, base = Base.MMIO, wordsPerCell = 2))
                assertNull(display.referenceCell(8192))
            }
            assertEquals(0, memory.countObservers())
        }
    }
}
