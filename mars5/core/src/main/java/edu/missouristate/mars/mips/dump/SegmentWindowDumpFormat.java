package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.mips.hardware.*;
import edu.missouristate.mars.util.Binary;
import java.io.*;

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/**
 * Dump MIPS memory contents in Segment Window format. Each line of text output resembles the Text
 * Segment Window or Data Segment Window depending on which segment is selected for the dump.
 * Written using PrintStream's println() method. Each line of Text Segment Window represents one
 * word of text segment memory. The line includes (1) address, (2) machine code in hex, (3) basic
 * instruction, (4) source line. Each line of Data Segment Window represents 8 words of data segment
 * memory. The line includes address of first word for that line followed by 8 32-bit values.
 *
 * <p>In either case, addresses and values are displayed in decimal or hexadecimal representation
 * according to the corresponding settings.
 *
 * @author Pete Sanderson
 * @version January 2008
 */
public class SegmentWindowDumpFormat extends AbstractDumpFormat {

  /** Constructor. There is no standard file extension for this format. */
  public SegmentWindowDumpFormat() {
    super(
        "Text/Data Segment Window",
        "SegmentWindow",
        " Text Segment Window or Data Segment Window format to text file",
        null);
  }

  /**
   * Write MIPS memory contents in Segment Window format. Each line of text output resembles the
   * Text Segment Window or Data Segment Window depending on which segment is selected for the dump.
   * Written using PrintStream's println() method.
   *
   * @param file File in which to store MIPS memory contents.
   * @param firstAddress first (lowest) memory address to dump. In bytes but must be on word
   *     boundary.
   * @param lastAddress last (highest) memory address to dump. In bytes but must be on word
   *     boundary. Will dump the word that starts at this address.
   * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
   * @throws IOException if error occurs during file output.
   */
  public void dumpMemoryRange(File file, int firstAddress, int lastAddress, MarsContext session)
      throws AddressErrorException, IOException {

    boolean hexAddresses = session.settings.displayAddressesInHex();
    try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
      if (session.memory.inDataSegment(firstAddress)) {
        dumpData(firstAddress, lastAddress, session, hexAddresses, out);
      } else if (session.memory.inTextSegment(firstAddress)) {
        dumpText(firstAddress, lastAddress, session, hexAddresses, out);
      }
      checkOutput(out);
    }
  }

  private static void dumpData(
      int firstAddress, int lastAddress, MarsContext session, boolean hexAddresses, PrintStream out)
      throws AddressErrorException {
    boolean hexValues = session.settings.displayValuesInHex();
    int wordsInRow = 0;
    StringBuilder row = new StringBuilder();
    for (int address = firstAddress;
        address <= lastAddress;
        address += MemoryState.WORD_LENGTH_BYTES) {
      Integer word = session.memory.getRawWordOrNull(address);
      if (word == null) {
        break;
      }
      if (wordsInRow == 0) {
        row.append(formatAddress(address, hexAddresses)).append("    ");
      }
      row.append(hexValues ? Binary.intToHexString(word) : ("           " + word).substring(word.toString().length()))
          .append(' ');
      if (++wordsInRow == 8) {
        out.println(row);
        row.setLength(0);
        wordsInRow = 0;
      }
    }
    if (wordsInRow != 0) {
      out.println(row);
    }
  }

  private static void dumpText(
      int firstAddress, int lastAddress, MarsContext session, boolean hexAddresses, PrintStream out)
      throws AddressErrorException {
    out.println(" Address    Code        Basic                     Source");
    out.println();
    for (int address = firstAddress;
        address <= lastAddress;
        address += MemoryState.WORD_LENGTH_BYTES) {
      ProgramStatement statement = session.memory.getStatementNoNotify(address);
      if (statement == null) {
        break;
      }
      String source = statement.getSource();
      out.println(
          formatAddress(address, hexAddresses) + "  "
              + Binary.intToHexString(statement.getBinaryStatement()) + "  "
              + (statement.getPrintableBasicAssemblyStatement() + "                      ").substring(0, 22)
              + ((source.isEmpty() ? "" : statement.getSourceLine()) + "     ").substring(0, 5)
              + source);
    }
  }

  private static String formatAddress(int address, boolean hexAddresses) {
    return hexAddresses ? Binary.intToHexString(address) : Binary.unsignedIntToIntString(address);
  }
}
