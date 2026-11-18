package edu.missouristate.mars.devices

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES
import java.util.Collections
import java.util.Random
import kotlin.math.roundToInt

/**
 * Observes forward data-access notices without affecting architectural memory or execution.
 * A store miss allocates a line. Each notice counts once at its starting address;
 * instruction fetches and external inspection are excluded. Backstep keeps observations.
 */
class DataCacheSimulator {
    enum class Placement {
        DIRECT, FULL, SET_ASSOCIATIVE;

        fun wayChoices(blocks: Int): List<Int> = when (this) {
            DIRECT -> listOf(1)
            FULL -> listOf(blocks)
            SET_ASSOCIATIVE -> sizes.takeWhile { it <= blocks }
        }
    }

    enum class Replacement { LRU, RANDOM }

    data class Options(
        val blocks: Int = 8,
        val wordsPerBlock: Int = 4,
        val placement: Placement = Placement.DIRECT,
        val ways: Int = 1,
        val replacement: Replacement = Replacement.LRU,
    ) {
        init {
            require(blocks in sizes)
            require(wordsPerBlock in sizes)
            require(ways in placement.wayChoices(blocks))
        }

        val sets: Int get() = blocks / ways
        val bytes: Int get() = blocks * wordsPerBlock * WORD_LENGTH_BYTES
    }

    data class Block(val tag: Long?, val lastAccess: Long, val lastHit: Boolean)
    data class Access(
        val number: Long, val address: Long, val write: Boolean, val set: Int, val tag: Long, val block: Int, val previousTag: Long?,
    ) {
        val hit: Boolean get() = previousTag == tag
    }

    data class Snapshot(
        val connected: Boolean, val logging: Boolean, val options: Options, val accesses: Long, val hits: Long,
        val blocks: List<Block>, val recent: List<Access>,
    ) {
        val misses: Long get() = accesses - hits
        val hitRate: Int get() = if (accesses == 0L) 0 else (100.0 * hits / accesses).roundToInt()
    }

    private var options = Options()
    private var connected = true
    private var logging = false
    private var accesses = 0L
    private var hits = 0L
    private var tags = LongArray(options.blocks) { EMPTY_TAG }
    private var recency = LongArray(options.blocks)
    private var hit = BooleanArray(options.blocks)
    private val random = Random(0)
    private val recent = ArrayDeque<Access>()
    private var cached: Snapshot? = null

    fun observe(memory: MemoryState): AutoCloseable {
        val classroom = memory.dataSegmentBaseAddress.toUInt().toLong()..(memory.stackBaseAddress.toUInt().toLong() + 3)
        val listener: (MemoryAccessNotice) -> Unit = { notice ->
            val address = notice.addressBits
            // Full-width notices from this data-only subscription use the mapped user data segment.
            if (address in classroom || address > UInt.MAX_VALUE.toLong()) {
                record(notice)
            }
        }
        memory.addAddressObserver(listener, includeInstructionFetch = false)
        return AutoCloseable { memory.deleteAddressObserver(listener) }
    }

    @Synchronized
    fun record(notice: MemoryAccessNotice) {
        if (!connected || notice.origin() != EXECUTION || notice.isInstructionFetch) return
        val address = notice.addressBits
        val memoryBlock = (address ushr 2) / options.wordsPerBlock
        val set = (memoryBlock % options.sets).toInt()
        val tag = memoryBlock / options.sets
        val block = findBlock(set * options.ways, tag)
        val previous = tags[block]
        val number = ++accesses
        val found = previous == tag
        if (found) hits++
        tags[block] = tag
        recency[block] = number
        hit[block] = found
        if (logging) {
            if (recent.size == LOG_LIMIT) recent.removeFirst()
            recent.addLast(Access(number, address, notice.accessType == WRITE, set, tag, block, previous.takeUnless { it == EMPTY_TAG }))
        }
        cached = null
    }

    private fun findBlock(first: Int, tag: Long): Int {
        var oldest = first
        for (block in first until first + options.ways) {
            if (tags[block] == tag || tags[block] == EMPTY_TAG) return block
            if (recency[block] < recency[oldest]) oldest = block
        }
        // One-way sets have a single victim and do not consume the random sequence.
        return if (options.ways > 1 && options.replacement == Replacement.RANDOM) first + random.nextInt(options.ways) else oldest
    }

    @Synchronized
    fun snapshot(): Snapshot = cached ?: Snapshot(
        connected, logging, options, accesses, hits,
        Collections.unmodifiableList(tags.indices.map { Block(tags[it].takeUnless { tag -> tag == EMPTY_TAG }, recency[it], hit[it]) }),
        Collections.unmodifiableList(recent.toList()),
    ).also { cached = it }

    @Synchronized
    fun setConnected(value: Boolean) {
        connected = value
        cached = null
    }

    @Synchronized
    fun setLogging(value: Boolean) {
        logging = value
        cached = null
    }

    @Synchronized
    fun setReplacement(value: Replacement) {
        if (options.replacement == value) return
        options = options.copy(replacement = value)
        cached = null
    }

    @Synchronized
    fun configure(value: Options) {
        options = value
        reset()
    }

    @Synchronized
    fun reset() {
        tags = LongArray(options.blocks) { EMPTY_TAG }
        recency = LongArray(options.blocks)
        hit = BooleanArray(options.blocks)
        accesses = 0
        hits = 0
        random.setSeed(0)
        recent.clear()
        cached = null
    }

    companion object {
        val sizes: List<Int> = Collections.unmodifiableList((0..11).map { 1 shl it })
        private const val EMPTY_TAG = -1L
        private const val LOG_LIMIT = 128
    }
}
