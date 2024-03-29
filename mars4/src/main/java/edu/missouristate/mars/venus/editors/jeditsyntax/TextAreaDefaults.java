package edu.missouristate.mars.venus.editors.jeditsyntax;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.Settings;
import org.jetbrains.annotations.NotNull;

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
    public static @NotNull TextAreaDefaults getDefaults() {
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
