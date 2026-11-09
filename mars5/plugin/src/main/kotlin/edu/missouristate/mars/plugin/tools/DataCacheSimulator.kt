package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessNotice.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.mips.hardware.MemoryState.WORD_LENGTH_BYTES
import java.util.Random
import java.util.function.Consumer
import kotlin.math.roundToInt

/** One cache per execution. The simulator writes observations and the tool window reads snapshots. */
internal class DataCacheSimulator {
    enum class Placement(val messageKey: String) {
        DIRECT("cache.direct"), FULL("cache.full"), SET_ASSOCIATIVE("cache.setAssociative");

        fun wayChoices(blocks: Int): List<Int> = when (this) {
            DIRECT -> listOf(1)
            FULL -> listOf(blocks)
            SET_ASSOCIATIVE -> sizes.takeWhile { it <= blocks }
        }
    }

    enum class Replacement(val messageKey: String) { LRU("cache.lru"), RANDOM("cache.random") }

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

    data class Block(val tag: Int?, val lastAccess: Long, val lastHit: Boolean)
    data class Access(
        val number: Long, val address: Int, val write: Boolean, val set: Int, val tag: Int, val block: Int, val previousTag: Int?,
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
    private var tags = IntArray(options.blocks) { emptyTag }
    private var recency = LongArray(options.blocks)
    private var hit = BooleanArray(options.blocks)
    private val random = Random(0)
    private val recent = ArrayDeque<Access>()
    private var cached: Snapshot? = null

    fun observe(memory: MemoryState): AutoCloseable {
        val listener = Consumer<MemoryAccessNotice>(::record)
        memory.addObserver(listener, memory.dataSegmentBaseAddress, memory.stackBaseAddress)
        return AutoCloseable { memory.deleteObserver(listener) }
    }

    @Synchronized
    fun record(notice: MemoryAccessNotice) {
        if (!connected || notice.origin() != EXECUTION) return
        val address = notice.address
        val memoryBlock = (address ushr 2) / options.wordsPerBlock
        val set = memoryBlock % options.sets
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
            if (recent.size == logLimit) recent.removeFirst()
            recent.addLast(Access(number, address, notice.accessType == WRITE, set, tag, block, previous.takeUnless { it == emptyTag }))
        }
        cached = null
    }

    private fun findBlock(first: Int, tag: Int): Int {
        var oldest = first
        for (block in first until first + options.ways) {
            if (tags[block] == tag || tags[block] == emptyTag) return block
            if (recency[block] < recency[oldest]) oldest = block
        }
        // One-way sets have a single victim and do not consume the random sequence.
        return if (options.ways > 1 && options.replacement == Replacement.RANDOM) first + random.nextInt(options.ways) else oldest
    }

    @Synchronized
    fun snapshot(): Snapshot = cached ?: Snapshot(
        connected, logging, options, accesses, hits,
        tags.indices.map { Block(tags[it].takeUnless { tag -> tag == emptyTag }, recency[it], hit[it]) }, recent.toList(),
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
        tags = IntArray(options.blocks) { emptyTag }
        recency = LongArray(options.blocks)
        hit = BooleanArray(options.blocks)
        accesses = 0
        hits = 0
        random.setSeed(0)
        recent.clear()
        cached = null
    }

    companion object {
        val sizes = (0..11).map { 1 shl it }
        private const val emptyTag = -1
        private const val logLimit = 128
    }
}
