package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.util.Binary;
import edu.missouristate.mars.mips.hardware.*;

import java.io.*;

/**
 * Dump MIPS memory contents in Segment Window format.  Each line of
 * text output resembles the Text Segment Window or Data Segment Window
 * depending on which segment is selected for the dump.  Written
 * using PrintStream's println() method.  Each line of Text Segment
 * Window represents one word of text segment memory.  The line
 * includes (1) address, (2) machine code in hex, (3) basic instruction,
 * (4) source line.  Each line of Data Segment Window represents 8
 * words of data segment memory.  The line includes address of first
 * word for that line followed by 8 32-bit values.
 * <p>
 * In either case, addresses and values are displayed in decimal or
 * hexadecimal representation according to the corresponding settings.
 *
 * @author Pete Sanderson
 * @version January 2008
 */


public class SegmentWindowDumpFormat extends AbstractDumpFormat {

    /**
     * Constructor.  There is no standard file extension for this format.
     */
    public SegmentWindowDumpFormat() {
        super("Text/Data Segment Window", "SegmentWindow", " Text Segment Window or Data Segment Window format to text file", null);
    }


    /**
     * Write MIPS memory contents in Segment Window format.  Each line of
     * text output resembles the Text Segment Window or Data Segment Window
     * depending on which segment is selected for the dump.  Written
     * using PrintStream's println() method.
     *
     * @param file         File in which to store MIPS memory contents.
     * @param firstAddress first (lowest) memory address to dump.  In bytes but
     *                     must be on word boundary.
     * @param lastAddress  last (highest) memory address to dump.  In bytes but
     *                     must be on word boundary.  Will dump the word that starts at this address.
     * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
     * @throws IOException           if error occurs during file output.
     */
    public void dumpMemoryRange(File file, int firstAddress, int lastAddress)
            throws AddressErrorException, IOException {

        PrintStream out = new PrintStream(new FileOutputStream(file));

        boolean hexAddresses = Globals.getSettings().getDisplayAddressesInHex();

        // If address in data segment, print in same format as Data Segment Window
        if (Memory.inDataSegment(firstAddress)) {
            boolean hexValues = Globals.getSettings().getDisplayValuesInHex();
            int offset = 0;
            String string = "";
            try {
                for (int address = firstAddress; address <= lastAddress; address += Memory.WORD_LENGTH_BYTES) {
                    if (offset % 8 == 0) {
                        string = ((hexAddresses) ? Binary.intToHexString(address) : Binary.unsignedIntToIntString(address)) + "    ";
                    }
                    offset++;
                    Integer temp = Globals.memory.getRawWordOrNull(address);
                    if (temp == null)
                        break;
                    string += ((hexValues)
                            ? Binary.intToHexString(temp.intValue())
                            : ("           " + temp).substring(temp.toString().length())
                    ) + " ";
                    if (offset % 8 == 0) {
                        out.println(string);
                        string = "";
                    }
                }
            } finally {
                out.close();
            }
            return;
        }

        if (!Memory.inTextSegment(firstAddress)) {
            return;
        }
        // If address in text segment, print in same format as Text Segment Window
        out.println(" Address    Code        Basic                     Source");
        //           12345678901234567890123456789012345678901234567890
        //                    1         2         3         4         5
        out.println();
        String string = null;
        try {
            for (int address = firstAddress; address <= lastAddress; address += Memory.WORD_LENGTH_BYTES) {
                string = ((hexAddresses) ? Binary.intToHexString(address) : Binary.unsignedIntToIntString(address)) + "  ";
                Integer temp = Globals.memory.getRawWordOrNull(address);
                if (temp == null)
                    break;
                string += Binary.intToHexString(temp.intValue()) + "  ";
                try {
                    ProgramStatement ps = Globals.memory.getStatement(address);
                    string += (ps.getPrintableBasicAssemblyStatement() + "                      ").substring(0, 22);
                    string += (((ps.getSource() == "") ? "" : Integer.valueOf(ps.getSourceLine()).toString()) + "     ").substring(0, 5);
                    string += ps.getSource();
                } catch (AddressErrorException ignored) {
                }
                out.println(string);
            }
        } finally {
            out.close();
        }
    }


}