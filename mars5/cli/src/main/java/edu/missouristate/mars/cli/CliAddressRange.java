package edu.missouristate.mars.cli;

import edu.missouristate.mars.mips.hardware.MemoryState;

/** Inclusive, word-aligned addresses in unsigned machine order. */
record CliAddressRange(int first, int last) {
  static boolean hasAddressPrefix(String text) {
    int separator = text.indexOf('-', 1);
    if (separator < 0) {
      return false;
    }
    try {
      Long.decode(text.substring(0, separator));
      return true;
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  static CliAddressRange parse(String text) {
    int separator = text.indexOf('-', 1);
    try {
      if (separator > 0) {
        int first = address(text.substring(0, separator));
        int last = address(text.substring(separator + 1));
        if (Integer.compareUnsigned(first, last) <= 0
            && MemoryState.wordAligned(first)
            && MemoryState.wordAligned(last)) {
          return new CliAddressRange(first, last);
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
