/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Originally developed by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
 * Maintained by Nicholas Hubbard (nhubbard@users.noreply.github.com)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * 1. The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 *    the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

@file:Suppress("NAME_SHADOWING")

package edu.missouristate.mars.util

import java.io.File
import javax.swing.filechooser.FileFilter
import java.net.URI
import java.util.zip.ZipFile

/**
 * Utility class to perform necessary file-related search operations.
 * One is to find file names in JAR files; another is to find names of files in given directory of a normal file system.
 */
object FilenameFinder {
    private const val JAR_EXTENSION = ".jar"
    private const val FILE_URL = "file:"
    private const val JAR_URI_PREFIX = "jar:"
    private const val NO_DIRECTORIES = false
    const val MATCH_ALL_EXTENSIONS = "*"

    /**
     * Locate files and return the list of file names.
     * Given a known relative directory path, it will locate it and build the list of all names of files in that
     * directory having the given file extension.
     * If the "known file path" doesn't work because MARS is running from an executable JAR file, it will locate the
     * directory in the JAR file and proceed from there.
     * NOTE: Since this uses the class loader to get the resource, the directory path needs to be relative to classpath,
     * not absolute.
     * To work with an arbitrary file system, use the other version of this overloaded method.
     * This will NOT match directories that happen to have the desired extension.
     *
     * @param classLoader   class loader to use
     * @param directoryPath Search will be confined to this directory.  Use "/" as
     *                      separator but do NOT include starting or ending "/" (e.g., mars/tools)
     * @param fileExtension Only files with this extension will be added
     *                      to the list.  Do NOT include the "." in extension.
     * @return array list of matching file names as Strings.  If none, the list is empty.
     */
    @JvmStatic
    fun getFilenameList(classLoader: ClassLoader, directoryPath: String, fileExtension: String): ArrayList<String> {
        val fileExtension = checkFileExtension(fileExtension)
        val filenameList = arrayListOf<String>()
        var uri: URI
        try {
            val urls = classLoader.getResources(directoryPath)
            for (url in urls) {
                uri = URI(url.toString())
                if (uri.toString().indexOf(JAR_URI_PREFIX) == 0)
                    uri = URI(uri.toString().substring(JAR_URI_PREFIX.length))
                val f = File(uri.path)
                val files = f.listFiles()
                if (files == null) {
                    if (f.toString().lowercase().indexOf(JAR_EXTENSION) > 0)
                        // Running from a JAR file.
                        filenameList.addAll(getListFromJar(extractJarFilename(f.toString()), directoryPath, fileExtension))
                } else {
                    val filter = getFileFilter(fileExtension, "", NO_DIRECTORIES)
                    for (file in files)
                        if (filter.accept(file)) filenameList.add(file.name)
                }
            }
            return filenameList
        } catch (e: Exception) {
            e.printStackTrace()
            return filenameList
        }
    }

    /**
     * Locate files and return the list of file names.
     * Given a known relative directory path,
     * it will locate it and build the list of all names of files in that directory
     * having the given file extension.
     * If the "known file path" doesn't work
     * because MARS is running from an executable JAR file, it will locate the
     * directory in the JAR file and proceed from there.
     * NOTE: since this uses
     * the class loader to get the resource, the directory path needs to be
     * relative to classpath, not absolute.
     * To work with an arbitrary file system,
     * use the other version of this overloaded method.
     *
     * @param classLoader    class loader to use
     * @param directoryPath  Search will be confined to this directory.  Use "/" as
     *                       separator but do NOT include starting or ending "/" (e.g., mars/tools)
     * @param fileExtensions ArrayList of Strings containing file extensions.
     *                       Only files with an extension in this list will be added to the list.
     *                       Do NOT include the ".", e.g. "class" not ".class".
     *                       If the Arraylist or the extension is null or empty, all files are added.
     * @return array list of matching file names as Strings.  If none, the list is empty.
     */
    @JvmStatic
    fun getFilenameList(
        classLoader: ClassLoader,
        directoryPath: String,
        fileExtensions: ArrayList<String>?
    ): ArrayList<String> {
        var filenameList = arrayListOf<String>()
        var fileExtension: String
        if (fileExtensions.isNullOrEmpty()) {
            filenameList = getFilenameList(classLoader, directoryPath, "")
        } else {
            for (extension in fileExtensions) {
                fileExtension = checkFileExtension(extension)
                filenameList.addAll(getFilenameList(classLoader, directoryPath, fileExtension))
            }
        }
        return filenameList
    }

    /**
     * Locate files and return the list of file names.  Given a known directory path,
     * it will locate it and build the list of all names of files in that directory
     * having the given file extension.
     * If the file extension is null or empty, all
     * filenames are returned.
     * The returned list contains absolute filename paths.
     *
     * @param directoryPath Search will be confined to this directory.
     * @param fileExtension Only files with this extension will be added to the list.
     *                      Do NOT include "." in extension.
     *                      If null or empty string, all files are added.
     * @return array list of matching file names (absolute path).  If none, the list is empty.
     */
    @JvmStatic
    fun getFilenameList(directoryPath: String, fileExtension: String): ArrayList<String> {
        val fileExtension = checkFileExtension(fileExtension)
        val filenameList = arrayListOf<String>()
        val directory = File(directoryPath)
        if (directory.isDirectory) {
            val allFiles = directory.listFiles()
            val filter = getFileFilter(fileExtension, "", NO_DIRECTORIES)
            for (file in allFiles ?: arrayOf())
                if (filter.accept(file))
                    filenameList.add(file.absolutePath)
        }
        return filenameList
    }

    /**
     * Locate files and return the list of file names.
     * Given a known directory path,
     * it will locate it and build the list of all names of files in that directory
     * having the given file extension.
     * If the file extension is null or empty, all
     * filenames are returned.
     * The returned list contains absolute filename paths.
     *
     * @param directoryPath  Search will be confined to this directory.
     * @param fileExtensions ArrayList of Strings containing file extensions.
     *                       Only files with an extension in this list will be added
     *                       to the list.  Do NOT include the "." in extensions.  If Arraylist or
     *                       the extension is null or empty, all files are added.
     * @return array list of matching file names (absolute path).  If none, the list is empty.
     */
    @JvmStatic
    fun getFilenameList(directoryPath: String, fileExtensions: ArrayList<String>?): ArrayList<String> {
        var filenameList = arrayListOf<String>()
        var fileExtension: String
        if (fileExtensions.isNullOrEmpty()) {
            filenameList = getFilenameList(directoryPath, "")
        } else {
            for (extension in fileExtensions) {
                fileExtension = checkFileExtension(extension)
                filenameList.addAll(getFilenameList(directoryPath, fileExtension))
            }
        }
        return filenameList
    }

    /**
     * Return list of file names.  Given a list of file names, it will return the list
     * of all having the given file extension.
     * If the file extension is null or empty, all
     * filenames are returned.
     * The returned list contains absolute filename paths.
     *
     * @param nameList      ArrayList of String containing file names.
     * @param fileExtension Only files with this extension will be added to the list.
     *                      If null or empty string, all files are added.  Do NOT include "." in extension.
     * @return array list of matching file names (absolute path).  If none, the list is empty.
     */
    @JvmStatic
    fun getFilenameList(nameList: ArrayList<String>, fileExtension: String?): ArrayList<String> {
        val fileExtension = checkFileExtension(fileExtension)
        val filenameList = arrayListOf<String>()
        val filter = getFileFilter(fileExtension, "", NO_DIRECTORIES)
        for (s in nameList) {
            val file = File(s)
            if (filter.accept(file)) filenameList.add(file.absolutePath)
        }
        return filenameList
    }

    /**
     * Return list of file names.  Given a list of file names, it will return the list
     * of all having the given file extension.
     * If the file extension is null or empty, all
     * filenames are returned.
     * The returned list contains absolute filename paths.
     *
     * @param nameList       ArrayList of String containing file names.
     * @param fileExtensions ArrayList of Strings containing file extensions.
     *                       Only files with an extension in this list will be added
     *                       to the list.
     *                       Do NOT include the "." in extensions.
     *                       If the Arraylist or
     *                       the extension is null or empty, all files are added.
     * @return array list of matching file names (absolute path).  If none, the list is empty.
     */
    @JvmStatic
    fun getFilenameList(nameList: ArrayList<String>, fileExtensions: ArrayList<String>?): ArrayList<String> {
        var filenameList = arrayListOf<String>()
        var fileExtension: String
        if (fileExtensions.isNullOrEmpty()) {
            filenameList = getFilenameList(nameList, "")
        } else {
            for (extension in fileExtensions) {
                fileExtension = checkFileExtension(extension)
                filenameList.addAll(getFilenameList(nameList, fileExtension))
            }
        }
        return filenameList
    }

    /**
     * Get the filename extension of the specified File.
     *
     * @param file the File object representing the file of interest
     * @return The filename extension (everything that follows
     * last '.' in filename) or null if none.
     */
    @JvmStatic
    fun getExtension(file: File): String? {
        var ext: String? = null
        val s = file.name
        val i = s.lastIndexOf('.')
        if (i > 0 && i < s.length - 1) {
            ext = s.substring(i + 1).lowercase()
        }
        return ext
    }

    /**
     * Get a FileFilter that will filter files based on the given list of filename extensions.
     *
     * @param extensions        ArrayList of Strings, each string is an acceptable filename extension.
     * @param description       String containing description to be added in parentheses after the list of extensions.
     * @param acceptDirectories boolean value true if the filter should accept directories, false otherwise.
     * @return a FileFilter object that accepts files with given extensions, and directories if so indicated.
     */
    @JvmStatic
    @JvmOverloads
    fun getFileFilter(extensions: ArrayList<String>, description: String, acceptDirectories: Boolean = true): FileFilter =
        MarsFileFilter(extensions, description, acceptDirectories)

    /**
     * Get a FileFilter that will filter files based on the given filename extension.
     *
     * @param extension         String containing the acceptable filename extension.
     * @param description       String containing description to be added in parentheses after the list of extensions.
     * @param acceptDirectories boolean value true if the filter accepts directories, false otherwise.
     * @return a FileFilter object that accepts files with given extensions, and directories if so indicated.
     */
    @JvmStatic
    @JvmOverloads
    fun getFileFilter(extension: String, description: String, acceptDirectories: Boolean = true): FileFilter =
        MarsFileFilter(arrayListOf(extension), description, acceptDirectories)

    /**
     * Determine if given filename ends with the given extension.
     *
     * @param name      A String containing the file name
     * @param extension A String containing the file extension.  Leading period is optional.
     * @return Returns true if filename ends with the given extension, false otherwise.
     */
    @JvmStatic
    fun fileExtensionMatch(name: String, extension: String?): Boolean =
        extension.isNullOrEmpty() || name.endsWith((if (extension.startsWith(".")) "" else ".") + extension)

    @JvmStatic
    private fun getListFromJar(jarName: String?, directoryPath: String, fileExtension: String): ArrayList<String> {
        val fileExtension = checkFileExtension(fileExtension)
        val nameList = arrayListOf<String>()
        if (jarName == null) return nameList
        try {
            ZipFile(File(jarName)).use { zf ->
                val list = zf.entries()
                for (ze in list) {
                    if (ze.name.startsWith("$directoryPath/") && fileExtensionMatch(ze.name, fileExtension))
                        nameList.add(ze.name.substring(ze.name.lastIndexOf('/') + 1))
                }
            }
        } catch (e: Exception) {
            println("Exception occurred reading MarsTool list from JAR: $e")
        }
        return nameList
    }

    @JvmStatic
    private fun extractJarFilename(path: String): String {
        var path = path
        if (path.lowercase().startsWith(FILE_URL))
            path = path.substring(FILE_URL.length)
        val jarPosition = path.lowercase().indexOf(JAR_EXTENSION)
        return if (jarPosition >= 0) path.substring(0, jarPosition + JAR_EXTENSION.length) else path
    }

    @JvmStatic
    private fun checkFileExtension(fileExtension: String?): String =
        if (fileExtension == null || !fileExtension.startsWith("."))
            fileExtension ?: "" else fileExtension.substring(1)

    private class MarsFileFilter(
        private val extensions: ArrayList<String>,
        description: String,
        acceptDirectories: Boolean
    ) : FileFilter() {
        private val fullDescription: String
        private val acceptDirectories: Boolean

        init {
            this.fullDescription = buildFullDescription(description, extensions)
            this.acceptDirectories = acceptDirectories
        }

        // User provides descriptive phrase to be parenthesized.
        // We will attach it to the description of the extensions.
        // For example, if the extensions given are s and asm and the description is "Assembler Programs",
        // the full description generated here will be "Assembler Programs (*.s; *.asm)"
        private fun buildFullDescription(description: String?, extensions: ArrayList<String>): String {
            val result = StringBuilder(if ((description == null)) "" else description)
            if (extensions.isNotEmpty()) result.append("  (")
            for (i in extensions.indices) {
                val extension = extensions[i]
                if (extension.isNotEmpty()) {
                    result.append(if ((i == 0)) "" else "; ")
                    result.append("*")
                    result.append(if ((extension[0] == '.')) "" else ".")
                    result.append(extension)
                }
            }
            if (extensions.isNotEmpty()) result.append(")")
            return result.toString()
        }

        // required by the abstract superclass
        override fun getDescription(): String = fullDescription

        // required by the abstract superclass.
        override fun accept(file: File): Boolean {
            if (file.isDirectory) return acceptDirectories
            val fileExtension = getExtension(file)
            if (fileExtension != null) {
                for (s in extensions) {
                    val extension = checkFileExtension(s)
                    if (extension == MATCH_ALL_EXTENSIONS || fileExtension == extension) {
                        return true
                    }
                }
            }
            return false
        }
    }
}