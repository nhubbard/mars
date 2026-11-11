package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.BitmapCell
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Options
import java.util.function.Consumer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class BitmapDisplayTest {
    @Test
    fun `tracks complete RGB words through partial writes and reconnect`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val display = MemoryGridDisplay()
            val base = memory.dataBaseAddress
            display.configure(Options(64, 64, 32, 32))
            memory.setWord(base, 0x00abcdef)
            assertNull(display.bitmapCell(0))
            display.observe(memory).use {
                assertNull(display.snapshot().frame.image)
                assertEquals(BitmapCell(0, 0, base, 0), display.bitmapCell(0))
                assertEquals(0, memory.countObservers())
                display.setConnected(true)
                assertEquals(1, memory.countObservers())
                memory.setWord(base, 0xaa112233.toInt())
                memory.setByte(base + 1, 0x99)
                memory.setHalf(base + 2, 0x0044)
                memory.setWord(base + 4, 0x000000ff)
                memory.setWord(base + 8, 0x0000ff00)
                memory.setWord(base + 12, 0x00ff0000)
                val frame = display.snapshot().frame
                val image = requireNotNull(frame.image)
                assertEquals(0xff449933.toInt(), image.getRGB(0, 0))
                assertEquals(0xff0000ff.toInt(), image.getRGB(1, 0))
                assertEquals(0xff00ff00.toInt(), image.getRGB(0, 1))
                assertEquals(BitmapCell(0, 1, base + 8, 0x00ff00), display.bitmapCell(2))
                assertEquals(0xffff0000.toInt(), image.getRGB(1, 1))
                memory.getWord(base)
                memory.setWord(base + 16, 0x00ffffff)
                assertSame(frame, display.snapshot().frame)

                display.setConnected(false)
                assertEquals(0, memory.countObservers())
                memory.setWord(base, 0x00cafe00)
                assertSame(frame, display.snapshot().frame)
                display.setConnected(true)
                memory.setByte(base, 0xaa)
                assertEquals(0xffcafeaa.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                assertEquals(0xff449933.toInt(), image.getRGB(0, 0))
                display.releaseView()
                assertEquals(0, memory.countObservers())
            }
            assertEquals(0, memory.countObservers())
            display.reset()
            assertNull(display.snapshot().frame.image)
        }
    }

    @Test
    fun `reconfiguration drops queued old writes and clamps the memory map range`() {
        assertThrows(IllegalArgumentException::class.java) { Options(width = 63) }
        assertThrows(IllegalArgumentException::class.java) { Options(height = 63) }
        assertThrows(IllegalArgumentException::class.java) { Options(unitWidth = 3) }
        assertThrows(IllegalArgumentException::class.java) { Options(unitHeight = 3) }
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val display = MemoryGridDisplay(captureAtLaunch = true)
            val watcher = Consumer<MemoryAccessNotice> { display.configure(Options(1024, 64, base = Base.MMIO)) }
            memory.addObserver(watcher, memory.dataBaseAddress)
            display.observe(memory).use {
                memory.setWord(memory.dataBaseAddress, 0x00ff0000)
                assertNull(display.snapshot().frame.image)
                display.releaseView()
                memory.setWord(0xfffffffc.toInt(), 0x00ff00ff)
                assertEquals(0xffff00ff.toInt(), display.snapshot().frame.image!!.getRGB(1023, 15))
                assertEquals(BitmapCell(1023, 15, -4, 0xff00ff), display.bitmapCell(16_383))
                assertNull(display.bitmapCell(16_384))
                assertEquals(memory.heapBaseAddress, display.snapshot().addresses[Base.HEAP])
            }
            memory.deleteObserver(watcher)
            assertEquals(0, memory.countObservers())
        }
    }
}
