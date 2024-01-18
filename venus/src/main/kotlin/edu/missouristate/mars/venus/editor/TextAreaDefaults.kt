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
import edu.missouristate.mars.CoreSettings
import java.awt.Color
import javax.swing.JPopupMenu

class TextAreaDefaults {
    companion object {
        @JvmStatic
        fun getDefaults(): TextAreaDefaults = TextAreaDefaults().apply {

            inputHandler = DefaultInputHandler()
            inputHandler.addDefaultKeyBindings()
            editable = true

            blockCaret = false
            caretVisible = true
            caretBlinks = Globals.settings.getCaretBlinkRate() != 0
            caretBlinkRate = Globals.settings.getCaretBlinkRate()
            tabSize = Globals.settings.getEditorTabSize()
            electricScroll = 0

            cols = 80
            rows = 25
            styles = SyntaxUtilities.getCurrentSyntaxStyles()
            caretColor = Color.black
            selectionColor = Color(0xccccff)
            lineHighlightColor = Color(0xeeeeee)
            lineHighlight = Globals.settings.getBooleanSetting(CoreSettings.EDITOR_CURRENT_LINE_HIGHLIGHTING)
            bracketHighlightColor = Color.black
            bracketHighlight = false
            eolMarkerColor = Color(0x009999)
            eolMarkers = false
            paintInvalid = false
            document = SyntaxDocument()
        }
    }

    lateinit var inputHandler: InputHandler
    lateinit var document: SyntaxDocument
    @JvmField var editable: Boolean = true
    @JvmField var caretVisible: Boolean = true
    @JvmField var caretBlinks: Boolean = Globals.settings.getCaretBlinkRate() != 0
    @JvmField var blockCaret: Boolean = false
    @JvmField var caretBlinkRate: Int = Globals.settings.getCaretBlinkRate()
    @JvmField var electricScroll: Int = 0
    @JvmField var tabSize: Int = Globals.settings.getEditorTabSize()
    @JvmField var cols: Int = 80
    @JvmField var rows: Int = 25
    lateinit var styles: Array<SyntaxStyle>
    lateinit var caretColor: Color
    lateinit var selectionColor: Color
    lateinit var lineHighlightColor: Color
    @JvmField var lineHighlight: Boolean = Globals.settings.getBooleanSetting(CoreSettings.EDITOR_CURRENT_LINE_HIGHLIGHTING)
    lateinit var bracketHighlightColor: Color
    @JvmField var bracketHighlight: Boolean = false
    lateinit var eolMarkerColor: Color
    @JvmField var eolMarkers: Boolean = false
    @JvmField var paintInvalid: Boolean = false
    lateinit var popup: JPopupMenu
}