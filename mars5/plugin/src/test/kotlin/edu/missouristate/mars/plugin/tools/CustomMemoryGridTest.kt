package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Base
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Mode
import edu.missouristate.mars.plugin.tools.MemoryGridDisplay.Options
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CustomMemoryGridTest {
    @Test
    fun `custom bitmap tracks only its window and retains immutable frames`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val address = memory.dataBaseAddress + 0x100
            val display = MemoryGridDisplay(captureAtLaunch = true)
            display.configure(Options(64, 64, 32, 32, Base.CUSTOM, customAddress = address.toLong()))
            display.observe(memory).use {
                assertTrue(display.acceptsCustomAddress(address.toLong()))
                assertEquals(address.toLong(), display.bitmapCell(0)!!.address)
                memory.setWord(memory.dataBaseAddress, 0xffffff)
                assertNull(display.snapshot().frame.image)
                memory.setWord(address, 0xaa112233.toInt())
                val first = display.snapshot().frame
                assertEquals(0xff112233.toInt(), first.image!!.getRGB(0, 0))
                memory.getWord(address)
                memory.setWord(address + 16, 0xffffff)
                assertSame(first, display.snapshot().frame)
                memory.setByte(address + 1, 0x99)
                assertEquals(0xff119933.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                assertEquals(0xff112233.toInt(), first.image.getRGB(0, 0))
                display.setConnected(false)
                assertEquals(0, memory.countObservers())
                display.setConnected(true)
                assertEquals(1, memory.countObservers())
            }
            assertEquals(0, memory.countObservers())
        }
    }

    @Test
    fun `wide custom data and top unsigned word use bounded observers`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val memory = session.memory
            val address = memory.wideDataRange!!.first + 64
            val display = MemoryGridDisplay(captureAtLaunch = true)
            display.configure(Options(64, 64, 32, 32, Base.CUSTOM, customAddress = address))
            display.observe(memory).use {
                assertTrue(display.acceptsCustomAddress(memory.wideTextRange!!.first))
                session.editMemoryWord(address, 0x123456)
                assertEquals(0xff123456.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                assertEquals(address, display.bitmapCell(0)!!.address)
                display.configure(Options(64, 64, 32, 32, Base.CUSTOM, customAddress = 0xffff_fffcL))
                memory.setWord(-4, 0xabcdef)
                assertEquals(0xffabcdef.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                assertEquals(0xffff_fffcL, display.bitmapCell(0)!!.address)
                assertNull(display.bitmapCell(1))
            }
        }
    }

    @Test
    fun `narrow sessions reject unavailable wide windows and malformed addresses`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            session.selectTarget(MarsTarget.MARS32)
            val display = MemoryGridDisplay(captureAtLaunch = true)
            display.configure(Options(base = Base.CUSTOM, customAddress = 0x1_0000_0000L))
            display.observe(session.memory).use {
                assertFalse(display.acceptsCustomAddress(0x1_0000_0000L))
                assertNull(display.bitmapCell(0))
                assertEquals(0, session.memory.countObservers())
                val address = session.memory.dataBaseAddress.toLong() + 64
                assertEquals(address, parseGridAddress(address.toString(), display))
                assertEquals(address, parseGridAddress("0X${address.toString(16)}", display))
                for (text in listOf("0x100000000", "bad", "-4", "0x10010001")) assertNull(parseGridAddress(text, display))
                assertNull(parseGridAddress("0x10010000", null))
                display.configure(Options(base = Base.CUSTOM, customAddress = address))
                assertEquals(1, session.memory.countObservers())
            }
        }
    }

    @Test
    fun `reconfiguration discards old callbacks and sessions own their pixels`() {
        MarsSessionService.createMarsComponent().context().use { first ->
            MarsSessionService.createMarsComponent().context().use { second ->
                val a = first.memory.dataBaseAddress + 64
                val b = a + 64
                val one = MemoryGridDisplay(captureAtLaunch = true)
                val two = MemoryGridDisplay(captureAtLaunch = true)
                val options = Options(64, 64, 32, 32, Base.CUSTOM, customAddress = a.toLong())
                one.configure(options)
                two.configure(options)
                val reconfigure: (MemoryAccessNotice) -> Unit = { one.configure(options.copy(customAddress = b.toLong())) }
                first.memory.addObserver(reconfigure, a)
                try {
                    one.observe(first.memory).use {
                        two.observe(second.memory).use {
                            first.memory.setWord(a, 0xff0000)
                            assertNull(one.snapshot().frame.image)
                            assertNull(two.snapshot().frame.image)
                            first.memory.setWord(b, 0x00ff00)
                            second.memory.setWord(a, 0x0000ff)
                            assertEquals(0xff00ff00.toInt(), one.snapshot().frame.image!!.getRGB(0, 0))
                            assertEquals(0xff0000ff.toInt(), two.snapshot().frame.image!!.getRGB(0, 0))
                        }
                    }
                } finally { first.memory.deleteObserver(reconfigure) }
                assertEquals(0, first.memory.countObservers())
                assertEquals(0, second.memory.countObservers())
            }
        }
    }

    @Test
    fun `custom ranges reject invalid addresses and stop at a segment boundary`() {
        for (address in listOf(-4L, 1L)) assertThrows<IllegalArgumentException> { Options(base = Base.CUSTOM, customAddress = address) }
        assertNull(MemoryGridDisplay.customRange(-4, null, null))
        assertNull(MemoryGridDisplay.customRange(1, null, null))
        assertNull(MemoryGridDisplay.customRange(0x1_0000_0000L, null, null))
        assertEquals(0x7fff_fffcL..0x7fff_fffcL, MemoryGridDisplay.customRange(0x7fff_fffcL, null, null))
        assertEquals(0xffff_fffcL..0xffff_fffcL, MemoryGridDisplay.customRange(0xffff_fffcL, null, null))
        val data = 0x1_0000_0000L..0x1_0000_00ffL
        val text = 0x1_0040_0000L..0x1_0040_00ffL
        assertEquals(0x1_0000_0040L..data.last, MemoryGridDisplay.customRange(0x1_0000_0040L, data, text))
        assertEquals(text, MemoryGridDisplay.customRange(text.first, data, text))
        assertNull(MemoryGridDisplay.customRange(data.last + 1, data, text))
    }

    @Test
    fun `custom reference groups count execution accesses without changing memory`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val address = memory.dataBaseAddress + 64
            val display = MemoryGridDisplay(captureAtLaunch = true, mode = Mode.REFERENCES)
            display.configure(Options(64, 64, 32, 32, Base.CUSTOM, 2, address.toLong()))
            display.observe(memory).use {
                memory.setWord(address, 0x123456)
                AccessOrigin.duringExecution<Int, Exception> {
                    memory.getWord(address)
                    memory.setByte(address + 5, 1)
                    memory.getWord(address + 8)
                }
                assertEquals(2L, display.referenceCell(0)!!.count)
                assertEquals(1L, display.referenceCell(1)!!.count)
                assertEquals(0x123456, memory.getWordNoNotify(address))
                assertEquals(address.toLong(), display.referenceCell(0)!!.address)
            }
        }
    }

    @Test
    fun `initialized narrow and wide pixels are read without access events`() {
        for (wide in listOf(false, true)) {
            MarsSessionService.createMarsComponent().context().use { session ->
                session.selectTarget(MarsTarget.MARS64)
                val memory = session.memory
                val address = if (wide) memory.wideDataRange!!.first + 64 else memory.dataBaseAddress.toLong() + 64
                session.editMemoryWord(address, 0xaa112233.toInt())
                session.editMemoryWord(address + 4, 0x445566)
                var notices = 0
                val observer: (MemoryAccessNotice) -> Unit = { notices++ }
                memory.addAddressObserver(observer)
                try {
                    val display = MemoryGridDisplay(captureAtLaunch = true)
                    display.configure(Options(64, 64, 32, 32, Base.CUSTOM, customAddress = address, captureInitial = true))
                    display.observe(memory).use {
                        val first = display.snapshot().frame
                        assertEquals(0xff112233.toInt(), first.image!!.getRGB(0, 0))
                        assertEquals(0xff445566.toInt(), first.image.getRGB(1, 0))
                        assertEquals(0, notices)
                        assertEquals(0xaa112233.toInt(), memory.inspectWord(address))
                        session.editMemoryWord(address + 4, 0x00ff00)
                        assertEquals(1, notices)
                        assertEquals(0xff00ff00.toInt(), display.snapshot().frame.image!!.getRGB(1, 0))
                        assertEquals(0xff445566.toInt(), first.image.getRGB(1, 0))
                    }
                } finally { memory.deleteAddressObserver(observer) }
            }
        }
    }

    @Test
    fun `initial capture respects connection state unavailable windows and range ends`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            session.selectTarget(MarsTarget.MARS32)
            val memory = session.memory
            val address = memory.dataBaseAddress.toLong() + 64
            val options = Options(64, 64, 32, 32, Base.CUSTOM, customAddress = address, captureInitial = true)
            memory.setWord(address.toInt(), 0x112233)
            val inactive = MemoryGridDisplay()
            inactive.configure(options)
            inactive.observe(memory).use {
                assertNull(inactive.snapshot().frame.image)
                inactive.setConnected(true)
                assertNull(inactive.snapshot().frame.image)
            }
            val display = MemoryGridDisplay(captureAtLaunch = true)
            display.configure(options.copy(customAddress = 0x1_0000_0000L))
            display.observe(memory).use { assertNull(display.snapshot().frame.image) }
            display.configure(options.copy(customAddress = 0))
            display.observe(memory).use { assertEquals(0xff000000.toInt(), display.snapshot().frame.image!!.getRGB(0, 0)) }
            memory.setWord(-4, 0xabcdef)
            display.configure(options.copy(customAddress = 0xffff_fffcL))
            display.observe(memory).use {
                assertEquals(0xffabcdef.toInt(), display.snapshot().frame.image!!.getRGB(0, 0))
                assertEquals(0xff000000.toInt(), display.snapshot().frame.image!!.getRGB(1, 0))
                assertNull(display.bitmapCell(1))
            }
            val references = MemoryGridDisplay(mode = Mode.REFERENCES)
            val previous = references.controls().options
            assertThrows<IllegalArgumentException> { references.configure(options) }
            assertEquals(previous, references.controls().options)
        }
    }

}
