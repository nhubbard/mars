package edu.missouristate.mars.cli.grading

import edu.missouristate.mars.devices.DataCacheSimulator
import edu.missouristate.mars.devices.DataCacheSimulator.Access
import edu.missouristate.mars.devices.DataCacheSimulator.Options
import edu.missouristate.mars.devices.DataCacheSimulator.Snapshot
import edu.missouristate.mars.mips.hardware.MemoryState

/** Owns an opt-in observation of the same data cache model used by the IDE. */
class DataCacheCheck
internal constructor(
    memory: MemoryState,
    options: Options,
    logAccesses: Boolean,
    logLimit: Int,
) : AutoCloseable {
  private val cache =
      DataCacheSimulator(logLimit).apply {
        configure(options)
        setLogging(logAccesses)
      }
  private var observation: AutoCloseable? = cache.observe(memory)

  fun snapshot(): Snapshot = cache.snapshot()

  /** Return a complete immutable log, failing the case if its configured capacity was exceeded. */
  fun recordedAccesses(): List<Access> {
    val snapshot = snapshot()
    check(snapshot.logging) { "Enable logAccesses when connecting this cache" }
    requireCase(snapshot.recent.size.toLong() == snapshot.accesses) {
      "Cache access log exceeds its configured capacity"
    }
    return snapshot.recent
  }

  /** Establish a cold measurement boundary while execution is stopped. */
  fun reset() {
    check(observation != null) { "Cannot reset a closed cache observation" }
    cache.reset()
  }

  /** Check an exact reference measurement; correctness grading can inspect counts independently. */
  fun expectCounts(accesses: Long, hits: Long) {
    require(accesses >= 0 && hits in 0..accesses) {
      "Choose nonnegative accesses and hits no greater than accesses"
    }
    val actual = snapshot()
    requireCase(actual.accesses == accesses && actual.hits == hits) {
      "Expected cache accesses/hits = $accesses/$hits, got ${actual.accesses}/${actual.hits}"
    }
  }

  override fun close() {
    observation?.close()
    observation = null
  }
}
