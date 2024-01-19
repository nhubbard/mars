/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */
package edu.missouristate.mars.venus.actions

import edu.missouristate.mars.venus.HardcopyWriter
import edu.missouristate.mars.venus.HardcopyWriter.PrintCanceledException
import edu.missouristate.mars.venus.VenusUI
import java.awt.event.ActionEvent
import java.io.BufferedReader
import java.io.IOException
import java.io.StringReader
import java.util.*
import javax.swing.Icon
import javax.swing.KeyStroke

/**
 * Action  for the File -> Print menu item
 */
class FilePrintAction(
    name: String?, icon: Icon?, descrip: String?,
    mnemonic: Int?, accel: KeyStroke?, gui: VenusUI?
) : GuiAction(name, icon, descrip, mnemonic, accel, gui) {
    /**
     * Uses the HardcopyWriter class developed by David Flanagan for the book
     * "Java Examples in a Nutshell".  It will do basic printing of multipage
     * text documents.  It displays a print dialog but does not act on any
     * changes the user may have specified there, such as number of copies.
     *
     * @param e component triggering this call
     */
    override fun actionPerformed(e: ActionEvent) {
        val editPane = mainUI.mainPane.editPane ?: return
        val fontsize = 10 // fixed at 10 point
        val margins = .5 // all margins (left,right,top,bottom) fixed at .5"
        val out: HardcopyWriter
        try {
            out = HardcopyWriter(
                mainUI, editPane.filename,
                fontsize, margins, margins, margins, margins
            )
        } catch (pce: PrintCanceledException) {
            return
        }
        val `in` = BufferedReader(StringReader(editPane.source))
        val lineNumberDigits = editPane.sourceLineCount.toString().length
        var line: java.lang.StringBuilder?
        var lineNumberString = java.lang.StringBuilder()
        var lineNumber = 0
        try {
            line = Optional.ofNullable(`in`.readLine()).map { str: String? -> StringBuilder(str) }
                .orElse(null)
            while (line != null) {
                if (editPane.showingLineNumbers()) {
                    lineNumber++
                    lineNumberString = java.lang.StringBuilder("$lineNumber: ")
                    while (lineNumberString.length < lineNumberDigits) {
                        lineNumberString.append(" ")
                    }
                }
                line = StringBuilder(lineNumberString.toString() + line.toString() + "\n")
                out.write(line.toString().toCharArray(), 0, line.length)
                line = Optional.ofNullable(`in`.readLine()).map { str: String? -> StringBuilder(str) }
                    .orElse(null)
            }
            `in`.close()
            out.close()
        } catch (ignored: IOException) {
        }
    }
}
