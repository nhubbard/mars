package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.api.ErrorEvent
import edu.missouristate.mars.api.SimulatorListener
import edu.missouristate.mars.api.StepEvent
import edu.missouristate.mars.api.StopEvent
import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessNotice.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.MemoryState.WORD_LENGTH_BYTES
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.administratorId
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.playerCount
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Companion.taskSize
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Player
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Point
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Rejection
import edu.missouristate.mars.plugin.tools.ScavengerHuntGame.Result
import java.util.function.Consumer

/** One simulation worker owns the protocol. The view reads immutable snapshots. */
internal class ScavengerHuntDevice(session: MarsContext) : AutoCloseable {
    /** A null game rejection denotes a store to another player's region. */
    data class Diagnostic(val address: Int, val rejection: Rejection?)
    data class Snapshot(val game: ScavengerHuntGame, val diagnostic: Diagnostic?)

    private inner class Frame(val before: Snapshot) : Runnable {
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
        override fun onStep(event: StepEvent) = completeInstruction(event.stepCount())
        override fun onStop(event: StopEvent) = Unit
        override fun onError(event: ErrorEvent) = discardInstruction()
    }
    private val history: AutoCloseable
    private val stores = mutableListOf<Store>()
    private var state = Snapshot(ScavengerHuntGame(), null)
    private var frame: Frame? = null
    private var attached = true

    init {
        require(supports(memory)) { "The original Scavenger Hunt registers require the default memory map" }
        memory.addObserver(observer, playerBase, playerEnd)
        memory.addObserver(observer, authentication, turns)
        history = session.backStepper.addInstructionCheckpoint(::capture)
        simulator.addListener(listener)
    }

    @Synchronized
    fun snapshot() = state

    @Synchronized
    override fun close() {
        attached = false
        simulator.removeListener(listener)
        memory.deleteObserver(observer)
        history.close()
        discardInstruction()
    }

    @Synchronized
    private fun record(notice: MemoryAccessNotice) {
        if (attached && notice.origin() == EXECUTION && notice.accessType == WRITE &&
            notice.length == WORD_LENGTH_BYTES && notice.address and 3 == 0
        ) {
            stores += Store(notice.address, memory.getWordNoNotify(notice.address))
        }
    }

    @Synchronized
    private fun completeInstruction(instruction: Long) {
        try {
            for ((address, value) in stores) {
                when (address) {
                    authentication -> Unit
                    identity -> {
                        update(address, state.game.authenticate(memory.getWordNoNotify(authentication), value))
                        publish(identity, state.game.playerId)
                    }
                    gameOn -> startRound(instruction)
                    turns -> state = state.copy(game = state.game.copy(turnsRemaining = value))
                    else -> writePlayer(address, value, instruction)
                }
            }
        } finally {
            discardInstruction()
        }
    }

    private fun startRound(instruction: Long) {
        val result = state.game.startRound(instruction)
        update(gameOn, result)
        val round = state.game.round
        publish(gameOn, if (round == null) 0 else 1)
        if (result.rejection != null) return

        publish(authentication, 0)
        publish(identity, administratorId)
        round!!.players.forEachIndexed { index, player ->
            val base = playerBase + index * playerStride
            publishPosition(base, player)
            publish(base + targetX, player.position.x)
            publish(base + targetY, player.position.y)
            publish(base + moveReady, 0)
            publish(base + locationCount, round.locations.size)
            publish(base + color, player.color)
            publish(base + taskSizeOffset, taskSize)
            round.locations.forEachIndexed { location, point ->
                publish(base + locationArray + location * 8, point.x)
                publish(base + locationArray + location * 8 + 4, point.y)
            }
            publish(base + taskComplete, 0)
            publishTask(base, player)
        }
    }

    private fun writePlayer(address: Int, value: Int, instruction: Long) {
        val id = state.game.playerId
        if (id == administratorId) return
        val base = playerBase + id * playerStride
        if (address !in base until base + playerStride) {
            state = state.copy(diagnostic = Diagnostic(address, null))
            return
        }
        when (address - base) {
            moveReady -> if (value != 0) {
                val point = Point(memory.getWordNoNotify(base + targetX), memory.getWordNoNotify(base + targetY))
                val result = state.game.move(point, instruction)
                update(address, result)
                if (result.rejection == null) {
                    publishPosition(base, player(id))
                    publish(address, 0)
                }
            }
            color -> update(address, state.game.changeColor(value))
            taskComplete -> if (value != 0) {
                val words = List(taskSize) { memory.getWordNoNotify(base + taskArray + it * WORD_LENGTH_BYTES) }
                val result = state.game.completeTask(words)
                update(address, result)
                if (result.rejection == null) {
                    val player = player(id)
                    publish(base + energy, player.energy)
                    publishTask(base, player)
                    publish(address, 0)
                }
            }
        }
    }

    private fun player(id: Int) = state.game.round!!.players[id]

    private fun update(address: Int, result: Result) {
        state = Snapshot(result.game, result.rejection?.let { Diagnostic(address, it) })
    }

    private fun publishPosition(base: Int, player: Player) {
        publish(base + positionX, player.position.x)
        publish(base + positionY, player.position.y)
        publish(base + energy, player.energy)
    }

    private fun publishTask(base: Int, player: Player) {
        player.task.forEachIndexed { index, word -> publish(base + taskArray + index * WORD_LENGTH_BYTES, word) }
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
        frame = null
    }

    @Synchronized
    private fun capture(): Runnable {
        return Frame(state).also { frame = it }
    }

    @Synchronized
    private fun restore(previous: Frame) {
        state = previous.before
        previous.overwritten?.forEach { (address, value) -> memory.setWord(address, value) }
    }

    companion object {
        const val playerBase = 0xffff8000.toInt()
        const val playerStride = 0x400
        private const val playerEnd = playerBase + playerStride * playerCount - WORD_LENGTH_BYTES
        const val authentication = 0xffffe000.toInt()
        const val identity = authentication + 4
        const val gameOn = authentication + 8
        const val turns = authentication + 12
        const val positionX = 0x0
        const val positionY = 0x4
        const val targetX = 0x8
        const val targetY = 0xc
        const val moveReady = 0x10
        const val energy = 0x14
        const val locationCount = 0x18
        const val color = 0x1c
        const val taskSizeOffset = 0x20
        const val locationArray = 0x24
        const val taskComplete = 0x124
        const val taskArray = 0x128

        fun supports(memory: MemoryState): Boolean =
            memory.memoryMapBaseAddress <= playerBase && turns < memory.memoryMapLimitAddress
    }
}
