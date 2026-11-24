package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState

/**
 * Every byte in this range must be written during each call, before its first execution read. Reads
 * after initialization and repeated stores are allowed, in any order. Setup and previous calls do
 * not initialize a byte for this call. Use accessBounds/storeBounds for ownership.
 */
data class FunctionInitializationCheck(val at: FunctionDataAddress, val byteLength: Int) {
  init {
    require(byteLength in 0..ConsoleMemoryCheck.MAX_BYTES) {
      "Initialize zero through one MiB of function output"
    }
  }
}

/** One bounded byte bitmap per active range; no execution history or simulator modifications. */
internal class FunctionInitializationObservation(
    memory: MemoryState,
    private val check: FunctionInitializationCheck,
    private val first: Long,
) : AutoCloseable {
  private val written = BooleanArray(check.byteLength)
  private var failure: String? = null
  private var subscribed: MemoryState? = null
  private val observer = ::record

  init {
    requireCase(first >= 0 && first <= Long.MAX_VALUE - check.byteLength) {
      "Initialization address interval overflows or is negative"
    }
    memory.addAddressObserver(observer, includeInstructionFetch = false)
    subscribed = memory
  }

  fun beginCall() {
    written.fill(false)
    failure = null
  }

  fun expectComplete() {
    requireCase(failure == null) { checkNotNull(failure) }
    val missing = written.indexOfFirst { !it }
    requireCase(missing < 0) {
      "Output byte $missing at ${check.at.label}+${check.at.offset} was not initialized during this call"
    }
  }

  override fun close() {
    subscribed?.deleteAddressObserver(observer)
    subscribed = null
  }

  private fun record(notice: MemoryAccessNotice) {
    if (
        failure != null ||
            notice.origin() != EXECUTION ||
            notice.isInstructionFetch ||
            check.byteLength == 0
    )
        return
    val address = notice.addressBits
    if (
        address >= first + check.byteLength || (address < first && first - address >= notice.length)
    )
        return
    val start = maxOf(0L, address - first).toInt()
    val end = minOf(check.byteLength.toLong(), address - first + notice.length).toInt()
    for (index in start until end) {
      if (notice.accessType == WRITE) written[index] = true
      else if (!written[index]) {
        failure =
            "Output byte $index at ${check.at.label}+${check.at.offset} was read before initialization during this call"
        return
      }
    }
  }
}
