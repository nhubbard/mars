package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.mips.hardware.AddressErrorException;

import java.io.File;
import java.io.IOException;

/**
 * The Memory Initialization File (.mif) VHDL-supported file format
 * This is documented for the Altera platform at
 * www.altera.com/support/software/nativelink/quartus2/glossary/def_mif.html.
 *
 * @author Pete Sanderson
 * @version December 2007
 */

// NOT READY FOR PRIME TIME.  WHEN IT IS, UNCOMMENT THE "extends" CLAUSE
// AND THE SUPERCLASS CONSTRUCTOR CALL SO THE FORMAT LOADER WILL ACCEPT IT 
// AND IT WILL BE ADDED TO THE LIST.
@SuppressWarnings("EmptyMethod")
public class MIFDumpFormat { //extends AbstractDumpFormat {

    /**
     * Constructor.  File extention is "mif".
     */
    public MIFDumpFormat() {
        //   super("MIF", "MIF", "Written as Memory Initialization File (Altera)", "mif");
    }

    /**
     * Write MIPS memory contents according to the Memory Initialization File
     * (MIF) specification.
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

    }
}