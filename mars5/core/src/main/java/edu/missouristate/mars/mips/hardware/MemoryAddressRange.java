package edu.missouristate.mars.mips.hardware;

/** Inclusive, word-aligned addresses in unsigned machine order. */
public record MemoryAddressRange(int first, int last) {
  public MemoryAddressRange {
    if (Integer.compareUnsigned(first, last) > 0
        || !MemoryState.wordAligned(first)
        || !MemoryState.wordAligned(last)) {
      throw new IllegalArgumentException("Memory range requires ordered, word-aligned endpoints");
    }
  }

  /** Distinguishes a range operand from other arguments containing hyphens. */
  public static boolean hasAddressPrefix(String text) {
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

  public static MemoryAddressRange parse(String text) {
    int separator = text.indexOf('-', 1);
    try {
      if (separator > 0) {
        return new MemoryAddressRange(address(text.substring(0, separator)),
            address(text.substring(separator + 1)));
      }
    } catch (IllegalArgumentException ignored) {
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
