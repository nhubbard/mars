package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.mips.hardware.MemoryState;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HexFormat;

/**
 * Intel HEX memory initialization format
 *
 * @author Leo Alterman
 * @version July 2011
 */
public class IntelHexDumpFormat extends AbstractDumpFormat {

  private static final HexFormat HEX = HexFormat.of().withUpperCase();

  /** Constructor. File extension is "hex". */
  public IntelHexDumpFormat() {
    super("Intel hex format", "HEX", "Written as Intel Hex Memory File", "hex");
  }

  /**
   * Write Intel HEX records with addresses relative to the first exported word.
   * Extended linear address records carry the high bits when the offset reaches 64 KiB.
   *
   * @param file File in which to store MIPS memory contents.
   * @param snapshot the memory to export.
   * @throws IOException if error occurs during file output.
   */
  @Override
  public void write(File file, MemoryDumpSnapshot snapshot) throws IOException {
    try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
      int upperAddress = 0;
      for (int index = 0; index < snapshot.size(); index++) {
        int word = snapshot.word(index);
        int offset = index * MemoryState.WORD_LENGTH_BYTES;
        int upper = offset >>> 16;
        if (upper != upperAddress) {
          int checksum = 2 + 4 + (upper >>> 8) + (upper & 0xFF);
          out.println(":02000004" + HEX.toHexDigits((short) upper) + HEX.toHexDigits((byte) -checksum));
          upperAddress = upper;
        }
        int checksum = 4 + (offset & 0xFF) + ((offset >>> 8) & 0xFF);
        for (int shift = 0; shift < Integer.SIZE; shift += Byte.SIZE) {
          checksum += (word >>> shift) & 0xFF;
        }
        out.println(
            ":04" + HEX.toHexDigits((short) offset) + "00" + HEX.toHexDigits(word)
                + HEX.toHexDigits((byte) -checksum));
      }
      out.println(":00000001FF");
      checkOutput(out);
    }
  }
}
