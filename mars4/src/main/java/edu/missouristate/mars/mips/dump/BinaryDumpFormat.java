package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Memory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Class that represents the "binary" memory dump format.  The output
 * is a binary file containing the memory words as a byte stream.  Output
 * is produced using PrintStream's write() method.
 *
 * @author Pete Sanderson
 * @version December 2007
 */
public class BinaryDumpFormat extends AbstractDumpFormat {

    /**
     * Constructor.  There is no standard file extension for this format.
     */
    public BinaryDumpFormat() {
        super("Binary", "Binary", "Written as byte stream to binary file", "bin");
    }

    /**
     * Write MIPS memory contents in pure binary format.  One byte at a time
     * using PrintStream's write() method.  Adapted by Pete Sanderson from
     * code written by Greg Gibeling.
     *
     * @param file         File in which to store MIPS memory contents.
     * @param firstAddress first (lowest) memory address to dump.  In bytes but
     *                     must be on word boundary.
     * @param lastAddress  last (highest) memory address to dump.  In bytes but
     *                     must be on word boundary.  Will dump the word that starts at this address.
     * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
     * @throws IOException           if error occurs during file output.
     */
    public void dumpMemoryRange(@NotNull File file, int firstAddress, int lastAddress)
            throws AddressErrorException, IOException {
        try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
            for (int address = firstAddress; address <= lastAddress; address += Memory.WORD_LENGTH_BYTES) {
                @Nullable Integer temp = Globals.memory.getRawWordOrNull(address);
                if (temp == null)
                    break;
                int word = temp;
                for (int i = 0; i < 4; i++)
                    out.write((word >>> (i << 3)) & 0xFF);
            }
        }
    }
}