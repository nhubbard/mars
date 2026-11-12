package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.api.StopEvent.Reason.BREAKPOINT
import edu.missouristate.mars.api.StopEvent.Reason.EXCEPTION
import edu.missouristate.mars.api.StopEvent.Reason.STEP_LIMIT
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.Companion.duringExecution
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.MarsSessionService
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.AUTHENTICATION
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.COLOR
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.ENERGY
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.GAME_ON
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.IDENTITY
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.LOCATION_ARRAY
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.LOCATION_COUNT
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.MOVE_READY
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.PLAYER_BASE
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.PLAYER_STRIDE
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.POSITION_X
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.POSITION_Y
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.TARGET_X
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.TARGET_Y
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.TASK_ARRAY
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.TASK_COMPLETE
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.TASK_SIZE_OFFSET
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Companion.TURNS
import edu.missouristate.mars.plugin.tools.ScavengerHuntDevice.Diagnostic
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Point
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Rejection
import java.nio.file.Path
import java.util.concurrent.TimeUnit.SECONDS
import java.util.function.Consumer
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ScavengerHuntDeviceTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `administrator initialization publishes every player region and rewinds a double store`() {
        withProgram("""
            lui ${'$'}s0, 0xffff
            ori ${'$'}s0, ${'$'}s0, 0xe000
            li ${'$'}t0, 5
            mtc1 ${'$'}t0, ${'$'}f2
            li ${'$'}t0, 123
            mtc1 ${'$'}t0, ${'$'}f3
            sdc1 ${'$'}f2, 8(${'$'}s0)
            lw ${'$'}t1, 8(${'$'}s0)
            nop
        """) { session, device ->
            val memory = session.memory
            memory.setWord(GAME_ON, 9)
            memory.setWord(TURNS, 7)
            memory.setWord(IDENTITY, 501)
            memory.setWord(AUTHENTICATION, 70)
            memory.setWord(PLAYER_BASE + POSITION_X, 84)
            memory.setWord(PLAYER_BASE + TASK_ARRAY, 16)
            assertNull(device.snapshot().game.round)
            step(session, 6)
            val before = device.snapshot()
            step(session)
            val started = device.snapshot()
            val round = requireNotNull(started.game.round)
            assertEquals(7L, round.firstInstruction)
            assertEquals(1, memory.getWord(GAME_ON))
            assertEquals(123, memory.getWord(TURNS))
            assertEquals(123, started.game.turnsRemaining)
            assertEquals(999, memory.getWord(IDENTITY))
            assertEquals(0, memory.getWord(AUTHENTICATION))
            assertNull(started.diagnostic)
            round.players.forEachIndexed { index, player ->
                val base = PLAYER_BASE + index * PLAYER_STRIDE
                assertEquals(255, memory.getWord(base + POSITION_X))
                assertEquals(255, memory.getWord(base + POSITION_Y))
                assertEquals(255, memory.getWord(base + TARGET_X))
                assertEquals(255, memory.getWord(base + TARGET_Y))
                assertEquals(0, memory.getWord(base + MOVE_READY))
                assertEquals(20, memory.getWord(base + ENERGY))
                assertEquals(7, memory.getWord(base + LOCATION_COUNT))
                assertEquals(0, memory.getWord(base + COLOR))
                assertEquals(20, memory.getWord(base + TASK_SIZE_OFFSET))
                assertEquals(0, memory.getWord(base + TASK_COMPLETE))
                assertEquals(round.locations, List(7) {
                    Point(memory.getWord(base + LOCATION_ARRAY + 8 * it), memory.getWord(base + LOCATION_ARRAY + 8 * it + 4))
                })
                assertEquals(player.task, List(20) { memory.getWord(base + TASK_ARRAY + 4 * it) })
            }
            backstep(session)
            assertEquals(before, device.snapshot())
            assertEquals(9, memory.getWord(GAME_ON))
            assertEquals(7, memory.getWord(TURNS))
            assertEquals(501, memory.getWord(IDENTITY))
            assertEquals(70, memory.getWord(AUTHENTICATION))
            assertEquals(84, memory.getWord(PLAYER_BASE + POSITION_X))
            assertEquals(16, memory.getWord(PLAYER_BASE + TASK_ARRAY))
            step(session)
            assertEquals(started, device.snapshot())
            step(session)
            assertEquals(1, session.registerFile.getValue(9))
            backstep(session)
            assertEquals(started, device.snapshot())
            assertEquals(0, session.registerFile.getValue(9))
        }
    }

    @Test
    fun `program commands move and sort through MMIO and rewind their acknowledgements`() {
        withProgram("""
            lui ${'$'}s0, 0xffff
            ori ${'$'}s0, ${'$'}s0, 0xe000
            lui ${'$'}s1, 0xffff
            ori ${'$'}s1, ${'$'}s1, 0x8000
            li ${'$'}t0, 1
            sw ${'$'}t0, 8(${'$'}s0)
            sw ${'$'}t0, 0(${'$'}s0)
            sw ${'$'}zero, 4(${'$'}s0)
            li ${'$'}t1, 257
            sw ${'$'}t1, 8(${'$'}s1)
            sw ${'$'}t0, 0x10(${'$'}s1)
            li ${'$'}t1, 0xff
            sw ${'$'}t1, 0x1c(${'$'}s1)
            lw ${'$'}t2, 0x20(${'$'}s1)
            addiu ${'$'}t2, ${'$'}t2, -1
        pass:
            addu ${'$'}t3, ${'$'}zero, ${'$'}t2
            addiu ${'$'}t4, ${'$'}s1, 0x128
        pair:
            lw ${'$'}t5, 0(${'$'}t4)
            lw ${'$'}t6, 4(${'$'}t4)
            ble ${'$'}t5, ${'$'}t6, keep
            nop
            sw ${'$'}t6, 0(${'$'}t4)
            sw ${'$'}t5, 4(${'$'}t4)
        keep:
            addiu ${'$'}t4, ${'$'}t4, 4
            addiu ${'$'}t3, ${'$'}t3, -1
            bgtz ${'$'}t3, pair
            nop
            addiu ${'$'}t2, ${'$'}t2, -1
            bgtz ${'$'}t2, pass
            nop
        submit:
            sw ${'$'}t0, 0x124(${'$'}s1)
            nop
        """) { session, device ->
            val memory = session.memory
            step(session, 10)
            assertEquals(0, memory.getWord(IDENTITY))
            assertEquals(1, device.snapshot().game.authenticationSequence)
            memory.setWord(PLAYER_BASE + MOVE_READY, 33)
            val beforeMove = device.snapshot()
            step(session)
            assertEquals(Point(257, 255), device.snapshot().game.round!!.players[0].position)
            assertEquals(257, memory.getWord(PLAYER_BASE + POSITION_X))
            assertEquals(19, memory.getWord(PLAYER_BASE + ENERGY))
            assertEquals(0, memory.getWord(PLAYER_BASE + MOVE_READY))
            backstep(session)
            assertEquals(beforeMove, device.snapshot())
            assertEquals(255, memory.getWord(PLAYER_BASE + POSITION_X))
            assertEquals(20, memory.getWord(PLAYER_BASE + ENERGY))
            assertEquals(33, memory.getWord(PLAYER_BASE + MOVE_READY))
            step(session, 3)
            assertEquals(0xff, device.snapshot().game.round!!.players[0].color)
            val submit = session.program!!.localSymbolTable!!.getAddress("submit")
            assertEquals(BREAKPOINT, session.simulator().runUntilAsync(4000) { it == submit }.get(5, SECONDS).reason)
            val beforeTask = device.snapshot()
            val sorted = beforeTask.game.round!!.players[0].task.sorted()
            assertEquals(sorted, List(20) { memory.getWord(PLAYER_BASE + TASK_ARRAY + 4 * it) })
            memory.setWord(PLAYER_BASE + TASK_COMPLETE, 66)
            step(session)
            val afterTask = device.snapshot()
            assertEquals(20, memory.getWord(PLAYER_BASE + ENERGY))
            assertEquals(0, memory.getWord(PLAYER_BASE + TASK_COMPLETE))
            assertEquals(afterTask.game.round!!.players[0].task, List(20) { memory.getWord(PLAYER_BASE + TASK_ARRAY + 4 * it) })
            assertEquals(beforeTask.game.round.players[1], afterTask.game.round.players[1])
            backstep(session)
            assertEquals(beforeTask, device.snapshot())
            assertEquals(sorted, List(20) { memory.getWord(PLAYER_BASE + TASK_ARRAY + 4 * it) })
            assertEquals(66, memory.getWord(PLAYER_BASE + TASK_COMPLETE))
            assertEquals(19, memory.getWord(PLAYER_BASE + ENERGY))
            step(session)
            assertEquals(afterTask, device.snapshot())
        }
    }

    @Test
    fun `rejections leave the active player and handshakes consistent`() {
        withProgram("""
            lui ${'$'}s0, 0xffff
            ori ${'$'}s0, ${'$'}s0, 0xe000
            lui ${'$'}s1, 0xffff
            ori ${'$'}s1, ${'$'}s1, 0x8000
            li ${'$'}t0, 1
            sw ${'$'}t0, 8(${'$'}s0)
            li ${'$'}t1, 88
            sw ${'$'}t1, 0(${'$'}s0)
            sw ${'$'}t0, 4(${'$'}s0)
            li ${'$'}t1, 2
            sw ${'$'}t1, 0(${'$'}s0)
            sw ${'$'}zero, 4(${'$'}s0)
            sw ${'$'}zero, 8(${'$'}s0)
            sw ${'$'}t0, 0x410(${'$'}s1)
            sw ${'$'}zero, 8(${'$'}s1)
            sw ${'$'}t0, 0x10(${'$'}s1)
            sw ${'$'}zero, 0x10(${'$'}s1)
            sw ${'$'}t0, 0x124(${'$'}s1)
            sw ${'$'}zero, 0x124(${'$'}s1)
            li ${'$'}t1, 3
            sw ${'$'}t1, 0(${'$'}s0)
            li ${'$'}t1, 21
            sw ${'$'}t1, 4(${'$'}s0)
            sw ${'$'}t0, 0x10(${'$'}s1)
            nop
        """) { session, device ->
            val memory = session.memory
            step(session, 10)
            assertEquals(999, memory.getWord(IDENTITY))
            assertEquals(Diagnostic(IDENTITY, Rejection.AUTHENTICATION), device.snapshot().diagnostic)
            step(session, 2)
            assertEquals(0, memory.getWord(IDENTITY))
            assertNull(device.snapshot().diagnostic)
            step(session)
            assertEquals(1, memory.getWord(GAME_ON))
            assertEquals(Diagnostic(GAME_ON, Rejection.ADMINISTRATOR_REQUIRED), device.snapshot().diagnostic)
            step(session)
            assertEquals(Diagnostic(PLAYER_BASE + PLAYER_STRIDE + MOVE_READY, null), device.snapshot().diagnostic)
            assertEquals(1, memory.getWord(PLAYER_BASE + PLAYER_STRIDE + MOVE_READY))
            assertEquals(20, memory.getWord(PLAYER_BASE + PLAYER_STRIDE + ENERGY))
            step(session, 2)
            assertEquals(Diagnostic(PLAYER_BASE + MOVE_READY, Rejection.MOVE_TOO_FAR), device.snapshot().diagnostic)
            assertEquals(1, memory.getWord(PLAYER_BASE + MOVE_READY))
            assertEquals(20, memory.getWord(PLAYER_BASE + ENERGY))
            step(session)
            assertEquals(0, memory.getWord(PLAYER_BASE + MOVE_READY))
            memory.setWord(PLAYER_BASE + TASK_ARRAY, Int.MAX_VALUE)
            memory.setWord(PLAYER_BASE + TASK_ARRAY + 4, Int.MIN_VALUE)
            step(session)
            assertEquals(Diagnostic(PLAYER_BASE + TASK_COMPLETE, Rejection.INVALID_TASK), device.snapshot().diagnostic)
            assertEquals(1, memory.getWord(PLAYER_BASE + TASK_COMPLETE))
            step(session)
            assertEquals(0, memory.getWord(PLAYER_BASE + TASK_COMPLETE))
            step(session, 5)
            assertEquals(21, device.snapshot().game.playerId)
            assertEquals(Diagnostic(PLAYER_BASE + MOVE_READY, null), device.snapshot().diagnostic)
        }
    }

    @Test
    fun `only aligned execution stores are commands and device detaches cleanly`() {
        withProgram("""
            lui ${'$'}s0, 0xffff
            ori ${'$'}s0, ${'$'}s0, 0xe000
            li ${'$'}t0, 1
            sb ${'$'}t0, 8(${'$'}s0)
            sw ${'$'}t0, 8(${'$'}s0)
            nop
            sw ${'$'}t0, 8(${'$'}s0)
            nop
        """) { session, device ->
            assertTrue(ScavengerHuntDevice.supports(session.memory))
            assertEquals(2, session.memory.countObservers())
            session.memory.setWord(GAME_ON, 1)
            session.memory.getWord(GAME_ON)
            duringExecution<Unit, Exception> {
                session.memory.getWord(GAME_ON)
                session.memory.set(GAME_ON + 1, 1, 4)
            }
            step(session, 4)
            assertNull(device.snapshot().game.round)
            session.backStepper.setEnabled(false)
            step(session)
            assertEquals(5L, device.snapshot().game.round!!.firstInstruction)
            device.requestReset()
            step(session)
            val finished = device.snapshot()
            assertEquals(6L, finished.game.round!!.firstInstruction)
            device.close()
            assertEquals(0, session.memory.countObservers())
            step(session)
            assertEquals(finished.copy(available = false, connected = false), device.snapshot())
        }
        MarsSessionService.createMarsComponent().context().use { session ->
            session.memory.memoryMapLimitAddress = TURNS
            assertFalse(ScavengerHuntDevice.supports(session.memory))
            session.setMemoryConfiguration("CompactTextAtZero")
            assertFalse(ScavengerHuntDevice.supports(session.memory))
            assertThrows(IllegalArgumentException::class.java) { ScavengerHuntDevice(session) }
            assertEquals(0, session.memory.countObservers())
        }
    }

    @Test
    fun `attaching during a double store leaves that instruction unclaimed`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val file = directory.resolve("attach.asm").also {
                it.writeText("""
                    lui ${'$'}t0, 0xffff
                    ori ${'$'}t0, ${'$'}t0, 0xe000
                    sdc1 ${'$'}f2, 0(${'$'}t0)
                    nop
                    nop
                """.trimIndent())
            }
            session.assemble(listOf(file))
            lateinit var device: ScavengerHuntDevice
            val connect = Consumer<MemoryAccessNotice> { notice ->
                if (notice.accessType == WRITE) {
                    device.setConnected(true)
                    device.requestReset()
                }
            }
            session.memory.addObserver(connect, AUTHENTICATION)
            device = ScavengerHuntDevice(session, connectAtLaunch = false)
            device.use {
                device.requestReset()
                assertFalse(device.snapshot().resetPending)
                step(session, 3)
                val attached = device.snapshot()
                assertTrue(attached.resetPending)
                assertEquals(0, attached.game.authenticationSequence)
                assertNull(attached.game.round)
                session.memory.deleteObserver(connect)
                step(session)
                assertEquals(4L, device.snapshot().game.round!!.firstInstruction)
                device.setConnected(false)
                step(session)
                val disconnected = device.snapshot()
                assertFalse(disconnected.connected)
                backstep(session)
                assertEquals(disconnected, device.snapshot())
            }
        }
    }

    @Test
    fun `reset waits for a reversible instruction after a boundary attachment`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val file = directory.resolve("reset.asm").also { it.writeText("nop\nnop\nnop\n") }
            session.assemble(listOf(file))
            lateinit var device: ScavengerHuntDevice
            val simulator = session.simulator()
            val connect = object : SimulatorListener {
                override fun onStep(event: StepEvent) {
                    device.setConnected(true)
                    device.requestReset()
                    simulator.removeListener(this)
                }
                override fun onStop(event: StopEvent) = Unit
                override fun onError(event: ErrorEvent) = Unit
            }
            simulator.addListener(connect)
            device = ScavengerHuntDevice(session, connectAtLaunch = false)
            device.use {
                step(session)
                assertTrue(device.snapshot().resetPending)
                assertNull(device.snapshot().game.round)
                step(session)
                assertFalse(device.snapshot().resetPending)
                assertEquals(2L, device.snapshot().game.round!!.firstInstruction)
                backstep(session)
                assertNull(device.snapshot().game.round)
                assertFalse(device.snapshot().resetPending)
            }
        }
    }

    @Test
    fun `a pending memory callback cannot restart a detached device`() {
        MarsSessionService.createMarsComponent().context().use { session ->
            val file = directory.resolve("detach.asm").also {
                it.writeText("lui \$t0, 0xffff\nori \$t0, \$t0, 0xe000\nsw \$zero, 8(\$t0)\nnop\n")
            }
            session.assemble(listOf(file))
            lateinit var device: ScavengerHuntDevice
            val detach = Consumer<MemoryAccessNotice> { notice ->
                if (notice.accessType == WRITE) device.close()
            }
            session.memory.addObserver(detach, GAME_ON)
            device = ScavengerHuntDevice(session)
            device.use {
                step(session, 3)
                assertNull(device.snapshot().game.round)
                assertEquals(1, session.memory.countObservers())
            }
            session.memory.deleteObserver(detach)
        }
    }

    @Test
    fun `administrator writes are allowed before a round and a player cannot start one`() {
        withProgram("""
            lui ${'$'}s0, 0xffff
            ori ${'$'}s0, ${'$'}s0, 0xe000
            lui ${'$'}s1, 0xffff
            ori ${'$'}s1, ${'$'}s1, 0x8000
            li ${'$'}t0, 1
            sw ${'$'}t0, 0x1c(${'$'}s1)
            sw ${'$'}t0, 0(${'$'}s0)
            sw ${'$'}zero, 4(${'$'}s0)
            sw ${'$'}t0, 8(${'$'}s0)
            lw ${'$'}t0, 1(${'$'}s0)
        """) { session, device ->
            step(session, 6)
            assertEquals(1, session.memory.getWord(PLAYER_BASE + COLOR))
            assertNull(device.snapshot().diagnostic)
            step(session, 3)
            assertEquals(0, session.memory.getWord(GAME_ON))
            assertEquals(Diagnostic(GAME_ON, Rejection.ADMINISTRATOR_REQUIRED), device.snapshot().diagnostic)
            assertNull(device.snapshot().game.round)
            val rejected = device.snapshot()
            assertEquals(EXCEPTION, session.simulator().runAsync(1, null).get(5, SECONDS).reason)
            assertEquals(rejected, device.snapshot())
        }
    }

    private fun withProgram(source: String, test: (MarsContext, ScavengerHuntDevice) -> Unit) {
        val file = directory.resolve("game.asm").also { it.writeText(source.trimIndent()) }
        MarsSessionService.createMarsComponent().context().use { session ->
            session.assemble(listOf(file))
            ScavengerHuntDevice(session).use { device -> test(session, device) }
        }
    }

    private fun step(session: MarsContext, count: Long = 1) {
        assertEquals(STEP_LIMIT, session.simulator().runAsync(count, null).get(5, SECONDS).reason)
    }

    private fun backstep(session: MarsContext) {
        session.simulator().backstepAsync().get(5, SECONDS)
    }
}
