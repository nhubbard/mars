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
import edu.missouristate.mars.venus.editor.marker.Token;

import javax.swing.*;
import javax.swing.text.Segment;
import javax.swing.text.TabExpander;
import javax.swing.text.Utilities;
import java.awt.*;
import java.awt.event.MouseEvent;

/**
 * Class with several utility functions used by jEdit's syntax colorizing
 * subsystem.
 *
 * @author Slava Pestov
 * @version $Id: SyntaxUtilities.java,v 1.9 1999/12/13 03:40:30 sp Exp $
 */
public class SyntaxUtilities {
    /**
     * Checks if a subregion of a <code>Segment</code> is equal to a
     * string.
     *
     * @param ignoreCase True if case should be ignored, false otherwise
     * @param text       The segment
     * @param offset     The offset into the segment
     * @param match      The string to match
     */
    public static boolean regionMatches(boolean ignoreCase, Segment text,
                                        int offset, String match) {
        int length = offset + match.length();
        char[] textArray = text.array;
        if (length > text.offset + text.count)
            return false;
        for (int i = offset, j = 0; i < length; i++, j++) {
            char c1 = textArray[i];
            char c2 = match.charAt(j);
            if (ignoreCase) {
                c1 = Character.toUpperCase(c1);
                c2 = Character.toUpperCase(c2);
            }
            if (c1 != c2)
                return false;
        }
        return true;
    }

    /**
     * Checks if a subregion of a <code>Segment</code> is equal to a
     * character array.
     *
     * @param ignoreCase True if case should be ignored, false otherwise
     * @param text       The segment
     * @param offset     The offset into the segment
     * @param match      The character array to match
     */
    public static boolean regionMatches(boolean ignoreCase, Segment text,
                                        int offset, char[] match) {
        int length = offset + match.length;
        char[] textArray = text.array;
        if (length > text.offset + text.count)
            return false;
        for (int i = offset, j = 0; i < length; i++, j++) {
            char c1 = textArray[i];
            char c2 = match[j];
            if (ignoreCase) {
                c1 = Character.toUpperCase(c1);
                c2 = Character.toUpperCase(c2);
            }
            if (c1 != c2)
                return false;
        }
        return true;
    }

    /**
     * Returns the default style table. This can be passed to the
     * <code>setStyles()</code> method of <code>SyntaxDocument</code>
     * to use the default syntax styles.
     */
    public static SyntaxStyle[] getDefaultSyntaxStyles() {
        SyntaxStyle[] styles = new SyntaxStyle[Token.Type.getEntries().size()];

        // SyntaxStyle constructor params: color, italic?, bold?
        // All need to be assigned even if not used by language (no gaps in array)
        styles[Token.Type.NULL.rawValue] = new SyntaxStyle(Color.black, false, false);
        styles[Token.Type.COMMENT1.rawValue] = new SyntaxStyle(new Color(0x00CC33), true, false);//(Color.black,true,false);
        styles[Token.Type.COMMENT2.rawValue] = new SyntaxStyle(new Color(0x990033), true, false);
        styles[Token.Type.KEYWORD1.rawValue] = new SyntaxStyle(Color.blue, false, false);//(Color.black,false,true);
        styles[Token.Type.KEYWORD2.rawValue] = new SyntaxStyle(Color.magenta, false, false);
        styles[Token.Type.KEYWORD3.rawValue] = new SyntaxStyle(Color.red, false, false);//(new Color(0x009600),false,false);
        styles[Token.Type.LITERAL1.rawValue] = new SyntaxStyle(new Color(0x00CC33), false, false);//(new Color(0x650099),false,false);
        styles[Token.Type.LITERAL2.rawValue] = new SyntaxStyle(new Color(0x00CC33), false, false);//(new Color(0x650099),false,true);
        styles[Token.Type.LABEL.rawValue] = new SyntaxStyle(Color.black, true, false);//(new Color(0x990033),false,true);
        styles[Token.Type.OPERATOR.rawValue] = new SyntaxStyle(Color.black, false, true);
        styles[Token.Type.INVALID.rawValue] = new SyntaxStyle(Color.red, false, false);
        styles[Token.Type.MACRO_ARG.rawValue] = new SyntaxStyle(new Color(150, 150, 0), false, false);
        return styles;
    }

    /**
     * Returns the CURRENT style table. This can be passed to the
     * <code>setStyles()</code> method of <code>SyntaxDocument</code>
     * to use the current syntax styles.  If changes have been made
     * via MARS Settings menu, the current settings will not be the
     * same as the default settings.
     */
    public static SyntaxStyle[] getCurrentSyntaxStyles() {
        SyntaxStyle[] styles = new SyntaxStyle[Token.Type.getEntries().size()];

        styles[Token.Type.NULL.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.NULL.rawValue);
        styles[Token.Type.COMMENT1.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.COMMENT1.rawValue);
        styles[Token.Type.COMMENT2.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.COMMENT2.rawValue);
        styles[Token.Type.KEYWORD1.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.KEYWORD1.rawValue);
        styles[Token.Type.KEYWORD2.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.KEYWORD2.rawValue);
        styles[Token.Type.KEYWORD3.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.KEYWORD3.rawValue);
        styles[Token.Type.LITERAL1.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.LITERAL1.rawValue);
        styles[Token.Type.LITERAL2.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.LITERAL2.rawValue);
        styles[Token.Type.LABEL.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.LABEL.rawValue);
        styles[Token.Type.OPERATOR.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.OPERATOR.rawValue);
        styles[Token.Type.INVALID.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.INVALID.rawValue);
        styles[Token.Type.MACRO_ARG.rawValue] = Globals.getSettings().getEditorSyntaxStyleByPosition(Token.Type.MACRO_ARG.rawValue);
        return styles;
    }

    public static boolean popupShowing = false;
    public static Popup popup;

    /**
     * Paints the specified line onto the graphics context. Note that this
     * method will mess with the offset and count values of the segment.
     *
     * @param line     The line segment
     * @param tokens   The token list for the line
     * @param styles   The syntax style list
     * @param expander The tab expander used to determine tab stops. May
     *                 be null
     * @param gfx      The graphics context
     * @param x        The x co-ordinate
     * @param y        The y co-ordinate
     * @return The x co-ordinate, plus the width of the painted string
     */
    public static int paintSyntaxLine(Segment line, Token tokens,
                                      SyntaxStyle[] styles, TabExpander expander, Graphics gfx,
                                      int x, int y) {
        Font defaultFont = gfx.getFont();
        Color defaultColor = gfx.getColor();

        int offset = 0;
        for (; ; ) {
            Token.Type id = tokens.getType();
            if (id == Token.Type.END)
                break;

            int length = tokens.getLength();
            if (id == Token.Type.NULL) {
                if (!defaultColor.equals(gfx.getColor()))
                    gfx.setColor(defaultColor);
                if (!defaultFont.equals(gfx.getFont()))
                    gfx.setFont(defaultFont);
            } else
                styles[id.rawValue].setGraphicsFlags(gfx, defaultFont);
            line.count = length;

            if (id == Token.Type.KEYWORD1) {
                //System.out.println("Instruction: "+line);
                // System.out.println("creating popup");
                //                   JComponent paintArea = (JComponent) expander;
                //                   JToolTip tip = paintArea.createToolTip();
                //                   tip.setTipText("Instruction: "+line);
                //                   Point screenLocation = paintArea.getLocationOnScreen();
                //                   PopupFactory popupFactory = PopupFactory.getSharedInstance();
                //                   popup = popupFactory.getPopup(paintArea, tip, screenLocation.x + x, screenLocation.y + y);
                //                   popupShowing = true;
                //                   popup.show();
                //                   int delay = 200; //milliseconds
                //                   ActionListener taskPerformer =
                //                       new ActionListener() {
                //                          public void actionPerformed(ActionEvent evt) {
                //                            //popupShowing = false;
                //                            if (popup!= null) {
                //                               popup.hide();
                //                            }
                //                         }
                //                      };
                //                   Timer popupTimer = new Timer(delay, taskPerformer);
                //                   popupTimer.setRepeats(false);
                //                   popupTimer.start();

                // ToolTipManager.sharedInstance().mouseMoved(
                //	   new MouseEvent((Component)expander, MouseEvent.MOUSE_MOVED, new java.util.Date().getTime(), 0, x, y, 0, false));
                //    new InstructionMouseEvent((Component)expander, x, y, line));
            }

            x = Utilities.drawTabbedText(line, x, y, gfx, expander, 0);
            line.offset += length;
            offset += length;

            tokens = tokens.getNext();
        }

        return x;
    }

    // private members
    private SyntaxUtilities() {
    }
}

class InstructionMouseEvent extends MouseEvent {
    private final Segment line;

    public InstructionMouseEvent(Component component, int x, int y, Segment line) {
        super(component, MouseEvent.MOUSE_MOVED, new java.util.Date().getTime(), 0, x, y, 0, false);
        System.out.println("Create InstructionMouseEvent " + x + " " + y + " " + line);
        this.line = line;
    }

    public Segment getLine() {
        return this.line;
    }
}
