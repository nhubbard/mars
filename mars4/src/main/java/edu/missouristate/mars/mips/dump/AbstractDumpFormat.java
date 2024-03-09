package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.mips.hardware.AddressErrorException;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.UnknownNullability;

import java.io.File;
import java.io.IOException;

/**
 * Abstract class for memory dump file formats.  Provides constructors and
 * defaults for everything except the dumpMemoryRange method itself.
 *
 * @author Pete Sanderson
 * @version December 2007
 */
public abstract class AbstractDumpFormat implements DumpFormat {
    private final String name;
    @Nullable
    private final String commandDescriptor;
    private final String description;
    @Nullable
    private final String extension;

    /**
     * Typical constructor.
     * Note you cannot create objects from this class, but the subclass constructor can call this one.
     *
     * @param name              Brief descriptive name to be displayed in selection list.
     * @param commandDescriptor One-word descriptive name to be used by MARS command mode parser and user.
     *                          Any spaces in this string will be removed.
     * @param description       Description to go with the standard file extension for
     *                          display in file save dialog or to be used as tool tip.
     * @param extension         Standard file extension for this format.  Null if none.
     */
    public AbstractDumpFormat(String name, @Nullable String commandDescriptor, String description, @Nullable String extension) {
        this.name = name;
        this.commandDescriptor = (commandDescriptor == null) ? null : commandDescriptor.replaceAll(" ", "");
        this.description = description;
        this.extension = extension;
    }

    /**
     * Get the file extension associated with this format.
     *
     * @return String containing file extension -- without the leading "." -- or
     * null if there is no standard extension.
     */
    @Nullable
    public String getFileExtension() {
        return extension;
    }

    /**
     * Get a short description of the format, suitable for displaying along with
     * the extension, in the file save dialog, or as a tool tip.
     *
     * @return String containing short description to go with the extension
     * or for use as tool tip.  Possibly null.
     */
    @UnknownNullability
    public String getDescription() {
        return description;
    }

    /**
     * String representing this object.
     *
     * @return Name given for this object.
     */
    public String toString() {
        return name;
    }

    /**
     * One-word description of format to be used by MARS command mode parser
     * and user in conjunction with the "dump" option.
     *
     * @return One-word String describing the format.
     */
    @Nullable
    public String getCommandDescriptor() {
        return commandDescriptor;
    }

    /**
     * Write MIPS memory contents according to the
     * specification for this format.
     *
     * @param file         File in which to store MIPS memory contents.
     * @param firstAddress first (lowest) memory address to dump.  In bytes but
     *                     must be on word boundary.
     * @param lastAddress  last (highest) memory address to dump.  In bytes but
     *                     must be on word boundary.  Will dump the word that starts at this address.
     * @throws AddressErrorException if firstAddress is invalid or not on a word boundary.
     * @throws IOException           if error occurs during file output.
     */
    public abstract void dumpMemoryRange(File file, int firstAddress, int lastAddress) throws AddressErrorException, IOException;
}