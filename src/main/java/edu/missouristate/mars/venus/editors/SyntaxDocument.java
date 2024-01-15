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

package edu.missouristate.mars.venus.editors;

import edu.missouristate.mars.venus.editors.tokenmarker.TokenMarker;

import javax.swing.event.DocumentEvent;
import javax.swing.text.BadLocationException;
import javax.swing.text.Element;
import javax.swing.text.PlainDocument;
import javax.swing.text.Segment;
import javax.swing.undo.UndoableEdit;

/**
 * A document implementation that can be tokenized by the syntax highlighting
 * system.
 *
 * @author Slava Pestov
 * @version $Id: SyntaxDocument.java,v 1.14 1999/12/13 03:40:30 sp Exp $
 */
@SuppressWarnings("EmptyMethod")
public class SyntaxDocument extends PlainDocument {
    /**
     * Returns the token marker that is to be used to split lines
     * of this document up into tokens. May return null if this
     * document is not to be colorized.
     */
    public TokenMarker getTokenMarker() {
        return tokenMarker;
    }

    /**
     * Sets the token marker that is to be used to split lines of
     * this document up into tokens. May throw an exception if
     * this is not supported for this type of document.
     *
     * @param tm The new token marker
     */
    public void setTokenMarker(TokenMarker tm) {
        tokenMarker = tm;
        if (tm == null)
            return;
        tokenMarker.insertLines(0, getDefaultRootElement()
                .getElementCount());
        tokenizeLines();
    }

    /**
     * Reparses the document, by passing all lines to the token
     * marker. This should be called after the document is first
     * loaded.
     */
    public void tokenizeLines() {
        tokenizeLines(0, getDefaultRootElement().getElementCount());
    }

    /**
     * Reparses the document, by passing the specified lines to the
     * token marker. This should be called after a large quantity of
     * text is first inserted.
     *
     * @param start The first line to parse
     * @param len   The number of lines, after the first one to parse
     */
    public void tokenizeLines(int start, int len) {
        if (tokenMarker == null || !tokenMarker.supportsMultilineTokens())
            return;

        Segment lineSegment = new Segment();
        Element map = getDefaultRootElement();

        len += start;

        try {
            for (int i = start; i < len; i++) {
                Element lineElement = map.getElement(i);
                int lineStart = lineElement.getStartOffset();
                getText(lineStart, lineElement.getEndOffset()
                        - lineStart - 1, lineSegment);
                tokenMarker.markTokens(lineSegment, i);
            }
        } catch (BadLocationException bl) {
            bl.printStackTrace();
        }
    }

    /**
     * Starts a compound edit that can be undone in one operation.
     * Subclasses that implement undo should override this method;
     * this class has no undo functionality so this method is
     * empty.
     */
    public void beginCompoundEdit() {
    }

    /**
     * Ends a compound edit that can be undone in one operation.
     * Subclasses that implement undo should override this method;
     * this class has no undo functionality so this method is
     * empty.
     */
    public void endCompoundEdit() {
    }

    /**
     * Adds an undoable edit to this document's undo list. The edit
     * should be ignored if something is currently being undone.
     *
     * @param edit The undoable edit
     * @since jEdit 2.2pre1
     */
    public void addUndoableEdit(UndoableEdit edit) {
    }

    // protected members
    protected TokenMarker tokenMarker;

    /**
     * We overwrite this method to update the token marker
     * state immediately so that any event listeners get a
     * consistent token marker.
     */
    protected void fireInsertUpdate(DocumentEvent evt) {
        if (tokenMarker != null) {
            DocumentEvent.ElementChange ch = evt.getChange(
                    getDefaultRootElement());
            if (ch != null) {
                tokenMarker.insertLines(ch.getIndex() + 1,
                        ch.getChildrenAdded().length -
                                ch.getChildrenRemoved().length);
            }
        }

        super.fireInsertUpdate(evt);
    }

    /**
     * We overwrite this method to update the token marker
     * state immediately so that any event listeners get a
     * consistent token marker.
     */
    protected void fireRemoveUpdate(DocumentEvent evt) {
        if (tokenMarker != null) {
            DocumentEvent.ElementChange ch = evt.getChange(
                    getDefaultRootElement());
            if (ch != null) {
                tokenMarker.deleteLines(ch.getIndex() + 1,
                        ch.getChildrenRemoved().length -
                                ch.getChildrenAdded().length);
            }
        }

        super.fireRemoveUpdate(evt);
    }
}
