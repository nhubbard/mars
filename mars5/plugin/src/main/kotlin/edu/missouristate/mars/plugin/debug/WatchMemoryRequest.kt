package edu.missouristate.mars.plugin.debug

import edu.missouristate.mars.mips.hardware.MemoryState
import kotlinx.coroutines.channels.Channel

internal data class WatchMemoryRequest(val inspect: (MemoryState) -> Unit, val unavailable: () -> Unit)

internal fun watchMemoryRequests(): Channel<WatchMemoryRequest> =
    Channel(16, onUndeliveredElement = { it.unavailable() })

/** Reads use the same side-effect-free word inspector as the Debug Memory tab. */
internal enum class WatchMemoryWidth(val spelling: String, private val bytes: Int, private val mask: Int) {
    BYTE("byte", 1, 0xff),
    HALF("half", 2, 0xffff),
    WORD("word", 4, -1);

    fun inspect(memory: MemoryState, address: Int): Int? {
        if (address and (bytes - 1) != 0) return null
        val word = memory.inspectWord(address and -Int.SIZE_BYTES) ?: return null
        val offset = address and (Int.SIZE_BYTES - 1)
        val shift = Byte.SIZE_BITS * if (memory.byteOrder == MemoryState.LITTLE_ENDIAN) offset else Int.SIZE_BYTES - bytes - offset
        return word ushr shift and mask
    }
}
