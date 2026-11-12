package edu.missouristate.mars.mips.dump

import edu.missouristate.mars.mips.hardware.MemoryState.WORD_LENGTH_BYTES
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.HexFormat

/** Intel HEX with offsets relative to the first captured word. */
open class IntelHexDumpFormat : AbstractDumpFormat("Intel hex format", "HEX", "Written as Intel Hex Memory File", "hex") {
    @Throws(IOException::class)
    override fun write(file: File, snapshot: MemoryDumpSnapshot) {
        PrintStream(FileOutputStream(file)).use { out ->
            var upperAddress = 0
            for (index in 0 until snapshot.size()) {
                val word = snapshot.word(index)
                val offset = index * WORD_LENGTH_BYTES
                val upper = offset ushr 16
                if (upper != upperAddress) {
                    val checksum = 2 + 4 + (upper ushr 8) + (upper and 0xff)
                    out.println(":02000004" + hex.toHexDigits(upper.toShort()) + hex.toHexDigits((-checksum).toByte()))
                    upperAddress = upper
                }
                var checksum = 4 + (offset and 0xff) + ((offset ushr 8) and 0xff)
                for (shift in 0..24 step 8) checksum += (word ushr shift) and 0xff
                out.println(
                    ":04" + hex.toHexDigits(offset.toShort()) + "00" + hex.toHexDigits(word) +
                        hex.toHexDigits((-checksum).toByte()),
                )
            }
            out.println(":00000001FF")
            checkOutput(out)
        }
    }

    private companion object {
        val hex = HexFormat.of().withUpperCase()
    }
}
