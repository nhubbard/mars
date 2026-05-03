package edu.missouristate.mars.util;

/**
 * Pure-Java number-formatting utilities used by the assembler, simulator, and UI alike.
 *
 * <p>This class contains only constants and static methods — no Swing dependency. The Venus
 * {@code NumberDisplayBaseChooser} Swing widget delegates to these methods so that core packages
 * can format numbers without importing a GUI class.
 */
public final class NumberDisplayBase {

  public static final int DECIMAL = 10;
  public static final int HEXADECIMAL = 16;
  public static final int ASCII = 0;

  private NumberDisplayBase() {}

  /** Returns {@link #HEXADECIMAL} if {@code displayInHex} is true, else {@link #DECIMAL}. */
  public static int getBase(boolean displayInHex) {
    return displayInHex ? HEXADECIMAL : DECIMAL;
  }

  /**
   * Formats {@code value} as an <em>unsigned</em> integer in the given base. In base 16 this is
   * identical to {@link #formatNumber(int, int)}; in base 10 the sign bit is not interpreted, so
   * {@code 0xffffffff} becomes {@code "4294967295"} rather than {@code "-1"}.
   */
  public static String formatUnsignedInteger(int value, int base) {
    if (base == HEXADECIMAL) {
      return Binary.intToHexString(value);
    } else {
      return Binary.unsignedIntToIntString(value);
    }
  }

  /** Formats a 32-bit signed integer in the given base. */
  public static String formatNumber(int value, int base) {
    return switch (base) {
      case HEXADECIMAL -> Binary.intToHexString(value);
      case ASCII -> Binary.intToAscii(value);
      default -> Integer.toString(value);
    };
  }

  /** Formats a {@code float} in the given base (base 16 shows the raw IEEE 754 bits). */
  public static String formatNumber(float value, int base) {
    if (base == HEXADECIMAL) {
      return Binary.intToHexString(Float.floatToIntBits(value));
    } else {
      return Float.toString(value);
    }
  }

  /** Formats a {@code double} in the given base (base 16 shows the raw IEEE 754 bits). */
  public static String formatNumber(double value, int base) {
    if (base == HEXADECIMAL) {
      long bits = Double.doubleToLongBits(value);
      return Binary.intToHexString(Binary.highOrderLongToInt(bits))
          + Binary.intToHexString(Binary.lowOrderLongToInt(bits)).substring(2);
    } else {
      return Double.toString(value);
    }
  }

  /**
   * Formats a 32-bit pattern as the corresponding {@code float} string (or hex if base 16).
   * Unlike {@link #formatNumber(float, int)}, this preserves NaN bit patterns in hex mode.
   */
  public static String formatFloatNumber(int value, int base) {
    if (base == HEXADECIMAL) {
      return Binary.intToHexString(value);
    } else {
      return Float.toString(Float.intBitsToFloat(value));
    }
  }

  /**
   * Formats a 64-bit pattern as the corresponding {@code double} string (or hex if base 16).
   * Unlike {@link #formatNumber(double, int)}, this preserves NaN bit patterns in hex mode.
   */
  public static String formatDoubleNumber(long value, int base) {
    if (base == HEXADECIMAL) {
      return Binary.longToHexString(value);
    } else {
      return Double.toString(Double.longBitsToDouble(value));
    }
  }
}
