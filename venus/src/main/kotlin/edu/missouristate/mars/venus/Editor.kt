/*
 * Copyright (c) 2003-2024, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * Created by Pete Sanderson (psanderson@otterbein.edu) and Kenneth Vollmar (kenvollmar@missouristate.edu)
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
 *
 * ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
 *
 * Copyright (c) 2017-2024, Niklas Persson
 * Copyright (c) 2024-present, Nicholas Hubbard
 *
 * The IntelliJ plugin is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for specific
 * language governing permissions and limitations under the License.
 */

package edu.missouristate.mars.venus

import edu.missouristate.mars.venus.FileStatus.*
import edu.missouristate.mars.venus.panes.EditTabbedPane
import java.io.File

class Editor(private val mainUI: VenusUI) {
    companion object {
        const val MIN_TAB_SIZE = 1
        const val MAX_TAB_SIZE = 32
        const val MIN_BLINK_RATE = 0    // No flashing
        const val MAX_BLINK_RATE = 1000 // Once per second
    }

    lateinit var editTabbedPane: EditTabbedPane
    private val mainUIBaseTitle: String
    private var newUsageCount: Int

    private val defaultOpenDirectory: String = System.getProperty("user.dir")
    var currentOpenDirectory: String = defaultOpenDirectory
        set(value) {
            val file = File(value)
            field = if (!file.exists() || !file.isDirectory) defaultOpenDirectory else value
        }

    private val defaultSaveDirectory: String = System.getProperty("user.dir")
    var currentSaveDirectory: String = defaultSaveDirectory
        set(value) {
            val file = File(value)
            field = if (!file.exists() || !file.isDirectory) defaultSaveDirectory else value
        }

    init {
        FileStatus.reset()
        mainUIBaseTitle = mainUI.title
        newUsageCount = 0
    }

    fun getNextDefaultFilename(): String {
        newUsageCount++
        return "mips${newUsageCount}.asm"
    }

    /**
     * Places name of file currently being edited into its edit tab and
     * the application's title bar.  The edit tab will contain only
     * the filename, the title bar will contain full pathname.
     * If file has been modified since created, opened or saved, as
     * indicated by value of the status parameter, the name and path
     * will be followed with an '*'.  If newly-created file has not
     * yet been saved, the title bar will show (temporary) file name
     * but not path.
     *
     * @param path   Full pathname for file
     * @param name   Name of file (last component of path)
     * @param status Edit status of file.  See FileStatus static constants.
     */
    fun setTitle(path: String?, name: String?, status: StatusType) =
        if (status == StatusType.NO_FILE || name.isNullOrEmpty()) mainUI.title = mainUIBaseTitle
        else {
            val edited = if ((status == StatusType.NEW_EDITED || status == StatusType.EDITED)) "*" else " "
            val titleName =
                if (status == StatusType.NEW_EDITED || status == StatusType.NEW_NOT_EDITED) name else path!!
            mainUI.title = "$titleName$edited - $mainUIBaseTitle"
            editTabbedPane.setTitleAt(editTabbedPane.selectedIndex, name + edited)
        }

    /**
     * Perform "new" operation to create an empty tab.
     */
    fun newFile() = editTabbedPane.newFile()

    /**
     * Perform "close" operation on current tab's file.
     *
     * @return true if succeeded, else false.
     */
    fun close(): Boolean = editTabbedPane.closeCurrentFile()

    /**
     * Close all currently open files.
     *
     * @return true if succeeded, else false.
     */
    fun closeAll(): Boolean = editTabbedPane.closeAllFiles()

    /**
     * Perform "save" operation on current tab's file.
     *
     * @return true if succeeded, else false.
     */
    fun save(): Boolean = editTabbedPane.saveCurrentFile()

    /**
     * Perform "save as" operation on current tab's file.
     *
     * @return true if succeeded, else false.
     */
    fun saveAs(): Boolean = editTabbedPane.saveAsCurrentFile()

    /**
     * Perform save operation on all open files (tabs).
     *
     * @return true if succeeded, else false.
     */
    fun saveAll(): Boolean = editTabbedPane.saveAllFiles()

    /**
     * Open file in a new tab.
     *
     * @return true if succeeded, else false.
     */
    fun open(): Boolean = editTabbedPane.openFile()

    /**
     * Called by several of the Action objects when there is potential
     * loss of editing changes.  Specifically: if there is a current
     * file open for editing and its modify flag is true, then give user
     * a dialog box with choice to save, discard edits, or cancel and
     * carry out the decision.  This applies to File->New, File->Open,
     * File->Close, and File->Exit.
     *
     * @return false means user selected Cancel so caller should do that.
     * Return of true means caller can proceed (edits were saved or discarded).
     */
    fun editsSavedOrAbandoned(): Boolean = editTabbedPane.editsSavedOrAbandoned()
}