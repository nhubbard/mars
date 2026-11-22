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
