package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.MemoryState;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The contiguous initialized prefix of a stopped machine's requested memory range. */
public final class MemoryDumpSnapshot {
  enum Segment { TEXT, DATA, OTHER }

  record SourceLine(String instruction, int line, String source) {}

  private final int firstAddress;
  private final int[] words;
  private final Segment segment;
  private final List<SourceLine> sourceLines;
  private final boolean hexAddresses;
  private final boolean hexValues;

  private MemoryDumpSnapshot(
      int firstAddress, int[] words, Segment segment, List<SourceLine> sourceLines,
      boolean hexAddresses, boolean hexValues) {
    this.firstAddress = firstAddress;
    this.words = words;
    this.segment = segment;
    this.sourceLines = sourceLines;
    this.hexAddresses = hexAddresses;
    this.hexValues = hexValues;
  }

  static MemoryDumpSnapshot capture(
      MarsContext session, int firstAddress, int lastAddress, boolean includeSource, Runnable checkCancellation)
      throws AddressErrorException {
    var memory = session.memory;
    var segment = memory.inTextSegment(firstAddress) ? Segment.TEXT
        : memory.inDataSegment(firstAddress) ? Segment.DATA : Segment.OTHER;
    boolean captureSource = includeSource && segment == Segment.TEXT;
    var lines = new ArrayList<SourceLine>();
    int[] words = new int[64];
    int count = 0;
    long end = Integer.toUnsignedLong(lastAddress);
    for (long address = Integer.toUnsignedLong(firstAddress); address <= end;
        address += MemoryState.WORD_LENGTH_BYTES) {
      checkCancellation.run();
      Integer word;
      if (captureSource) {
        var statement = memory.getStatementNoNotify((int) address);
        if (statement == null) {
          break;
        }
        word = statement.getBinaryStatement();
        lines.add(new SourceLine(
            statement.getPrintableBasicAssemblyStatement(), statement.getSourceLine(), statement.getSource()));
      } else {
        word = memory.getRawWordOrNull((int) address);
        if (word == null) {
          break;
        }
      }
      if (count == words.length) {
        words = Arrays.copyOf(words, count * 2);
      }
      words[count++] = word;
    }
    return new MemoryDumpSnapshot(firstAddress, Arrays.copyOf(words, count), segment,
        List.copyOf(lines), session.settings.displayAddressesInHex(), session.settings.displayValuesInHex());
  }

  public int size() {
    return words.length;
  }

  int address(int index) {
    return firstAddress + index * MemoryState.WORD_LENGTH_BYTES;
  }

  int word(int index) {
    return words[index];
  }

  Segment segment() {
    return segment;
  }

  SourceLine sourceLine(int index) {
    return sourceLines.get(index);
  }

  boolean hexAddresses() {
    return hexAddresses;
  }

  boolean hexValues() {
    return hexValues;
  }
}
