package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessNotice.Companion.WRITE
import edu.missouristate.mars.mips.hardware.AccessOrigin.EXECUTION
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.TreeMap
import java.util.function.Consumer

/** Per-execution memory grid. Repaints consume snapshots and never read simulator memory. */
internal class MemoryGridDisplay(private val captureAtLaunch: Boolean = false, mode: Mode = Mode.BITMAP) {
    enum class Mode(val nameKey: String, val helpKey: String, val inspectionHintKey: String) {
        BITMAP("bitmap.name", "bitmap.help", "bitmap.inspect.hint"),
        REFERENCES("references.name", "references.help", "references.inspect.hint");

        fun defaults() = if (this == REFERENCES) Options(256, 256, 16, 16) else Options()
    }

    enum class Base(val messageKey: String, val address: (MemoryState) -> Int) {
        TEXT("references.text", { it.textBaseAddress }),
        GLOBAL_DATA("bitmap.global", { it.dataSegmentBaseAddress }),
        GLOBAL_POINTER("bitmap.gp", { it.globalPointer }),
        STATIC_DATA("bitmap.static", { it.dataBaseAddress }),
        HEAP("bitmap.heap", { it.heapBaseAddress }),
        MMIO("bitmap.mmio", { it.memoryMapBaseAddress }),
    }

    data class Options(
        val width: Int = 512, val height: Int = 256, val unitWidth: Int = 1, val unitHeight: Int = 1,
        val base: Base = Base.STATIC_DATA, val wordsPerCell: Int = 1,
    ) {
        init {
            require(width in displaySizes && height in displaySizes)
            require(unitWidth in unitSizes && unitHeight in unitSizes)
            require(wordsPerCell in wordCounts)
        }

        val columns = width / unitWidth
        val rows = height / unitHeight
        val cells = columns * rows
    }

    data class Frame(val options: Options, val image: BufferedImage?)
    data class Snapshot(val connected: Boolean, val addresses: Map<Base, Int>, val frame: Frame)
    data class ReferenceCell(val address: Int, val count: Long)
    data class BitmapCell(val column: Int, val row: Int, val address: Int, val rgb: Int)

    private val tracksReferences = mode == Mode.REFERENCES
    private var connected = captureAtLaunch
    private var options = mode.defaults()
    private var addresses = emptyMap<Base, Int>()
    private var pixels: IntArray? = null
    private var counts: LongArray? = null
    private val palette = TreeMap(defaultPalette)
    private var cachedFrame: Frame? = null
    private var memory: MemoryState? = null
    private var observer: Consumer<MemoryAccessNotice>? = null
    private var generation = 0L

    @Synchronized
    fun observe(source: MemoryState): AutoCloseable {
        memory = source
        addresses = Base.entries.associateWith { it.address(source) }
        startWatching()
        return AutoCloseable { detach() }
    }

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

    fun releaseView() {
        if (!captureAtLaunch) setConnected(false)
    }

    @Synchronized
    fun configure(value: Options) {
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
        palette[threshold] = rgb and rgbMask
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

    private fun cellAddress(index: Int): Int? {
        val base = addresses[options.base] ?: return null
        val address = Integer.toUnsignedLong(base) + index.toLong() * options.wordsPerCell * 4
        if (address > addressLimit(base)) return null
        return address.toInt()
    }

    @Synchronized
    private fun detach() {
        stopWatching()
        memory = null
    }

    private fun startWatching() {
        val source = memory ?: return
        if (!connected) return
        val base = addresses.getValue(options.base)
        val start = Integer.toUnsignedLong(base)
        val last = start + (options.cells.toLong() * options.wordsPerCell - 1) * 4
        val registration = generation
        val callback = Consumer<MemoryAccessNotice> { record(source, base, registration, it) }
        source.addObserver(callback, base, minOf(last, addressLimit(base)).toInt())
        observer = callback
    }

    private fun stopWatching() {
        generation++
        observer?.let { memory!!.deleteObserver(it) }
        observer = null
    }

    @Synchronized
    private fun record(source: MemoryState, base: Int, registration: Long, notice: MemoryAccessNotice) {
        if (registration != generation) return
        val index = ((notice.address - base) ushr 2) / options.wordsPerCell
        val color = if (tracksReferences) {
            if (notice.origin() != EXECUTION) return
            val references = counts ?: LongArray(options.cells).also { counts = it }
            colorAt(++references[index])
        } else {
            if (notice.accessType != WRITE) return
            // The notice is delivered on the writer's thread after the store has completed.
            source.getWordNoNotify(notice.address and -4) and rgbMask
        }
        val colors = pixels ?: IntArray(options.cells).also { pixels = it }
        colors[index] = color
        cachedFrame = null
    }

    companion object {
        val displaySizes = listOf(64, 128, 256, 512, 1024)
        val unitSizes = listOf(1, 2, 4, 8, 16, 32)
        val wordCounts = (0..11).map { 1 shl it }
        private const val rgbMask = 0x00ff_ffff
        private val defaultPalette = mapOf(
            0L to 0x000000, 1L to 0x0000ff, 2L to 0x00ff00,
            3L to 0xffff00, 5L to 0xffc800, 10L to 0xff0000,
        )

        private fun addressLimit(base: Int) = if (base < 0) 0xffff_fffcL else 0x7fff_fffcL
    }
}
