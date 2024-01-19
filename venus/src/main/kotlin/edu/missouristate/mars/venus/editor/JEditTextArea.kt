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

@file:Suppress("LeakingThis", "MemberVisibilityCanBePrivate")

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.*
import edu.missouristate.mars.venus.editor.TextUtilities.findMatchingBracket
import edu.missouristate.mars.venus.editor.marker.Token
import edu.missouristate.mars.venus.editor.marker.Token.Type.*
import edu.missouristate.mars.venus.editor.marker.TokenMarker
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.event.*
import java.awt.event.KeyEvent.*
import javax.swing.*
import javax.swing.border.EmptyBorder
import javax.swing.event.CaretEvent
import javax.swing.event.CaretListener
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.EventListenerList
import javax.swing.text.BadLocationException
import javax.swing.text.Segment
import javax.swing.text.Utilities
import javax.swing.undo.AbstractUndoableEdit
import javax.swing.undo.UndoableEdit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * jEdit's text area component. It is more suited for editing program
 * source code than JEditorPane, because it drops the unnecessary features
 * (images, variable-width lines, and so on) and adds many useful goodies, such as:
 *
 *   - More flexible key binding scheme
 *   - Supports macro recorders
 *   - Rectangular selection
 *   - Bracket highlighting
 *   - Syntax highlighting
 *   - Command repetition
 *   - Block caret can be enabled
 *
 * It is also faster and doesn't have as many problems. It can be used
 * in other applications; the only other part of jEdit it depends on is
 * the syntax package.
 *
 * To use it in your app, treat it like any other component, for example:
 *
 * ```java
 * JEditTextArea ta = new JEditTextArea();
 * ta.setTokenMarker(new JavaTokenMarker());
 * ta.setText("public class Test {\n"
 *     + "    public static void main(String[] args) {\n"
 *     + "        System.out.println(\"Hello World\");\n"
 *     + "    }\n"
 *     + "}");
 * ```
 *
 * @param lineNumbers The line numbers component.
 * @param defaults    The default values for the editor. Uses the static defaults defined in TextAreaDefaults if not
 *                    specified.
 * @author Slava Pestov
 */
open class JEditTextArea @JvmOverloads constructor(
    lineNumbers: JComponent,
    defaults: TextAreaDefaults = TextAreaDefaults.getDefaults()
) : JComponent() {
    companion object {
        /**
         * Adding components with this name to the text area will place them left of the horizontal scroll bar.
         * This is how the status bar is added in jEdit.
         */
        const val LEFT_OF_SCROLLBAR = "los"

        @JvmField var POPUP_HELP_TEXT_COLOR: Color = Color.black

        /** Number of text lines moved for each click of the vertical scrollbar buttons. */
        private const val VERTICAL_SCROLLBAR_UNIT_INCREMENT_IN_LINES = 1

        /** Number of text lines moved for each "notch" of the mouse wheel. */
        private const val LINES_PER_MOUSE_WHEEL_NOTCH = 3

        protected const val CENTER = "center"
        protected const val RIGHT = "right"
        protected const val BOTTOM = "bottom"

        @JvmStatic protected var focusedComponent: JEditTextArea? = null
        @JvmStatic protected val caretTimer: Timer = Timer(500, CaretBlinker())

        init {
            caretTimer.initialDelay = 500
            caretTimer.start()
        }
    }

    private val lineNumbersVertical: JScrollBar
    var popupMenu: JPopupMenu? = null

    /** The object responsible for painting this text area. */
    var painter: TextAreaPainter? = TextAreaPainter(this, defaults)
        protected set

    var rightClickPopup: JPopupMenu = defaults.popup
    protected var listenerList: EventListenerList = EventListenerList()
    protected var caretEvent: MutableCaretEvent = MutableCaretEvent()

    /** Whether the caret should be blinking. */
    open var isCaretBlinking: Boolean = defaults.caretBlinks
        set(value) {
            field = value
            if (!value) blink = false
            painter!!.invalidateSelectedLines()
        }

    open var isCaretVisible: Boolean = defaults.caretVisible
        get() = (!isCaretBlinking || blink) && field
        set(value) {
            field = value
            blink = true
            painter!!.invalidateSelectedLines()
        }

    protected var blink: Boolean = true

    /**
     * Returns true if this text area is editable, false otherwise.
     */
    var isEditable: Boolean = defaults.editable

    open var caretBlinkRate: Int = defaults.caretBlinkRate

    /** The line displayed at the text area's origin. */
    var firstLine: Int = 0
        /** Set the line displayed at the text area's origin and update the scroll bars. */
        set(value) {
            if (value == field) return
            field = value
            updateScrollBars()
            painter!!.repaint()
        }

    var visibleLines: Int = 0
        private set

    /** The number of lines from the top and bottom of the text are that are always visible. */
    var electricScroll: Int = defaults.electricScroll

    open var horizontalOffset: Int = 0
        set(value) {
            if (value == field) return
            field = value
            if (value != horizontal?.value) updateScrollBars()
            painter!!.repaint()
        }

    protected var vertical: JScrollBar? = JScrollBar(JScrollBar.VERTICAL)
    protected var horizontal: JScrollBar? = JScrollBar(JScrollBar.HORIZONTAL)
    protected var scrollBarsInitialized: Boolean = false

    /** The input handler. */
    open var inputHandler: InputHandler = defaults.inputHandler

    open var document: SyntaxDocument? = defaults.document
        set(value) {
            if (field == value) return
            field?.removeDocumentListener(documentHandler)
            field = value
            field?.addDocumentListener(documentHandler)
            select(0, 0)
            updateScrollBars()
            painter!!.repaint()
        }

    protected var documentHandler: DocumentHandler = DocumentHandler()

    protected var lineSegment: Segment = Segment()

    var selectionStart: Int = 0
        set(value) {
            select(value, selectionEnd)
        }

    var selectionStartLine: Int = 0
        private set

    var selectionEnd: Int = 0
        set(value) {
            select(selectionStart, value)
        }

    var selectionEndLine: Int = 0
        private set

    protected var biasLeft: Boolean = false

    /**
     * Returns the position of the highlighted bracket (the bracket
     * matching the one before the caret)
     */
    var bracketPosition: Int = -1
        protected set

    /**
     * Returns the line of the highlighted bracket (the bracket
     * matching the one before the caret)
     */
    var bracketLine: Int = -1
        protected set

    /**
     * Returns the `magic' caret position. This can be used to preserve
     * the column position when moving up and down lines.
     */
    var magicCaretPosition: Int = 0

    /**
     * Returns true if overwrite mode is enabled, false otherwise.
     */
    var isOverwriteEnabled: Boolean = false
        set(value) {
            field = value
            painter!!.invalidateSelectedLines()
        }

    var isSelectionRectangular: Boolean = false
        set(value) {
            field = value
            painter!!.invalidateSelectedLines()
        }

    protected var unredoing: Boolean = false

    var tokenMarker: TokenMarker?
        get() = document?.tokenMarker
        set(value) {
            document?.tokenMarker = value
        }

    val lineCount: Int get() = document?.defaultRootElement?.elementCount ?: 0

    /**
     * Returns the caret position. This will either be the selection
     * start or the selection end, depending on which direction the
     * selection was made in.
     */
    var caretPosition: Int
        get() = if (biasLeft) selectionStart else selectionEnd
        set(value) {
            select(value, value)
        }

    /**
     * Returns the caret line.
     */
    val caretLine: Int get() = if (biasLeft) selectionStartLine else selectionEndLine

    /**
     * Returns the mark position. This will be the opposite selection
     * bound to the caret position.
     *
     * @see #getCaretPosition()
     */
    val markPosition: Int get() = if (biasLeft) selectionEnd else selectionStart

    /**
     * Returns the mark line.
     */
    val markLine: Int get() = if (biasLeft) selectionEndLine else selectionStartLine

    init {
        // Enable the necessary events.
        enableEvents(AWTEvent.KEY_EVENT_MASK)

        // Initialize the GUI
        val lineNumberScroller = JScrollPane(
            lineNumbers,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        )
        lineNumberScroller.border = EmptyBorder(1, 1, 1, 1)
        lineNumbersVertical = lineNumberScroller.verticalScrollBar

        val lineNumbersPlusPainter = JPanel(BorderLayout())
        lineNumbersPlusPainter.add(painter!!, BorderLayout.CENTER)
        lineNumbersPlusPainter.add(lineNumberScroller, BorderLayout.WEST)
        layout = ScrollLayout()
        add(CENTER, lineNumbersPlusPainter)
        add(RIGHT, vertical)
        add(BOTTOM, horizontal)

        // Add some event listeners
        vertical!!.addAdjustmentListener(AdjustHandler())
        horizontal!!.addAdjustmentListener(AdjustHandler())
        painter!!.addComponentListener(ComponentHandler())
        painter!!.addMouseListener(MouseHandler())
        painter!!.addMouseMotionListener(DragHandler())
        painter!!.addMouseWheelListener(MouseWheelHandler())
        addFocusListener(FocusHandler())

        caretTimer.delay = caretBlinkRate

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher {
            return@addKeyEventDispatcher if (
                this@JEditTextArea.isFocusOwner &&
                it.keyCode == VK_TAB &&
                it.modifiersEx == 0
            ) {
                processKeyEvent(it)
                true
            } else false
        }

        // Make sure we get the initial focus event
        focusedComponent = this
    }

    /** Blink the caret. */
    fun blinkCaret() {
        if (isCaretBlinking) {
            blink = !blink
            painter!!.invalidateSelectedLines()
        } else blink = true
    }

    /**
     * Update the state of the scroll bars. This should be called if the number of lines in the document changes, or
     * when the size of the text changes.
     */
    open fun updateScrollBars() {
        vertical?.let {
            if (visibleLines != 0) {
                it.setValues(firstLine, visibleLines, 0, lineCount)
                it.unitIncrement = VERTICAL_SCROLLBAR_UNIT_INCREMENT_IN_LINES
                it.blockIncrement = visibleLines

                // The editing area scrollbar has a custom model that increments the bar by the number of text lines
                // instead of by the number of pixels. The line number display uses a standard (but invisible) scrollbar
                // based on pixels, so we adjust it accordingly to keep it in sync with the editing area scrollbar.
                val height = painter!!.getFontMetrics(painter!!.font).height
                lineNumbersVertical.setValues(firstLine * height, visibleLines * height, 0, lineCount * height)
                lineNumbersVertical.unitIncrement = VERTICAL_SCROLLBAR_UNIT_INCREMENT_IN_LINES * height
                lineNumbersVertical.blockIncrement = visibleLines * height
            }
        }
        val width = painter!!.width
        horizontal?.let {
            if (width != 0) {
                it.setValues(-horizontalOffset, width, 0, width * 5)
                it.unitIncrement = painter!!.fontMetrics.charWidth('w')
                it.blockIncrement = width / 2
            }
        }
    }

    /** Recalculate the number of visible lines. This should not be called directly. */
    fun recalculateVisibleLines() {
        painter?.let {
            val height = it.height
            val lineHeight = it.fontMetrics.height
            visibleLines = height / lineHeight
            updateScrollBars()
        }
    }

    /**
     * A fast way of changing both the first line and horizontal offset.
     *
     * @param firstLine The new first line
     * @param horizontalOffset The new horizontal offset
     * @return True if any of the values were changed, false otherwise
     */
    open fun setOrigin(firstLine: Int, horizontalOffset: Int): Boolean {
        var changed = false
        if (horizontalOffset != this.horizontalOffset) {
            this.horizontalOffset = horizontalOffset
            changed = true
        }
        if (firstLine != this.firstLine) {
            this.firstLine = firstLine
            changed = true
        }
        if (changed) {
            updateScrollBars()
            painter!!.repaint()
        }
        return changed
    }

    /**
     * Scroll to the caret if necessary.
     * Returns true if the scroll was performed or false if the caret is already visible.
     */
    open fun scrollToCaret(): Boolean {
        val line = caretLine
        val lineStart = getLineStartOffset(line)
        val offset = max(0, min(getLineLength(line) - 1, caretPosition - lineStart))
        return scrollTo(line, offset)
    }

    /**
     * Ensures that the specified line and offset is visible by scrolling
     * the text area if necessary.
     *
     * @param line   The line to scroll to
     * @param offset The offset in the line to scroll to
     * @return True if scrolling was actually performed, false if the
     * line and the offset was already visible
     */
    open fun scrollTo(line: Int, offset: Int): Boolean {
        if (visibleLines == 0) {
            firstLine = max(0, line - electricScroll)
            return true
        }
        var newFirstLine = firstLine
        if (line < firstLine + electricScroll) {
            newFirstLine = max(0, line - electricScroll)
        } else if (line + electricScroll >= firstLine + visibleLines) {
            newFirstLine = (line - visibleLines) + electricScroll + 1
            if (newFirstLine + visibleLines >= lineCount)
                newFirstLine = lineCount - visibleLines
            if (newFirstLine < 0)
                newFirstLine = 0
        }
        val x = fastOffsetToX(line, offset)
        val width = painter!!.fontMetrics.charWidth('w')
        val newHorizontalOffset = if (x < 0) {
            min(0, horizontalOffset - x + width + 5)
        } else {
            horizontalOffset + (painter!!.width - x) - width - 5
        }
        return setOrigin(newFirstLine, newHorizontalOffset)
    }

    /**
     * Converts a line index to a y co-ordinate.
     *
     * @param line The line
     */
    open fun lineToY(line: Int): Int = painter?.fontMetrics?.let {
        (line - firstLine) * it.height - (it.leading + it.maxDescent)
    } ?: 0

    /**
     * Converts a y co-ordinate to a line index.
     *
     * @param y The y co-ordinate
     */
    open fun yToLine(y: Int): Int = painter?.fontMetrics?.let {
        val height = it.height
        max(0, min(lineCount - 1, y / height + firstLine))
    } ?: 0

    /**
     * Converts an offset in a line into an x co-ordinate. This is a
     * slow version that can be used any time.
     *
     * @param line   The line
     * @param offset The offset, from the start of the line
     */
    fun offsetToX(line: Int, offset: Int): Int {
        painter!!.currentLineTokens = null
        return fastOffsetToX(line, offset)
    }

    @Suppress("FunctionName")
    @Deprecated(
        "Renamed to fastOffsetToX.",
        ReplaceWith("fastOffsetToX(line, offset)"),
        DeprecationLevel.ERROR
    )
    fun _offsetToX(line: Int, offset: Int): Int = fastOffsetToX(line, offset)

    /**
     * Converts an offset in a line into an x co-ordinate. This is a
     * fast version that should only be used if no changes were made
     * to the text since the last repaint.
     *
     * @param line   The line
     * @param offset The offset, from the start of the line
     */
    open fun fastOffsetToX(line: Int, offset: Int): Int {
        var fm = painter!!.fontMetrics
        getLineText(line, lineSegment)
        val segmentOffset = lineSegment.offset
        var x = horizontalOffset

        // If syntax highlighting is disabled, do simple translation.
        if (tokenMarker == null) {
            lineSegment.count = offset
            return x + Utilities.getTabbedTextWidth(lineSegment, fm, x.toFloat(), painter!!, 0).toInt()
        } else {

            // If syntax coloring is enabled, we have to do this because tokens can vary in width.
            var tokens = if (painter!!.currentLineIndex == line && painter!!.currentLineTokens != null) {
                painter!!.currentLineTokens
            } else {
                painter!!.currentLineIndex = line
                painter!!.currentLineTokens = tokenMarker?.markTokens(lineSegment, line)
                painter!!.currentLineTokens
            }

            val defaultFont = painter!!.font
            val styles = painter!!.styles

            while (tokens != null) {
                val type = tokens.type
                if (type == END) return x
                fm = if (type == NULL) painter!!.fontMetrics
                else styles[type.rawValue.toInt()].getFontMetrics(defaultFont)
                val length = tokens.length
                if (offset + segmentOffset < lineSegment.offset - offset) {
                    lineSegment.count = offset - (lineSegment.offset - segmentOffset)
                    return x + Utilities.getTabbedTextWidth(lineSegment, fm, x.toFloat(), painter, 0).toInt()
                } else {
                    lineSegment.count = length
                    x += Utilities.getTabbedTextWidth(lineSegment, fm, x.toFloat(), painter, 0).toInt()
                    lineSegment.offset += length
                }
                tokens = tokens.next
            }
        }
        return 0
    }

    /**
     * Converts an x co-ordinate to an offset within a line.
     *
     * @param line The line
     * @param x    The x co-ordinate
     */
    open fun xToOffset(line: Int, x: Int): Int {
        var fm = painter!!.fontMetrics
        getLineText(line, lineSegment)
        val segmentArray = lineSegment.array
        val segmentOffset = lineSegment.offset
        val segmentCount = lineSegment.count
        var width = horizontalOffset

        if (tokenMarker == null) {
            for (i in 0..<segmentCount) {
                val c = segmentArray[i + segmentOffset]
                val charWidth = if (c == '\t') {
                    painter!!.nextTabStop(width.toFloat(), i).toInt() - width
                } else fm.charWidth(c)
                if (painter!!.isBlockCaretEnabled) {
                    if (x - charWidth <= width) return i
                } else {
                    if (x - charWidth / 2 <= width) return i
                }
                width += charWidth
            }
            return segmentCount
        } else {
            var tokens = if (painter!!.currentLineIndex == line && painter!!.currentLineTokens != null) {
                painter!!.currentLineTokens
            } else {
                painter!!.currentLineIndex = line
                painter!!.currentLineTokens = tokenMarker!!.markTokens(lineSegment, line)
                painter!!.currentLineTokens
            }
            var offset = 0
            val defaultFont = painter!!.font
            val styles = painter!!.styles
            while (true) {
                val type = tokens?.type
                if (type == END) return offset
                fm = if (type == NULL)
                    painter!!.fontMetrics
                else styles[type?.rawValue?.toInt() ?: NULL.rawValue.toInt()].getFontMetrics(defaultFont)
                val length = tokens?.length ?: 0
                for (i in 0..<length) {
                    val c = segmentArray[segmentOffset + offset + i]
                    val charWidth = if (c == '\t') {
                        painter!!.nextTabStop(width.toFloat(), offset + i).toInt()
                    } else fm.charWidth('c')
                    if (painter!!.isBlockCaretEnabled) {
                        if (x - charWidth <= width) return offset + i
                    } else {
                        if (x - charWidth / 2 <= width) return offset + i
                    }
                    width += charWidth
                }
                offset += length
                tokens = tokens?.next
            }
        }
    }

    /**
     * Converts a point to an offset, from the start of the text.
     *
     * @param x The x co-ordinate of the point
     * @param y The y co-ordinate of the point
     */
    open fun xyToOffset(x: Int, y: Int): Int {
        val line = yToLine(y)
        val start = getLineStartOffset(line)
        return start + xToOffset(line, x)
    }

    /**
     * Returns the line containing the specified offset.
     *
     * @param offset The offset
     */
    fun getLineOfOffset(offset: Int): Int = document?.defaultRootElement?.getElementIndex(offset) ?: 0

    /**
     * Returns the start offset of the specified line.
     *
     * @param line The line
     * @return The start offset of the specified line, or -1 if the line is invalid
     */
    open fun getLineStartOffset(line: Int): Int =
        document?.defaultRootElement?.getElement(line)?.startOffset ?: -1

    /**
     * Returns the end offset of the specified line.
     *
     * @param line The line
     * @return The end offset of the specified line, or -1 if the line is invalid.
     */
    fun getLineEndOffset(line: Int): Int =
        document?.defaultRootElement?.getElement(line)?.endOffset ?: -1

    /**
     * Returns the length of the specified line.
     *
     * @param line The line
     */
    fun getLineLength(line: Int): Int =
        document?.defaultRootElement?.getElement(line)?.let {
            it.endOffset - it.startOffset - 1
        } ?: -1

    /**
     * Returns the entire text of this text area.
     */
    fun getText(): String? = try {
        document?.getText(0, document?.length ?: 0)
    } catch (e: BadLocationException) {
        e.printStackTrace()
        null
    }

    /**
     * Sets the entire text of this text area.
     */
    fun setText(text: String) {
        document?.let {
            try {
                it.beginCompoundEdit()
                it.remove(0, it.length)
                it.insertString(0, text, null)
            } catch (bl: BadLocationException) {
                bl.printStackTrace()
            } finally {
                it.endCompoundEdit()
            }
        }
    }

    /**
     * Returns the specified substring of the document.
     *
     * @param start The start offset
     * @param len   The length of the substring
     * @return The substring, or null if the offsets are invalid
     */
    fun getText(start: Int, len: Int): String? = try {
        document?.getText(start, len)
    } catch (bl: BadLocationException) {
        bl.printStackTrace()
        null
    }

    /**
     * Copies the specified substring of the document into a segment.
     * If the offsets are invalid, the segment will contain a null string.
     *
     * @param start   The start offset
     * @param len     The length of the substring
     * @param segment The segment
     */
    fun getText(start: Int, len: Int, segment: Segment) = try {
        document?.getText(start, len, segment)
    } catch (bl: BadLocationException) {
        bl.printStackTrace()
        segment.offset = 0
        segment.count = 0
    }

    /**
     * Returns the text on the specified line.
     *
     * @param lineIndex The line
     * @return The text, or null if the line is invalid
     */
    fun getLineText(lineIndex: Int): String? {
        val start = getLineStartOffset(lineIndex)
        return getText(start, getLineEndOffset(lineIndex) - start - 1)
    }

    /**
     * Copies the text on the specified line into a segment. If the line
     * is invalid, the segment will contain a null string.
     *
     * @param lineIndex The line
     */
    fun getLineText(lineIndex: Int, segment: Segment) {
        val start = getLineStartOffset(lineIndex)
        getText(start, getLineEndOffset(lineIndex) - start - 1, segment)
    }

    /**
     * Returns the offset where the selection starts on the specified
     * line.
     */
    fun getSelectionStart(line: Int): Int {
        if (line == selectionStartLine)
            return selectionStart
        else if (isSelectionRectangular) {
            val map = document!!.defaultRootElement
            val start = selectionStart - map.getElement(selectionStartLine).startOffset
            val lineElement = map.getElement(line)
            val lineStart = lineElement.startOffset
            val lineEnd = lineElement.endOffset - 1
            return min(lineEnd, lineStart + start)
        } else return getLineStartOffset(line)
    }

    /**
     * Returns the offset where the selection ends on the specified
     * line.
     */
    fun getSelectionEnd(line: Int): Int {
        if (line == selectionEndLine)
            return selectionEnd
        else if (isSelectionRectangular) {
            val map = document!!.defaultRootElement
            val end = selectionEnd - map.getElement(selectionEndLine).startOffset
            val lineElement = map.getElement(line)
            val lineStart = lineElement.startOffset
            val lineEnd = lineElement.endOffset - 1
            return min(lineEnd, lineStart + end)
        } else return getLineEndOffset(line) - 1
    }

    /**
     * Selects all text in the document.
     */
    fun selectAll() = select(0, document!!.length)

    /**
     * Moves the mark to the caret position.
     */
    fun selectNone() = select(caretPosition, caretPosition)

    /**
     * Selects from the start offset to the end offset. This is the
     * general selection method used by all other selecting methods.
     * The caret position will be start if start &lt; end, and end
     * if end &gt; start.
     *
     * @param start The start offset
     * @param end   The end offset
     */
    fun select(start: Int, end: Int) {
        val (newStart, newEnd, newBias) = if (start <= end) {
            Triple(start, end, false)
        } else {
            Triple(end, start, true)
        }

        if (newStart < 0 || newEnd > document!!.length)
            throw IllegalArgumentException("Bounds out of range: $newStart,$newEnd")

        // If the new position is the same as the old, we don't do anything,
        // but we still clear the magic position and scroll.
        if (newStart != selectionStart || newEnd != selectionEnd || newBias != biasLeft) {
            val newStartLine = getLineOfOffset(newStart)
            val newEndLine = getLineOfOffset(newEnd)
            if (painter!!.isBracketHighlightingEnabled) {
                if (bracketLine != -1) painter!!.invalidateLine(bracketLine)
                updateBracketHighlight(end)
                if (bracketLine != -1) painter!!.invalidateLine(bracketLine)
            }
            painter!!.invalidateLineRange(selectionStartLine, selectionEndLine)
            painter!!.invalidateLineRange(newStartLine, newEndLine)

            document!!.addUndoableEdit(CaretUndo(selectionStart, selectionEnd))

            selectionStart = newStart
            selectionEnd = newEnd
            selectionStartLine = newStartLine
            selectionEndLine = newEndLine
            biasLeft = newBias

            fireCaretEvent()
        }

        // Don't blink the caret while the user is typing
        blink = true
        caretTimer.restart()

        // Disable rectangle selection if the selection start and end are equal
        if (selectionStart == selectionEnd) isSelectionRectangular = false

        // Clear the 'magic' caret position used by up/down
        magicCaretPosition = -1
        scrollToCaret()
    }

    /**
     * Returns the selected text, or null if no selection is active.
     */
    fun getSelectedText(): String? {
        if (selectionStart == selectionEnd) return null
        if (isSelectionRectangular) {
            // Return each row of the selection on a new line
            val map = document!!.defaultRootElement
            var start = selectionStart - map.getElement(selectionStartLine).startOffset
            var end = selectionEnd - map.getElement(selectionEndLine).startOffset
            // Certain rectangles satisfy this condition
            if (end < start) {
                val tmp = end
                end = start
                start = tmp
            }
            val buf = StringBuilder()
            val seg = Segment()
            for (i in selectionStartLine..selectionEndLine) {
                val lineElement = map.getElement(i)
                val lineEnd = lineElement.endOffset - 1
                val lineStart = min(lineElement.startOffset + start, lineEnd)
                val lineLen = min(end - start, lineEnd - lineStart)
                getText(lineStart, lineLen, seg)
                buf.appendRange(seg.array!!, seg.offset, seg.offset + seg.count)
                if (i != selectionEndLine) buf.append('\n')
            }
            return buf.toString()
        } else return getText(selectionStart, selectionEnd - selectionStart)
    }

    /**
     * Replaces the selection with the specified text.
     *
     * @param selectedText The replacement text for the selection
     */
    fun setSelectedText(selectedText: String?) {
        if (!isEditable) throw InternalError("Text component is read only")
        document!!.beginCompoundEdit()
        try {
            if (isSelectionRectangular) {
                val map = document!!.defaultRootElement
                var start = selectionStart - map.getElement(selectionStartLine).startOffset
                var end = selectionEnd - map.getElement(selectionEndLine).startOffset
                // Some rectangles satisfy this condition
                if (end < start) {
                    val tmp = end
                    end = start
                    start = tmp
                }
                var lastNewline = 0
                var currNewline = 0
                for (i in selectionStartLine..selectionEndLine) {
                    val lineElement = map.getElement(i)
                    val lineStart = lineElement.startOffset
                    val lineEnd = lineElement.endOffset - 1
                    val rectStart = min(lineEnd, lineStart + start)
                    document!!.remove(rectStart, min(lineEnd - rectStart, end - start))
                    if (selectedText == null) continue
                    currNewline = selectedText.indexOf('\n', lastNewline)
                    if (currNewline == -1)
                        currNewline = selectedText.length
                    document!!.insertString(rectStart, selectedText.substring(lastNewline, currNewline), null)
                    lastNewline = min(selectedText.length, currNewline + 1)
                }
                if (selectedText != null && currNewline != selectedText.length) {
                    val offset = map.getElement(selectionEndLine).endOffset - 1
                    document!!.insertString(offset, "\n", null)
                    document!!.insertString(offset + 1, selectedText.substring(currNewline + 1), null)
                }
            } else {
                document!!.remove(selectionStart, selectionEnd - selectionStart)
                if (selectedText != null)
                    document!!.insertString(selectionStart, selectedText, null)
            }
        } catch (e: BadLocationException) {
            e.printStackTrace()
            throw InternalError("Cannot replace selection!")
        } finally {
            // No matter what happens... don't leave the document in a corrupted state!
            document!!.endCompoundEdit()
        }
        caretPosition = selectionEnd
    }

    /**
     * Similar to <code>setSelectedText()</code>, but overwrites the
     * appropriate number of characters if overwrite mode is enabled.
     *
     * @param str The string
     * @see #setSelectedText(String)
     * @see #isOverwriteEnabled()
     */
    fun overwriteSetSelectedText(str: String) {
        // Don't overwrite if there is a selection
        if (!isOverwriteEnabled || selectionStart != selectionEnd) {
            setSelectedText(str)
            applySyntaxSensitiveHelp()
            return
        }
        // Don't overwrite if we're at the end of the line
        val caret = caretPosition
        val caretLineEnd = getLineEndOffset(caretLine)
        if (caretLineEnd - caret <= str.length) {
            setSelectedText(str)
            applySyntaxSensitiveHelp()
            return
        }

        document!!.beginCompoundEdit()
        try {
            document!!.remove(caret, str.length)
            document!!.insertString(caret, str, null)
        } catch (bl: BadLocationException) {
            bl.printStackTrace()
        } finally {
            document!!.endCompoundEdit()
        }
        applySyntaxSensitiveHelp()
    }

    /**
     * Adds a caret change listener to this text area.
     *
     * @param listener The listener
     */
    fun addCaretListener(listener: CaretListener) {
        listenerList.add(CaretListener::class.java, listener)
    }

    /**
     * Removes a caret change listener from this text area.
     *
     * @param listener The listener
     */
    fun removeCaretListener(listener: CaretListener) {
        listenerList.add(CaretListener::class.java, listener)
    }

    /**
     * Deletes the selected text from the text area and places it
     * into the clipboard.
     */
    fun cut() {
        if (isEditable) {
            copy()
            setSelectedText("")
        }
    }

    /**
     * Places the selected text into the clipboard.
     */
    fun copy() {
        if (selectionStart != selectionEnd) {
            val clipboard = toolkit.systemClipboard
            val selection = getSelectedText()
            val repeatCount = inputHandler.repeatCount
            clipboard.setContents(StringSelection(selection.toString().repeat(max(0, repeatCount))), null)
        }
    }

    /**
     * Inserts the clipboard contents into the text.
     */
    fun paste() {
        if (isEditable) {
            val clipboard = toolkit.systemClipboard
            try {
                var selection = clipboard
                    .getContents(this)
                    .getTransferData(DataFlavor.stringFlavor)
                    .toString()
                    .replace('\r', '\n')
                val repeatCount = inputHandler.repeatCount
                selection = selection.repeat(max(0, repeatCount))
                setSelectedText(selection)
            } catch (e: Exception) {
                toolkit.beep()
                System.err.println("Clipboard does not contain a string")
            }
        }
    }

    /**
     * Called by the AWT when this component is removed from its parent.
     * This stop clears the currently focused component.
     */
    override fun removeNotify() {
        super.removeNotify()
        if (focusedComponent == this) focusedComponent = null
    }

    /**
     * Forwards key events directly to the input handler.
     * This is slightly faster than using a KeyListener
     * because some Swing overhead is avoided.
     */
    override fun processKeyEvent(e: KeyEvent) {
        when (e.id) {
            KEY_TYPED -> inputHandler.keyTyped(e)
            KEY_PRESSED -> {
                if (!checkPopupCompletion(e)) inputHandler.keyPressed(e)
                checkPopupMenu(e)
            }
            KEY_RELEASED -> inputHandler.keyReleased(e)
        }
    }

    protected fun fireCaretEvent() {
        val listeners = listenerList.listenerList
        for (i in listeners.size - 2 downTo 0)
            if (listeners[i] == CaretListener::class.java)
                (listeners[i + 1] as CaretListener).caretUpdate(caretEvent)
    }

    protected fun updateBracketHighlight(newCaretPosition: Int) {
        if (newCaretPosition == 0) {
            bracketPosition = -1
            bracketLine = -1
            return
        }
        try {
            val offset = document!!.findMatchingBracket(newCaretPosition - 1)
            if (offset != -1) {
                bracketLine = getLineOfOffset(offset)
                bracketPosition = offset - getLineStartOffset(bracketLine)
                return
            }
        } catch (bl: BadLocationException) {
            bl.printStackTrace()
        }
        bracketLine = -1
        bracketPosition = -1
    }

    protected fun documentChanged(e: DocumentEvent) {
        val ch = e.getChange(document!!.defaultRootElement)
        val count = ch?.let { it.childrenAdded.size - it.childrenRemoved.size } ?: 0
        val line = getLineOfOffset(e.offset)
        if (count == 0) painter!!.invalidateLine(line)
        else if (line < firstLine) firstLine += count
        else {
            painter!!.invalidateLineRange(line, firstLine + visibleLines)
            updateScrollBars()
        }
    }

    /**
     * Return any relevant tool tip text for token at specified position. Keyword match
     * must be exact.
     *
     * @param x x-coordinate of current position
     * @param y y-coordinate of current position
     * @return String containing appropriate tool tip text.  Possibly HTML-encoded.
     */
    fun getSyntaxSensitiveToolTipText(x: Int, y: Int): String? {
        val line = yToLine(y)
        val matches = getSyntaxSensitiveHelpAtLineOffset(line, xToOffset(line, x), true) ?: return null
        val length = PopupHelpItem.maxExampleLength(matches) + 2
        return buildString {
            append("<html>")
            for (i in 0..<matches.size) {
                val match = matches[i]
                append(if (i == 0) "" else "<br>")
                append("<tt>")
                append(match.padExampleToLength(length).replace(" ", "&nbsp;"))
                append("</tt>")
                append(match.description)
            }
            append("</html>")
        }
    }

    /**
     * Constructs string for auto-indent feature. Returns empty string
     * if auto-intent is disabled or if line has no leading whitespace.
     * Used by InputHandler when processing key press for Enter key.
     *
     * @return String containing auto-indent characters to be inserted into text
     */
    fun getAutoIndent() = if (Globals.settings.getBooleanSetting(CoreSettings.ENABLE_AUTO_INDENT))
        getLeadingWhiteSpace() else ""

    /**
     * Makes a copy of leading whitespace (tab or space) from the current line and
     * returns it.
     *
     * @return String containing leading whitespace of current line. Empty string if none.
     */
    fun getLeadingWhiteSpace(): String {
        val line = caretLine
        val lineLength = getLineLength(line)
        var indent = ""
        if (lineLength > 0) {
            val text = getText(getLineStartOffset(line), lineLength)!!
            for (position in text.indices) {
                val character = text[position]
                if (character == '\t' || character == ' ') {
                    indent += character
                } else break
            }
        }
        return indent
    }

    /**
     * Get relevant help information at specified position. Returns ArrayList of
     * PopupHelpItem with one per match, or null if no matches.
     * The "exact" parameter is set depending on whether the match has to be
     * exact or whether a prefix match will do. The token "s" will not match
     * any instruction names if exact is true, but will match "sw", "sh", etc.
     * if exact is false. The former is helpful for mouse-movement-based tool
     * tips (this is what you have). The latter is helpful for caret-based tool
     * tips (this is what you can do).
     */
    private fun getSyntaxSensitiveHelpAtLineOffset(line: Int, offset: Int, exact: Boolean): ArrayList<PopupHelpItem>? {
        var matches: ArrayList<PopupHelpItem>? = null
        tokenMarker?.let {
            val lineSegment = Segment()
            getLineText(line, lineSegment)
            var tokens: Token? = it.markTokens(lineSegment, line)
            val tokenList = tokens
            var tokenOffset = 0
            var tokenAtOffset: Token? = null
            while (tokens != null) {
                val type = tokens.type
                if (type == END) break
                val length = tokens.length
                if (offset > tokenOffset && offset <= tokenOffset + length) {
                    tokenAtOffset = tokens
                    break
                }
                tokenOffset += length
                tokens = tokens.next
            }
            if (tokenAtOffset != null) {
                val tokenText = lineSegment.toString().substring(tokenOffset, tokenOffset + tokenAtOffset.length)
                matches = if (exact) {
                    it.getTokenExactMatchHelp(tokenAtOffset, tokenText)
                } else {
                    it.getTokenPrefixMatchHelp(lineSegment.toString(), tokenList, tokenAtOffset, tokenText)
                }
            }
        }
        return matches
    }

    /**
     * Compose and display syntax-sensitive help. Typically invoked upon typing a key.
     * Results in a popup menu. Not used for creating tool tips.
     */
    private fun applySyntaxSensitiveHelp() {
        if (!Globals.settings.getBooleanSetting(CoreSettings.POPUP_INSTRUCTION_GUIDANCE)) return
        val line = caretLine
        val lineStart = getLineStartOffset(line)
        val offset = max(1, min(getLineLength(line), caretPosition - lineStart))
        val helpItems = getSyntaxSensitiveHelpAtLineOffset(line, offset, false)
        if (helpItems == null && popupMenu != null) {
            popupMenu!!.isVisible = false
            popupMenu = null
        } else if (helpItems != null) {
            popupMenu = JPopupMenu()
            val length = PopupHelpItem.maxExampleLength(helpItems) + 2
            for (helpItem in helpItems) {
                val menuItem = JMenuItem(
                    "<html><tt>${
                        helpItem.padExampleToLength(length).replace(" ".toRegex(), "&nbsp;")
                    }</tt>${helpItem.description}</html>"
                )
                if (helpItem.isExact) {
                    menuItem.isSelected = false
                } else {
                    menuItem.addActionListener(PopupHelpActionListener(helpItem.tokenText, helpItem.example))
                }
                popupMenu!!.add(menuItem)
            }
            popupMenu!!.pack()
            val y = lineToY(line)
            val x = offsetToX(line, offset)
            val height = painter!!.getFontMetrics(painter!!.font).height
            val width = painter!!.getFontMetrics(painter!!.font).charWidth('w')
            val menuXLoc = x + (width * 3)
            val menuYLoc = y + (height * 2)
            popupMenu!!.show(this, menuXLoc, menuYLoc)
            requestFocusInWindow()
        }
    }

    private fun checkAutoIndent(e: KeyEvent) {
        if (e.keyCode == VK_ENTER) {
            val line = caretLine
            if (line <= 0) return
            val previousLine = line - 1
            val previousLineLength = getLineLength(previousLine)
            if (previousLineLength <= 0) return
            val previous = getText(getLineStartOffset(previousLine), previousLineLength)!!
            var indent = ""
            for (position in previous.indices) {
                val character = previous[position]
                if (character == '\t' || character == ' ') {
                    indent += character
                } else {
                    break
                }
            }
            overwriteSetSelectedText(indent)
        }
    }

    /**
     * Called after processing a Key Pressed event. Will make the popup menu disappear if
     * Enter or Escape keys pressed.  Will update if Backspace or Delete pressed.
     * Not really concerned with modifiers here.
     */
    private fun checkPopupMenu(e: KeyEvent) {
        if (e.keyCode == VK_BACK_SPACE || e.keyCode == VK_DELETE) applySyntaxSensitiveHelp()
        if ((e.keyCode == VK_ENTER || e.keyCode == VK_ESCAPE) && popupMenu != null && popupMenu!!.isVisible)
            popupMenu!!.isVisible = false
    }

    /**
     * Called before processing a keypress event. If the popup menu is visible, will process
     * tab and enter keys to select from the menu, and arrow keys to traverse the menu.
     */
    private fun checkPopupCompletion(e: KeyEvent): Boolean {
        if ((e.keyCode == VK_UP || e.keyCode == VK_DOWN) &&
            popupMenu != null && popupMenu!!.isVisible && popupMenu!!.componentCount > 0) {
            val path = MenuSelectionManager.defaultManager().selectedPath
            if (path.isEmpty() || path.last() !is AbstractButton) return false
            val item = path.last().component as AbstractButton
            if (item.isEnabled) {
                var index = popupMenu!!.getComponentIndex(item)
                if (index < 0) return false
                index = if (e.keyCode == VK_UP) {
                    if (index == 0) popupMenu!!.componentCount - 1 else index - 1
                } else {
                    if (index == popupMenu!!.componentCount - 1) 0 else index + 1
                }
                val newPath = arrayOf(path[0], popupMenu!!.getComponent(index) as MenuElement)
                SwingUtilities.invokeLater {
                    MenuSelectionManager.defaultManager().selectedPath = newPath
                }
                return true
            } else return false
        }
        if ((e.keyCode == VK_TAB || e.keyCode == VK_ENTER) &&
            popupMenu != null && popupMenu!!.isVisible && popupMenu!!.componentCount > 0) {
            val path = MenuSelectionManager.defaultManager().selectedPath
            if (path.isEmpty() || path.last() !is AbstractButton) return false
            val item = path.last().component as AbstractButton
            if (item.isEnabled) {
                val listeners = item.actionListeners
                if (listeners.isNotEmpty()) {
                    listeners[0].actionPerformed(ActionEvent(
                        item,
                        ActionEvent.ACTION_FIRST,
                        if (e.keyCode == VK_TAB) "\t" else " "
                    ))
                    return true
                }
            }
        }
        return false
    }

    inner class ScrollLayout : LayoutManager {
        private var center: Component? = null
        private var right: Component? = null
        private var bottom: Component? = null

        private val leftOfScrollBar = vectorOf<Component>()

        override fun addLayoutComponent(name: String?, comp: Component?) {
            when (name) {
                CENTER -> center = comp
                RIGHT -> right = comp
                BOTTOM -> bottom = comp
                LEFT_OF_SCROLLBAR -> leftOfScrollBar.addElement(comp)
            }
        }

        override fun removeLayoutComponent(comp: Component?) {
            if (center == comp) center = null
            else if (right == comp) right = null
            else if (bottom == comp) bottom = null
            else leftOfScrollBar.removeElement(comp)
        }

        override fun preferredLayoutSize(parent: Container?): Dimension {
            val dim = Dimension()
            val insets = insets
            dim.width = insets.left + insets.right
            dim.height = insets.top + insets.bottom

            dim.width += center?.preferredSize?.width ?: 0
            dim.height += center?.preferredSize?.height ?: 0
            dim.width += right?.preferredSize?.width ?: 0
            dim.height += bottom?.preferredSize?.height ?: 0

            return dim
        }

        override fun minimumLayoutSize(parent: Container?): Dimension {
            val dim = Dimension()
            val insets = insets
            dim.width = insets.left + insets.right
            dim.height = insets.top + insets.bottom

            dim.width += center?.minimumSize?.width ?: 0
            dim.height += center?.minimumSize?.height ?: 0
            dim.width += right?.minimumSize?.width ?: 0
            dim.height += bottom?.minimumSize?.height ?: 0

            return dim
        }

        override fun layoutContainer(parent: Container?) {
            parent ?: return
            val size = parent.size
            val insets = parent.insets
            var (iTop, iRight, iBottom, iLeft) = insets

            val rightWidth = right?.preferredSize?.width ?: 0
            val bottomHeight = bottom?.preferredSize?.height ?: 0
            val centerWidth = size.width - rightWidth - iLeft - iRight
            val centerHeight = size.height - bottomHeight - iTop - iBottom

            center?.bounds = Rectangle(iLeft, iTop, centerWidth, centerHeight)
            right?.bounds = Rectangle(iLeft + centerWidth, iTop, rightWidth, centerHeight)

            val status = leftOfScrollBar.elements()
            while (status.hasMoreElements()) {
                val comp = status.nextElement()
                val dim = comp.preferredSize
                comp.bounds = Rectangle(iLeft, iTop + centerHeight, dim.width, bottomHeight)
                iLeft += dim.width
            }

            bottom?.bounds = Rectangle(iLeft, iTop + centerHeight, size.width - rightWidth - iLeft - iRight, bottomHeight)
        }
    }

    class CaretBlinker : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            if (focusedComponent?.hasFocus() == true) focusedComponent?.blinkCaret()
        }
    }

    protected inner class MutableCaretEvent : CaretEvent(this) {
        override fun getDot(): Int = caretPosition
        override fun getMark(): Int = markPosition
    }

    protected inner class AdjustHandler : AdjustmentListener {
        override fun adjustmentValueChanged(e: AdjustmentEvent) {
            if (!scrollBarsInitialized) return
            SwingUtilities.invokeLater {
                if (e.adjustable == vertical) firstLine = vertical!!.value
                else horizontalOffset = -horizontal!!.value
            }
        }
    }

    protected inner class ComponentHandler : ComponentAdapter() {
        override fun componentResized(e: ComponentEvent) {
            recalculateVisibleLines()
            scrollBarsInitialized = true
        }
    }

    protected inner class DocumentHandler : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) {
            documentChanged(e)
            val offset = e.offset
            val length = e.length
            // If the event was fired because of an undo or redo, select the inserted text.
            if (unredoing) {
                select(offset, offset + length)
                return
            }
            val newStart =
                if (selectionStart > offset || (selectionStart == selectionEnd && selectionStart == offset))
                    selectionStart + length
                else selectionStart
            val newEnd = if (selectionEnd >= offset) selectionEnd + length else selectionEnd
            select(newStart, newEnd)
        }

        override fun removeUpdate(e: DocumentEvent) {
            documentChanged(e)
            val offset = e.offset
            val length = e.length
            // If the event was fired because of an undo or redo, move the caret to the position of removal.
            if (unredoing) {
                select(offset, offset)
                caretPosition = offset
                return
            }
            val newStart = if (selectionStart > offset) {
                if (selectionStart > offset + length) selectionStart - length else offset
            } else selectionStart
            val newEnd = if (selectionEnd > offset) {
                if (selectionEnd > offset + length) selectionEnd - length else offset
            } else selectionEnd
            select(newStart, newEnd)
        }

        override fun changedUpdate(e: DocumentEvent) {}
    }

    inner class DragHandler : MouseMotionListener {
        override fun mouseDragged(e: MouseEvent) {
            if (popupMenu?.isVisible == true) return
            isSelectionRectangular = (e.modifiersEx and CTRL_DOWN_MASK) != 0
            select(markPosition, xyToOffset(e.x, e.y))
        }

        override fun mouseMoved(e: MouseEvent) {}
    }

    inner class FocusHandler : FocusListener {
        override fun focusGained(e: FocusEvent) {
            isCaretVisible = true
            focusedComponent = this@JEditTextArea
        }

        override fun focusLost(e: FocusEvent) {
            isCaretVisible = false
            focusedComponent = null
        }
    }

    // Allows use of mouse wheel to scroll.
    // Scrolling as fast as I could, the maximum number of notches I could get in
    // one MouseWheelEvent was 3. Normally it will be 1. Nonetheless,
    // this will scroll up to the number in the event, subject to
    // scrolling ability of the text in its viewport.
    inner class MouseWheelHandler : MouseWheelListener {
        override fun mouseWheelMoved(e: MouseWheelEvent) {
            val maxMotion = abs(e.wheelRotation) * LINES_PER_MOUSE_WHEEL_NOTCH
            firstLine = if (e.wheelRotation < 0) {
                firstLine - min(maxMotion, firstLine)
            } else {
                firstLine + (min(maxMotion, max(0, lineCount - (firstLine + visibleLines))))
            }
        }
    }

    inner class MouseHandler : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            requestFocus()
            // Focus events aren't fired sometimes...
            isCaretVisible = true
            focusedComponent = this@JEditTextArea

            if ((e.modifiersEx and BUTTON3_DOWN_MASK) != 0 && popupMenu != null) {
                popupMenu!!.show(painter!!, e.x, e.y)
                return
            }

            val line = yToLine(e.y)
            val offset = xToOffset(line, e.x)
            val dot = getLineStartOffset(line) + offset

            when (e.clickCount) {
                1 -> e.doSingleClick(dot)
                2 -> try {
                    doDoubleClick(line, offset, dot)
                } catch (bl: BadLocationException) {
                    bl.printStackTrace()
                }
                3 -> doTripleClick(line)
            }
        }

        private fun MouseEvent.doSingleClick(dot: Int) {
            if ((modifiersEx and SHIFT_DOWN_MASK) != 0) {
                isSelectionRectangular = (modifiersEx and CTRL_DOWN_MASK) != 0
                select(markPosition, dot)
            } else caretPosition = dot
        }

        private fun doDoubleClick(line: Int, offset: Int, dot: Int) {
            // Ignore empty lines
            if (getLineLength(line) == 0) return
            try {
                var bracket = document?.findMatchingBracket(max(0, dot - 1)) ?: -1
                if (bracket != -1) {
                    var mark = markPosition
                    if (bracket > mark) {
                        bracket++
                        mark--
                    }
                    select(mark, bracket)
                    return
                }
            } catch (bl: BadLocationException) {
                bl.printStackTrace()
            }

            // Okay, it's not a bracket; select the word.
            val lineText = getLineText(line)!!
            var ch = lineText[max(0, offset - 1)]
            val noWordSep = document?.getProperty("noWordSep") as? String ?: ""

            // If the user clicked on a non-letter character, we select the surrounding non-letters
            val selectNoLetter = !ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1

            var wordStart = 0

            for (i in offset - 1 downTo 0) {
                ch = lineText[i]
                if (selectNoLetter xor (!ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1)) {
                    wordStart = i + 1
                    break
                }
            }

            var wordEnd = lineText.length
            for (i in offset..<lineText.length) {
                ch = lineText[i]
                if (selectNoLetter xor (!ch.isLetterOrDigit() && noWordSep.indexOf(ch) == -1)) {
                    wordEnd = i
                    break
                }
            }

            val lineStart = getLineStartOffset(line)
            select(lineStart + wordStart, lineStart + wordEnd)
        }

        private fun doTripleClick(line: Int) {
            select(getLineStartOffset(line), getLineEndOffset(line) - 1)
        }
    }

    inner class CaretUndo(private var start: Int, private var end: Int) : AbstractUndoableEdit() {
        override fun isSignificant(): Boolean = false

        override fun getPresentationName(): String = "caret move"

        override fun undo() {
            super.undo()
            select(start, end)
        }

        override fun redo() {
            super.redo()
            select(start, end)
        }

        override fun addEdit(edit: UndoableEdit): Boolean {
            if (edit !is CaretUndo) return false
            start = edit.start
            end = edit.end
            edit.die()
            return true
        }
    }

    private inner class PopupHelpActionListener(
        private val tokenText: String,
        text: String
    ) : ActionListener {
        private val text: String = text.split(" ".toRegex())[0]

        override fun actionPerformed(e: ActionEvent) {
            val insert = if (e.actionCommand[0] == '\t') "\t" else " "
            if (tokenText.length >= text.length) {
                overwriteSetSelectedText(insert)
            } else {
                overwriteSetSelectedText(text.substring(tokenText.length) + insert)
            }
        }
    }
}