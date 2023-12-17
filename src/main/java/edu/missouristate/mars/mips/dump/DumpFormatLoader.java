package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.util.FilenameFinder;

import java.lang.reflect.Modifier;
import java.util.ArrayList;

/****************************************************************************/
/* This class provides functionality to bring external memory dump format definitions
 * into MARS.  This is adapted from the ToolLoader class, which is in turn adapted
 * from Bret Barker's GameServer class from the book "Developing Games In Java".
 */

public class DumpFormatLoader {

    private static final String CLASS_PREFIX = "mars.mips.dump.";
    private static final String DUMP_DIRECTORY_PATH = "mars/mips/dump";
    private static final String SYSCALL_INTERFACE = "DumpFormat.class";
    private static final String CLASS_EXTENSION = "class";

    private static ArrayList<DumpFormat> formatList = null;

    /**
     * Dynamically loads dump formats into an ArrayList.  This method is adapted from
     * the loadGameControllers() method in Bret Barker's GameServer class.
     * Barker (bret@hypefiend.com) is co-author of the book "Developing Games
     * in Java".  Also see the ToolLoader and SyscallLoader classes elsewhere in MARS.
     */
    // TODO: Consider using a class indexing system with annotations instead?
    public ArrayList<DumpFormat> loadDumpFormats() {
        // The list will be populated only the first time this method is called.
        if (formatList == null) {
            formatList = new ArrayList<>();
            // grab all class files in the dump directory
            ArrayList<String> candidates = FilenameFinder.getFilenameList(this.getClass().getClassLoader(),
                    DUMP_DIRECTORY_PATH, CLASS_EXTENSION);
            for (String file : candidates) {
                try {
                    // grab the class, make sure it implements DumpFormat, instantiate, add to list
                    String formatClassName = CLASS_PREFIX + file.substring(0, file.indexOf(CLASS_EXTENSION) - 1);
                    Class<DumpFormat> clas = (Class<DumpFormat>) Class.forName(formatClassName);
                    if (DumpFormat.class.isAssignableFrom(clas) &&
                            !Modifier.isAbstract(clas.getModifiers()) &&
                            !Modifier.isInterface(clas.getModifiers())) {
                        formatList.add(clas.getDeclaredConstructor().newInstance());
                    }
                } catch (Exception e) {
                    System.out.println("Error instantiating DumpFormat from file " + file + ": " + e);
                }
            }
        }
        return formatList;
    }

    public static DumpFormat findDumpFormatGivenCommandDescriptor(ArrayList<DumpFormat> formatList, String formatCommandDescriptor) {
        DumpFormat match = null;
        for (DumpFormat dumpFormat : formatList) {
            if (dumpFormat.getCommandDescriptor().equals(formatCommandDescriptor)) {
                match = dumpFormat;
                break;
            }
        }
        return match;
    }


}
