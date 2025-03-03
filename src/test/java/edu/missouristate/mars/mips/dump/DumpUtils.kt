/*
 * Copyright (c) 2003-2025, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2025-present, Nicholas Hubbard
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

package edu.missouristate.mars.mips.dump

import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.file.Paths
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.path.createTempFile
import kotlin.test.assertEquals

/**
 * Some of the data we use for our tests is incredibly large (>25MB).
 * We use Java's internal GZip decompressor to save space for this data.
 */
object DumpUtils {
    @JvmStatic
    fun getTempFile(): File =
        createTempFile(Paths.get(System.getProperty("java.io.tmpdir"))).toFile()

    @JvmStatic
    fun decompressTestData(type: String, filename: String, prefix: String = "src/test/resources/expected/dumpformats/"): String {
        FileInputStream(Paths.get("$prefix$type/$filename").toFile()).use { fis ->
            GZIPInputStream(fis).use { gis ->
                InputStreamReader(gis).use { isr ->
                    BufferedReader(isr).use { br ->
                        return br.readText()
                    }
                }
            }
        }
    }

    @JvmStatic
    fun compressTestData(inputFile: File, outputFile: File) {
        FileInputStream(inputFile).use { fis ->
            FileOutputStream(outputFile).use { fos ->
                HighCompressionGZIPOutputStream(fos).use { gos ->
                    fis.copyTo(gos)
                }
            }
        }
    }

    @JvmStatic
    fun testDecompression(expected: String, actual: String) {
        try {
            assertEquals(expected, actual)
        } catch (e: AssertionError) {
            println("Test failed! Difference detected:")
            println("Expected: ${expected.take(100)}...")
            println("Actual:   ${actual.take(100)}...")
            println("Detailed diff:")
            expected.zip(actual).forEachIndexed { index, (exp, act) ->
                if (exp != act) {
                    println("Mismatch at index $index: Expected '$exp', got '$act'")
                }
            }
            throw e // Rethrow for proper test reporting
        }
    }
}