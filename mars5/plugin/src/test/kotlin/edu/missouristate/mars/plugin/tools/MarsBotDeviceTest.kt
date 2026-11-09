package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessOrigin.duringExecution
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.MarsBotWorld.Point
import edu.missouristate.mars.plugin.tools.MarsBotWorld.Stroke
import java.nio.file.Path
import java.util.function.Consumer
import kotlin.io.path.writeText
import kotlin.math.sqrt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class MarsBotDeviceTest {
    @Test
    fun `world retains corners and pen gaps while combining straight tracks`() {
        val start = MarsBotWorld(position = Point(2.0, 2.0), heading = 90, penDown = true)
        val east = start.moveOnePixel().moveOnePixel()
        assertEquals(Point(4.0, 2.0), east.position)
        assertEquals(listOf(Stroke(start.position, east.position)), east.trail!!.strokes().toList())
        val south = east.copy(heading = 180).moveOnePixel()
        assertEquals(Point(4.0, 3.0), south.position)
        val north = south.copy(heading = 0).moveOnePixel()
        assertEquals(Point(4.0, 2.0), north.position)
        assertEquals(listOf(Stroke(south.position, north.position), Stroke(east.position, south.position),
            Stroke(start.position, east.position)), north.trail!!.strokes().toList())
        val gap = north.copy(heading = 270, penDown = false).moveOnePixel()
        assertSame(north.trail, gap.trail)
        assertEquals(Point(3.0, 2.0), gap.position)
        val resumed = gap.copy(penDown = true).moveOnePixel()
        assertEquals(Stroke(gap.position, Point(2.0, 2.0)), resumed.trail!!.stroke)
        assertSame(north.trail, resumed.trail.previous)
    }

    @Test
    fun `fractional headings and board edges preserve the actual traveled path`() {
        val diagonal = MarsBotWorld(position = Point(5.0, 5.0), heading = 135).moveOnePixel()
        assertEquals(5.0 + sqrt(0.5), diagonal.position.x, 1e-9)
        assertEquals(5.0 + sqrt(0.5), diagonal.position.y, 1e-9)
        val nearWall = MarsBotWorld(position = Point(510.8, 510.0), heading = 135, penDown = true)
        val wall = nearWall.moveOnePixel()
        assertEquals(511.0, wall.position.x)
        val corner = wall.moveOnePixel()
        assertEquals(Point(511.0, 511.0), corner.position)
        assertEquals(2, corner.trail!!.strokes().count())
        assertSame(corner, corner.moveOnePixel())
        val origin = MarsBotWorld()
        assertSame(origin, origin.moveOnePixel())
        assertSame(origin.position, origin.copy(heading = 270).moveOnePixel().position)
    }

    @Test
    fun `default ports accept CPU control writes and publish coordinates on scheduled ticks`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val bot = MarsBotDevice()
            bot.setConnected(true)
            assertFalse(bot.snapshot().connected)
            assertNull(bot.snapshot().ports)
            bot.observe(session).use {
                val ports = bot.snapshot().ports!!
                assertEquals(0xffff8010.toInt(), ports.heading)
                assertEquals(0xffff8020.toInt(), ports.pen)
                assertEquals(0xffff8030.toInt(), ports.x)
                assertEquals(0xffff8040.toInt(), ports.y)
                assertEquals(0xffff8050.toInt(), ports.move)
                assertEquals(0, memory.countObservers())
                bot.instructionCompleted()
                memory.setWord(ports.x, 999)
                bot.setConnected(true)
                assertEquals(1, memory.countObservers())
                assertEquals(999, memory.getWord(ports.x))
                bot.instructionCompleted()
                assertEquals(0, memory.getWord(ports.x))
                memory.setWord(ports.heading, 15)
                duringExecution<Unit, Exception> {
                    memory.getWord(ports.heading)
                    memory.setWord(ports.heading + 4, 90)
                }
                assertEquals(0, bot.snapshot().world.heading)
                bot.setInstructionsPerTick(2)
                duringExecution<Unit, Exception> {
                    memory.setWord(ports.heading, 450)
                    memory.setByte(ports.pen, 1)
                    memory.setWord(ports.pen, 2)
                    memory.setByte(ports.move + 1, 1)
                }
                bot.instructionCompleted()
                assertEquals(Point(0.0, 0.0), bot.snapshot().world.position)
                assertEquals(90, bot.snapshot().world.heading)
                assertTrue(bot.snapshot().world.penDown)
                duringExecution<Unit, Exception> { memory.setWord(ports.move, 1) }
                bot.instructionCompleted()
                assertEquals(1, memory.getWord(ports.x))
                assertEquals(0, memory.getWord(ports.y))
                duringExecution<Unit, Exception> {
                    memory.setWord(ports.move, 0)
                    memory.setWord(ports.pen, 0)
                    memory.setWord(ports.x, 200)
                    memory.setWord(ports.y, 200)
                }
                bot.instructionCompleted()
                assertEquals(1, memory.getWord(ports.x))
                assertEquals(0, memory.getWord(ports.y))
                assertFalse(bot.snapshot().world.penDown)
                bot.reset()
                assertEquals(MarsBotWorld(), bot.snapshot().world)
                bot.instructionCompleted()
                assertEquals(0, memory.getWord(ports.x))
                bot.releaseView()
                assertFalse(bot.snapshot().connected)
                assertEquals(0, memory.countObservers())
                assertEquals(2, bot.snapshot().instructionsPerTick)
            }
            assertFalse(bot.snapshot().available)
        }
        val bot = MarsBotDevice()
        assertThrows(IllegalArgumentException::class.java) { bot.setInstructionsPerTick(0) }
        assertThrows(IllegalArgumentException::class.java) { bot.setInstructionsPerTick(1_000_001) }
    }

    @Test
    fun `compact ports and movement remain isolated between simultaneous sessions`() {
        MarsSessionService.createMarsComponent().context().use { first ->
            MarsSessionService.createMarsComponent().context().use { second ->
                first.setMemoryConfiguration("CompactTextAtZero")
                val bot = MarsBotDevice(true)
                val other = MarsBotDevice(true)
                val memory = first.memory
                memory.setWord(0x7fc0, 123)
                bot.observe(first).use {
                    other.observe(second).use {
                        val ports = bot.snapshot().ports!!
                        assertEquals(0x7f90, ports.heading)
                        assertEquals(0x7fd0, ports.move)
                        assertEquals(0, memory.getWord(ports.y))
                        bot.releaseView()
                        assertTrue(bot.snapshot().connected)
                        bot.setInstructionsPerTick(1)
                        duringExecution<Unit, Exception> {
                            memory.setWord(ports.heading, -180)
                            memory.setWord(ports.move, 1)
                        }
                        bot.instructionCompleted()
                        assertEquals(Point(0.0, 1.0), bot.snapshot().world.position)
                        assertEquals(1, memory.getWord(ports.y))
                        assertEquals(Point(0.0, 0.0), other.snapshot().world.position)
                        duringExecution<Unit, Exception> {
                            memory.setWord(ports.heading, 0)
                            memory.setWord(ports.x, 500)
                        }
                        bot.instructionCompleted()
                        assertEquals(0, memory.getWord(ports.x))
                        bot.instructionCompleted()
                        assertEquals(Point(0.0, 0.0), bot.snapshot().world.position)
                    }
                }
            }
        }
    }

    @Test
    fun `rewind restores the clock position words and pen history of real instructions`(@TempDir directory: Path) {
        val entry = directory.resolve("robot.asm").also {
            it.writeText("""
                lui ${'$'}t0, 0xffff
                ori ${'$'}t0, ${'$'}t0, 0x8000
                li ${'$'}t1, 90
                sw ${'$'}t1, 0x10(${'$'}t0)
                li ${'$'}t1, 1
                sw ${'$'}t1, 0x20(${'$'}t0)
                sw ${'$'}t1, 0x50(${'$'}t0)
                nop
                nop
                nop
                nop
            """.trimIndent())
        }
        MarsSessionService.createMarsComponent().context().use { session ->
            val program = session.assemble(listOf(entry))
            val bot = MarsBotDevice()
            bot.setInstructionsPerTick(2)
            bot.observe(session).use {
                val ports = bot.snapshot().ports!!
                fun step() {
                    program.executeSessionInstruction()
                    bot.instructionCompleted()
                }
                repeat(2) { step() }
                bot.setConnected(true)
                repeat(8) { step() }
                assertEquals(Point(2.0, 0.0), bot.snapshot().world.position)
                assertEquals(2, session.memory.getWord(ports.x))
                session.backStepper.backStep()
                assertEquals(Point(1.0, 0.0), bot.snapshot().world.trail!!.stroke.to)
                assertEquals(1, session.memory.getWord(ports.x))
                step()
                assertEquals(2, session.memory.getWord(ports.x))
                repeat(3) { session.backStepper.backStep() }
                assertTrue(bot.snapshot().world.moving)
                assertNull(bot.snapshot().world.trail)
                assertEquals(0, session.memory.getWord(ports.x))
                step()
                assertEquals(1, session.memory.getWord(ports.x))
                repeat(8) { session.backStepper.backStep() }
                assertEquals(MarsBotWorld(), bot.snapshot().world)
            }
        }
    }

    @Test
    fun `disconnect ignores a notice already being delivered from the old observer list`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val memory = session.memory
            val bot = MarsBotDevice(true)
            val heading = 0xffff8010.toInt()
            val disconnect = Consumer<MemoryAccessNotice> { bot.setConnected(false) }
            memory.addObserver(disconnect, heading)
            bot.observe(session).use {
                duringExecution<Unit, Exception> { memory.setWord(heading, 90) }
                assertEquals(0, bot.snapshot().world.heading)
                assertFalse(bot.snapshot().connected)
                assertEquals(1, memory.countObservers())
            }
            memory.deleteObserver(disconnect)
        }
    }
}
