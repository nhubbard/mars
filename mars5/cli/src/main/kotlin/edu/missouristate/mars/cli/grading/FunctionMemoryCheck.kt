package edu.missouristate.mars.cli.grading

/** A data object's assembled address plus a bounded byte displacement. */
data class FunctionDataAddress(val label: String, val offset: Int = 0) {
    init {
        functionLabel(label)
        require(offset in -ConsoleMemoryCheck.MAX_BYTES..ConsoleMemoryCheck.MAX_BYTES) {
            "Function data offsets must be within one MiB of the public label"
        }
    }
}

/** Exact bytes in increasing address order; no target-endian reinterpretation or executable predicate. */
data class FunctionMemoryCheck(val label: String, val bytes: String, val offset: Int = 0) {
    private val contents = ConsoleMemoryCheck(0, bytes)

    init {
        FunctionDataAddress(label, offset)
    }

    val byteLength: Int get() = contents.byteLength
    internal fun decoded(): IntArray = contents.decoded()
}

internal fun functionMemory(ranges: List<FunctionMemoryCheck>) {
    require(ranges.size <= ConsoleMemoryCheck.MAX_RANGES &&
        ranges.sumOf { it.byteLength.toLong() } <= ConsoleMemoryCheck.MAX_BYTES) {
        "Function memory checks exceed 64 ranges or one MiB"
    }
}


/** Forbid any completed execution store overlapping this label-relative region. */
data class FunctionNoStoreCheck(val label: String, val byteLength: Int, val offset: Int = 0) {
    init {
        FunctionDataAddress(label, offset)
        require(byteLength in 1..ConsoleMemoryCheck.MAX_BYTES) {
            "Protected function regions need one through 1048576 bytes"
        }
    }
}

/** Every execution access overlapping the watched window must fit in its permitted subwindow. */
data class FunctionAccessCheck(
    val label: String,
    val byteLength: Int,
    val permittedOffset: Int,
    val permittedByteLength: Int,
    val offset: Int = 0,
) {
    init {
        FunctionDataAddress(label, offset)
        FunctionDataAddress(label, permittedOffset)
        require(byteLength in 1..ConsoleMemoryCheck.MAX_BYTES &&
            permittedByteLength in 0..ConsoleMemoryCheck.MAX_BYTES) {
            "Watch one through 1048576 bytes and permit zero through 1048576 bytes"
        }
        require(permittedOffset >= offset &&
            permittedOffset.toLong() + permittedByteLength <= offset.toLong() + byteLength) {
            "The permitted function access window must lie within its watched window"
        }
    }
}
