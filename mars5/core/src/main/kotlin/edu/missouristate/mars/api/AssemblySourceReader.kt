package edu.missouristate.mars.api

import java.io.IOException
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.io.Reader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets.UTF_16
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.NotDirectoryException
import java.nio.file.Path
import java.util.Arrays
import java.util.HexFormat

/** The disk source decoding rules shared by assembly and editor snapshots. */
object AssemblySourceReader {
    /** Opens the file as assembly source. The caller owns the returned reader. */
    @JvmStatic
    @Throws(IOException::class)
    fun open(file: Path): Reader {
        val input = PushbackInputStream(Files.newInputStream(file), 4)
        try {
            val prefix = input.readNBytes(4)
            val bom = sourceBoms.firstOrNull {
                val length = it.signature.size
                prefix.size >= length && Arrays.equals(prefix, 0, length, it.signature, 0, length)
            }
            val skip = bom?.skip ?: 0
            input.unread(prefix, skip, prefix.size - skip)
            return InputStreamReader(input, bom?.charset ?: Charset.defaultCharset())
        } catch (failure: Exception) {
            try {
                input.close()
            } catch (closeFailure: Throwable) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    @JvmStatic
    @Throws(IOException::class)
    fun openIncluded(file: Path): Reader = open(walkIncludePath(file))

    @JvmStatic
    @Throws(IOException::class)
    fun resolveIncludeDirectory(directory: Path): Path = walkIncludePath(directory).toRealPath()

    private fun walkIncludePath(path: Path): Path {
        if (path.none { it.toString() == "." || it.toString() == ".." }) return path
        val absolute = path.toAbsolutePath()
        var prefix = absolute.root
        for (part in absolute) {
            val segment = part.toString()
            if (segment == "." || segment == "..") {
                if (!Files.isDirectory(prefix)) throw NotDirectoryException(prefix.toString())
                if (segment == "..") {
                    // Resolve the directory before walking up, even on hosts that normalize parent segments first.
                    val directory = prefix.toRealPath()
                    prefix = directory.parent ?: directory
                }
            } else {
                prefix = prefix.resolve(part)
            }
        }
        return prefix
    }

    private class SourceBom(hex: String, val charset: Charset, val skip: Int) {
        val signature: ByteArray = HexFormat.of().parseHex(hex)
    }

    // UTF-32LE starts with the UTF-16LE signature. UTF-16 and UTF-32 decoders read their own BOM.
    private val utf32 = Charset.forName("UTF-32")
    private val sourceBoms = listOf(
        SourceBom("efbbbf", UTF_8, 3),
        SourceBom("0000feff", utf32, 0),
        SourceBom("fffe0000", utf32, 0),
        SourceBom("feff", UTF_16, 0),
        SourceBom("fffe", UTF_16, 0),
    )
}
