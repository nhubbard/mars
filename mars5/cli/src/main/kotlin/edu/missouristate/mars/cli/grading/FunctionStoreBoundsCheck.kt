package edu.missouristate.mars.cli.grading

/**
 * Observe stores overlapping [from, until), resolving both ends from the submission. Every such
 * store must fit wholly in the optional permitted window. Reads are unrestricted by this check; use
 * accessBounds for input bounds. A zero permitted length forbids stores. The permitted window may
 * lie outside the watched interval, as with reordered caller data.
 */
data class FunctionStoreBoundsCheck(
    val from: FunctionDataAddress,
    val until: FunctionDataAddress,
    val permittedFrom: FunctionDataAddress? = null,
    val permittedByteLength: Int = 0,
) {
  init {
    require(permittedByteLength in 0..ConsoleMemoryCheck.MAX_BYTES) {
      "Permit zero through one MiB of function stores"
    }
    require(permittedFrom != null || permittedByteLength == 0) {
      "A nonempty store window needs a permitted address"
    }
  }
}
