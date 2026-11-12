package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.READ
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.plugin.tools.MmioTerminalDisplay.Geometry
import edu.missouristate.mars.simulator.Exceptions.EXTERNAL_INTERRUPT_DISPLAY
import edu.missouristate.mars.simulator.Exceptions.EXTERNAL_INTERRUPT_KEYBOARD
import java.util.SplittableRandom
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.random.Random

/** Per-execution SPIM keyboard and display. MMIO effects run at instruction boundaries. */
internal class KeyboardDisplayDevice(
    private val connectAtLaunch: Boolean = false,
    private val seed: Long = Random.nextLong(),
) {
    enum class Delay { FIXED, UNIFORM, NORMAL }

    data class Options(
        val delay: Delay = Delay.FIXED,
        val instructions: Int = 5,
        val displayAfterDelay: Boolean = true,
    ) {
        init { require(instructions in 1..1_000_000) }
    }

    data class Snapshot(
        val available: Boolean, val connected: Boolean, val display: MmioTerminalDisplay,
        val options: Options, val remaining: Int, val base: Int?,
    )

    private data class Transfer(val word: Int, val remaining: Int, val delayed: Boolean)
    private data class Ports(val receiverControl: Int, val receiverData: Int, val transmitterControl: Int)
    private data class RewindState(
        val display: MmioTerminalDisplay, val transfer: Transfer?, val sequence: Long,
        val ports: Ports?, val initialize: Boolean, val interrupts: Int,
    )

    private var memory: MemoryState? = null
    private var base: Int? = null
    private val observer = Consumer<MemoryAccessNotice>(::record)
    private var connected = connectAtLaunch
    private var hasConnected = connectAtLaunch
    private var options = Options()
    private var display = MmioTerminalDisplay()
    private var transfer: Transfer? = null
    private var sequence = 0L
    private var initialize = false
    private var queuedKey: Int? = null
    private var receiverRead = false
    private var transmitted: Int? = null
    private var checkpoint: RewindState? = null
    private var restoreCheckpoint: Runnable? = null

    @Synchronized
    fun observe(session: MarsContext): AutoCloseable {
        val source = session.memory
        memory = source
        base = source.memoryMapBaseAddress
        updateObserver()
        if (connected) initializePorts(source, source.memoryMapBaseAddress)
        val history = session.backStepper.addInstructionCheckpoint { capture(session) }
        return AutoCloseable {
            history.close()
            detach()
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(memory != null, connected, display, options, transfer?.remaining ?: 0, base)

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value && memory != null
        hasConnected = hasConnected || connected
        clearControls()
        initialize = connected
        updateObserver()
    }

    fun releaseView() {
        if (!connectAtLaunch) setConnected(false)
    }

    @Synchronized
    fun configure(value: Options) {
        options = value
    }

    @Synchronized
    fun sendKey(character: Char) {
        if (connected && memory != null) queuedKey = character.code and 0xff
    }

    @Synchronized
    fun reset(geometry: Geometry? = null) {
        display = MmioTerminalDisplay(geometry ?: display.geometry)
        sequence = 0L
        clearControls()
        initialize = connected
    }

    @Synchronized
    fun instructionCompleted(session: MarsContext) {
        if (!connected) return
        val source = session.memory
        val start = source.memoryMapBaseAddress
        if (initialize) {
            initialize = false
            initializePorts(source, start)
        }
        if (receiverRead) {
            receiverRead = false
            setReady(source, start, false)
        }
        var interrupts = 0
        queuedKey?.let { character ->
            queuedKey = null
            source.setWord(start + RECEIVER_DATA, character)
            if (setReady(source, start, true) and INTERRUPT_ENABLE != 0) interrupts = EXTERNAL_INTERRUPT_KEYBOARD
        }
        val word = transmitted
        if (word != null) {
            transmitted = null
            setReady(source, start + TRANSMITTER_CONTROL, false)
            transfer = Transfer(word, nextDelay(), options.displayAfterDelay)
            if (!options.displayAfterDelay) display = display.transmit(word)
        } else {
            val current = transfer
            if (current != null) {
                if (current.remaining == 1) {
                    transfer = null
                    if (current.delayed) display = display.transmit(current.word)
                    if (setReady(source, start + TRANSMITTER_CONTROL, true) and INTERRUPT_ENABLE != 0) {
                        interrupts = interrupts or EXTERNAL_INTERRUPT_DISPLAY
                    }
                } else {
                    transfer = current.copy(remaining = current.remaining - 1)
                }
            }
        }
        // Interrupts require global enable and a clear exception level.
        if (interrupts != 0 && session.coprocessor0.getRegister(STATUS)!!.valueNoNotify and 3 == 1) {
            session.setDeviceInterrupts(interrupts, interrupts)
        }
    }

    private fun nextDelay(): Int = when (options.delay) {
        Delay.FIXED -> options.instructions
        Delay.UNIFORM -> nextRandom().nextInt(options.instructions) + 1
        Delay.NORMAL -> (abs(nextRandom().nextGaussian() * options.instructions) + 1).toInt()
    }

    private fun nextRandom() = SplittableRandom(seed + sequence++ * -7046029254386353131L)

    private fun clearControls() {
        transfer = null
        clearInput()
    }

    private fun clearInput() {
        queuedKey = null
        receiverRead = false
        transmitted = null
    }

    private fun updateObserver() {
        val source = memory ?: return
        val start = source.memoryMapBaseAddress
        source.deleteObserver(observer)
        if (connected) {
            source.addObserver(observer, start + RECEIVER_DATA)
            source.addObserver(observer, start + TRANSMITTER_DATA)
        }
    }

    @Synchronized
    private fun detach() {
        setConnected(false)
        memory = null
        checkpoint = null
        restoreCheckpoint = null
    }

    @Synchronized
    private fun record(notice: MemoryAccessNotice) {
        if (!connected || notice.origin() != EXECUTION) return
        val source = memory!!
        val start = source.memoryMapBaseAddress
        val offset = notice.address - start
        if (offset == RECEIVER_DATA && notice.accessType == READ) {
            receiverRead = true
        } else if (offset == TRANSMITTER_DATA && notice.accessType == WRITE && transfer == null &&
            transmitted == null && !initialize && source.getWordNoNotify(start + TRANSMITTER_CONTROL) and READY != 0) {
            transmitted = source.getWordNoNotify(start + TRANSMITTER_DATA)
        }
    }

    private fun initializePorts(source: MemoryState, start: Int) {
        setReady(source, start, false)
        setReady(source, start + TRANSMITTER_CONTROL, true)
    }

    private fun setReady(source: MemoryState, address: Int, value: Boolean): Int {
        val previous = source.getWordNoNotify(address)
        val current = if (value) previous or READY else previous and READY.inv()
        if (current != previous) source.setWord(address, current)
        return current
    }

    @Synchronized
    private fun capture(session: MarsContext): Runnable {
        if (!hasConnected && checkpoint?.display === display) return restoreCheckpoint!!
        val source = session.memory
        val start = source.memoryMapBaseAddress
        val ports = if (connected) Ports(source.getWordNoNotify(start),
            source.getWordNoNotify(start + RECEIVER_DATA), source.getWordNoNotify(start + TRANSMITTER_CONTROL)) else null
        val state = RewindState(display, transfer, sequence, ports, initialize,
            session.pendingDeviceInterrupts() and INTERRUPT_MASK)
        if (state != checkpoint) {
            checkpoint = state
            restoreCheckpoint = Runnable { restore(session, start, state) }
        }
        return restoreCheckpoint!!
    }

    @Synchronized
    private fun restore(session: MarsContext, start: Int, state: RewindState) {
        display = state.display
        transfer = state.transfer
        sequence = state.sequence
        initialize = state.initialize
        clearInput()
        state.ports?.let { ports ->
            val source = session.memory
            restorePort(source, start, ports.receiverControl)
            restorePort(source, start + RECEIVER_DATA, ports.receiverData)
            restorePort(source, start + TRANSMITTER_CONTROL, ports.transmitterControl)
        }
        session.setDeviceInterrupts(INTERRUPT_MASK, state.interrupts)
    }

    private fun restorePort(source: MemoryState, address: Int, value: Int) {
        if (source.getWordNoNotify(address) != value) source.setWord(address, value)
    }

    private companion object {
        const val RECEIVER_DATA = 4
        const val TRANSMITTER_CONTROL = 8
        const val TRANSMITTER_DATA = 12
        const val READY = 1
        const val INTERRUPT_ENABLE = 2
        const val INTERRUPT_MASK = EXTERNAL_INTERRUPT_KEYBOARD or EXTERNAL_INTERRUPT_DISPLAY
    }
}
