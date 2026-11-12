package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.ADMINISTRATOR_ID
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.PLAYER_COUNT
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.TASK_SIZE
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Player
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Point
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Rejection
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Result
import java.util.function.Consumer

/** One simulation worker owns the protocol. The view reads immutable snapshots. */
internal class ScavengerHuntDevice(session: MarsContext, connectAtLaunch: Boolean = true) : AutoCloseable {
    /** A null game rejection denotes a store to another player's region. */
    data class Diagnostic(val address: Int, val rejection: Rejection?)
    data class Snapshot(
        val game: ScavengerHuntGame, val diagnostic: Diagnostic?,
        val available: Boolean, val connected: Boolean, val resetPending: Boolean,
    )

    private data class State(val game: ScavengerHuntGame = ScavengerHuntGame(), val diagnostic: Diagnostic? = null)

    private inner class Frame(val before: State) : Runnable {
        var overwritten: MutableMap<Int, Int>? = null

        fun record(address: Int, value: Int) {
            val changes = overwritten ?: mutableMapOf<Int, Int>().also { overwritten = it }
            changes.putIfAbsent(address, value)
        }

        override fun run() = restore(this)
    }

    private data class Store(val address: Int, val value: Int)

    private val memory = session.memory
    private val simulator = session.simulator()
    private val observer = Consumer<MemoryAccessNotice>(::record)
    private val listener = object : SimulatorListener {
        override fun onStep(event: StepEvent) = completeInstruction(event.stepCount)
        override fun onStop(event: StopEvent) = Unit
        override fun onError(event: ErrorEvent) = discardInstruction()
    }
    private val backStepper = session.backStepper
    private val history: AutoCloseable
    private val stores = mutableListOf<Store>()
    private var state = State()
    private var frame: Frame? = null
    private var inactiveFrame = Frame(state)
    private var attached = true
    private var connected = false
    private var resetPending = false
    private var wrotePort = false

    init {
        require(supports(memory)) { "The original Scavenger Hunt registers require the default memory map" }
        setConnected(connectAtLaunch)
        history = backStepper.addInstructionCheckpoint(::capture)
        simulator.addListener(listener)
    }

    @Synchronized
    fun snapshot() = Snapshot(state.game, state.diagnostic, attached, connected, resetPending)

    @Synchronized
    fun setConnected(value: Boolean) {
        val next = attached && value
        if (next == connected) return
        connected = next
        if (connected) {
            memory.addObserver(observer, PLAYER_BASE, PLAYER_END)
            memory.addObserver(observer, AUTHENTICATION, TURNS)
        } else {
            memory.deleteObserver(observer)
            resetPending = false
            discardInstruction()
        }
    }

    @Synchronized
    fun requestReset() {
        if (connected) resetPending = true
    }

    @Synchronized
    override fun close() {
        attached = false
        setConnected(false)
        simulator.removeListener(listener)
        history.close()
        discardInstruction()
    }

    @Synchronized
    private fun record(notice: MemoryAccessNotice) {
        if (!connected || notice.origin() != EXECUTION || notice.accessType != WRITE) return
        // A connection made during an instruction starts processing at the next captured boundary.
        wrotePort = true
        if (notice.length == WORD_LENGTH_BYTES && notice.address and 3 == 0 &&
            (frame != null || !backStepper.enabled())
        ) {
            stores += Store(notice.address, memory.getWordNoNotify(notice.address))
        }
    }

    @Synchronized
    private fun completeInstruction(instruction: Long) {
        try {
            // Wait for an instruction with no device-port store so reset can journal full words.
            if (resetPending && !wrotePort) {
                if (backStepper.enabled() && frame == null) return
                resetPending = false
                state = state.copy(game = state.game.copy(playerId = ADMINISTRATOR_ID))
                startRound(instruction)
            }
            for ((address, value) in stores) {
                when (address) {
                    AUTHENTICATION -> Unit
                    IDENTITY -> {
                        update(address, state.game.authenticate(memory.getWordNoNotify(AUTHENTICATION), value))
                        publish(IDENTITY, state.game.playerId)
                    }
                    GAME_ON -> startRound(instruction)
                    TURNS -> state = state.copy(game = state.game.copy(turnsRemaining = value))
                    else -> writePlayer(address, value, instruction)
                }
            }
        } finally {
            discardInstruction()
        }
    }

    private fun startRound(instruction: Long) {
        val result = state.game.startRound(instruction)
        update(GAME_ON, result)
        val round = state.game.round
        publish(GAME_ON, if (round == null) 0 else 1)
        if (result.rejection != null) return

        publish(AUTHENTICATION, 0)
        publish(IDENTITY, ADMINISTRATOR_ID)
        round!!.players.forEachIndexed { index, player ->
            val base = PLAYER_BASE + index * PLAYER_STRIDE
            publishPosition(base, player)
            publish(base + TARGET_X, player.position.x)
            publish(base + TARGET_Y, player.position.y)
            publish(base + MOVE_READY, 0)
            publish(base + LOCATION_COUNT, round.locations.size)
            publish(base + COLOR, player.color)
            publish(base + TASK_SIZE_OFFSET, TASK_SIZE)
            round.locations.forEachIndexed { location, point ->
                publish(base + LOCATION_ARRAY + location * 8, point.x)
                publish(base + LOCATION_ARRAY + location * 8 + 4, point.y)
            }
            publish(base + TASK_COMPLETE, 0)
            publishTask(base, player)
        }
    }

    private fun writePlayer(address: Int, value: Int, instruction: Long) {
        val id = state.game.playerId
        if (id == ADMINISTRATOR_ID) return
        val base = PLAYER_BASE + id * PLAYER_STRIDE
        if (address !in base until base + PLAYER_STRIDE) {
            state = state.copy(diagnostic = Diagnostic(address, null))
            return
        }
        when (address - base) {
            MOVE_READY -> if (value != 0) {
                val point = Point(memory.getWordNoNotify(base + TARGET_X), memory.getWordNoNotify(base + TARGET_Y))
                val result = state.game.move(point, instruction)
                update(address, result)
                if (result.rejection == null) {
                    publishPosition(base, player(id))
                    publish(address, 0)
                }
            }
            COLOR -> update(address, state.game.changeColor(value))
            TASK_COMPLETE -> if (value != 0) {
                val words = List(TASK_SIZE) { memory.getWordNoNotify(base + TASK_ARRAY + it * WORD_LENGTH_BYTES) }
                val result = state.game.completeTask(words)
                update(address, result)
                if (result.rejection == null) {
                    val player = player(id)
                    publish(base + ENERGY, player.energy)
                    publishTask(base, player)
                    publish(address, 0)
                }
            }
        }
    }

    private fun player(id: Int) = state.game.round!!.players[id]

    private fun update(address: Int, result: Result) {
        state = State(result.game, result.rejection?.let { Diagnostic(address, it) })
    }

    private fun publishPosition(base: Int, player: Player) {
        publish(base + POSITION_X, player.position.x)
        publish(base + POSITION_Y, player.position.y)
        publish(base + ENERGY, player.energy)
    }

    private fun publishTask(base: Int, player: Player) {
        player.task.forEachIndexed { index, word -> publish(base + TASK_ARRAY + index * WORD_LENGTH_BYTES, word) }
    }

    private fun publish(address: Int, value: Int) {
        val before = memory.getWordNoNotify(address)
        if (before == value) return
        // The CPU already records undo for the entire word at any command address it wrote.
        if (stores.none { it.address == address }) frame?.record(address, before)
        memory.setWord(address, value)
    }

    @Synchronized
    private fun discardInstruction() {
        stores.clear()
        wrotePort = false
        frame = null
    }

    @Synchronized
    private fun capture(): Runnable {
        if (connected) return Frame(state).also { frame = it }
        if (inactiveFrame.before !== state) inactiveFrame = Frame(state)
        return inactiveFrame
    }

    @Synchronized
    private fun restore(previous: Frame) {
        state = previous.before
        resetPending = false
        previous.overwritten?.forEach { (address, value) -> memory.setWord(address, value) }
    }

    companion object {
        const val PLAYER_BASE = 0xffff8000.toInt()
        const val PLAYER_STRIDE = 0x400
        private const val PLAYER_END = PLAYER_BASE + PLAYER_STRIDE * PLAYER_COUNT - WORD_LENGTH_BYTES
        const val AUTHENTICATION = 0xffffe000.toInt()
        const val IDENTITY = AUTHENTICATION + 4
        const val GAME_ON = AUTHENTICATION + 8
        const val TURNS = AUTHENTICATION + 12
        const val POSITION_X = 0x0
        const val POSITION_Y = 0x4
        const val TARGET_X = 0x8
        const val TARGET_Y = 0xc
        const val MOVE_READY = 0x10
        const val ENERGY = 0x14
        const val LOCATION_COUNT = 0x18
        const val COLOR = 0x1c
        const val TASK_SIZE_OFFSET = 0x20
        const val LOCATION_ARRAY = 0x24
        const val TASK_COMPLETE = 0x124
        const val TASK_ARRAY = 0x128

        fun supports(memory: MemoryState): Boolean =
            memory.memoryMapBaseAddress <= PLAYER_BASE && TURNS < memory.memoryMapLimitAddress
    }
}
