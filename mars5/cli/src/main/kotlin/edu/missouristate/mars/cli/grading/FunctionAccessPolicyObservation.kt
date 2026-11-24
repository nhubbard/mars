package edu.missouristate.mars.cli.grading

/** One observation lifetime: either the complete case or exactly one invocation. */
internal class FunctionAccessPolicyObservation(
    private val execution: AssemblyCase,
    policies: List<FunctionAccessPolicyCheck>,
) : AutoCloseable {
  private data class Observed(
      val trace: MemoryAccessTrace,
      val readable: List<Pair<Long, Int>>,
      val writable: List<Pair<Long, Int>>,
      val checkReads: Boolean,
  )

  private val observed = run {
    var watchedBytes = 0L
    policies.map { policy ->
      val first = execution.readDataAddress(policy.from.label, policy.from.offset.toLong())
      val until = execution.readDataAddress(policy.until.label, policy.until.offset.toLong())
      execution.expectCondition(
          until > first && until - first <= ConsoleMemoryCheck.MAX_BYTES,
          "Assembled access interval must contain one through one MiB of bytes",
      )
      watchedBytes += until - first
      execution.expectCondition(
          watchedBytes <= ConsoleMemoryCheck.MAX_BYTES,
          "Combined assembled access intervals exceed one MiB",
      )
      fun resolve(windows: List<FunctionDataWindow>) = windows.map { window ->
        val address = execution.readDataAddress(window.at.label, window.at.offset.toLong())
        execution.expectCondition(
            address <= Long.MAX_VALUE - window.byteLength,
            "Permitted data window overflows",
        )
        address to window.byteLength
      }
      Observed(
          execution.traceMemoryAccesses(first, (until - first).toInt(), policy.maxAccesses),
          resolve(policy.readable),
          resolve(policy.writable),
          policy.checkReads,
      )
    }
  }

  fun expectAllowed() {
    for ((trace, readable, writable, checkReads) in observed) for (access in trace.snapshot()) {
      if (!access.write && !checkReads) continue
      val windows = if (access.write) writable else readable
      execution.expectCondition(
          windows.any { (first, length) ->
            access.address >= first &&
                access.length <= length &&
                access.address - first <= length.toLong() - access.length
          },
          "Execution ${if (access.write) "store" else "read"} of ${access.length} bytes at 0x${access.address.toString(16)} is outside its permitted caller windows",
      )
    }
  }

  override fun close() {
    observed.forEach { it.trace.closeAndDiscard() }
  }
}
