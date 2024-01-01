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

package edu.missouristate.mars.venus.editors.jeditsyntax;

import edu.missouristate.mars.util.Binary;

import java.awt.*;

/**
 * A simple text style class. It can specify the color, italic flag,
 * and bold flag of a run of text.
 *
 * @author Slava Pestov
 * @version $Id: SyntaxStyle.java,v 1.6 1999/12/13 03:40:30 sp Exp $
 */
public class SyntaxStyle {
    /**
     * Creates a new SyntaxStyle.
     *
     * @param color  The text color
     * @param italic True if the text should be italics
     * @param bold   True if the text should be bold
     */
    public SyntaxStyle(Color color, boolean italic, boolean bold) {
        this.color = color;
        this.italic = italic;
        this.bold = bold;
    }

    /**
     * Returns the color specified in this style.
     */
    public Color getColor() {
        return color;
    }

    /**
     * Returns the color coded as Stringified 32-bit hex with
     * Red in bits 16-23, Green in bits 8-15, Blue in bits 0-7
     * e.g. "0x00FF3366" where Red is FF, Green is 33, Blue is 66.
     * This is used by Settings initialization to avoid direct
     * use of Color class.  Long story. DPS 13-May-2010
     *
     * @return String containing hex-coded color value.
     */

    public String getColorAsHexString() {
        return Binary.intToHexString(color.getRed() << 16 | color.getGreen() << 8 | color.getBlue());
    }

    /**
     * Returns true if no font styles are enabled.
     */
    public boolean isPlain() {
        return !(bold || italic);
    }

    /**
     * Returns true if italics is enabled for this style.
     */
    public boolean isItalic() {
        return italic;
    }

    /**
     * Returns true if boldface is enabled for this style.
     */
    public boolean isBold() {
        return bold;
    }

    /**
     * Returns the specified font, but with the style's bold and
     * italic flags applied.
     */
    public Font getStyledFont(Font font) {
        if (font == null)
            throw new NullPointerException("font param must not"
                    + " be null");
        if (font.equals(lastFont))
            return lastStyledFont;
        lastFont = font;
        lastStyledFont = new Font(font.getFamily(),
                (bold ? Font.BOLD : Font.PLAIN)
                        | (italic ? Font.ITALIC : Font.PLAIN),
                font.getSize());
        return lastStyledFont;
    }

    /**
     * Returns the font metrics for the styled font.
     */
    public FontMetrics getFontMetrics(Font font) {
        if (font == null)
            throw new NullPointerException("font param must not"
                    + " be null");
        if (font.equals(lastFont) && fontMetrics != null)
            return fontMetrics;
        lastFont = font;
        lastStyledFont = new Font(font.getFamily(),
                (bold ? Font.BOLD : 0)
                        | (italic ? Font.ITALIC : 0),
                font.getSize());
        fontMetrics = Toolkit.getDefaultToolkit().getFontMetrics(
                lastStyledFont);
        return fontMetrics;
    }


    /**
     * Sets the foreground color and font of the specified graphics
     * context to that specified in this style.
     *
     * @param gfx  The graphics context
     * @param font The font to add the styles to
     */
    public void setGraphicsFlags(Graphics gfx, Font font) {
        Font _font = getStyledFont(font);
        gfx.setFont(_font);
        gfx.setColor(color);
    }

    /**
     * Returns a string representation of this object.
     */
    public String toString() {
        return getClass().getName() + "[color=" + color +
                (italic ? ",italic" : "") +
                (bold ? ",bold" : "") + "]";
    }

    // private members
    private final Color color;
    private final boolean italic;
    private final boolean bold;
    private Font lastFont;
    private Font lastStyledFont;
    private FontMetrics fontMetrics;
}
