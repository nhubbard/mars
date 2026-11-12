package edu.missouristate.mars.mips.hardware.memory

import edu.missouristate.mars.ProgramStatement

/** Sparse text storage, addressed in words relative to its owning segment. */
class TextMemorySegment(capacity: Int) {
    private val blocks = arrayOfNulls<Array<ProgramStatement?>>(capacity)

    @Synchronized
    fun store(word: Int, value: ProgramStatement?) {
        val index = word / BLOCK_WORDS
        if (index !in blocks.indices) return
        val block = blocks[index] ?: arrayOfNulls<ProgramStatement>(BLOCK_WORDS).also { blocks[index] = it }
        block[word % BLOCK_WORDS] = value
    }

    @Synchronized
    fun read(word: Int): ProgramStatement? = blocks.getOrNull(word / BLOCK_WORDS)?.get(word % BLOCK_WORDS)

    companion object {
        private const val BLOCK_WORDS = 1024
    }
}
