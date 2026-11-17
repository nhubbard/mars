package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState

/**
 * Records subsequent execution stores overlapping a region. Assertions run while execution is stopped.
 * Wider overlapping stores are retained so a byte-port contract cannot silently accept them.
 */
class MemoryStoreTrace internal constructor(
    memory: MemoryState,
    private val firstAddress: Long,
    byteLength: Int,
) : AutoCloseable {
    private data class Store(val address: Long, val length: Int, val value: Int)

    private val lastAddress: Long
    private val stores = ArrayList<Store>()
    private var overflow = false
    private var subscribedMemory: MemoryState? = memory
    private val observer = ::record

    init {
        require(firstAddress >= 0 && byteLength > 0 && firstAddress <= Long.MAX_VALUE - (byteLength - 1L)) {
            "Choose a nonnegative byte address and a positive, non-overflowing region length"
        }
        lastAddress = firstAddress + byteLength - 1L
        memory.addAddressObserver(observer, includeInstructionFetch = false)
    }

    /** Check the complete sequence, including repeated values and the absence of extra stores. */
    fun expectByteStores(address: Long, vararg expected: Int) {
        require(address in firstAddress..lastAddress) { "Expected port must be inside the traced region" }
        require(expected.all { it in 0..255 }) { "Expected byte values must be from 0 through 255" }
        requireComplete()
        requireCase(stores.size == expected.size) {
            "Expected ${expected.size} byte stores at 0x${address.toString(16)}, observed ${stores.size} stores in the region"
        }
        for ((index, store) in stores.withIndex()) {
            requireCase(store.address == address && store.length == 1) {
                "Expected byte store ${index + 1} at 0x${address.toString(16)}, " +
                    "got ${store.length}-byte store at 0x${store.address.toString(16)}"
            }
            val byte = store.value and 0xff
            requireCase(byte == expected[index]) {
                "Expected byte store ${index + 1} = ${expected[index]}, got $byte"
            }
        }
    }

    /** Check that no execution store of any width overlapped the observed object. */
    fun expectNone() {
        requireComplete()
        requireCase(stores.isEmpty()) {
            "Expected no stores in 0x${firstAddress.toString(16)}..0x${lastAddress.toString(16)}, observed ${stores.size}"
        }
    }

    override fun close() {
        subscribedMemory?.deleteAddressObserver(observer)
        subscribedMemory = null
    }

    private fun record(notice: MemoryAccessNotice) {
        if (notice.origin() != EXECUTION || notice.accessType != WRITE) return
        val address = notice.addressBits
        if (address > lastAddress || (address < firstAddress && firstAddress - address >= notice.length)) return
        if (stores.size == MAX_STORES) {
            overflow = true
        } else {
            stores.add(Store(address, notice.length, notice.value))
        }
    }

    private fun requireComplete() {
        requireCase(!overflow) { "Memory-store trace exceeds $MAX_STORES writes" }
    }

    private companion object {
        const val MAX_STORES = 65_536
    }
}
