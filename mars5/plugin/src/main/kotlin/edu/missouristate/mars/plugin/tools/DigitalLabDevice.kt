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
    private var right = 0
    private var left = 0
    private var key: Int? = null
    private var keyChanged = false
    private var keyOutputDirty = false
    private var row = 0
    private var timerRemaining = 0

    @Synchronized
    fun observe(source: MemoryState): AutoCloseable {
        memory = source
        updateObserver()
        return AutoCloseable { detach() }
    }

    @Synchronized
    fun snapshot() = Snapshot(memory != null, connected, right, left, key, timerRemaining)

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value && memory != null
        if (!connected) {
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
        if (keyOutputDirty) {
            keyOutputDirty = false
            writeKey(session.memory)
        }
        if (keyChanged) {
            keyChanged = false
            if (key != null && row and 0xf0 != 0) interrupts = keyboardInterrupt
        }
        if (timerRemaining > 0 && --timerRemaining == 0) {
            timerRemaining = timerPeriod
            interrupts = interrupts or timerInterrupt
        }
        if (interrupts != 0 && session.coprocessor0.getRegister(STATUS).valueNoNotify and 2 == 0) {
            session.requestExternalInterrupt(interrupts)
        }
    }

    private fun clearControls() {
        key = null
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
        val code = key?.let {
            val rowBit = 1 shl (it / 4)
            if (row and 0xf == rowBit) rowBit or (1 shl (4 + it % 4)) else 0
        } ?: 0
        source.setByte(source.memoryMapBaseAddress + keyOffset, code)
    }

    private companion object {
        const val controlOffset = 0x10
        const val keyOffset = 0x14
        const val timerPeriod = 30
        // The core shifts exception codes left by two for Coprocessor 0 Cause.
        const val timerInterrupt = 0x100
        const val keyboardInterrupt = 0x200
    }
}
