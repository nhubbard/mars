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

package edu.missouristate.mars.venus.editor;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.Settings;

import javax.swing.*;
import java.awt.*;

/**
 * Encapsulates default settings for a text area. This can be passed
 * to the constructor once the necessary fields have been filled out.
 * The advantage of doing this over calling lots of set() methods after
 * creating the text area is that this method is faster.
 */
public class TextAreaDefaults {

    public InputHandler inputHandler;
    public SyntaxDocument document;
    public boolean editable;

    public boolean caretVisible;
    public boolean caretBlinks;
    public boolean blockCaret;
    public int caretBlinkRate;
    public int electricScroll;
    public int tabSize;

    public int cols;
    public int rows;
    public SyntaxStyle[] styles;
    public Color caretColor;
    public Color selectionColor;
    public Color lineHighlightColor;
    public boolean lineHighlight;
    public Color bracketHighlightColor;
    public boolean bracketHighlight;
    public Color eolMarkerColor;
    public boolean eolMarkers;
    public boolean paintInvalid;

    public JPopupMenu popup;

    /**
     * Returns a new TextAreaDefaults object with the default values filled
     * in.
     */
    public static TextAreaDefaults getDefaults() {
        TextAreaDefaults DEFAULTS = new TextAreaDefaults();

        DEFAULTS.inputHandler = new DefaultInputHandler();
        DEFAULTS.inputHandler.addDefaultKeyBindings();
        DEFAULTS.editable = true;

        DEFAULTS.blockCaret = false;
        DEFAULTS.caretVisible = true;
        DEFAULTS.caretBlinks = (Globals.getSettings().getCaretBlinkRate() != 0);
        DEFAULTS.caretBlinkRate = Globals.getSettings().getCaretBlinkRate();
        DEFAULTS.tabSize = Globals.getSettings().getEditorTabSize();
        DEFAULTS.electricScroll = 0;// was 3.  Will begin scrolling when cursor is this many lines from the edge.

        DEFAULTS.cols = 80;
        DEFAULTS.rows = 25;
        DEFAULTS.styles = SyntaxUtilities.getCurrentSyntaxStyles(); // was getDefaultSyntaxStyles()
        DEFAULTS.caretColor = Color.black; // Color.red;
        DEFAULTS.selectionColor = new Color(0xccccff);
        DEFAULTS.lineHighlightColor = new Color(0xeeeeee);//0xe0e0e0);
        DEFAULTS.lineHighlight = Globals.getSettings().getBooleanSetting(Settings.EDITOR_CURRENT_LINE_HIGHLIGHTING);
        DEFAULTS.bracketHighlightColor = Color.black;
        DEFAULTS.bracketHighlight = false; // assembly language doesn't need this.
        DEFAULTS.eolMarkerColor = new Color(0x009999);
        DEFAULTS.eolMarkers = false; // true;
        DEFAULTS.paintInvalid = false; //true;
        DEFAULTS.document = new SyntaxDocument();
        return DEFAULTS;
    }
}
