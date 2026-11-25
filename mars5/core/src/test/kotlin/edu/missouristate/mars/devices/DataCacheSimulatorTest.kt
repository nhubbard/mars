package edu.missouristate.mars.devices

import edu.missouristate.mars.devices.DataCacheSimulator.Options
import edu.missouristate.mars.devices.DataCacheSimulator.Placement
import edu.missouristate.mars.devices.DataCacheSimulator.Replacement
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.READ
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DataCacheSimulatorTest {
  @Test
  fun `direct mapping shares a block across bytes and evicts a conflicting unsigned address`() {
    val cache = DataCacheSimulator()
    cache.configure(Options(blocks = 2, wordsPerBlock = 2, replacement = Replacement.RANDOM))
    cache.setLogging(true)
    cache.access(0x80000000.toInt())
    cache.access(0x80000007.toInt(), WRITE)
    cache.access(0x80000010.toInt())
    val snapshot = cache.snapshot()
    assertSame(snapshot, cache.snapshot())
    assertEquals(16, snapshot.options.bytes)
    assertEquals(3L, snapshot.accesses)
    assertEquals(1L, snapshot.hits)
    assertEquals(2L, snapshot.misses)
    assertEquals(33, snapshot.hitRate)
    assertEquals(listOf(0, 0, 0), snapshot.recent.map { it.block })
    assertEquals(listOf(0x08000000L, 0x08000000L, 0x08000001L), snapshot.recent.map { it.tag })
    assertEquals(listOf(false, true, false), snapshot.recent.map { it.hit })
    assertTrue(snapshot.recent[1].write)
    assertNull(snapshot.recent.first().previousTag)
    assertEquals(0x08000000L, snapshot.recent.last().previousTag)
    assertEquals(listOf(0x08000001L, null), snapshot.blocks.map { it.tag })
  }

  @Test
  fun `two-way sets isolate recency and evict the least recently used line`() {
    val cache = DataCacheSimulator()
    cache.configure(
        Options(blocks = 4, wordsPerBlock = 1, placement = Placement.SET_ASSOCIATIVE, ways = 2),
    )
    cache.setLogging(true)
    for (address in listOf(0, 8, 4, 0, 16, 12, 20)) cache.access(address)
    val snapshot = cache.snapshot()
    assertEquals(listOf(0, 1, 2, 0, 1, 3, 2), snapshot.recent.map { it.block })
    assertEquals(listOf(0, 0, 1, 0, 0, 1, 1), snapshot.recent.map { it.set })
    assertEquals(listOf(0L, 2L, 2L, 1L), snapshot.blocks.map { it.tag })
    assertEquals(listOf(4L, 5L, 7L, 6L), snapshot.blocks.map { it.lastAccess })
    assertTrue(snapshot.blocks.first().lastHit)
  }

  @Test
  fun `fully associative random eviction is reproducible after reset`() {
    val cache = DataCacheSimulator()
    cache.configure(
        Options(
            blocks = 2,
            wordsPerBlock = 1,
            placement = Placement.FULL,
            ways = 2,
            replacement = Replacement.RANDOM,
        ),
    )
    cache.setLogging(true)
    repeat(2) {
      for (address in listOf(0, 4, 8, 12, 16)) cache.access(address)
      assertEquals(listOf(0, 1, 1, 1, 0), cache.snapshot().recent.map { it.block })
      cache.reset()
      assertEquals(0, cache.snapshot().hitRate)
      assertTrue(cache.snapshot().recent.isEmpty())
    }
  }

  @Test
  fun `inspection and disconnected accesses are ignored while logging is bounded`() {
    val cache = DataCacheSimulator()
    cache.record(MemoryAccessNotice(READ, 0, 0))
    assertEquals(0L, cache.snapshot().accesses)
    cache.setConnected(false)
    cache.access(0)
    assertFalse(cache.snapshot().connected)
    assertEquals(0L, cache.snapshot().accesses)
    cache.setConnected(true)
    cache.access(0)
    assertTrue(cache.snapshot().recent.isEmpty())
    cache.setLogging(true)
    repeat(140) { cache.access(0) }
    cache.setLogging(false)
    cache.access(0)
    val snapshot = cache.snapshot()
    assertEquals(142L, snapshot.accesses)
    assertEquals(141L, snapshot.hits)
    assertFalse(snapshot.logging)
    assertEquals((14L..141L).toList(), snapshot.recent.map { it.number })
  }

  @Test
  fun `cache geometry must use supported sizes and a placement-compatible set width`() {
    assertThrows<IllegalArgumentException> { Options(blocks = 3) }
    assertThrows<IllegalArgumentException> { Options(wordsPerBlock = 0) }
    assertThrows<IllegalArgumentException> { Options(ways = 2) }
    assertEquals(listOf(1, 2, 4), Placement.SET_ASSOCIATIVE.wayChoices(4))
  }

  @Test
  fun `public snapshots and geometry cannot be mutated and replacement switches retain contents`() {
    val cache = DataCacheSimulator()
    cache.configure(Options(2, 1, Placement.FULL, 2))
    cache.setLogging(true)
    cache.access(0)
    cache.access(4)
    cache.access(0)
    val prefix = cache.snapshot()
    assertThrows<UnsupportedOperationException> {
      (prefix.blocks as MutableList).clear()
    }
    assertThrows<UnsupportedOperationException> {
      (prefix.recent as MutableList).clear()
    }
    assertThrows<UnsupportedOperationException> {
      (DataCacheSimulator.sizes as MutableList).clear()
    }
    cache.setReplacement(Replacement.LRU)
    assertSame(prefix, cache.snapshot())
    cache.setReplacement(Replacement.RANDOM)
    assertEquals(Replacement.RANDOM, cache.snapshot().options.replacement)
    assertEquals(prefix.blocks, cache.snapshot().blocks)
    cache.access(8)
    assertEquals(4L, cache.snapshot().accesses)
    assertEquals(3L, prefix.accesses)
    assertEquals(listOf(0L, 1L), prefix.blocks.map { it.tag })
    assertEquals(1L, prefix.hits)
  }

  @Test
  fun `logging retains native widths and bounded history without splitting crossing notices`() {
    assertThrows<IllegalArgumentException> { DataCacheSimulator(0) }
    val cache = DataCacheSimulator(logLimit = 2)
    cache.configure(Options(1, 4))
    cache.setLogging(true)
    AccessOrigin.duringExecution<Unit, RuntimeException> {
      cache.record(
          object : MemoryAccessNotice(READ, 12, 0) {
            override val length = 16
          },
      )
    }
    assertEquals(1L, cache.snapshot().accesses)
    assertEquals(0L, cache.snapshot().blocks.single().tag)
    assertEquals(16, cache.snapshot().recent.single().length)
    cache.access(16)
    cache.access(32, WRITE)
    val snapshot = cache.snapshot()
    assertEquals(3L, snapshot.accesses)
    assertEquals(listOf(2L, 3L), snapshot.recent.map { it.number })
    assertEquals(listOf(4, 4), snapshot.recent.map { it.length })
  }

  private fun DataCacheSimulator.access(address: Int, type: Int = READ) {
    AccessOrigin.duringExecution<Unit, RuntimeException> {
      record(MemoryAccessNotice(type, address, 0))
    }
  }
}
