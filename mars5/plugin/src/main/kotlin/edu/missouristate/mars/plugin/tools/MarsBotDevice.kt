package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessNotice.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import java.util.function.Consumer

/** MarsBot moves on the execution worker and shares its instruction history. */
internal class MarsBotDevice(private val connectAtLaunch: Boolean = false) {
    data class Ports(val base: Int) {
        val heading = base + 0x10
        val pen = base + 0x20
        val x = base + 0x30
        val y = base + 0x40
        val move = base + 0x50
    }

    data class Snapshot(
        val available: Boolean, val connected: Boolean, val world: MarsBotWorld,
        val instructionsPerTick: Int, val ports: Ports?,
    )

    private data class PositionWords(val x: Int, val y: Int)
    private data class RewindState(
        val world: MarsBotWorld, val remaining: Int, val publish: Boolean, val coordinates: PositionWords?,
    )

    private var memory: MemoryState? = null
    private var ports: Ports? = null
    private val observer = Consumer<MemoryAccessNotice>(::record)
    private var connected = connectAtLaunch
    private var hasConnected = connectAtLaunch
    private var world = MarsBotWorld()
    private var instructionsPerTick = 64
    private var remaining = instructionsPerTick
    private var publish = false
    private var checkpoint: RewindState? = null
    private var restoreCheckpoint: Runnable? = null

    @Synchronized
    fun observe(session: MarsContext): AutoCloseable {
        val source = session.memory
        val offset = if (source.usingCompactMemoryConfiguration()) 0x80 else 0x8000
        val addresses = Ports(source.memoryMapBaseAddress + offset)
        memory = source
        ports = addresses
        updateObserver()
        if (connected) publishPosition(source, addresses)
        val history = session.backStepper.addInstructionCheckpoint { capture(source, addresses) }
        return AutoCloseable {
            history.close()
            detach()
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(memory != null, connected, world, instructionsPerTick, ports)

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value && memory != null
        hasConnected = hasConnected || connected
        publish = connected
        updateObserver()
    }

    fun releaseView() {
        if (!connectAtLaunch) setConnected(false)
    }

    @Synchronized
    fun setInstructionsPerTick(count: Int) {
        require(count in 1..1_000_000)
        instructionsPerTick = count
        remaining = count
    }

    @Synchronized
    fun reset() {
        world = MarsBotWorld()
        remaining = instructionsPerTick
        publish = connected
    }

    @Synchronized
    fun instructionCompleted() {
        if (!connected) return
        if (world.moving && --remaining == 0) {
            remaining = instructionsPerTick
            val next = world.moveOnePixel()
            publish = publish || next !== world
            world = next
        }
        if (publish) {
            publish = false
            publishPosition(memory!!, ports!!)
        }
    }

    @Synchronized
    private fun record(notice: MemoryAccessNotice) {
        if (!connected || notice.origin() != EXECUTION || notice.accessType != WRITE) return
        val source = memory!!
        val addresses = ports!!
        val address = notice.address and -4
        when (address) {
            addresses.heading -> world = world.copy(heading = Math.floorMod(source.getWordNoNotify(address), 360))
            addresses.pen -> when (source.getWordNoNotify(address)) {
                0 -> world = world.copy(penDown = false)
                1 -> world = world.copy(penDown = true)
            }
            addresses.move -> {
                val moving = source.getWordNoNotify(address) != 0
                if (moving != world.moving) remaining = instructionsPerTick
                world = world.copy(moving = moving)
            }
            addresses.x, addresses.y -> publish = true
        }
    }

    private fun updateObserver() {
        val source = memory ?: return
        source.deleteObserver(observer)
        if (connected) source.addObserver(observer, ports!!.heading, ports!!.move)
    }

    @Synchronized
    private fun detach() {
        setConnected(false)
        memory = null
        checkpoint = null
        restoreCheckpoint = null
    }

    private fun publishPosition(source: MemoryState, addresses: Ports) {
        writePosition(source, addresses, PositionWords(world.position.x.toInt(), world.position.y.toInt()))
    }

    private fun writePosition(source: MemoryState, addresses: Ports, coordinates: PositionWords) {
        if (source.getWordNoNotify(addresses.x) != coordinates.x) source.setWord(addresses.x, coordinates.x)
        if (source.getWordNoNotify(addresses.y) != coordinates.y) source.setWord(addresses.y, coordinates.y)
    }

    @Synchronized
    private fun capture(source: MemoryState, addresses: Ports): Runnable {
        if (!hasConnected) restoreCheckpoint?.let { return it }
        val coordinates = if (connected) PositionWords(
            source.getWordNoNotify(addresses.x), source.getWordNoNotify(addresses.y),
        ) else null
        val state = RewindState(world, remaining, publish, coordinates)
        if (state != checkpoint) {
            checkpoint = state
            restoreCheckpoint = Runnable { restore(source, addresses, state) }
        }
        return restoreCheckpoint!!
    }

    @Synchronized
    private fun restore(source: MemoryState, addresses: Ports, state: RewindState) {
        world = state.world
        remaining = state.remaining
        publish = state.publish
        state.coordinates?.let { writePosition(source, addresses, it) }
    }
}
