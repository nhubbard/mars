package edu.missouristate.mars.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.filechooser.FileFilter;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Utility class to perform necessary file-related search
 * operations.  One is to find file names in JAR file,
 * another is to find names of files in given directory
 * of normal file system.
 *
 * @author Pete Sanderson
 * @version October 2006
 */
public class FilenameFinder {
    private static final String JAR_EXTENSION = ".jar";
    private static final String FILE_URL = "file:";
    private static final String JAR_URI_PREFIX = "jar:";
    private static final boolean NO_DIRECTORIES = false;
    public static final String MATCH_ALL_EXTENSIONS = "*";

    /**
     * Locate files and return list of file names.  Given a known relative directory path,
     * it will locate it and build list of all names of files in that directory
     * having the given file extension. If the "known file path" doesn't work
     * because MARS is running from an executable JAR file, it will locate the
     * directory in the JAR file and proceed from there.  NOTE: since this uses
     * the class loader to get the resource, the directory path needs to be
     * relative to classpath, not absolute.  To work with an arbitrary file system,
     * use the other version of this overloaded method.  Will NOT match directories
     * that happen to have the desired extension.
     *
     * @param classLoader   class loader to use
     * @param directoryPath Search will be confined to this directory.  Use "/" as
     *                      separator but do NOT include starting or ending "/"  (e.g. mars/tools)
     * @param fileExtension Only files with this extension will be added
     *                      to the list.  Do NOT include the "." in extension.
     * @return array list of matching file names as Strings.  If none, list is empty.
     */
    public static @NotNull ArrayList<String> getFilenameList(@NotNull ClassLoader classLoader,
                                                             String directoryPath,
                                                             String fileExtension) {
        fileExtension = checkFileExtension(fileExtension);
        ArrayList<String> filenameList = new ArrayList<>();
        // Modified by DPS 10-July-2008 to better handle path containing space
        // character (%20) and to hopefully handle path containing non-ASCII
        // characters.  The "toURI()" approach was suggested by MARS user
        // Felipe Lessa and worked for him when running 'java Mars' but it did
        // not work when executing from a jar file 'java -jar Mars.jar'.  I
        // took it from there and discovered that in the latter situation,
        // "toURI()" created a URI prefixed with "jar:" and the "getPath()" in
        // that case returns null! If you strip the "jar:" prefix and create a
        // new URI from the resulting string, it works!  Thanks Felipe!
        //
        // NOTE 5-Sep-2008: "toURI()" was introduced in Java 1.5.  To maintain
        // 1.4 compatibility, I need to change it to call URI constructor with
        // string argument, as documented in Sun API.
        //
        // Modified by Ingo Kofler 24-Sept-2009 to handle multiple JAR files.
        // This requires use of ClassLoader getResources() instead of
        // getResource().  The former will look in all JAR files listed in
        // in the java command.
        //
        URI uri;
        try {
            Iterator<URL> urls = classLoader.getResources(directoryPath).asIterator();

            while (urls.hasNext()) {
                uri = new URI(urls.next().toString());
                if (uri.toString().indexOf(JAR_URI_PREFIX) == 0) {
                    uri = new URI(uri.toString().substring(JAR_URI_PREFIX.length()));
                }

                File f = new File(uri.getPath());
                File[] files = f.listFiles();
                if (files == null) {
                    if (f.toString().toLowerCase().indexOf(JAR_EXTENSION) > 0) {
                        // Must be running from a JAR file. Use ZipFile to find files and create list.
                        // Modified 12/28/09 by DPS to add results to existing filenameList instead of overwriting it.
                        filenameList.addAll(getListFromJar(extractJarFilename(f.toString()), directoryPath, fileExtension));
                    }
                } else {  // have array of File objects; convert to names and add to list
                    FileFilter filter = getFileFilter(fileExtension, "", NO_DIRECTORIES);
                    for (File file : files) {
                        if (filter.accept(file)) {
                            filenameList.add(file.getName());
                        }
                    }
                }
            }
            return filenameList;

        } catch (URISyntaxException | IOException e) {
            e.printStackTrace();
            return filenameList;
        }
    }


    /**
     * Locate files and return list of file names.  Given a known relative directory path,
     * it will locate it and build list of all names of files in that directory
     * having the given file extension. If the "known file path" doesn't work
     * because MARS is running from an executable JAR file, it will locate the
     * directory in the JAR file and proceed from there.  NOTE: since this uses
     * the class loader to get the resource, the directory path needs to be
     * relative to classpath, not absolute.  To work with an arbitrary file system,
     * use the other version of this overloaded method.
     *
     * @param classLoader    class loader to use
     * @param directoryPath  Search will be confined to this directory.  Use "/" as
     *                       separator but do NOT include starting or ending "/"  (e.g. mars/tools)
     * @param fileExtensions ArrayList of Strings containing file extensions.
     *                       Only files with an extension in this list will be added to the list.
     *                       Do NOT include the ".", eg "class" not ".class".  If Arraylist or
     *                       extension null or empty, all files are added.
     * @return array list of matching file names as Strings.  If none, list is empty.
     */
    public static ArrayList<String> getFilenameList(@NotNull ClassLoader classLoader,
                                                    String directoryPath,
                                                    @Nullable ArrayList<String> fileExtensions) {
        ArrayList<String> filenameList = new ArrayList<>();
        String fileExtension;
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            filenameList = getFilenameList(classLoader, directoryPath, "");
        } else {
            for (String extension : fileExtensions) {
                fileExtension = checkFileExtension(extension);
                filenameList.addAll(getFilenameList(classLoader, directoryPath, fileExtension));
            }
        }
        return filenameList;
    }


    /**
     * Locate files and return list of file names.  Given a known directory path,
     * it will locate it and build list of all names of files in that directory
     * having the given file extension.  If file extenion is null or empty, all
     * filenames are returned. Returned list contains absolute filename paths.
     *
     * @param directoryPath Search will be confined to this directory.
     * @param fileExtension Only files with this extension will be added to the list.
     *                      Do NOT include "." in extension.
     *                      If null or empty string, all files are added.
     * @return array list of matching file names (absolute path).  If none, list is empty.
     */
    public static @NotNull ArrayList<String> getFilenameList(@NotNull String directoryPath, String fileExtension) {
        fileExtension = checkFileExtension(fileExtension);
        ArrayList<String> filenameList = new ArrayList<>();
        File directory = new File(directoryPath);
        if (directory.isDirectory()) {
            File[] allFiles = directory.listFiles();
            FileFilter filter = getFileFilter(fileExtension, "", NO_DIRECTORIES);
            for (File allFile : Objects.requireNonNull(allFiles)) {
                if (filter.accept(allFile)) {
                    filenameList.add(allFile.getAbsolutePath());
                }
            }
        }
        return filenameList;
    }


    /**
     * Locate files and return list of file names.  Given a known directory path,
     * it will locate it and build list of all names of files in that directory
     * having the given file extension.  If file extenion is null or empty, all
     * filenames are returned. Returned list contains absolute filename paths.
     *
     * @param directoryPath  Search will be confined to this directory.
     * @param fileExtensions ArrayList of Strings containing file extensions.
     *                       Only files with an extension in this list will be added
     *                       to the list.  Do NOT include the "." in extensions.  If Arraylist or
     *                       extension null or empty, all files are added.
     * @return array list of matching file names (absolute path).  If none, list is empty.
     */
    public static @NotNull ArrayList<String> getFilenameList(@NotNull String directoryPath, @Nullable ArrayList<String> fileExtensions) {
        ArrayList<String> filenameList = new ArrayList<>();
        String fileExtension;
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            filenameList = getFilenameList(directoryPath, "");
        } else {
            for (String extension : fileExtensions) {
                fileExtension = checkFileExtension(extension);
                filenameList.addAll(getFilenameList(directoryPath, fileExtension));
            }
        }
        return filenameList;
    }


    /**
     * Return list of file names.  Given a list of file names, it will return the list
     * of all having the given file extension.  If file extenion is null or empty, all
     * filenames are returned.  Returned list contains absolute filename paths.
     *
     * @param nameList      ArrayList of String containing file names.
     * @param fileExtension Only files with this extension will be added to the list.
     *                      If null or empty string, all files are added.  Do NOT include "." in extension.
     * @return array list of matching file names (absolute path).  If none, list is empty.
     */
    public static @NotNull ArrayList<String> getFilenameList(@NotNull ArrayList<String> nameList, String fileExtension) {
        fileExtension = checkFileExtension(fileExtension);
        ArrayList<String> filenameList = new ArrayList<>();
        FileFilter filter = getFileFilter(fileExtension, "", NO_DIRECTORIES);
        for (String s : nameList) {
            File file = new File(s);
            if (filter.accept(file)) {
                filenameList.add(file.getAbsolutePath());
            }
        }
        return filenameList;
    }


    /**
     * Return list of file names.  Given a list of file names, it will return the list
     * of all having the given file extension.  If file extenion is null or empty, all
     * filenames are returned.  Returned list contains absolute filename paths.
     *
     * @param nameList       ArrayList of String containing file names.
     * @param fileExtensions ArrayList of Strings containing file extensions.
     *                       Only files with an extension in this list will be added
     *                       to the list.  Do NOT include the "." in extensions.  If Arraylist or
     *                       extension null or empty, all files are added.
     * @return array list of matching file names (absolute path).  If none, list is empty.
     */
    public static @NotNull ArrayList<String> getFilenameList(@NotNull ArrayList<String> nameList, @Nullable ArrayList<String> fileExtensions) {
        ArrayList<String> filenameList = new ArrayList<>();
        String fileExtension;
        if (fileExtensions == null || fileExtensions.isEmpty()) {
            filenameList = getFilenameList(nameList, "");
        } else {
            for (String extension : fileExtensions) {
                fileExtension = checkFileExtension(extension);
                filenameList.addAll(getFilenameList(nameList, fileExtension));
            }
        }
        return filenameList;
    }

    /**
     * Get the filename extension of the specified File.
     *
     * @param file the File object representing the file of interest
     * @return The filename extension (everything that follows
     * last '.' in filename) or null if none.
     */
    // Source code from Sun Microsystems "The Java Tutorials : How To Use File Choosers"
    public static @Nullable String getExtension(@NotNull File file) {
        String ext = null;
        String s = file.getName();
        int i = s.lastIndexOf('.');
        if (i > 0 && i < s.length() - 1) {
            ext = s.substring(i + 1).toLowerCase();
        }
        return ext;
    }

    /**
     * Get a FileFilter that will filter files based on the given list of filename extensions.
     *
     * @param extensions        ArrayList of Strings, each string is acceptable filename extension.
     * @param description       String containing description to be added in parentheses after list of extensions.
     * @param acceptDirectories boolean value true if directories are accepted by the filter, false otherwise.
     * @return a FileFilter object that accepts files with given extensions, and directories if so indicated.
     */

    public static @NotNull FileFilter getFileFilter(@NotNull ArrayList<String> extensions, String description, boolean acceptDirectories) {
        return new MarsFileFilter(extensions, description, acceptDirectories);
    }

    /**
     * Get a FileFilter that will filter files based on the given list of filename extensions.
     * All directories are accepted by the filter.
     *
     * @param extensions  ArrayList of Strings, each string is acceptable filename extension
     * @param description String containing description to be added in parentheses after list of extensions.
     * @return a FileFilter object that accepts files with given extensions, and directories if so indicated.
     */

    public static @NotNull FileFilter getFileFilter(@NotNull ArrayList<String> extensions, String description) {
        return getFileFilter(extensions, description, true);
    }

    /**
     * Get a FileFilter that will filter files based on the given filename extension.
     *
     * @param extension         String containing acceptable filename extension.
     * @param description       String containing description to be added in parentheses after list of extensions.
     * @param acceptDirectories boolean value true if directories are accepted by the filter, false otherwise.
     * @return a FileFilter object that accepts files with given extensions, and directories if so indicated.
     */

    public static @NotNull FileFilter getFileFilter(String extension, String description, boolean acceptDirectories) {
        ArrayList<String> extensions = new ArrayList<>();
        extensions.add(extension);
        return new MarsFileFilter(extensions, description, acceptDirectories);
    }

    /**
     * Get a FileFilter that will filter files based on the given filename extension.
     * All directories are accepted by the filter.
     *
     * @param extension   String containing acceptable filename extension
     * @param description String containing description to be added in parentheses after list of extensions.
     * @return a FileFilter object that accepts files with given extensions, and directories if so indicated.
     */

    public static @NotNull FileFilter getFileFilter(String extension, String description) {
        ArrayList<String> extensions = new ArrayList<>();
        extensions.add(extension);
        return getFileFilter(extensions, description, true);
    }

    /**
     * Determine if given filename ends with given extension.
     *
     * @param name      A String containing the file name
     * @param extension A String containing the file extension.  Leading period is optional.
     * @return Returns true if filename ends with given extension, false otherwise.
     */
    // For assured results, make sure extension starts with "."	(will add it if not there)
    public static boolean fileExtensionMatch(@NotNull String name, @Nullable String extension) {
        return (extension == null || extension.isEmpty() || name.endsWith(((extension.startsWith(".")) ? "" : ".") + extension));
    }

    // return list of file names in specified folder inside JAR
    private static @NotNull ArrayList<String> getListFromJar(@Nullable String jarName, String directoryPath, String fileExtension) {
        fileExtension = checkFileExtension(fileExtension);
        ArrayList<String> nameList = new ArrayList<>();
        if (jarName == null) {
            return nameList;
        }
        try {
            try (ZipFile zf = new ZipFile(new File(jarName))) {
                Iterator<? extends ZipEntry> list = zf.entries().asIterator();
                while (list.hasNext()) {
                    ZipEntry ze = list.next();
                    if (ze.getName().startsWith(directoryPath + "/") &&
                            fileExtensionMatch(ze.getName(), fileExtension)) {
                        nameList.add(ze.getName().substring(ze.getName().lastIndexOf('/') + 1));
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Exception occurred reading MarsTool list from JAR: " + e);
        }
        return nameList;
    }

    // Given pathname, extract and return JAR file name (must be only element containing ".jar")
    // 5 Dec 2007 DPS: Modified to return file path of JAR file, not just its name.  This was
    //                 by request of Zachary Kurmas of Grant Valley State, who got errors trying
    //                 to run the Mars.jar file from a different working directory.  He helpfully
    //                 pointed out what the error is and where it occurs.  Originally, it would
    //                 work only if the JAR file was in the current working directory (as would
    //                 be the case if executed from a GUI by double-clicking the jar icon).
    private static @NotNull String extractJarFilename(@NotNull String path) {
        StringTokenizer findTheJar = new StringTokenizer(path, "\\/");
        if (path.toLowerCase().startsWith(FILE_URL)) {
            path = path.substring(FILE_URL.length());
        }
        int jarPosition = path.toLowerCase().indexOf(JAR_EXTENSION);
        return (jarPosition >= 0) ? path.substring(0, jarPosition + JAR_EXTENSION.length()) : path;
    }

    // make sure file extension, if it is real, does not start with '.' -- remove it.
    private static String checkFileExtension(@Nullable String fileExtension) {
        return (fileExtension == null || !fileExtension.startsWith("."))
                ? fileExtension
                : fileExtension.substring(1);
    }


    ///////////////////////////////////////////////////////////////////////////
    //  FileFilter subclass to be instantiated by the getFileFilter method above.
    //  This extends javax.swing.filechooser.FileFilter

    private static class MarsFileFilter extends FileFilter {

        private final ArrayList<String> extensions;
        private final @NotNull String fullDescription;
        private final boolean acceptDirectories;

        private MarsFileFilter(@NotNull ArrayList<String> extensions, String description, boolean acceptDirectories) {
            this.extensions = extensions;
            this.fullDescription = buildFullDescription(description, extensions);
            this.acceptDirectories = acceptDirectories;
        }

        // User provides descriptive phrase to be parenthesized.
        // We will attach it to description of the extensions.  For example, if the extensions
        // given are s and asm and the description is "Assembler Programs" the full description
        // generated here will be "Assembler Programs (*.s; *.asm)"
        private @NotNull String buildFullDescription(@Nullable String description, @NotNull ArrayList<String> extensions) {
            StringBuilder result = new StringBuilder((description == null) ? "" : description);
            if (!extensions.isEmpty()) {
                result.append("  (");
            }
            for (int i = 0; i < extensions.size(); i++) {
                String extension = extensions.get(i);
                if (extension != null && !extension.isEmpty()) {
                    result.append((i == 0) ? "" : "; ");
                    result.append("*");
                    result.append((extension.charAt(0) == '.') ? "" : ".");
                    result.append(extension);
                }
            }
            if (!extensions.isEmpty()) {
                result.append(")");
            }
            return result.toString();
        }

        // required by the abstract superclass
        public String getDescription() {
            return this.fullDescription;
        }

        // required by the abstract superclass.
        public boolean accept(@NotNull File file) {
            if (file.isDirectory()) {
                return acceptDirectories;
            }
            String fileExtension = getExtension(file);
            if (fileExtension != null) {
                for (String s : extensions) {
                    String extension = checkFileExtension(s);
                    if (extension.equals(MATCH_ALL_EXTENSIONS) ||
                            fileExtension.equals(extension)) {
                        return true;
                    }
                }
            }
            return false;
        }

    } // MarsFileFilter class

} // FilenameFinder class

