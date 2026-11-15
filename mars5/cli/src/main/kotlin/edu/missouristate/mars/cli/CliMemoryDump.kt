package edu.missouristate.mars.cli

import edu.missouristate.mars.core.MarsContext
import edu.missouristate.mars.mips.dump.DumpFormat
import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.MemoryAddressRange
import edu.missouristate.mars.mips.hardware.MemoryState.Companion.WORD_LENGTH_BYTES
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.MemoryDump
import java.io.IOException
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal class CliMemoryDump(
    private val region: String,
    private val range: MemoryAddressRange?,
    private val format: DumpFormat,
    private val output: Path,
) {
    fun write(session: MarsContext, error: PrintStream) {
        val memory = session.memory
        val first: Long
        val last: Long
        if (range == null) {
            val (base, limit) = checkNotNull(MemoryDump.getSegmentBounds(region, memory)) {
                "Unknown memory segment: $region"
            }
            first = base.toUInt().toLong()
            last = (limit - WORD_LENGTH_BYTES).toUInt().toLong()
        } else {
            first = range.first
            last = range.last
        }
        try {
            val userText = memory.inUserText(first) && memory.inUserText(last)
            val userData = memory.inUserData(first) && memory.inUserData(last)
            if (format.includesSourceListing() && !userText && !userData) {
                throw IOException("SegmentWindow requires a range within user text or user data")
            }
            val snapshot = format.capture(session, first, last) {}
            if (snapshot.size() == 0) throw IOException("no initialized words at ${formatAddress(first)}")
            val directory = output.parent ?: throw IOException("expected an output file")
            val temporary = Files.createTempFile(directory, ".mars-dump-", ".tmp")
            try {
                format.write(temporary.toFile(), snapshot)
                Files.move(temporary, output, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
        } catch (failure: AddressErrorException) {
            error.println("Cannot dump $region: invalid address ${formatAddress(failure.addressBits)}")
        } catch (failure: IOException) {
            error.println("Cannot dump $region to $output: ${failure.message}")
        }
    }

    companion object {
        fun parse(region: String, descriptor: String, filename: String): CliMemoryDump {
            val range = if (region in MemoryDump.getSegmentNames()) null else MemoryAddressRange.parse(region)
            val format = requireNotNull(DumpFormatLoader.findByCommandDescriptor(descriptor)) {
                "Unknown memory dump format: $descriptor"
            }
            return CliMemoryDump(region, range, format, Path.of(filename).toAbsolutePath().normalize())
        }

        fun formatAddress(address: Long): String =
            if (address <= UInt.MAX_VALUE.toLong()) Binary.intToHexString(address.toInt()) else Binary.longToHexString(address)
    }
}
