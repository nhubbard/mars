package edu.missouristate.mars.cli.grading

/** A null port forbids all stores in the region; otherwise require its complete byte-store sequence. */
data class ConsoleStoreCheck(
    val address: Long,
    val byteLength: Int,
    val port: Long? = null,
    val bytes: String = "",
) {
    init {
        requireTraceRegion(address, byteLength)
        require(bytes.length <= MAX_EXPECTED_BYTES * 2 && bytes.length % 2 == 0 &&
            bytes.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            "Store expectations need hexadecimal byte pairs, at most 65536 bytes"
        }
        require(if (port == null) bytes.isEmpty() else port >= address && port - address < byteLength) {
            "A store sequence needs a port inside its traced region; a no-store check has no expected bytes"
        }
    }

    internal fun verify(trace: MemoryStoreTrace) {
        if (port == null) trace.expectNone()
        else trace.expectByteStores(port, *IntArray(bytes.length / 2) { bytes.substring(it * 2, it * 2 + 2).toInt(16) })
    }

    companion object {
        const val MAX_EXPECTED_BYTES = 65_536
        const val MAX_CHECKS = 64
    }
}

/** All observed execution reads must fit in the permitted subrange; zero permitted length forbids reads. */
data class ConsoleReadCheck(
    val address: Long,
    val byteLength: Int,
    val allowedAddress: Long,
    val allowedLength: Int,
) {
    init {
        requireTraceRegion(address, byteLength)
        require(allowedAddress >= address && allowedAddress - address <= byteLength && allowedLength >= 0 &&
            allowedLength.toLong() <= byteLength - (allowedAddress - address)) {
            "Permitted reads must form a nonnegative subrange of the traced region"
        }
    }

    internal fun verify(trace: MemoryAccessTrace) = trace.expectReadsWithin(allowedAddress, allowedLength)
}

private fun requireTraceRegion(address: Long, byteLength: Int) {
    require(byteLength in 1..ConsoleMemoryCheck.MAX_BYTES && address >= 0 && address <= Long.MAX_VALUE - (byteLength - 1L)) {
        "Trace regions need a nonnegative non-overflowing address and one through 1048576 bytes"
    }
}
