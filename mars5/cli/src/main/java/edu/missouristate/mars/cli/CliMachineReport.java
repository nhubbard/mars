package edu.missouristate.mars.cli;

import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.MemoryAddressRange;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.mips.hardware.Register;
import edu.missouristate.mars.util.Binary;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Objects;

/** Register and memory selections for one command-line invocation. */
final class CliMachineReport {
  enum Format { HEX, DECIMAL, ASCII }

  private record RegisterSelection(String label, Register register, boolean floating) {}

  private final ArrayList<RegisterSelection> registers = new ArrayList<>();
  private final ArrayList<MemoryAddressRange> ranges = new ArrayList<>();
  private long instructions;
  Format format = Format.HEX;
  boolean brief;
  boolean countInstructions;

  // Both callers handle the failed lookup. A successful lookup records the selected register.
  @SuppressWarnings("BooleanMethodIsAlwaysInverted")
  boolean tryAddRegister(String name, MarsContext session) {
    String label = name.startsWith("$") ? name : "$" + name;
    if (label.length() == 1 || label.equals("$f")) {
      return false;
    }
    Register integer = session.registerFile.getUserRegister(label);
    if (integer != null) {
      registers.add(new RegisterSelection(label, integer, false));
      return true;
    }
    Register floating = session.coprocessor1.getRegister(label);
    if (floating == null) {
      return false;
    }
    registers.add(new RegisterSelection(label, floating, true));
    return true;
  }

  void requireRegister(String name, MarsContext session) {
    if (!tryAddRegister(name, session)) {
      throw new IllegalArgumentException("Invalid register name: " + name);
    }
  }

  void addRange(String text) {
    ranges.add(MemoryAddressRange.parse(text));
  }

  void recordInstructionCount(long completed) {
    instructions = completed;
  }

  void write(MarsContext session, PrintStream out) {
    if (countInstructions) {
      out.println("\n" + instructions);
    } else if (registers.isEmpty() && ranges.isEmpty()) {
      return;
    }
    out.println();
    for (RegisterSelection selection : registers) {
      if (!brief) {
        out.print(selection.label() + "\t");
      }
      out.println(registerValue(selection, session));
    }
    for (MemoryAddressRange range : ranges) {
      writeRange(range, session.memory, out);
    }
  }

  private String registerValue(RegisterSelection selection, MarsContext session) {
    Register register = selection.register();
    int bits = register.getValue();
    if (!selection.floating()) {
      return formatWord(bits);
    }
    String value = format == Format.DECIMAL ? Float.toString(Float.intBitsToFloat(bits)) : formatWord(bits);
    if (register.getNumber() % 2 != 0) {
      return value;
    }
    int high = session.coprocessor1.getIntFromRegister(register.getNumber() + 1);
    long pair = Binary.twoIntsToLong(high, bits);
    return value + "\t" + switch (format) {
      case HEX -> Binary.longToHexString(pair);
      case DECIMAL -> Double.toString(Double.longBitsToDouble(pair));
      case ASCII -> Binary.intToAscii(high) + Binary.intToAscii(bits);
    };
  }

  private void writeRange(MemoryAddressRange range, MemoryState memory, PrintStream out) {
    long first = Integer.toUnsignedLong(range.first());
    long last = Integer.toUnsignedLong(range.last());
    int column = 0;
    for (long address = first; address <= last; address += MemoryState.WORD_LENGTH_BYTES) {
      int wordAddress = (int) address;
      if (column == 0 && !brief) {
        out.print("Mem[" + Binary.intToHexString(wordAddress) + "]\t");
      }
      try {
        int bits = memory.inTextSegment(wordAddress) || memory.inKernelTextSegment(wordAddress)
            ? Objects.requireNonNullElse(memory.getRawWordOrNull(wordAddress), 0)
            : memory.getWordNoNotify(wordAddress);
        out.print(formatWord(bits) + "\t");
      } catch (AddressErrorException ignored) {
        out.print("Invalid address: " + wordAddress + "\t");
      }
      column = (column + 1) % 4;
      if (column == 0 || address == last) {
        out.println();
      }
    }
  }

  private String formatWord(int bits) {
    return switch (format) {
      case HEX -> Binary.intToHexString(bits);
      case DECIMAL -> Integer.toString(bits);
      case ASCII -> Binary.intToAscii(bits);
    };
  }
}
