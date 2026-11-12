package edu.missouristate.mars.util

import java.io.File
import java.io.FileFilter
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.zip.ZipFile

/*
Copyright (c) 2003-2008,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
 */

/** Finds assembly files on disk and resources on the classpath. */
open class FilenameFinder {
    class MarsFileFilter internal constructor(
        private val extensions: ArrayList<out String?>,
        description: String?,
        private val acceptDirectories: Boolean,
    ) : FileFilter {
        val description: String = buildString {
            append(description.orEmpty())
            if (extensions.isNotEmpty()) {
                append("  (")
                extensions.forEachIndexed { index, extension ->
                    if (!extension.isNullOrEmpty()) {
                        if (index != 0) append("; ")
                        append('*')
                        if (!extension.startsWith('.')) append('.')
                        append(extension)
                    }
                }
                append(')')
            }
        }

        override fun accept(file: File): Boolean {
            if (file.isDirectory) return acceptDirectories
            val actual = getExtension(file) ?: return false
            return extensions.any {
                val extension = stripLeadingDot(it)!!
                extension == MATCH_ALL_EXTENSIONS || extension == actual
            }
        }
    }

    companion object {
        const val MATCH_ALL_EXTENSIONS = "*"
        private const val JAR_EXTENSION = ".jar"
        private const val FILE_URL = "file:"
        private const val JAR_URI_PREFIX = "jar:"

        /** Lists matching resource basenames in classpath directories and JAR files. */
        @JvmStatic
        fun getFilenameList(loader: ClassLoader, directory: String, extension: String?): ArrayList<String> {
            val fileExtension = stripLeadingDot(extension)
            val names = ArrayList<String>()
            try {
                val resources = loader.getResources(directory)
                for (resource in resources) {
                    val uri = URI(resource.toString().removePrefix(JAR_URI_PREFIX))
                    val entry = File(uri.path)
                    val files = entry.listFiles()
                    if (files != null) {
                        val filter = getFileFilter(fileExtension, "", false)
                        for (file in files) {
                            if (filter.accept(file)) names.add(file.name)
                        }
                    } else if (entry.path.lowercase(Locale.getDefault()).indexOf(JAR_EXTENSION) > 0) {
                        val path = entry.path
                        val filePath = if (path.startsWith(FILE_URL, ignoreCase = true)) path.substring(FILE_URL.length) else path
                        val end = filePath.lowercase(Locale.getDefault()).indexOf(JAR_EXTENSION)
                        val jar = if (end < 0) filePath else filePath.substring(0, end + JAR_EXTENSION.length)
                        addJarEntries(names, jar, directory, fileExtension)
                    }
                }
            } catch (failure: URISyntaxException) {
                failure.printStackTrace()
            } catch (failure: IOException) {
                failure.printStackTrace()
            }
            return names
        }

        @JvmStatic
        fun getFilenameList(loader: ClassLoader, directory: String, extensions: ArrayList<out String?>?): ArrayList<String> =
            collectByExtension(extensions) { getFilenameList(loader, directory, it) }

        /** Lists matching immediate children of a filesystem directory by absolute path. */
        @JvmStatic
        fun getFilenameList(directory: String, extension: String?): ArrayList<String> {
            val path = File(directory)
            if (!path.isDirectory) return ArrayList()
            val files = path.listFiles() ?: return ArrayList()
            val filter = getFileFilter(stripLeadingDot(extension), "", false)
            return files.filterToPaths(filter)
        }

        @JvmStatic
        fun getFilenameList(directory: String, extensions: ArrayList<out String?>?): ArrayList<String> =
            collectByExtension(extensions) { getFilenameList(directory, it) }

        /** Filters an explicit list and returns matching absolute paths. */
        @JvmStatic
        fun getFilenameList(names: ArrayList<String>, extension: String?): ArrayList<String> {
            val filter = getFileFilter(stripLeadingDot(extension), "", false)
            return names.mapToFiles(filter)
        }

        @JvmStatic
        fun getFilenameList(names: ArrayList<String>, extensions: ArrayList<out String?>?): ArrayList<String> =
            collectByExtension(extensions) { getFilenameList(names, it) }

        /** Returns the lowercase extension, excluding a leading or trailing dot. */
        @JvmStatic
        fun getExtension(file: File): String? = getExtension(file.name)

        @JvmStatic
        fun getExtension(filename: String): String? {
            val dot = filename.lastIndexOf('.')
            return if (dot > 0 && dot < filename.lastIndex) {
                filename.substring(dot + 1).lowercase(Locale.getDefault())
            } else null
        }

        @JvmStatic
        @JvmOverloads
        fun getFileFilter(
            extensions: ArrayList<out String?>, description: String?, acceptDirectories: Boolean = true,
        ): MarsFileFilter = MarsFileFilter(extensions, description, acceptDirectories)

        @JvmStatic
        @JvmOverloads
        fun getFileFilter(extension: String?, description: String?, acceptDirectories: Boolean = true): MarsFileFilter =
            getFileFilter(arrayListOf(extension), description, acceptDirectories)

        @JvmStatic
        fun fileExtensionMatch(name: String, extension: String?): Boolean =
            extension.isNullOrEmpty() || name.endsWith(if (extension.startsWith('.')) extension else ".$extension")

        private fun addJarEntries(names: ArrayList<String>, jar: String, directory: String, extension: String?) {
            try {
                ZipFile(File(jar)).use { zip ->
                    for (entry in zip.entries()) {
                        if (entry.name.startsWith("$directory/") && fileExtensionMatch(entry.name, stripLeadingDot(extension))) {
                            names.add(entry.name.substringAfterLast('/'))
                        }
                    }
                }
            } catch (failure: Exception) {
                println("Exception occurred reading MarsTool list from JAR: $failure")
            }
        }

        private fun stripLeadingDot(extension: String?): String? = extension?.removePrefix(".")

        private inline fun collectByExtension(
            extensions: ArrayList<out String?>?, read: (String?) -> ArrayList<String>,
        ): ArrayList<String> {
            if (extensions.isNullOrEmpty()) return read("")
            val names = ArrayList<String>()
            for (extension in extensions) names.addAll(read(stripLeadingDot(extension)))
            return names
        }

        private fun Array<File>.filterToPaths(filter: FileFilter): ArrayList<String> =
            mapNotNullTo(ArrayList()) { if (filter.accept(it)) it.absolutePath else null }

        private fun ArrayList<String>.mapToFiles(filter: FileFilter): ArrayList<String> =
            mapNotNullTo(ArrayList()) { name -> File(name).takeIf(filter::accept)?.absolutePath }
    }
}
