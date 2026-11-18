package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import java.util.Collections

/**
 * Bounded forward execution data notices overlapping a byte region, including full crossing accesses.
 * Inspect or close only while execution is stopped. External inspection and backstep are excluded;
 * replayed forward accesses append to the history rather than rewinding it.
 */
class MemoryAccessTrace internal constructor(
    memory: MemoryState,
    private val firstAddress: Long,
    byteLength: Int,
    private val maxAccesses: Int,
) : AutoCloseable {
    data class Access(val address: Long, val length: Int, val write: Boolean)

    private val lastAddress: Long
    private val accesses = ArrayList<Access>()
    private var overflow = false
    private var subscribedMemory: MemoryState? = memory
    private val observer = ::record

    init {
        require(firstAddress >= 0 && byteLength > 0 && firstAddress <= Long.MAX_VALUE - (byteLength - 1L)) {
            "Choose a nonnegative byte address and a positive, non-overflowing region length"
        }
        require(maxAccesses > 0) { "Memory-access history capacity must be positive" }
        lastAddress = firstAddress + byteLength - 1L
        memory.addAddressObserver(observer, includeInstructionFetch = false)
    }

    /** Complete immutable detached history; a truncated observation fails explicitly. */
    fun snapshot(): List<Access> {
        requireCase(!overflow) { "Memory-access trace exceeds $maxAccesses notices" }
        return Collections.unmodifiableList(accesses.toList())
    }

    /** Every observed read must fit wholly in this interval; zero length permits no reads. */
    fun expectReadsWithin(address: Long, byteLength: Int) {
        require(address >= 0 && byteLength >= 0 &&
            (byteLength == 0 || address <= Long.MAX_VALUE - (byteLength - 1L))) {
            "Choose a nonnegative byte address and a nonnegative, non-overflowing permitted length"
        }
        for (access in snapshot()) {
            if (access.write) continue
            val fits = access.address >= address && access.length.toLong() <= byteLength &&
                access.address - address <= byteLength.toLong() - access.length
            requireCase(fits) {
                "Expected reads within $byteLength bytes at 0x${address.toString(16)}, " +
                    "observed ${access.length}-byte read at 0x${access.address.toString(16)}"
            }
        }
    }

    override fun close() {
        subscribedMemory?.deleteAddressObserver(observer)
        subscribedMemory = null
    }

    private fun record(notice: MemoryAccessNotice) {
        if (notice.origin() != EXECUTION || notice.isInstructionFetch) return
        val address = notice.addressBits
        if (address > lastAddress || (address < firstAddress && firstAddress - address >= notice.length)) return
        if (accesses.size == maxAccesses) {
            overflow = true
        } else {
            accesses.add(Access(address, notice.length, notice.accessType == WRITE))
        }
    }
}
