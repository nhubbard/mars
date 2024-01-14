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
@file:Suppress("UNCHECKED_CAST")

package edu.missouristate.mars.venus

import edu.missouristate.mars.tools.MarsTool
import edu.missouristate.mars.util.FilenameFinder.getFilenameList
import edu.missouristate.mars.venus.actions.ToolAction
import java.awt.event.KeyEvent
import java.lang.reflect.Modifier
import javax.swing.JMenu

/**
 * This class provides functionality to bring external Mars tools into the Mars
 * system by adding them to its Tools menu.  This permits anyone with knowledge
 * of the Mars public interfaces, in particular of the Memory and Register
 * classes, to write applications which can interact with a MIPS program
 * executing under Mars.  The execution is of course simulated.  The
 * private method for loading tool classes is adapted from Bret Barker's
 * GameServer class from the book "Developing Games In Java".
 *
 * @author Pete Sanderson with help from Bret Barker
 * @version August 2005
 */
class ToolLoader {
    /**
     * Called in VenusUI to build its Tools menu.  If there are no qualifying tools
     * or any problems accessing those tools, it returns null.  A qualifying tool
     * must be a class in the Tools package that implements MarsTool, must be compiled
     * into a .class file, and its .class file must be in the same Tools folder as
     * MarsTool.class.
     *
     * @return a Tools JMenu if qualifying tool classes are found, otherwise null
     */
    fun buildToolsMenu(): JMenu? {
        var menu: JMenu? = null
        val marsToolList = loadMarsTools()
        if (marsToolList.isNotEmpty()) {
            menu = JMenu(TOOLS_MENU_NAME)
            menu.mnemonic = KeyEvent.VK_T
            // traverse array list and build menu
            for ((marsToolClass, marsToolInstance) in marsToolList) menu.add(
                ToolAction(
                    marsToolClass,
                    marsToolInstance.toolName
                )
            )
        }
        return menu
    }

    /*
     *  Dynamically loads MarsTools into an ArrayList.  This method is adapted from
     *  the loadGameControllers() method in Bret Barker's GameServer class.
     *  Barker (bret@hypefiend.com) is co-author of the book "Developing Games
     *  in Java".  It was demo'ed to me by Otterbein student Chris Dieterle
     *  as part of his Spring 2005 independent study of implementing a networked
     *  multi-player game playing system.  Thanks Bret and Chris!
     *
     *  Bug Fix 25 Feb 06, DPS: method did not recognize tools folder if its
     *  absolute pathname contained one or more spaces (e.g. C:\Program Files\mars\tools).
     *  Problem was, class loader's getResource method returns a URL, in which spaces
     *  are replaced with "%20".  So I added a replaceAll() to change them back.
     *
     *  Enhanced 3 Oct 06, DPS: method did not work if running MARS from a JAR file.
     *  The array of files returned is null, but the File object contains the name
     *  of the JAR file (using toString, not getToolName).  Extract that name, open it
     *  as a ZipFile, get the ZipEntry enumeration, find the class files in the tools
     *  folder, then continue as before.
     */
    private fun loadMarsTools(): ArrayList<MarsToolClassAndInstance> {
        val toolList = arrayListOf<MarsToolClassAndInstance>()
        val candidates = getFilenameList(this.javaClass.classLoader, TOOLS_DIRECTORY_PATH, CLASS_EXTENSION)
        // Add any tools stored externally, as listed in Config.properties file.
        // This needs some work, because mars.Globals.getExternalTools() returns
        // whatever is in the properties file entry.  Since the class file will
        // not be located in the mars.tools folder, the loop below will not process
        // it correctly.  Not sure how to create a Class object given an absolute
        // pathname.
        val tools = hashMapOf<String, String>()
        for (file in candidates) {
            // Do not add class if already encountered (happens if run in MARS development directory)
            if (tools.containsKey(file)) continue else tools[file] = file
            if (file != MARSTOOL_INTERFACE) {
                try {
                    // grab the class, make sure it implements MarsTool, instantiate, add to the menu
                    val toolClassName = CLASS_PREFIX + file.substring(0, file.indexOf(CLASS_EXTENSION) - 1)
                    val clas = Class.forName(toolClassName)
                    if (!MarsTool::class.java.isAssignableFrom(clas) ||
                        Modifier.isAbstract(clas.modifiers) ||
                        Modifier.isInterface(clas.modifiers)
                    ) continue
                    toolList.add(MarsToolClassAndInstance(
                        clas as Class<in MarsTool?>,
                        clas.getDeclaredConstructor().newInstance() as MarsTool
                    ))
                } catch (e: Exception) {
                    println("Error instantiating MarsTool from file $file: $e")
                }
            }
        }
        return toolList
    }


    @JvmRecord
    private data class MarsToolClassAndInstance(val marsToolClass: Class<in MarsTool?>?, val marsToolInstance: MarsTool)

    companion object {
        private const val CLASS_PREFIX = "edu.missouristate.mars.tools."
        private const val TOOLS_DIRECTORY_PATH = "edu/missouristate/mars/tools"
        private const val TOOLS_MENU_NAME = "Tools"
        private const val MARSTOOL_INTERFACE = "MarsTool.class"
        private const val CLASS_EXTENSION = "class"
    }
}