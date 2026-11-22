package edu.missouristate.mars.cli.grading

/** Exact bytes in increasing address order. A string keeps shared case definitions immutable. */
data class ConsoleMemoryCheck(val address: Long, val bytes: String) {
    init {
        require(bytes.length in 2..MAX_BYTES * 2 && bytes.length % 2 == 0 && bytes.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Memory expectations need nonempty hexadecimal byte pairs, at most 1 MiB"
        }
        require(address >= 0 && address <= Long.MAX_VALUE - (byteLength - 1L)) {
            "Memory expectation addresses must be nonnegative and cannot overflow"
        }
    }
    val byteLength: Int get() = bytes.length / 2
    internal fun decoded(): IntArray = IntArray(byteLength) { bytes.substring(it * 2, it * 2 + 2).toInt(16) }

    companion object {
        const val MAX_BYTES = 1024 * 1024
        const val MAX_RANGES = 64
    }
}
