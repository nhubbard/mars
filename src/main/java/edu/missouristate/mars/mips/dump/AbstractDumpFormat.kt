package edu.missouristate.mars.mips.dump

/**
 * Abstract class for memory dump file formats.  Provides constructors and
 * defaults for everything except the dumpMemoryRange method itself.
 *
 * @author Pete Sanderson
 * @version December 2007
 *
 * @param name The name for this format to be used in the user interface of MARS.
 * @param commandDescriptor A one-word description of the format to be used by the MARS command-line parser and user
 * in conjunction with the "dump" option.
 * @param description A short description of the format, suitable for displaying along with the extension, in the file
 * save dialog, or as a tool tip.
 * @param fileExtension The file extension associated with this format.
 */
abstract class AbstractDumpFormat(
    private val name: String,
    commandDescriptor: String?,
    override val description: String,
    override val fileExtension: String
) : DumpFormat {
    // Getter provides a guaranteed safe representation of this dump format's name.
    override val commandDescriptor: String? =
        commandDescriptor?.replace(" ".toRegex(), "")

    override fun toString(): String = name
}