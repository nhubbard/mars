package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.TreeMap

/** Per-execution memory grid. Repaints consume snapshots and never read simulator memory. */
internal class MemoryGridDisplay(private var captureAtLaunch: Boolean = false, mode: Mode = Mode.BITMAP) {
    enum class Mode(val nameKey: String, val helpKey: String, val inspectionHintKey: String) {
        BITMAP("bitmap.name", "bitmap.help", "bitmap.inspect.hint"),
        REFERENCES("references.name", "references.help", "references.inspect.hint");

        fun defaults() = if (this == REFERENCES) Options(256, 256, 16, 16) else Options()
    }

    enum class Base(val messageKey: String, val range: (MemoryState) -> LongRange?) {
        TEXT("references.text", { narrowRange(it.textBaseAddress) }),
        GLOBAL_DATA("bitmap.global", { narrowRange(it.dataSegmentBaseAddress) }),
        GLOBAL_POINTER("bitmap.gp", { narrowRange(it.globalPointer) }),
        STATIC_DATA("bitmap.static", { narrowRange(it.dataBaseAddress) }),
        HEAP("bitmap.heap", { narrowRange(it.heapBaseAddress) }),
        MMIO("bitmap.mmio", { narrowRange(it.memoryMapBaseAddress) }),
        WIDE_DATA("bitmap.wideData", MemoryState::wideDataRange),
        WIDE_TEXT("references.wideText", MemoryState::wideTextRange),
        CUSTOM("bitmap.custom", { null }),
    }

    data class Options(
        val width: Int = 512, val height: Int = 256, val unitWidth: Int = 1, val unitHeight: Int = 1,
        val base: Base = Base.STATIC_DATA, val wordsPerCell: Int = 1, val customAddress: Long = 0, val captureInitial: Boolean = false,
    ) {
        init {
            require(width in displayWidths && height in displayHeights)
            require(unitWidth in unitSizes && unitHeight in unitSizes)
            require(wordsPerCell in wordCounts)
            require(customAddress >= 0 && customAddress and 3L == 0L) { "Custom base address must be nonnegative and word aligned" }
        }

        val columns = width / unitWidth
        val rows = height / unitHeight
        val cells = columns * rows
    }

    data class Frame(val options: Options, val image: BufferedImage?)
    data class Controls(val connected: Boolean, val addresses: Map<Base, Long>, val options: Options)
    data class Snapshot(val connected: Boolean, val addresses: Map<Base, Long>, val frame: Frame)
    data class ReferenceCell(val address: Long, val count: Long)
    data class BitmapCell(val column: Int, val row: Int, val address: Long, val rgb: Int)

    private val tracksReferences = mode == Mode.REFERENCES
    private var connected = captureAtLaunch
    private var options = mode.defaults()
    private var ranges = emptyMap<Base, LongRange>()
    private var addresses = emptyMap<Base, Long>()
    private var pixels: IntArray? = null
    private var counts: LongArray? = null
    private val palette = TreeMap(defaultPalette)
    private var cachedFrame: Frame? = null
    private var memory: MemoryState? = null
    private var unsubscribe: (() -> Unit)? = null
    private var generation = 0L

    @Synchronized
    fun observe(source: MemoryState): AutoCloseable {
        memory = source
        ranges = buildMap {
            for (base in Base.entries) base.range(source)?.let { put(base, it) }
        }
        addresses = ranges.mapValues { (_, range) -> range.first }
        if (connected && options.captureInitial) captureInitialPixels(source)
        startWatching()
        return AutoCloseable { detach() }
    }

    @Synchronized
    fun controls() = Controls(connected, addresses, options)

    @Synchronized
    fun snapshot(): Snapshot {
        val frame = cachedFrame ?: Frame(options, pixels?.let { colors ->
            BufferedImage(options.columns, options.rows, BufferedImage.TYPE_INT_RGB).apply {
                colors.copyInto((raster.dataBuffer as DataBufferInt).data)
            }
        }).also { cachedFrame = it }
        return Snapshot(connected, addresses, frame)
    }

    @Synchronized
    fun setConnected(value: Boolean) {
        stopWatching()
        connected = value
        startWatching()
    }

    @Synchronized
    fun setSourceConnection(value: Boolean) {
        captureAtLaunch = value
        setConnected(value)
    }

    @Synchronized
    fun releaseView() {
        if (!captureAtLaunch) setConnected(false)
    }

    @Synchronized
    fun configure(value: Options) {
        require(!tracksReferences || !value.captureInitial) { "Initial pixels apply only to Bitmap Display" }
        stopWatching()
        options = value
        reset()
        startWatching()
    }

    @Synchronized
    fun reset() {
        counts = null
        pixels = if (tracksReferences && colorAt(0) != 0) IntArray(options.cells) { colorAt(0) } else null
        cachedFrame = null
    }

    @Synchronized
    fun colorAt(count: Long): Int = palette.floorEntry(count).value

    @Synchronized
    fun setColor(threshold: Long, rgb: Int) {
        palette[threshold] = rgb and RGB_MASK
        val current = counts
        pixels = IntArray(options.cells) { colorAt(current?.get(it) ?: 0) }
        cachedFrame = null
    }

    @Synchronized
    fun referenceCell(index: Int): ReferenceCell? {
        val address = cellAddress(index) ?: return null
        return ReferenceCell(address, counts?.get(index) ?: 0)
    }

    @Synchronized
    fun bitmapCell(index: Int): BitmapCell? {
        val address = cellAddress(index) ?: return null
        return BitmapCell(index % options.columns, index / options.columns, address, pixels?.get(index) ?: 0)
    }

    private fun cellAddress(index: Int): Long? {
        val range = selectedRange() ?: return null
        val address = range.first + index.toLong() * options.wordsPerCell * 4
        return address.takeIf { it in range }
    }

    @Synchronized
    fun acceptsCustomAddress(address: Long): Boolean =
        customRange(address, ranges[Base.WIDE_DATA], ranges[Base.WIDE_TEXT]) != null

    private fun selectedRange(): LongRange? = if (options.base == Base.CUSTOM) {
        customRange(options.customAddress, ranges[Base.WIDE_DATA], ranges[Base.WIDE_TEXT])
    } else ranges[options.base]

    @Synchronized
    private fun detach() {
        stopWatching()
        memory = null
    }

    // observe is called by the execution owner before the first instruction. GUI repaints
    // still consume immutable frames; initialized pixels never emit simulated access events.
    private fun captureInitialPixels(source: MemoryState) {
        val range = selectedRange() ?: return
        val last = range.last and -4L
        pixels = IntArray(options.cells) { index ->
            val address = range.first + index.toLong() * options.wordsPerCell * 4
            if (address <= last) source.inspectWord(address)?.and(RGB_MASK) ?: 0 else 0
        }
        cachedFrame = null
    }

    private fun startWatching() {
        val source = memory ?: return
        if (!connected) return
        val range = selectedRange() ?: return
        val first = range.first
        val last = minOf(first + (options.cells.toLong() * options.wordsPerCell - 1) * 4, range.last and -4L)
        val registration = generation
        val callback: (MemoryAccessNotice) -> Unit = { notice ->
            val address = notice.addressBits
            if (address >= first && address <= last + 3) {
                record(source, first, registration, notice)
            }
        }
        if (first > UInt.MAX_VALUE.toLong()) {
            source.addAddressObserver(callback)
            unsubscribe = { source.deleteAddressObserver(callback) }
        } else {
            source.addObserver(callback, first.toInt(), last.toInt())
            unsubscribe = { source.deleteObserver(callback) }
        }
    }

    private fun stopWatching() {
        generation++
        unsubscribe?.invoke()
        unsubscribe = null
    }

    @Synchronized
    private fun record(source: MemoryState, base: Long, registration: Long, notice: MemoryAccessNotice) {
        if (registration != generation) return
        val index = (((notice.addressBits - base) ushr 2) / options.wordsPerCell).toInt()
        val color = if (tracksReferences) {
            if (notice.origin() != EXECUTION) return
            val references = counts ?: LongArray(options.cells).also { counts = it }
            colorAt(++references[index])
        } else {
            if (notice.accessType != WRITE) return
            // The notice is delivered on the writer's thread after the store has completed.
            val address = notice.addressBits and -4L
            val word = if (address <= UInt.MAX_VALUE.toLong()) source.getWordNoNotify(address.toInt())
                // The observer range contains only mapped addresses, rounded to complete words.
                else source.inspectWord(address)!!
            word and RGB_MASK
        }
        val colors = pixels ?: IntArray(options.cells).also { pixels = it }
        colors[index] = color
        cachedFrame = null
    }

    companion object {
        val displaySizes = listOf(64, 128, 256, 512, 1024)
        val displayWidths = displaySizes + 1920
        val displayHeights = displaySizes + 1080
        val unitSizes = listOf(1, 2, 4, 8, 16, 32)
        val wordCounts = (0..11).map { 1 shl it }
        private const val RGB_MASK = 0x00ff_ffff
        private val defaultPalette = mapOf(
            0L to 0x000000, 1L to 0x0000ff, 2L to 0x00ff00,
            3L to 0xffff00, 5L to 0xffc800, 10L to 0xff0000,
        )

        fun customRange(address: Long, wideData: LongRange?, wideText: LongRange?): LongRange? {
            if (address < 0 || address and 3L != 0L) return null
            if (address <= UInt.MAX_VALUE.toLong()) return address..narrowRange(address.toInt()).last
            val range = listOfNotNull(wideData, wideText).firstOrNull { address in it } ?: return null
            return address..range.last
        }

        private fun narrowRange(base: Int): LongRange =
            base.toUInt().toLong()..if (base < 0) 0xffff_fffcL else 0x7fff_fffcL
    }
}
