package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.hardware.Memory;
import edu.missouristate.mars.util.Binary;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;

/**
 * Class that represents the "ASCII text" memory dump format. Memory contents
 * are interpreted as ASCII codes. The output
 * is a text file with one word of MIPS memory per line.  The word is formatted
 * to leave three spaces for each character.  Non-printing characters
 * rendered as period (.) as placeholder.  Common escaped characters
 * rendered using backslash and single-character descriptor, e.g. \t for tab.
 *
 * @author Pete Sanderson
 * @version December 2010
 */
public class AsciiTextDumpFormat extends AbstractDumpFormat {
    /**
     * Constructor.  There is no standard file extension for this format.
     */
    public AsciiTextDumpFormat() {
        super("ASCII Text", "Ascii Text", "Memory contents interpreted as ASCII characters", "txt");
    }

    /**
     * Interpret MIPS memory contents as ASCII characters.  Each line of
     * text contains one memory word written in ASCII characters.  Those
     * corresponding to tab, newline, null, etc. are rendered as backslash
     * followed by single-character code, e.g. \t for tab, \0 for null.
     * Non-printing character (control code,
     * values above 127) is rendered as a period (.).  Written
     * using PrintStream's println() method.
     * Adapted by Pete Sanderson from code written by Greg Gibeling.
     *
     * @param file         File in which to store MIPS memory contents.
     * @param firstAddress first (lowest) memory address to dump.  In bytes but
     *                     must be on word boundary.
     * @param lastAddress  last (highest) memory address to dump.  In bytes but
     *                     must be on word boundary.  Will dump the word that starts at this address.
     * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
     * @throws IOException           if error occurs during file output.
     */
    public void dumpMemoryRange(@NotNull File file, int firstAddress, int lastAddress) throws AddressErrorException, IOException {
        try (PrintStream out = new PrintStream(new FileOutputStream(file))) {
            @Nullable String string = null;
            for (int address = firstAddress; address <= lastAddress; address += Memory.WORD_LENGTH_BYTES) {
                @Nullable Integer temp = Globals.memory.getRawWordOrNull(address);
                if (temp == null) break;
                out.println(Binary.intToAscii(temp));
            }
        }
    }
}