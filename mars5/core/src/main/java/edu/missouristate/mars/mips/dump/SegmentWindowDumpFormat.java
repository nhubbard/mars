package edu.missouristate.mars.mips.dump;

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
   * @param snapshot the memory to export.
   * @throws IOException if error occurs during file output.
   */
  @Override
  public void write(File file, MemoryDumpSnapshot snapshot) throws IOException {

    try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
      switch (snapshot.segment()) {
        case DATA -> dumpData(snapshot, out);
        case TEXT -> dumpText(snapshot, out);
        case OTHER -> { }
      }
      checkOutput(out);
    }
  }

  @Override
  public boolean includesSourceListing() {
    return true;
  }

  private static void dumpData(MemoryDumpSnapshot snapshot, PrintStream out) {
    int wordsInRow = 0;
    StringBuilder row = new StringBuilder();
    for (int index = 0; index < snapshot.size(); index++) {
      int word = snapshot.word(index);
      if (wordsInRow == 0) {
        row.append(formatAddress(snapshot.address(index), snapshot.hexAddresses())).append("    ");
      }
      String value = Integer.toString(word);
      row.append(snapshot.hexValues() ? Binary.intToHexString(word) : ("           " + value).substring(value.length()))
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

  private static void dumpText(MemoryDumpSnapshot snapshot, PrintStream out) {
    out.println(" Address    Code        Basic                     Source");
    out.println();
    for (int index = 0; index < snapshot.size(); index++) {
      var line = snapshot.sourceLine(index);
      out.println(
          formatAddress(snapshot.address(index), snapshot.hexAddresses()) + "  "
              + Binary.intToHexString(snapshot.word(index)) + "  "
              + (line.instruction() + "                      ").substring(0, 22)
              + ((line.source().isEmpty() ? "" : line.line()) + "     ").substring(0, 5)
              + line.source());
    }
  }

  private static String formatAddress(int address, boolean hexAddresses) {
    return hexAddresses ? Binary.intToHexString(address) : Binary.unsignedIntToIntString(address);
  }
}
