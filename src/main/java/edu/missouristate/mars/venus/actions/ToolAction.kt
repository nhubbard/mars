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
package edu.missouristate.mars.venus.actions

import edu.missouristate.mars.tools.MarsTool
import java.awt.event.ActionEvent
import javax.swing.AbstractAction

/**
 * Connects a MarsTool class (class that implements MarsTool interface) to
 * the Mars menu system by supplying the response to that tool's menu item
 * selection.
 *
 * @author Pete Sanderson
 * @version August 2005
 * @param toolClass Class object for the associated MarsTool subclass
 * @param toolName  Name of this tool, for the menu.
 */
class ToolAction(
    private val toolClass: Class<in MarsTool>, toolName: String?
) : AbstractAction(toolName, null) {
    /**
     * Response when tool's item selected from menu.  Invokes tool's action() method.
     *
     * @param e the ActionEvent that triggered this call
     */
    override fun actionPerformed(e: ActionEvent) {
        try {
            // An exception should not occur here because we got here only after
            // already successfully creating an instance from the same Class object
            // in ToolLoader's loadMarsTools() method.
            (toolClass.getDeclaredConstructor().newInstance() as MarsTool).action()
        } catch (ignored: Exception) { }
    }
}