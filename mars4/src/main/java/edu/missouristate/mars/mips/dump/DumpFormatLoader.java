package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.util.FilenameFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class provides functionality to bring external memory dump format definitions
 * into MARS.  This is adapted from the ToolLoader class, which is in turn adapted
 * from Bret Barker's GameServer class from the book "Developing Games In Java".
 */
public class DumpFormatLoader {
    private static final String CLASS_PREFIX = "edu.missouristate.mars.mips.dump.";
    private static final String DUMP_DIRECTORY_PATH = "edu/missouristate/mars/mips/dump";
    private static final String SYSCALL_INTERFACE = "DumpFormat.class";
    private static final String CLASS_EXTENSION = "class";
    private static @Nullable ArrayList<DumpFormat> formatList = null;

    /**
     * Dynamically loads dump formats into an ArrayList.  This method is adapted from
     * the loadGameControllers() method in Bret Barker's GameServer class.
     * Barker (bret@hypefiend.com) is co-author of the book "Developing Games
     * in Java".  Also see the ToolLoader and SyscallLoader classes elsewhere in MARS.
     */
    // TODO: Consider using a class indexing system with annotations instead?
    @NotNull
    public ArrayList<@NotNull DumpFormat> loadDumpFormats() {
        // The list will be populated only the first time this method is called.
        if (formatList == null) {
            formatList = new ArrayList<>();
            // grab all class files in the dump directory
            ArrayList<String> candidates = FilenameFinder.getFilenameList(this.getClass().getClassLoader(), DUMP_DIRECTORY_PATH, CLASS_EXTENSION);
            for (String file : candidates) {
                try {
                    // grab the class, make sure it implements DumpFormat, instantiate, add to list
                    String formatClassName = CLASS_PREFIX + file.substring(0, file.indexOf(CLASS_EXTENSION) - 1);
                    Class<?> clas = Class.forName(formatClassName);
                    if (DumpFormat.class.isAssignableFrom(clas) && !Modifier.isAbstract(clas.getModifiers())) {
                        formatList.add((DumpFormat) clas.getDeclaredConstructor().newInstance());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return formatList;
    }

    @Nullable
    public static DumpFormat findDumpFormatGivenCommandDescriptor(@NotNull ArrayList<DumpFormat> formatList, String formatCommandDescriptor) {
        AtomicReference<@Nullable DumpFormat> match = new AtomicReference<>();
        for (DumpFormat dumpFormat : formatList) {
            Optional<String> descriptor = Optional.ofNullable(dumpFormat.getCommandDescriptor());
            if (descriptor.isPresent() && descriptor.get().equals(formatCommandDescriptor)) {
                match.set(dumpFormat);
                break;
            }
        }
        return match.get();
    }
}
