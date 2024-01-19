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

@file:Suppress("NAME_SHADOWING")

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.venus.editor.marker.TokenMarker
import javax.swing.event.DocumentEvent
import javax.swing.text.BadLocationException
import javax.swing.text.PlainDocument
import javax.swing.text.Segment
import javax.swing.undo.UndoableEdit

/** A document implementation that can be tokenized by the syntax highlighting system. */
open class SyntaxDocument : PlainDocument() {
    var tokenMarker: TokenMarker? = null
        set(tm) {
            field = tm
            if (tm == null) return
            field!!.insertLines(0, defaultRootElement.elementCount)
            tokenizeLines()
        }

    /**
     * Reparse the document by passing the specified lines to the
     * token marker. This should be called after a large quantity of new text is first inserted.
     *
     * @param start The first line to parse
     * @param len   The number of lines, after the first one to parse
     */
    @JvmOverloads
    fun tokenizeLines(start: Int = 0, len: Int = defaultRootElement.elementCount) {
        var len = len
        tokenMarker?.let {
            if (!it.supportsMultilineTokens()) return
            val lineSegment = Segment()
            val map = defaultRootElement
            len += start
            try {
                for (i in start..<len) {
                    val lineElement = map.getElement(i)
                    val lineStart = lineElement.startOffset
                    getText(lineStart, lineElement.endOffset - lineStart - 1, lineSegment)
                    it.markTokens(lineSegment, i)
                }
            } catch (bl: BadLocationException) {
                bl.printStackTrace()
            }
        }
    }

    /**
     * Starts a compound edit that can be undone in one operation.
     * Subclasses that implement undo should override this method;
     * this class has no undo functionality, so this method is
     * empty.
     */
    open fun beginCompoundEdit() {}

    /**
     * Ends a compound edit that can be undone in one operation.
     * Subclasses that implement undo should override this method;
     * this class has no undo functionality, so this method is
     * empty.
     */
    open fun endCompoundEdit() {}

    /**
     * Adds an undoable edit to this document's undo list. The edit
     * should be ignored if something is currently being undone.
     *
     * @param edit The undoable edit
     * @since jEdit 2.2pre1
     */
    open fun addUndoableEdit(edit: UndoableEdit) {}

    /**
     * We overwrite this method to update the token marker
     * state immediately so that any event listeners get a
     * consistent token marker.
     */
    override fun fireInsertUpdate(e: DocumentEvent) {
        tokenMarker?.let { tm ->
            e.getChange(defaultRootElement)?.let {
                tm.insertLines(it.index + 1, it.childrenAdded.size - it.childrenRemoved.size)
            }
        }
        super.fireInsertUpdate(e)
    }

    /**
     * We overwrite this method to update the token marker
     * state immediately so that any event listeners get a
     * consistent token marker.
     */
    override fun fireRemoveUpdate(e: DocumentEvent) {
        tokenMarker?.let { tm ->
            e.getChange(defaultRootElement)?.let {
                tm.deleteLines(it.index + 1, it.childrenRemoved.size - it.childrenAdded.size)
            }
        }
        super.fireRemoveUpdate(e)
    }
}