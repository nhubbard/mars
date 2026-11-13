package edu.missouristate.mars.util

import java.io.File
import java.io.FileFilter
import java.util.Locale

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
    }
}
