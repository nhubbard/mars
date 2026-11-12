package edu.missouristate.mars.core

import edu.missouristate.mars.api.MarsMemory
import edu.missouristate.mars.api.MemoryAccessException
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.simulator.BackStepper

/** Exposes memory through the frontend API and translates hardware address faults. */
class MarsMemoryAdapter(private val memory: MemoryState, private val backStepper: BackStepper?) : MarsMemory {
    constructor(memory: MemoryState) : this(memory, null)

    @Throws(MemoryAccessException::class)
    override fun getWord(address: Int): Int = access { memory.getWord(address) }

    @Throws(MemoryAccessException::class)
    override fun setWord(address: Int, value: Int) {
        access { memory.setWord(address, value) }
    }

    @Throws(MemoryAccessException::class)
    override fun getHalfWord(address: Int): Int = access { memory.getHalf(address) }

    @Throws(MemoryAccessException::class)
    override fun getByte(address: Int): Int = access { memory.getByte(address) }

    override fun clear() {
        backStepper?.clear()
        memory.clear()
    }

    private inline fun <T> access(operation: () -> T): T = try {
        operation()
    } catch (error: AddressErrorException) {
        throw MemoryAccessException(error.address, error.message).apply { initCause(error) }
    }
}
