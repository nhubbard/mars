package edu.missouristate.mars.cli.grading

/**
 * Bound one invocation's complete data-access history over the assembled enclosing span of
 * [regions], including padding and private workspace between those objects. This observes capacity
 * only; access policies separately define read/write permission. Region order does not prescribe
 * the submission's data layout.
 */
data class FunctionAccessHistoryCheck(
    val regions: List<FunctionDataWindow>,
    val maxAccesses: Int = 65_536,
) {
  init {
    require(
        regions.size in 1..64 &&
            regions.all { it.byteLength > 0 } &&
            regions.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES,
    ) {
      "Observe one through 64 positive data regions totaling at most one MiB"
    }
    require(maxAccesses in 1..1_000_000) { "Observe one through one million data accesses" }
  }
}

internal class FunctionAccessHistoryObservation(
    execution: AssemblyCase,
    definition: FunctionAccessHistoryCheck,
) : AutoCloseable {
  private val trace = run {
    val regions =
        definition.regions.map { region ->
          val first = execution.readDataAddress(region.at.label, region.at.offset.toLong())
          execution.expectCondition(
              first <= Long.MAX_VALUE - region.byteLength,
              "Observed data region overflows",
          )
          first to first + region.byteLength
        }
    val first = regions.minOf { it.first }
    val until = regions.maxOf { it.second }
    execution.expectCondition(
        until > first && until - first <= ConsoleMemoryCheck.MAX_BYTES,
        "Enclosing data-access history span must contain one through one MiB of bytes",
    )
    execution.traceMemoryAccesses(
        first,
        (until - first).toInt(),
        maxAccesses = definition.maxAccesses,
    )
  }

  /** Snapshot rejects overflow; a truncated history is never accepted. */
  fun expectComplete() {
    trace.snapshot()
  }

  override fun close() {
    trace.close()
  }
}
