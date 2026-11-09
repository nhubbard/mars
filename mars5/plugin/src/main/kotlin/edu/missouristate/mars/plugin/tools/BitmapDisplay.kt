package edu.missouristate.mars.plugin.tools

import edu.missouristate.mars.mips.hardware.AccessNotice.WRITE
import edu.missouristate.mars.mips.hardware.MemoryAccessNotice
import edu.missouristate.mars.mips.hardware.MemoryState
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.function.Consumer

/** One write-driven bitmap per execution. Repaints consume snapshots and never read simulator memory. */
internal class BitmapDisplay(private val captureAtLaunch: Boolean = false) {
    enum class Base(val messageKey: String, val address: (MemoryState) -> Int) {
        GLOBAL_DATA("bitmap.global", { it.dataSegmentBaseAddress }),
        GLOBAL_POINTER("bitmap.gp", { it.globalPointer }),
        STATIC_DATA("bitmap.static", { it.dataBaseAddress }),
        HEAP("bitmap.heap", { it.heapBaseAddress }),
        MMIO("bitmap.mmio", { it.memoryMapBaseAddress }),
    }

    data class Options(
        val width: Int = 512, val height: Int = 256, val unitWidth: Int = 1, val unitHeight: Int = 1,
        val base: Base = Base.STATIC_DATA,
    ) {
        init {
            require(width in displaySizes && height in displaySizes)
            require(unitWidth in unitSizes && unitHeight in unitSizes)
        }

        val columns = width / unitWidth
        val rows = height / unitHeight
    }

    data class Frame(val options: Options, val image: BufferedImage?)
    data class Snapshot(val connected: Boolean, val addresses: Map<Base, Int>, val frame: Frame)

    private var connected = captureAtLaunch
    private var options = Options()
    private var addresses = emptyMap<Base, Int>()
    private var pixels: IntArray? = null
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
        pixels = null
        cachedFrame = null
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
        val last = start + (options.columns * options.rows - 1) * 4L
        val limit = if (base < 0) 0xffff_fffcL else 0x7fff_fffcL
        val registration = generation
        val callback = Consumer<MemoryAccessNotice> { record(source, base, registration, it) }
        source.addObserver(callback, base, minOf(last, limit).toInt())
        observer = callback
    }

    private fun stopWatching() {
        generation++
        observer?.let { memory!!.deleteObserver(it) }
        observer = null
    }

    @Synchronized
    private fun record(source: MemoryState, base: Int, registration: Long, notice: MemoryAccessNotice) {
        if (notice.accessType != WRITE || registration != generation) return
        val address = notice.address and -4
        // This callback runs on the writer's thread after the store has completed.
        val color = source.getWordNoNotify(address) and 0x00ff_ffff
        val colors = pixels ?: IntArray(options.columns * options.rows).also { pixels = it }
        colors[(address - base) ushr 2] = color
        cachedFrame = null
    }

    companion object {
        val displaySizes = listOf(64, 128, 256, 512, 1024)
        val unitSizes = listOf(1, 2, 4, 8, 16, 32)
    }
}
