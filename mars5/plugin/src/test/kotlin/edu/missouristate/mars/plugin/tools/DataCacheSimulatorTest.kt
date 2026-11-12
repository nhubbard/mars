package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.READ
import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Options
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Placement
import edu.missouristate.mars.plugin.tools.DataCacheSimulator.Replacement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
        assertEquals(listOf(0x08000000, 0x08000000, 0x08000001), snapshot.recent.map { it.tag })
        assertEquals(listOf(false, true, false), snapshot.recent.map { it.hit })
        assertTrue(snapshot.recent[1].write)
        assertNull(snapshot.recent.first().previousTag)
        assertEquals(0x08000000, snapshot.recent.last().previousTag)
        assertEquals(listOf(0x08000001, null), snapshot.blocks.map { it.tag })
    }

    @Test
    fun `two-way sets isolate recency and evict the least recently used line`() {
        val cache = DataCacheSimulator()
        cache.configure(Options(blocks = 4, wordsPerBlock = 1, placement = Placement.SET_ASSOCIATIVE, ways = 2))
        cache.setLogging(true)
        for (address in listOf(0, 8, 4, 0, 16, 12, 20)) cache.access(address)
        val snapshot = cache.snapshot()
        assertEquals(listOf(0, 1, 2, 0, 1, 3, 2), snapshot.recent.map { it.block })
        assertEquals(listOf(0, 0, 1, 0, 0, 1, 1), snapshot.recent.map { it.set })
        assertEquals(listOf(0, 2, 2, 1), snapshot.blocks.map { it.tag })
        assertEquals(listOf(4L, 5L, 7L, 6L), snapshot.blocks.map { it.lastAccess })
        assertTrue(snapshot.blocks.first().lastHit)
    }

    @Test
    fun `fully associative random eviction is reproducible after reset`() {
        val cache = DataCacheSimulator()
        cache.configure(Options(blocks = 2, wordsPerBlock = 1, placement = Placement.FULL, ways = 2, replacement = Replacement.RANDOM))
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
        assertThrows(IllegalArgumentException::class.java) { Options(blocks = 3) }
        assertThrows(IllegalArgumentException::class.java) { Options(wordsPerBlock = 0) }
        assertThrows(IllegalArgumentException::class.java) { Options(ways = 2) }
        assertEquals(listOf(1, 2, 4), Placement.SET_ASSOCIATIVE.wayChoices(4))
    }

    private fun DataCacheSimulator.access(address: Int, type: Int = READ) {
        AccessOrigin.duringExecution<Unit, RuntimeException> { record(MemoryAccessNotice(type, address, 0)) }
    }
}
