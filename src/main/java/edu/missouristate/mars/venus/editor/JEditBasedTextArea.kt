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

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.Globals
import edu.missouristate.mars.venus.editor.SyntaxUtilities.getCurrentSyntaxStyles
import edu.missouristate.mars.venus.editor.marker.MIPSTokenMarker
import edu.missouristate.mars.venus.panes.EditPane
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.util.*
import javax.swing.JComponent
import javax.swing.event.CaretEvent
import javax.swing.event.CaretListener
import javax.swing.event.UndoableEditListener
import javax.swing.undo.CannotRedoException
import javax.swing.undo.CannotUndoException
import javax.swing.undo.CompoundEdit
import javax.swing.undo.UndoManager

/**
 * Adaptor subclass for JEditTextArea.
 *
 * Provides those methods required by the TextEditingArea interface
 * that are not defined by JEditTextArea. This permits JEditTextArea
 * to be used within MARS largely without modification.
 *
 * @author Pete Sanderson
 * @since 4.0
 */
class JEditBasedTextArea(
    private val editPane: EditPane,
    lineNumbers: JComponent
) : JEditTextArea(lineNumbers), CaretListener {
    private val undoManager: UndoManager = UndoManager()
    private var isCompoundEdit = false
    private var compoundEdit: CompoundEdit? = CompoundEdit()
    private val sourceCode = this

    init {
        val undoableEditListener = UndoableEditListener {
            if (isCompoundEdit) {
                compoundEdit?.addEdit(it.edit)
            } else {
                undoManager.addEdit(it.edit)
                editPane.updateUndoState()
                editPane.updateRedoState()
            }
        }

        document?.addUndoableEditListener(undoableEditListener)
        font = Globals.settings.getEditorFont()
        tokenMarker = MIPSTokenMarker()

        addCaretListener(this)
    }

    override fun setFont(font: Font?) {
        painter?.font = font
    }

    override fun getFont(): Font? = painter?.font

    /**
     * Use for highlighting the line currently being edited.
     *
     * @param highlight true to enable line highlighting, false to disable.
     */
    fun setLineHighlightEnabled(highlight: Boolean) {
        painter!!.isLineHighlightEnabled = highlight
    }

    /**
     * Set the caret blinking rate in milliseconds.  If the rate is 0
     * will disable blinking.  If negative, do nothing.
     */
    override var caretBlinkRate: Int
        get() = super.caretBlinkRate
        set(rate) {
            if (rate == 0) isCaretBlinking = false
            if (rate > 0) {
                isCaretBlinking = true
                super.caretBlinkRate = rate
                caretTimer.delay = rate
                caretTimer.initialDelay = rate
                caretTimer.restart()
            }
        }

    /**
     * Set the number of characters a tab will expand to.
     *
     * @param chars number of characters
     */
    fun setTabSize(chars: Int) {
        painter!!.tabSize = chars
    }

    /**
     * Update the syntax style table, which is obtained from
     * SyntaxUtilities.
     */
    fun updateSyntaxStyles() {
        painter!!.styles = getCurrentSyntaxStyles()
    }

    fun getOuterComponent(): Component = this

    /**
     * Get rid of any accumulated undoable edits.  It is useful to call
     * this method after opening a file into the text area.  The
     * act of setting its text content upon reading the file will generate
     * an undoable edit. Normally, you don't want a freshly opened file
     * to appear with its Undo action enabled.  But it will unless you
     * call this after setting the text.
     */
    fun discardAllUndoableEdits() {
        undoManager.discardAllEdits()
    }

    /**
     * Display caret position on the edit pane.
     *
     * @param e A CaretEvent
     */
    override fun caretUpdate(e: CaretEvent) {
        editPane.displayCaretPosition(e.dot)
    }

    /**
     * Same as setSelectedText but named for compatibility with
     * JTextComponent method replaceSelection.
     * DPS, 14 Apr 2010
     *
     * @param replacementText The replacement text for the selection
     */
    fun replaceSelection(replacementText: String?) {
        setSelectedText(replacementText)
    }

    @Suppress("UNUSED_PARAMETER")
    fun setSelectionVisible(vis: Boolean) {}

    fun setSourceCode(s: String?, editable: Boolean) {
        this.setText(s!!)
        this.background = if ((editable)) Color.WHITE else Color.GRAY
        this.isEditable = editable
        this.isEnabled = editable
        this.caretPosition = 0
        if (editable) this.requestFocusInWindow()
    }

    /**
     * Returns the undo manager for this editing area
     *
     * @return the undo manager
     */
    fun getUndoManager(): UndoManager = undoManager

    /**
     * Undo previous edit
     */
    fun undo() {
        // "unredoing" is a mode used by DocumentHandler's insertUpdate() and removeUpdate()
        // to pleasingly mark the text and location of the undo.
        unredoing = true
        try {
            undoManager.undo()
        } catch (ex: CannotUndoException) {
            println("Unable to undo: $ex")
            ex.printStackTrace()
        }
        unredoing = false
        this.isCaretVisible = true
    }

    /**
     * Redo previous edit
     */
    fun redo() {
        // "unredoing" is a mode used by DocumentHandler's insertUpdate() and removeUpdate()
        // to pleasingly mark the text and location of the redo.
        unredoing = true
        try {
            undoManager.redo()
        } catch (ex: CannotRedoException) {
            println("Unable to redo: $ex")
            ex.printStackTrace()
        }
        unredoing = false
        this.isCaretVisible = true
    }

    /**
     * Finds next occurrence of text in a forward search of a string. Search begins
     * at the current cursor location, and wraps around when the end of the string
     * is reached.
     *
     * @param find          the text to locate in the string
     * @param caseSensitive true if search is to be case-sensitive, false otherwise
     * @return TEXT_FOUND or TEXT_NOT_FOUND, depending on the result.
     */
    fun doFindText(find: String?, caseSensitive: Boolean): TextSearchResult {
        val findPosition = sourceCode.caretPosition
        val nextPosition = nextIndex(sourceCode.getText(), find, findPosition, caseSensitive)
        if (nextPosition >= 0) {
            sourceCode.requestFocus() // guarantees visibility of the blue highlight
            sourceCode.selectionStart = nextPosition // position cursor at word start
            sourceCode.selectionEnd = nextPosition + find!!.length
            // Need to repeat start due to quirk in JEditTextArea implementation of setSelectionStart.
            sourceCode.selectionStart = nextPosition
            return TextSearchResult.TEXT_FOUND
        } else return TextSearchResult.TEXT_NOT_FOUND
    }

    /**
     * Returns next position of the provided word in text using forward search. If the end of the string is
     * reached during the search, it will wrap around to the beginning one time.
     *
     * @param input         the string to search
     * @param find          the string to find
     * @param start         the character position to start the search
     * @param caseSensitive true for case-sensitive. false to ignore the word case
     * @return next indexed position of found text or -1 if not found
     */
    fun nextIndex(input: String?, find: String?, start: Int, caseSensitive: Boolean): Int {
        var textPosition = -1
        if (input != null && find != null && start < input.length) {
            if (caseSensitive) { // indexOf() returns -1 if not found
                textPosition = input.indexOf(find, start)
                // If not found from non-starting cursor position, wrap around
                if (start > 0 && textPosition < 0) {
                    textPosition = input.indexOf(find)
                }
            } else {
                val lowerCaseText = input.lowercase(Locale.getDefault())
                textPosition = lowerCaseText.indexOf(find.lowercase(Locale.getDefault()), start)
                // If not found from non-starting cursor position, wrap around
                if (start > 0 && textPosition < 0) {
                    textPosition = lowerCaseText.indexOf(find.lowercase(Locale.getDefault()))
                }
            }
        }
        return textPosition
    }

    /**
     * Finds and replaces next occurrence of text in a string in a forward search.
     * If the cursor is initially at the end
     * of matching selection, will immediately replace then find and select the
     * next occurrence if any. Otherwise, it performs a find operation. The replacement
     * can be undone with one undo operation.
     *
     * @param find          the text to locate in the string
     * @param replace       the text to replace the find text with - if the find text exists
     * @param caseSensitive true for case-sensitive. false for case-insensitive
     * @return Returns TEXT_FOUND if not initially at the end of selected match and matching
     * occurrence is found.  Returns TEXT_NOT_FOUND if the text is not matched.
     * Returns TEXT_REPLACED_NOT_FOUND_NEXT if replacement is successful, but there are
     * no additional matches.  Returns TEXT_REPLACED_FOUND_NEXT if replacement is
     * successful and there is at least one additional match.
     */
    fun doReplace(find: String?, replace: String, caseSensitive: Boolean): TextSearchResult {
        // Will perform a "find" and return, unless positioned at the end of
        // a selected "find" result.
        if (find == null || find != sourceCode.getSelectedText() || sourceCode.selectionEnd != sourceCode.caretPosition)
            return doFindText(find, caseSensitive)
        // We are positioned at the end of selected "find".  Replace and find next.
        val nextPosition = sourceCode.selectionStart
        sourceCode.grabFocus()
        sourceCode.selectionStart = nextPosition // Position cursor at word start
        sourceCode.selectionEnd = nextPosition + find.length //select found text
        // Need to repeat start due to quirk in JEditTextArea implementation of setSelectionStart.
        sourceCode.selectionStart = nextPosition
        isCompoundEdit = true
        compoundEdit = CompoundEdit()
        sourceCode.replaceSelection(replace)
        compoundEdit?.end()
        undoManager.addEdit(compoundEdit)
        editPane.updateUndoState()
        editPane.updateRedoState()
        isCompoundEdit = false
        sourceCode.caretPosition = nextPosition + replace.length
        return if (doFindText(find, caseSensitive) == TextSearchResult.TEXT_NOT_FOUND) {
            TextSearchResult.TEXT_REPLACED_NOT_FOUND_NEXT
        } else {
            TextSearchResult.TEXT_REPLACED_FOUND_NEXT
        }
    }

    /**
     * Finds and replaces <B>ALL</B> occurrences of the text in a string in a forward search.
     * All replacements are bundled into one CompoundEdit, so one Undo operation will
     * undo all of them.
     *
     * @param find          the text to locate in the string
     * @param replace       the text to replace the find text with - if the find text exists
     * @param caseSensitive true for case-sensitive. false to ignore case-insensitive
     * @return the number of occurrences that were matched and replaced.
     */
    fun doReplaceAll(find: String, replace: String, caseSensitive: Boolean): Int {
        var nextPosition = 0
        var findPosition = 0 // *** begin at the start of text
        var replaceCount = 0
        compoundEdit = null // the new one will be created upon first replacement
        isCompoundEdit = true // undo manager's action listener needs this
        while (nextPosition >= 0) {
            nextPosition = nextIndex(sourceCode.getText(), find, findPosition, caseSensitive)
            if (nextPosition >= 0) {
                // nextIndex() will wrap around, which causes infinite loop if the
                // find string is a substring of replacement string.  This
                // statement will prevent that.
                if (nextPosition < findPosition) break
                sourceCode.grabFocus()
                sourceCode.selectionStart = nextPosition // position cursor at word start
                sourceCode.selectionEnd = nextPosition + find.length //select found text
                // Need to repeat start due to quirk in JEditTextArea implementation of setSelectionStart.
                sourceCode.selectionStart = nextPosition
                if (compoundEdit == null) {
                    compoundEdit = CompoundEdit()
                }
                sourceCode.replaceSelection(replace)
                findPosition = nextPosition + replace.length // set for next search
                replaceCount++
            }
        }
        isCompoundEdit = false
        // Will be true if any replacements were performed
        if (compoundEdit != null) {
            compoundEdit?.end()
            undoManager.addEdit(compoundEdit)
            editPane.updateUndoState()
            editPane.updateRedoState()
        }
        return replaceCount
    }
}