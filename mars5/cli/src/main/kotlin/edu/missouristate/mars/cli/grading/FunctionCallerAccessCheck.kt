package edu.missouristate.mars.cli.grading

/**
 * One invocation's caller-owned objects, independent of their assembled order. The enclosing span
 * shares one history capacity, including traffic in private gaps. Permissions and byte preservation
 * apply only to the declared objects.
 */
data class FunctionCallerAccessCheck(
    val objects: List<FunctionDataWindow>,
    val readable: List<FunctionDataWindow> = emptyList(),
    val writable: List<FunctionDataWindow> = emptyList(),
    val maxAccesses: Int = 65_536,
) {
  init {
    require(
        objects.size in 1..64 &&
            objects.all { it.byteLength > 0 } &&
            objects.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Declare one through 64 positive caller objects totaling at most one MiB"
    }
    require(
        readable.size + writable.size <= 64 &&
            readable.sumOf { it.byteLength.toLong() } + writable.sumOf { it.byteLength.toLong() } <=
                ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Caller permissions exceed 64 windows or one MiB"
    }
    require(maxAccesses in 1..2_000_000) { "Observe one through two million caller-span accesses" }
  }
}

internal class FunctionCallerAccessObservation(
    private val execution: AssemblyCase,
    definition: FunctionCallerAccessCheck,
) : AutoCloseable {
  private data class Region(val address: Long, val size: Int) {
    fun fits(address: Long, size: Int) =
        address >= this.address &&
            size <= this.size &&
            address - this.address <= this.size.toLong() - size

    fun overlaps(address: Long, size: Int) =
        address < this.address + this.size &&
            (address >= this.address || this.address - address < size)
  }

  private fun resolve(windows: List<FunctionDataWindow>) = windows.map { window ->
    val address = execution.readDataAddress(window.at.label, window.at.offset.toLong())
    execution.expectCondition(
        address <= Long.MAX_VALUE - window.byteLength,
        "Caller data window overflows",
    )
    Region(address, window.byteLength)
  }

  private val objects = resolve(definition.objects)
  private val readable = resolve(definition.readable)
  private val writable = resolve(definition.writable)
  private val before = objects.map { execution.readBytes(it.address, it.size) }
  private val trace = run {
    val first = objects.minOf { it.address }
    val until = objects.maxOf { it.address + it.size }
    execution.expectCondition(
        until > first && until - first <= Int.MAX_VALUE,
        "Enclosing caller-object span must contain one through 2147483647 bytes",
    )
    execution.traceMemoryAccesses(first, (until - first).toInt(), definition.maxAccesses)
  }

  fun expectAllowed() {
    // Snapshot also rejects overflow caused by accesses to private gaps.
    for ((address, length, write) in trace.snapshot()) {
      if (objects.none { it.overlaps(address, length) }) continue
      val permitted = if (write) writable else readable
      execution.expectCondition(
          permitted.any { it.fits(address, length) },
          "Execution ${if (write) "store" else "read"} of $length bytes at 0x${address.toString(16)} is outside its permitted caller-object windows",
      )
    }
    for ((index, region) in objects.withIndex()) {
      val after = execution.readBytes(region.address, region.size)
      execution.expectCondition(
          after.indices.all { byte ->
            writable.any { it.fits(region.address + byte, 1) } || after[byte] == before[index][byte]
          },
          "Caller bytes outside selected output changed",
      )
    }
  }

  override fun close() {
    trace.close()
  }
}
