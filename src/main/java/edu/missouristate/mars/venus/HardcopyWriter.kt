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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.venus

import edu.missouristate.mars.isSpaceChar
import java.awt.*
import java.io.FileReader
import java.io.Writer
import java.text.DateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.system.exitProcess

class HardcopyWriter @Throws(PrintCanceledException::class) constructor(
    frame: Frame,
    private var jobName: String,
    private var fontSize: Int,
    leftMargin: Double,
    rightMargin: Double,
    topMargin: Double,
    bottomMargin: Double
) : Writer(), AutoCloseable {
    companion object {
        @JvmStatic private val printLock = ReentrantLock()

        @JvmStatic
        fun main(args: Array<String>) {
            try {
                if (args.size != 1) throw IllegalArgumentException("Incorrect number of arguments!")
                val `in` = FileReader(args[0])
                val f = Frame("PrintFile: ${args[0]}")
                f.size = Dimension(200, 50)
                f.isVisible = true
                val out = try {
                    HardcopyWriter(f, args[0], 10, 0.5, 0.5, 0.5, 0.5)
                } catch (e: PrintCanceledException) {
                    e.printStackTrace()
                    exitProcess(1)
                }
                f.isVisible = false
                val buffer = CharArray(4096)
                var numChars: Int
                while ((`in`.read(buffer).also { numChars = it }) != -1)
                    out.write(buffer, 0, numChars)
                `in`.close()
                out.close()
            } catch (e: Exception) {
                System.err.println(e)
                System.err.println("Usage: java HardcopyWriter\$PrintFile <filename>")
                exitProcess(1)
            }
            exitProcess(0)
        }
    }

    private var job: PrintJob? = null
    private var page: Graphics? = null
    private var time: String
    private var pageSize: Dimension
    private var pageDPI: Int
    private var font: Font
    private var headerFont: Font
    private var metrics: FontMetrics
    private var headerMetrics: FontMetrics
    private var x0: Int
    private var y0: Int
    private var width: Int
    private var height: Int
    private var headerY: Int
    private var charWidth: Int
    private var lineHeight: Int
    private var lineAscent: Int
    var charsPerLine: Int
        private set
    var linesPerPage: Int
        private set

    private val charsPerTab = 4
    private var charNum = 0
    private var lineNum = 0
    private var pageNum = 0
    private var lastCharWasReturn = false

    init {
        // Get the PrintJob object with which we'll do all the printing operations.
        // The call is synchronized on the ReentrantLock to prevent more than one
        // print dialog from appearing at the same time. If the user clicks Cancel
        // in the print dialog, it will throw an exception.
        val toolkit = frame.toolkit
        printLock.withLock {
            val ja = JobAttributes()
            val pa = PageAttributes()
            job = toolkit.getPrintJob(frame, jobName, ja, pa)
        }
        if (job == null) throw PrintCanceledException("Printing request cancelled!")
        // TODO: Page DPI is fixed to 72. Should probably change this in the future.
        /*
         Original code that was commented out:

         pageSize = job.pageDimension
         pageDPI = job.pageResolution

         Also contained a bug-fix for Windows that might still apply?

         if (System.getProperty("os.name").regionMatches(true, 0, "windows", 0, 7)) {
             pageDPI = toolkit.screenResolution
             pageSize = Dimension((8.5 * pageDPI).roundToInt(), 11 * pageDPI)
             fontSize *= pageDPI / 72
         }
         */
        pageDPI = 72
        pageSize = Dimension((8.5 * pageDPI).roundToInt(), 11 * pageDPI)
        fontSize *= pageDPI / 72
        // Determine where the upper-left corner of the page is located.
        x0 = (leftMargin * pageDPI).roundToInt()
        y0 = (topMargin * pageDPI).roundToInt()
        width = pageSize.width - ((leftMargin + rightMargin) * pageDPI).roundToInt()
        height = pageSize.height - ((topMargin + bottomMargin) * pageDPI).roundToInt()
        // Get body font and font size
        font = Font("Monospaced", Font.PLAIN, fontSize)
        metrics = frame.getFontMetrics(font)
        lineHeight = metrics.height
        lineAscent = metrics.ascent
        charWidth = metrics.charWidth('0')
        // Compute the number of columns and lines
        charsPerLine = width / charWidth
        linesPerPage = height / lineHeight
        // Get header font information and compute baseline of page header
        // (1/8" above the top margin)
        headerFont = Font("SansSerif", Font.ITALIC, fontSize)
        headerMetrics = frame.getFontMetrics(font)
        headerY = y0 - (0.125 * pageDPI).roundToInt() - headerMetrics.height + headerMetrics.ascent
        // TODO: Replace with new Temporal API for sanity
        val df = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.SHORT)
        df.timeZone = TimeZone.getDefault()
        time = df.format(Date())
    }

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        printLock.withLock {
            // Loop through all characters passed to this function
            for (i in off..<(off + len)) {
                // If we haven't started a page (or a new page), do that now.
                if (page == null) newPage()
                // If the character is a line terminator, then begin a new line,
                // unless it is a \n immediately after a \r.
                if (cbuf[i] == '\n') {
                    if (!lastCharWasReturn) newLine()
                    continue
                }
                if (cbuf[i] == '\r') {
                    newLine()
                    lastCharWasReturn = true
                    continue
                } else lastCharWasReturn = false
                // If it's some other non-printing character, ignore it.
                if (cbuf[i].isWhitespace() && !cbuf[i].isSpaceChar() && cbuf[i] != '\t') continue
                // If no more characters fit on the line, start a new line.
                if (charNum >= charsPerLine) {
                    newLine()
                    // Also start a new page, if necessary.
                    if (page == null) newPage()
                }
                // Now print the character.
                // If it is a space, skip one space without output.
                // If it is a tab, skip the necessary number of spaces.
                // Otherwise, print the character.
                // It is inefficient to draw only one character at a time, but because
                // the FontMetrics don't match up exactly to what the printer uses, we
                // need to position each character individually.
                if (cbuf[i].isSpaceChar()) charNum++
                else if (cbuf[i] == '\t') charNum += charsPerTab - (charNum % charsPerTab)
                else {
                    page!!.drawChars(
                        cbuf,
                        i,
                        1,
                        x0 + charNum * charWidth,
                        y0 + (lineNum * lineHeight) + lineAscent
                    )
                    charNum++
                }
            }
        }
    }

    override fun flush() { /* Do nothing. */ }

    override fun close() {
        printLock.withLock {
            // Send page to the printer
            page?.dispose()
            // Terminate the job
            job?.end()
        }
    }

    fun setFontStyle(style: Int) {
        printLock.withLock {
            // Try to set a new font, but restore the current font if the change fails
            val current = font
            font = try {
                Font("Monospaced", style, fontSize)
            } catch (ignored: Exception) {
                current
            }
            // If a page is pending, set the new font. Otherwise, newPage() will fail.
            page?.font = font
        }
    }

    fun pageBreak() {
        printLock.withLock { newPage() }
    }

    private fun newLine() {
        // Reset character position and increment line number
        charNum = 0
        lineNum++
        // If we've reached the end of the page...
        if (lineNum >= linesPerPage) {
            // Send the current page to the printer
            page?.dispose()
            // But don't start a new page yet
            page = null
        }
    }

    private fun newPage() {
        // Begin the new page
        page = job?.graphics
        // Reset line number and character position
        lineNum = 0
        charNum = 0
        // Increment page number
        pageNum++
        // Set the header font
        page?.font = headerFont
        // Print job name left-justified
        page?.drawString(jobName, x0, headerY)
        // Print the page number centered
        val s = "- $pageNum -"
        var w = headerMetrics.stringWidth(s)
        page?.drawString(s, x0 + (width - w) / 2, headerY)
        // Print date right-justified
        w = headerMetrics.stringWidth(time)
        page?.drawString(time, x0 + width - w, headerY)
        // Draw a line below the header
        val y = headerY + headerMetrics.descent + 1
        page?.drawLine(x0, y, x0 + width, y)
        page?.font = font
    }

    class PrintCanceledException(message: String) : Exception(message)
}