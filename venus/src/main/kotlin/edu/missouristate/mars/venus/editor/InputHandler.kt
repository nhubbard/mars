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

@file:Suppress("MemberVisibilityCanBePrivate")

package edu.missouristate.mars.venus.editor

import edu.missouristate.mars.hashTableOf
import edu.missouristate.mars.venus.editor.TextUtilities.findWordEnd
import edu.missouristate.mars.venus.editor.TextUtilities.findWordStart
import java.awt.Component
import java.awt.event.ActionEvent
import java.awt.event.ActionListener
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.JPopupMenu
import javax.swing.text.BadLocationException
import kotlin.math.max
import kotlin.math.min

/**
 * An input handler converts the user's keystrokes into concrete actions. It also takes care of macro recording and
 * action repetition.
 *
 * This class provides all the necessary support code for an input handler, but doesn't do any key binding logic. It is
 * up to the implementations of this class to do so.
 */
abstract class InputHandler : KeyAdapter() {
    companion object {
        /**
         * If this client property is set to `true` on the text area, the home/end keys will support 'smart' BRIEF-like
         * behavior (one press means go to start/end of line, two presses means start/end of view screen, and three
         * presses means start/end of document). This property is set to false by default.
         */
        const val SMART_HOME_END_PROPERTY = "InputHandler.homeEnd"

        @JvmField val BACKSPACE = Backspace()
        @JvmField val BACKSPACE_WORD = BackspaceWord()
        @JvmField val DELETE = Delete()
        @JvmField val DELETE_WORD = DeleteWord()
        @JvmField val END = End(false)
        @JvmField val DOCUMENT_END = DocumentEnd(false)
        @JvmField val SELECT_ALL = SelectAll()
        @JvmField val SELECT_END = End(true)
        @JvmField val SELECT_DOC_END = DocumentEnd(true)
        @JvmField val INSERT_BREAK = InsertBreak()
        @JvmField val INSERT_TAB = InsertTab()
        @JvmField val HOME = Home(false)
        @JvmField val DOCUMENT_HOME = DocumentHome(false)
        @JvmField val SELECT_HOME = Home(true)
        @JvmField val SELECT_DOC_HOME = DocumentHome(true)
        @JvmField val NEXT_CHAR = NextChar(false)
        @JvmField val NEXT_LINE = NextLine(false)
        @JvmField val NEXT_PAGE = NextPage(false)
        @JvmField val NEXT_WORD = NextWord(false)
        @JvmField val SELECT_NEXT_CHAR = NextChar(true)
        @JvmField val SELECT_NEXT_LINE = NextLine(true)
        @JvmField val SELECT_NEXT_PAGE = NextPage(true)
        @JvmField val SELECT_NEXT_WORD = NextWord(true)
        @JvmField val OVERWRITE = Overwrite()
        @JvmField val PREV_CHAR = PrevChar(false)
        @JvmField val PREV_LINE = PrevLine(false)
        @JvmField val PREV_PAGE = PrevPage(false)
        @JvmField val PREV_WORD = PrevWord(false)
        @JvmField val SELECT_PREV_CHAR = PrevChar(true)
        @JvmField val SELECT_PREV_LINE = PrevLine(true)
        @JvmField val SELECT_PREV_PAGE = PrevPage(true)
        @JvmField val SELECT_PREV_WORD = PrevWord(true)
        @JvmField val REPEAT = Repeat()
        @JvmField val TOGGLE_RECT = ToggleRect()
        @JvmField val CLIP_COPY = ClipCopy()
        @JvmField val CLIP_PASTE = ClipPaste()
        @JvmField val CLIP_CUT = ClipCut()
        @JvmField val INSERT_CHAR = InsertChar()

        @JvmField val actions = hashTableOf(
            "backspace" to BACKSPACE,
            "backspace-word" to BACKSPACE_WORD,
            "delete" to DELETE,
            "delete-word" to DELETE_WORD,
            "end" to END,
            "select-all" to SELECT_ALL,
            "select-end" to SELECT_END,
            "document-end" to DOCUMENT_END,
            "select-doc-end" to SELECT_DOC_END,
            "insert-break" to INSERT_BREAK,
            "insert-tab" to INSERT_TAB,
            "home" to HOME,
            "select-home" to SELECT_HOME,
            "document-home" to DOCUMENT_HOME,
            "select-doc-home" to SELECT_DOC_HOME,
            "next-char" to NEXT_CHAR,
            "next-line" to NEXT_LINE,
            "next-page" to NEXT_PAGE,
            "next-word" to NEXT_WORD,
            "select-next-char" to SELECT_NEXT_CHAR,
            "select-next-line" to SELECT_NEXT_LINE,
            "select-next-page" to SELECT_NEXT_PAGE,
            "select-next-word" to SELECT_NEXT_WORD,
            "overwrite" to OVERWRITE,
            "prev-char" to PREV_CHAR,
            "prev-line" to PREV_LINE,
            "prev-page" to PREV_PAGE,
            "prev-word" to PREV_WORD,
            "select-prev-char" to SELECT_PREV_CHAR,
            "select-prev-line" to SELECT_PREV_LINE,
            "select-prev-page" to SELECT_PREV_PAGE,
            "select-prev-word" to SELECT_PREV_WORD,
            "repeat" to REPEAT,
            "toggle-rect" to TOGGLE_RECT,
            "insert-char" to INSERT_CHAR,
            "clipboard-copy" to CLIP_COPY,
            "clipboard-paste" to CLIP_PASTE,
            "clipboard-cut" to CLIP_CUT
        )
        @JvmField val reverseActions = hashTableOf<ActionListener, String>(
            *actions.entries.map {
                it.value to it.key
            }.toTypedArray()
        )

        @Deprecated(
            "Use array accessor syntax instead.",
            ReplaceWith("actions[name]"),
            DeprecationLevel.ERROR
        )
        @JvmStatic
        fun getAction(name: String): ActionListener = actions[name]!!

        @Deprecated(
            "Use array accessor syntax instead.",
            ReplaceWith("reverseActions[listener]"),
            DeprecationLevel.ERROR
        )
        @JvmStatic
        fun getActionName(listener: ActionListener): String = reverseActions[listener]!!

        @Deprecated(
            "Use property access directly instead.",
            ReplaceWith("actions.keys"),
            DeprecationLevel.ERROR
        )
        @JvmStatic
        fun getActions() = actions.keys

        /**
         * Returns the text area that fired the specified event.
         *
         * @param event The event
         */
        @JvmStatic
        fun getTextArea(event: EventObject?): JEditTextArea {
            if (event != null) {
                var o = event.source
                if (o is Component) {
                    // find the parent text area
                    while (true) {
                        if (o is JEditTextArea) return o
                        else if (o == null) break
                        o = if (o is JPopupMenu) o.invoker
                        else (o as Component).parent
                    }
                }
            }
            // This should never happen!
            System.err.println("BUG: InputHandler.getTextArea() returning null!")
            System.err.println("Report this to Slava Pestov <sp@gjt.org>.")
            throw IllegalStateException("InputHandler.getTextArea() cannot return null!")
        }
    }

    var grabAction: ActionListener? = null
    var isRepeatEnabled: Boolean = false
    var repeatCount: Int = 0
        get() = if (isRepeatEnabled) max(1, field) else 1
    var macroRecorder: MacroRecorder? = null

    /**
     * Adds the default key bindings to this input handler.
     * This should not be called in the constructor of this
     * input handler, because applications might load the
     * key bindings from a file, etc.
     */
    abstract fun addDefaultKeyBindings()

    /**
     * Adds a key binding to this input handler.
     *
     * @param keyBinding The key binding (the format of this is
     *                   input-handler specific)
     * @param action     The action
     */
    abstract fun addKeyBinding(keyBinding: String, action: ActionListener)

    /**
     * Removes a key binding from this input handler.
     *
     * @param keyBinding The key binding
     */
    abstract fun removeKeyBinding(keyBinding: String)

    /**
     * Removes all key bindings from this input handler.
     */
    abstract fun removeAllKeyBindings()

    /**
     * Returns a copy of this input handler that shares the same
     * key bindings. Setting key bindings in the copy will also
     * set them in the original.
     */
    abstract fun copy(): InputHandler

    /**
     * Executes the specified action, repeating and recording it as
     * necessary.
     *
     * @param listener      The action listener
     * @param source        The event source
     * @param actionCommand The action command
     */
    fun executeAction(listener: ActionListener, source: Any, actionCommand: String?) {
        // Create the event
        val event = ActionEvent(source, ActionEvent.ACTION_PERFORMED, actionCommand)
        // Don't do anything if the action is a wrapper
        if (listener is Wrapper) {
            listener.actionPerformed(event)
            return
        }
        // Remember old values in case the action changes them
        val oldRepeat = isRepeatEnabled
        val oldRepeatCount = repeatCount
        // Execute the action
        if (listener is NonRepeatable) listener.actionPerformed(event)
        else for (i in 0..<max(1, repeatCount)) listener.actionPerformed(event)
        // Start recording. Notice that we don't do recording for actions that grab keys.
        if (grabAction == null) {
            macroRecorder?.let {
                if (listener !is NonRecordable) {
                    if (oldRepeatCount != 1) it.actionPerformed(REPEAT, oldRepeatCount.toString())
                    it.actionPerformed(listener, actionCommand)
                }
            }
            // If repeat was originally true, clear it.
            // Otherwise, it might have been set by the action.
            if (oldRepeat) {
                isRepeatEnabled = false
                repeatCount = 0
            }
        }
    }

    /**
     * If a key is being grabbed, this method should be called with
     * the appropriate key event. It executes the grab action with
     * the typed character as the parameter.
     */
    protected fun handleGrabAction(event: KeyEvent) {
        val localGrabAction = grabAction!!
        grabAction = null
        executeAction(localGrabAction, event.source, event.keyChar.toString())
    }

    /**
     * If an action implements this interface, it should not be repeated.
     * It will handle repetition itself.
     */
    interface NonRepeatable

    /**
     * If an action implements this interface, it should not be recorded by the macro recorder.
     * It will do its own macro recording.
     */
    interface NonRecordable

    /**
     * For use by EditAction.Wrapper only.
     */
    interface Wrapper

    /**
     * Macro recorder interface.
     */
    interface MacroRecorder {
        fun actionPerformed(listener: ActionListener, actionCommand: String?)
    }

    class Backspace : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)

            if (!textArea.isEditable) {
                textArea.toolkit.beep()
                return
            }

            if (textArea.selectionStart != textArea.selectionEnd) {
                textArea.setSelectedText("")
            } else {
                val caret = textArea.caretPosition
                if (caret == 0) {
                    textArea.toolkit.beep()
                    return
                }
                try {
                    textArea.document?.remove(caret - 1, 1)
                } catch (bl: BadLocationException) {
                    bl.printStackTrace()
                }
            }
        }
    }

    class BackspaceWord : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            val start = textArea.selectionStart
            if (start != textArea.selectionEnd) textArea.setSelectedText("")

            val line = textArea.caretLine
            val lineStart = textArea.getLineStartOffset(line)
            var caret = start - lineStart

            val lineText = textArea.getLineText(textArea.caretLine)
            if (caret == 0) {
                if (lineStart == 0) {
                    textArea.toolkit.beep()
                    return
                }
                caret--
            } else {
                val noWordSep = textArea.document?.getProperty("noWordSep") as String
                caret = lineText!!.findWordStart(caret, noWordSep)
            }

            try {
                textArea.document!!.remove(caret + lineStart, start - (caret + lineStart))
            } catch (bl: BadLocationException) {
                bl.printStackTrace()
            }
        }
    }

    class Delete : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)

            if (!textArea.isEditable) {
                textArea.toolkit.beep()
                return
            }

            if (textArea.selectionStart != textArea.selectionEnd) {
                textArea.setSelectedText("")
            } else {
                val caret = textArea.caretPosition
                if (caret == textArea.document!!.length) {
                    textArea.toolkit.beep()
                    return
                }
                try {
                    textArea.document!!.remove(caret, 1)
                } catch (bl: BadLocationException) {
                    bl.printStackTrace()
                }
            }
        }
    }

    class DeleteWord : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            val start = textArea.selectionStart
            if (start != textArea.selectionEnd) textArea.setSelectedText("")

            val line = textArea.caretLine
            val lineStart = textArea.getLineStartOffset(line)
            var caret = start - lineStart

            val lineText = textArea.getLineText(textArea.caretLine)

            if (caret == lineText!!.length) {
                if (lineStart + caret == textArea.document!!.length) {
                    textArea.toolkit.beep()
                    return
                }
                caret++
            } else {
                val noWordSep = textArea.document!!.getProperty("noWordSep") as String
                caret = lineText.findWordEnd(caret, noWordSep)
            }

            try {
                textArea.document!!.remove(start, (caret + lineStart) - start)
            } catch (bl: BadLocationException) {
                bl.printStackTrace()
            }
        }
    }

    class End(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)

            var caret = textArea.caretPosition

            val lastOfLine = textArea.getLineEndOffset(textArea.caretLine) - 1
            var lastVisibleLine = textArea.firstLine + textArea.visibleLines
            if (lastVisibleLine >= textArea.lineCount) {
                lastVisibleLine = min(textArea.lineCount - 1, lastVisibleLine)
            } else lastVisibleLine -= (textArea.electricScroll + 1)

            val lastVisible = textArea.getLineEndOffset(lastVisibleLine) - 1
            val lastDocument = textArea.document!!.length

            caret = if (caret == lastDocument) {
                textArea.toolkit.beep()
                return
            } else if (!(textArea.getClientProperty(SMART_HOME_END_PROPERTY) as String).toBoolean()) lastOfLine
            else if (caret == lastVisible) lastDocument
            else if (caret == lastOfLine) lastVisible
            else lastOfLine

            if (select) textArea.select(textArea.markPosition, caret)
            else textArea.caretPosition = caret
        }
    }

    class SelectAll : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            textArea.selectAll()
        }
    }

    class DocumentEnd(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            if (select) textArea.select(textArea.markPosition, textArea.document!!.length)
            else textArea.caretPosition = textArea.document!!.length
        }
    }

    class Home(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)

            var caret = textArea.caretPosition

            val firstLine = textArea.firstLine

            val firstOfLine = textArea.getLineStartOffset(textArea.caretLine)
            val firstVisibleLine = if (firstLine == 0) 0 else firstLine + textArea.electricScroll
            val firstVisible = textArea.getLineStartOffset(firstVisibleLine)

            caret = if (caret == 0) {
                textArea.toolkit.beep()
                return
            } else if (!(textArea.getClientProperty(SMART_HOME_END_PROPERTY) as String).toBoolean()) firstOfLine
            else if (caret == firstVisible) 0
            else if (caret == firstOfLine) firstVisible
            else firstOfLine

            if (select) textArea.select(textArea.markPosition, caret)
            else textArea.caretPosition = caret
        }
    }

    class DocumentHome(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            if (select) textArea.select(textArea.markPosition, 0)
            else textArea.caretPosition = 0
        }
    }

    class InsertBreak : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            if (!textArea.isEditable) {
                textArea.toolkit.beep()
                return
            }
            textArea.setSelectedText("\n${textArea.getAutoIndent()}")
        }
    }

    class InsertTab : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            if (!textArea.isEditable) {
                textArea.toolkit.beep()
                return
            }
            textArea.overwriteSetSelectedText("\t")
        }
    }

    class NextChar(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            val caret = textArea.caretPosition
            if (caret == textArea.document!!.length) {
                textArea.toolkit.beep()
                return
            }
            if (select) textArea.select(textArea.markPosition, caret + 1)
            else textArea.caretPosition = caret + 1
        }
    }

    class NextLine(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            var caret = textArea.caretPosition
            val line = textArea.caretLine

            if (line == textArea.lineCount - 1) {
                textArea.toolkit.beep()
                return
            }

            var magic = textArea.magicCaretPosition
            if (magic == -1) magic = textArea.offsetToX(line, caret - textArea.getLineStartOffset(line))

            caret = textArea.getLineStartOffset(line + 1) + textArea.xToOffset(line + 1, magic)
            if (select) textArea.select(textArea.markPosition, caret)
            else textArea.caretPosition = caret
            textArea.magicCaretPosition = magic
        }
    }

    class NextPage(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            val lineCount = textArea.lineCount
            var firstLine = textArea.firstLine
            val visibleLines = textArea.visibleLines
            val line = textArea.caretLine

            firstLine += visibleLines

            if (firstLine + visibleLines >= lineCount - 1) firstLine = lineCount - visibleLines

            textArea.firstLine = firstLine

            val caret = textArea.getLineStartOffset(min(textArea.lineCount - 1, line + visibleLines))
            if (select) textArea.select(textArea.markPosition, caret)
            else textArea.caretPosition = caret
        }
    }

    class NextWord(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            var caret = textArea.caretPosition
            val line = textArea.caretLine
            val lineStart = textArea.getLineStartOffset(line)
            caret -= lineStart

            val lineText = textArea.getLineText(textArea.caretLine)

            if (caret == lineText!!.length) {
                if (lineStart + caret == textArea.document!!.length) {
                    textArea.toolkit.beep()
                    return
                }
                caret++
            } else {
                val noWordSep = textArea.document!!.getProperty("noWordSep") as String
                caret = lineText.findWordEnd(caret, noWordSep)
            }

            if (select) textArea.select(textArea.markPosition, lineStart + caret)
            else textArea.caretPosition = lineStart + caret
        }
    }

    class Overwrite : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            textArea.isOverwriteEnabled = !textArea.isOverwriteEnabled
        }
    }

    class PrevChar(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            val caret = textArea.caretPosition
            if (caret == 0) {
                textArea.toolkit.beep()
                return
            }

            if (select) textArea.select(textArea.markPosition, caret - 1)
            else textArea.caretPosition = caret - 1
        }
    }

    class PrevLine(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            var caret = textArea.caretPosition
            val line = textArea.caretLine

            if (line == 0) {
                textArea.toolkit.beep()
                return
            }

            var magic = textArea.magicCaretPosition
            if (magic == -1) magic = textArea.offsetToX(line, caret - textArea.getLineStartOffset(line))

            caret = textArea.getLineStartOffset(line - 1) + textArea.xToOffset(line - 1, magic)
            if (select) textArea.select(textArea.markPosition, caret)
            else textArea.caretPosition = caret
            textArea.magicCaretPosition = magic
        }
    }

    class PrevPage(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            var firstLine = textArea.firstLine
            val visibleLines = textArea.visibleLines
            val line = textArea.caretLine

            if (firstLine < visibleLines) firstLine = visibleLines

            textArea.firstLine = firstLine - visibleLines

            val caret = textArea.getLineStartOffset(max(0, line - visibleLines))
            if (select) textArea.select(textArea.markPosition, caret)
            else textArea.caretPosition = caret
        }
    }

    class PrevWord(private val select: Boolean) : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            var caret = textArea.caretPosition
            val line = textArea.caretLine
            val lineStart = textArea.getLineStartOffset(line)
            caret -= lineStart

            val lineText = textArea.getLineText(textArea.caretLine)

            if (caret == 0) {
                if (lineStart == 0) {
                    textArea.toolkit.beep()
                    return
                }
                caret--
            } else {
                val noWordSep = textArea.document!!.getProperty("noWordSep") as String
                caret = lineText!!.findWordStart(caret, noWordSep)
            }

            if (select) textArea.select(textArea.markPosition, lineStart + caret)
            else textArea.caretPosition = lineStart + caret
        }
    }

    class Repeat : ActionListener, NonRecordable {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            textArea.inputHandler.isRepeatEnabled = true
            e.actionCommand?.let {
                textArea.inputHandler.repeatCount = it.toInt()
            }
        }
    }

    class ToggleRect : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            textArea.isSelectionRectangular = !textArea.isSelectionRectangular
        }
    }

    class InsertChar : ActionListener, NonRepeatable {
        override fun actionPerformed(e: ActionEvent) {
            val textArea = getTextArea(e)
            val str = e.actionCommand
            val repeatCount = textArea.inputHandler.repeatCount

            if (textArea.isEditable) {
                textArea.overwriteSetSelectedText(str.toString().repeat(max(0, repeatCount)))
            } else {
                textArea.toolkit.beep()
            }
        }
    }

    class ClipCopy : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            getTextArea(e).copy()
        }
    }

    class ClipPaste : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            getTextArea(e).paste()
        }
    }

    class ClipCut : ActionListener {
        override fun actionPerformed(e: ActionEvent) {
            getTextArea(e).cut()
        }
    }
}