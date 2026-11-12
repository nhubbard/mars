package edu.missouristate.mars.api

/** Concurrent read and write access to simulated memory. Addresses are unsigned 32-bit values. */
interface MarsMemory {
    /** Read a 32-bit word at an aligned byte address. */
    @Throws(MemoryAccessException::class)
    fun getWord(address: Int): Int

    /** Write a 32-bit word at an aligned byte address. */
    @Throws(MemoryAccessException::class)
    fun setWord(address: Int, value: Int)

    /** Read a zero-extended halfword at an aligned byte address. */
    @Throws(MemoryAccessException::class)
    fun getHalfWord(address: Int): Int

    /** Read a zero-extended byte. */
    @Throws(MemoryAccessException::class)
    fun getByte(address: Int): Int

    /** Clear memory and reset heap allocation for the current layout. */
    fun clear()
}
