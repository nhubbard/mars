package edu.missouristate.mars.venus;

import edu.missouristate.mars.tools.*;

import javax.swing.*;
import java.awt.event.*;

/**
 * Connects a MarsTool class (class that implements MarsTool interface) to
 * the Mars menu system by supplying the response to that tool's menu item
 * selection.
 *
 * @author Pete Sanderson
 * @version August 2005
 */

public class ToolAction extends AbstractAction {
    private final Class<? super MarsTool> toolClass; //MarsTool tool;

    /**
     * Simple constructor.
     *
     * @param toolClass Class object for the associated MarsTool subclass
     * @param toolName  Name of this tool, for the menu.
     */
    public ToolAction(Class<? super MarsTool> toolClass, String toolName) {
        super(toolName, null);
        this.toolClass = toolClass;
    }


    /**
     * Response when tool's item selected from menu.  Invokes tool's action() method.
     *
     * @param e the ActionEvent that triggered this call
     */
    public void actionPerformed(ActionEvent e) {
        try {
            // An exception should not occur here because we got here only after
            // already successfully creating an instance from the same Class object
            // in ToolLoader's loadMarsTools() method.
            ((MarsTool) this.toolClass.getDeclaredConstructor().newInstance()).action();
        } catch (Exception ignored) {
        }
    }
}