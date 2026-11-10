package edu.missouristate.mars.cli;

import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.dump.DumpFormat;
import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.util.Binary;
import edu.missouristate.mars.util.MemoryDump;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

record CliMemoryDump(String region, AddressRange range, DumpFormat format, Path output) {
  static CliMemoryDump parse(String region, String descriptor, String filename) {
    var range =
        Arrays.asList(MemoryDump.getSegmentNames()).contains(region)
            ? null
            : AddressRange.parse(region);
    var format = DumpFormatLoader.findByCommandDescriptor(descriptor);
    if (format == null) {
      throw new IllegalArgumentException("Unknown memory dump format: " + descriptor);
    }
    return new CliMemoryDump(region, range, format, Path.of(filename).toAbsolutePath().normalize());
  }

  void write(MarsContext session, PrintStream error) {
    var memory = session.memory;
    int first;
    int last;
    if (range == null) {
      Integer[] bounds = MemoryDump.getSegmentBounds(region, memory);
      first = bounds[0];
      last = bounds[1] - MemoryState.WORD_LENGTH_BYTES;
    } else {
      first = range.first();
      last = range.last();
    }
    try {
      boolean userText = memory.inTextSegment(first) && memory.inTextSegment(last);
      boolean userData = memory.inDataSegment(first) && memory.inDataSegment(last);
      if (format.includesSourceListing() && !userText && !userData) {
        throw new IOException("SegmentWindow requires a range within user text or user data");
      }
      var snapshot = format.capture(session, first, last, () -> {});
      if (snapshot.size() == 0) {
        throw new IOException("no initialized words at " + Binary.intToHexString(first));
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
          "Cannot dump " + region + ": invalid address " + Binary.intToHexString(failure.getAddress()));
    } catch (IOException failure) {
      error.println("Cannot dump " + region + " to " + output + ": " + failure.getMessage());
    }
  }

  record AddressRange(int first, int last) {
    static AddressRange parse(String text) {
      int separator = text.indexOf('-', 1);
      try {
        if (separator > 0) {
          int first = address(text.substring(0, separator));
          int last = address(text.substring(separator + 1));
          if (Integer.compareUnsigned(first, last) <= 0
              && MemoryState.wordAligned(first)
              && MemoryState.wordAligned(last)) {
            return new AddressRange(first, last);
          }
        }
      } catch (NumberFormatException ignored) {
        // Report malformed addresses together with the original range.
      }
      throw new IllegalArgumentException("Invalid or unaligned memory range: " + text);
    }

    private static int address(String text) {
      long value = Long.decode(text);
      if (value < Integer.MIN_VALUE || value > 0xffffffffL) {
        throw new NumberFormatException(text);
      }
      return (int) value;
    }
  }
}
