package edu.missouristate.mars.venus.editors;

import javax.swing.text.*;
import javax.swing.undo.*;
import java.awt.*;

/**
 * Specifies capabilities that any test editor used in MARS must have.
 */

public interface MARSTextEditingArea {

    // Used by Find/Replace
    int TEXT_NOT_FOUND = 0;
    int TEXT_FOUND = 1;
    int TEXT_REPLACED_FOUND_NEXT = 2;
    int TEXT_REPLACED_NOT_FOUND_NEXT = 3;


    void copy();

    void cut();

    int doFindText(String find, boolean caseSensitive);

    int doReplace(String find, String replace, boolean caseSensitive);

    int doReplaceAll(String find, String replace, boolean caseSensitive);

    int getCaretPosition();

    Document getDocument();

    String getSelectedText();

    int getSelectionEnd();

    int getSelectionStart();

    void select(int selectionStart, int selectionEnd);

    void selectAll();

    String getText();

    UndoManager getUndoManager();

    void paste();

    void replaceSelection(String str);

    void setCaretPosition(int position);

    void setEditable(boolean editable);

    void setSelectionEnd(int pos);

    void setSelectionStart(int pos);

    void setText(String text);

    void setFont(Font f);

    Font getFont();

    boolean requestFocusInWindow();

    FontMetrics getFontMetrics(Font f);

    void setBackground(Color c);

    void setEnabled(boolean enabled);

    void grabFocus();

    void redo();

    void revalidate();

    void setSourceCode(String code, boolean editable);

    void setCaretVisible(boolean vis);

    void setSelectionVisible(boolean vis);

    void undo();

    void discardAllUndoableEdits();

    void setLineHighlightEnabled(boolean highlight);

    void setCaretBlinkRate(int rate);

    void setTabSize(int chars);

    void updateSyntaxStyles();

    Component getOuterComponent();
}