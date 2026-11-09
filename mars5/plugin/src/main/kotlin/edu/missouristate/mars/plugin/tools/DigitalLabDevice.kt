package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.hardware.AccessNotice.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.Coprocessor0State.STATUS
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import java.util.function.Consumer

/** Digital Lab's byte ports and instruction-driven timer, owned by one execution. */
internal class DigitalLabDevice(private val connectAtLaunch: Boolean = false) {
    data class Snapshot(
        val available: Boolean, val connected: Boolean, val right: Int, val left: Int,
        val key: Int?, val timerRemaining: Int,
    )

    private var memory: MemoryState? = null
    private val observer = Consumer<MemoryAccessNotice>(::record)
    private var connected = connectAtLaunch
    private var hasConnected = connectAtLaunch
    private var right = 0
    private var left = 0
    private var key: Int? = null
    private var appliedKey: Int? = null
    private var keyChanged = false
    private var keyOutputDirty = false
    private var row = 0
    private var timerRemaining = 0
    private var checkpoint: RewindState? = null
    private var restoreCheckpoint: Runnable? = null

    private data class RewindState(
        val right: Int, val left: Int, val key: Int?, val row: Int, val timerRemaining: Int,
        val output: Int?, val pendingInterrupts: Int,
    )

    @Synchronized
    fun observe(session: MarsContext): AutoCloseable {
        memory = session.memory
        updateObserver()
        val history = session.backStepper.addInstructionCheckpoint { capture(session) }
        return AutoCloseable {
            history.close()
            detach()
        }
    }

    @Synchronized
    fun snapshot() = Snapshot(memory != null, connected, right, left, key, timerRemaining)

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value && memory != null
        if (connected) {
            hasConnected = true
        } else {
            clearControls()
            keyChanged = false
            keyOutputDirty = false
        }
        updateObserver()
    }

    fun releaseView() {
        if (!connectAtLaunch) setConnected(false)
    }

    @Synchronized
    fun toggleKey(number: Int) {
        if (!connected || memory == null) return
        key = if (key == null) number else null
        keyChanged = true
        keyOutputDirty = true
    }

    @Synchronized
    fun reset() {
        right = 0
        left = 0
        clearControls()
        keyChanged = false
        keyOutputDirty = true
    }

    @Synchronized
    fun instructionCompleted(session: MarsContext) {
        if (!connected) return
        var interrupts = 0
        if (keyChanged) {
            keyChanged = false
            appliedKey = key
            if (appliedKey != null && row and 0xf0 != 0) interrupts = keyboardInterrupt
        }
        if (keyOutputDirty) {
            keyOutputDirty = false
            writeKey(session.memory)
        }
        if (timerRemaining > 0 && --timerRemaining == 0) {
            timerRemaining = timerPeriod
            interrupts = interrupts or timerInterrupt
        }
        if (interrupts != 0 && session.coprocessor0.getRegister(STATUS).valueNoNotify and 2 == 0) {
            session.setDeviceInterrupts(interrupts, interrupts)
        }
    }

    private fun clearControls() {
        key = null
        appliedKey = null
        row = 0
        timerRemaining = 0
    }

    private fun updateObserver() {
        val source = memory ?: return
        source.deleteObserver(observer)
        if (connected) source.addObserver(observer, source.memoryMapBaseAddress + controlOffset)
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
        if (!connected || notice.origin() != EXECUTION || notice.accessType != WRITE) return
        val source = memory!!
        val firstAddress = source.memoryMapBaseAddress + controlOffset
        // Read the stored bytes so byte, halfword, and word writes use the machine's layout.
        val word = source.getWordNoNotify(firstAddress)
        val first = notice.address - firstAddress
        for (port in first until minOf(first + notice.length, 4)) {
            val byte = (word ushr (port * 8)) and 0xff
            when (port) {
                0 -> right = byte
                1 -> left = byte
                2 -> {
                    row = byte
                    keyOutputDirty = true
                }
                else -> timerRemaining = if (byte == 0) 0 else timerPeriod
            }
        }
    }

    private fun writeKey(source: MemoryState) {
        val code = appliedKey?.let {
            val rowBit = 1 shl (it / 4)
            if (row and 0xf == rowBit) rowBit or (1 shl (4 + it % 4)) else 0
        } ?: 0
        source.setByte(source.memoryMapBaseAddress + keyOffset, code)
    }

    @Synchronized
    private fun capture(session: MarsContext): Runnable {
        // A device that has never connected can reuse the initial, inactive checkpoint.
        if (!hasConnected) restoreCheckpoint?.let { return it }
        val source = session.memory
        val address = source.memoryMapBaseAddress + keyOffset
        val output = if (connected) source.getWordNoNotify(address) and 0xff else null
        val state = RewindState(right, left, appliedKey, row, timerRemaining, output,
            session.pendingDeviceInterrupts() and interruptMask)
        if (state != checkpoint) {
            checkpoint = state
            restoreCheckpoint = Runnable { restore(session, address, state) }
        }
        return restoreCheckpoint!!
    }

    @Synchronized
    private fun restore(session: MarsContext, address: Int, state: RewindState) {
        right = state.right
        left = state.left
        key = state.key
        appliedKey = state.key
        row = state.row
        timerRemaining = state.timerRemaining
        keyChanged = false
        keyOutputDirty = false
        val source = session.memory
        state.output?.let { output ->
            if (source.getWordNoNotify(address) and 0xff != output) source.setByte(address, output)
        }
        session.setDeviceInterrupts(interruptMask, state.pendingInterrupts)
    }

    private companion object {
        const val controlOffset = 0x10
        const val keyOffset = 0x14
        const val timerPeriod = 30
        // The core shifts exception codes left by two for Coprocessor 0 Cause.
        const val timerInterrupt = 0x100
        const val keyboardInterrupt = 0x200
        const val interruptMask = timerInterrupt or keyboardInterrupt
    }
}
