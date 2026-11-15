package edu.missouristate.mars.mips.hardware.memory

import java.util.function.BooleanSupplier

/** Sparse word storage shared by data, kernel, stack, and MMIO segments. */
class WordMemorySegment(blocks: Int, private val descending: Boolean, private val littleEndian: BooleanSupplier) {
    private val blockTable = arrayOfNulls<IntArray>(blocks)

    /** Copy allocated blocks. A restored segment keeps its own byte order supplier. */
    @Synchronized
    fun capture(checkCancellation: () -> Unit): Image = Image.capture(blockTable, checkCancellation)

    @Synchronized
    fun load(image: Image, checkCancellation: () -> Unit) {
        val copy = image.copy(checkCancellation)
        copy.copyInto(blockTable, endIndex = blockTable.size)
    }

    class Image private constructor(private val blocks: Array<IntArray?>) {
        internal fun copy(checkCancellation: () -> Unit): Array<IntArray?> = copyBlocks(blocks, checkCancellation)

        fun hasSameContents(other: Image, checkCancellation: () -> Unit): Boolean {
            if (blocks.size != other.blocks.size) return false
            for (index in blocks.indices) {
                checkCancellation()
                if (!blocks[index].contentEquals(other.blocks[index])) return false
            }
            return true
        }

        companion object {
            internal fun capture(blocks: Array<IntArray?>, checkCancellation: () -> Unit): Image =
                Image(copyBlocks(blocks, checkCancellation))
        }
    }

    fun storeBytes(relativeByteAddress: Int, length: Int, value: Int): Int =
        transferBytes(relativeByteAddress, length, value, store = true)

    fun fetchBytes(relativeByteAddress: Int, length: Int): Int =
        transferBytes(relativeByteAddress, length, 0, store = false)

    @Synchronized
    private fun transferBytes(relativeByteAddress: Int, length: Int, value: Int, store: Boolean): Int {
        var address = relativeByteAddress
        // Stack word offsets descend, but bytes within each word are still transferred in order.
        if (descending) {
            val delta = address % 4
            if (delta != 0) address += (4 - delta) shl 1
        }

        val little = littleEndian.asBoolean
        var result = 0
        for (index in 0 until length) {
            val byteInValue = if (little) 3 - index else 4 - length + index
            val word = address shr 2
            val blockIndex = word / BLOCK_LENGTH_WORDS
            val offset = word % BLOCK_LENGTH_WORDS
            val block = blockTable[blockIndex] ?: if (store) allocateBlock(blockIndex) else return 0
            val byteOffset = address % 4
            val byteInMemory = if (little) 3 - byteOffset else byteOffset
            result = replaceByte(block[offset], byteInMemory, result, byteInValue)
            if (store) block[offset] = replaceByte(value, byteInValue, block[offset], byteInMemory)
            address++
        }
        return result
    }

    @Synchronized
    fun storeWord(relative: Int, value: Int): Int {
        val index = relative / BLOCK_LENGTH_WORDS
        val offset = relative % BLOCK_LENGTH_WORDS
        val block = blockTable[index] ?: allocateBlock(index)
        val previous = block[offset]
        block[offset] = value
        return previous
    }

    @Synchronized
    fun fetchWord(relative: Int): Int =
        blockTable[relative / BLOCK_LENGTH_WORDS]?.get(relative % BLOCK_LENGTH_WORDS) ?: 0

    @Synchronized
    fun fetchWordOrNull(relative: Int): Int? =
        blockTable[relative / BLOCK_LENGTH_WORDS]?.get(relative % BLOCK_LENGTH_WORDS)

    private fun allocateBlock(index: Int): IntArray =
        IntArray(BLOCK_LENGTH_WORDS).also { blockTable[index] = it }

    companion object {
        private const val BLOCK_LENGTH_WORDS = 1024

        private fun copyBlocks(source: Array<IntArray?>, checkCancellation: () -> Unit): Array<IntArray?> =
            Array(source.size) { index ->
                source[index]?.let { block ->
                    checkCancellation()
                    block.copyOf()
                }
            }

        /** Byte positions count from the most significant byte. */
        private fun replaceByte(source: Int, sourceByte: Int, destination: Int, destinationByte: Int): Int {
            val shift = 24 - (destinationByte shl 3)
            val byte = (source shr (24 - (sourceByte shl 3))) and 0xff
            return (destination and (0xff shl shift).inv()) or (byte shl shift)
        }
    }
}
