package edu.missouristate.mars.cli;

import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.dump.DumpFormat;
import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.MemoryAddressRange;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.util.Binary;
import edu.missouristate.mars.util.MemoryDump;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Objects;

record CliMemoryDump(String region, MemoryAddressRange range, DumpFormat format, Path output) {
  static CliMemoryDump parse(String region, String descriptor, String filename) {
    var range =
        Arrays.asList(MemoryDump.getSegmentNames()).contains(region)
            ? null
            : MemoryAddressRange.parse(region);
    var format = DumpFormatLoader.findByCommandDescriptor(descriptor);
    if (format == null) {
      throw new IllegalArgumentException("Unknown memory dump format: " + descriptor);
    }
    return new CliMemoryDump(region, range, format, Path.of(filename).toAbsolutePath().normalize());
  }

  static String formatAddress(long address) {
    return address <= 0xffffffffL ? Binary.intToHexString((int) address) : Binary.longToHexString(address);
  }

  void write(MarsContext session, PrintStream error) {
    var memory = session.memory;
    long first;
    long last;
    if (range == null) {
      Integer[] bounds = Objects.requireNonNull(
          MemoryDump.getSegmentBounds(region, memory), "Unknown memory segment: " + region);
      first = Integer.toUnsignedLong(bounds[0]);
      last = Integer.toUnsignedLong(bounds[1] - MemoryState.WORD_LENGTH_BYTES);
    } else {
      first = range.first();
      last = range.last();
    }
    try {
      boolean userText = memory.inUserText(first) && memory.inUserText(last);
      boolean userData = memory.inUserData(first) && memory.inUserData(last);
      if (format.includesSourceListing() && !userText && !userData) {
        throw new IOException("SegmentWindow requires a range within user text or user data");
      }
      var snapshot = format.capture(session, first, last, () -> {});
      if (snapshot.size() == 0) {
        throw new IOException("no initialized words at " + formatAddress(first));
      }
      if (output.getParent() == null) {
        throw new IOException("expected an output file");
      }
      Path temporary = Files.createTempFile(output.getParent(), ".mars-dump-", ".tmp");
      try {
        format.write(temporary.toFile(), snapshot);
        Files.move(
            temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } finally {
        Files.deleteIfExists(temporary);
      }
    } catch (AddressErrorException failure) {
      error.println(
          "Cannot dump " + region + ": invalid address " + formatAddress(failure.getAddressBits()));
    } catch (IOException failure) {
      error.println("Cannot dump " + region + " to " + output + ": " + failure.getMessage());
    }
  }
}
