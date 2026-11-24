package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState

/**
 * One execution data notice for each read and each write of an element, with each read preceding
 * its mapped write. destinationIndices[sourceIndex] declares the destination index. Traversal order
 * is unrestricted. Check final bytes separately; use accessBounds to forbid traffic outside the
 * active windows, including an empty transfer's allocation.
 */
data class FunctionTransferCheck(
    val source: FunctionDataAddress,
    val destination: FunctionDataAddress,
    val destinationIndices: List<Int>,
    val elementBytes: Int = 4,
) {
  init {
    require(elementBytes in setOf(1, 2, 4)) { "Transfer elements must be one, two or four bytes" }
    require(
        destinationIndices.size <= 65536 &&
            destinationIndices.size.toLong() * elementBytes <= ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Transfer maps exceed 65536 elements or one MiB"
    }
    require(
        destinationIndices.all { it in destinationIndices.indices } &&
            destinationIndices.toSet().size == destinationIndices.size,
    ) {
      "Transfer destination indices must be a permutation of the active element indices"
    }
  }

  val byteLength: Int
    get() = destinationIndices.size * elementBytes
}

/** Bounded streaming observation; no full access history or simulator state changes. */
internal class FunctionTransferObservation(
    memory: MemoryState,
    private val check: FunctionTransferCheck,
    private val source: Long,
    private val destination: Long,
) : AutoCloseable {
  private val read = BooleanArray(check.destinationIndices.size)
  private val written = BooleanArray(check.destinationIndices.size)
  private val sourceForDestination =
      IntArray(check.destinationIndices.size).also { inverse ->
        check.destinationIndices.forEachIndexed { from, to -> inverse[to] = from }
      }
  private var failure: String? = null
  private var subscribed: MemoryState? = null
  private val observer = ::record

  init {
    requireCase(
        source >= 0 &&
            destination >= 0 &&
            source % check.elementBytes == 0L &&
            destination % check.elementBytes == 0L,
    ) {
      "Transfer source and destination must be nonnegative aligned element addresses"
    }
    requireCase(
        source <= Long.MAX_VALUE - check.byteLength &&
            destination <= Long.MAX_VALUE - check.byteLength,
    ) {
      "Transfer address interval overflows"
    }
    requireCase(
        check.byteLength == 0 ||
            source + check.byteLength <= destination ||
            destination + check.byteLength <= source,
    ) {
      "Transfer source and destination windows overlap"
    }
    memory.addAddressObserver(observer, includeInstructionFetch = false)
    subscribed = memory
  }

  fun beginCall() {
    read.fill(false)
    written.fill(false)
    failure = null
  }

  fun expectComplete() {
    requireCase(failure == null) { checkNotNull(failure) }
    val missingRead = read.indexOfFirst { !it }
    requireCase(missingRead < 0) { "Transfer source element $missingRead was not read" }
    val missingWrite = written.indexOfFirst { !it }
    requireCase(missingWrite < 0) { "Transfer destination element $missingWrite was not written" }
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
    fun touches(base: Long) =
        address < base + check.byteLength && (address >= base || base - address < notice.length)
    val fromSource = touches(source)
    if (!fromSource && !touches(destination)) return
    val relative = address - if (fromSource) source else destination
    val region = if (fromSource) "source" else "destination"
    val isWrite = notice.accessType == WRITE
    failure =
        when {
          relative < 0 ||
              relative >= check.byteLength ||
              relative % check.elementBytes != 0L ||
              notice.length != check.elementBytes ->
              "Transfer $region access must be one aligned ${check.elementBytes}-byte element; observed ${notice.length} bytes at 0x${address.toString(16)}"
          isWrite == fromSource ->
              "Transfer $region must be ${if (fromSource) "read-only" else "write-only"}"
          else -> {
            val index = (relative / check.elementBytes).toInt()
            when {
              fromSource && read[index] -> "Transfer source element $index was read more than once"
              !fromSource && written[index] ->
                  "Transfer destination element $index was written more than once"
              !fromSource && !read[sourceForDestination[index]] ->
                  "Transfer destination element $index was written before source element ${sourceForDestination[index]} was read"
              else -> {
                if (fromSource) read[index] = true else written[index] = true
                null
              }
            }
          }
        }
  }
}
