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

import edu.missouristate.mars.UIGlobals
import java.io.File

/**
 * Used to store and return information on the status of the current assembly file that is being edited in the program.
 */
class FileStatus @JvmOverloads constructor(pathName: String? = null) {
    enum class StatusType(val rawValue: Int) {
        NO_FILE(0),
        NEW_NOT_EDITED(1),
        NEW_EDITED(2),
        NOT_EDITED(3),
        EDITED(4),
        RUNNABLE(5),
        RUNNING(6),
        TERMINATED(7),
        OPENING(8);

        companion object {
            @JvmStatic
            fun fromInt(rawValue: Int) = entries.firstOrNull { it.rawValue == rawValue } ?: NO_FILE
        }
    }

    companion object {
        @JvmStatic var status: StatusType = StatusType.NO_FILE
            set(value) {
                field = value
                UIGlobals.gui?.setMenuState(value)
            }

        @JvmStatic var isAssembled: Boolean = false
        @JvmStatic var isSaved: Boolean = false
        @JvmStatic var isEdited: Boolean = false
        @JvmStatic var name: String = ""
        @JvmStatic var file: File? = null

        @JvmStatic
        fun reset() {
            status = StatusType.NO_FILE
            name = ""
            isAssembled = false
            isSaved = false
            isEdited = false
            file = null
        }
    }

    init {
        file = pathName?.let { File(it) }
    }

    val isNew: Boolean
        get() = status == StatusType.NEW_NOT_EDITED || status == StatusType.NEW_EDITED

    val hasUnsavedEdits: Boolean
        get() = status == StatusType.NEW_EDITED || status == StatusType.EDITED

    fun setPathName(newPath: String) {
        file = File(newPath)
    }

    fun setPathName(parent: String, name: String) {
        file = File(parent, name)
    }

    @Deprecated(
        "Use StatusType and status variable instead. No automatic replacement is available.",
        ReplaceWith(""),
        DeprecationLevel.ERROR
    )
    fun set(status: Int) {
        Companion.status = StatusType.fromInt(status)
    }

    @Deprecated(
        "Move to StatusType and status variable instead. No automatic replacement is available.",
        ReplaceWith(""),
        DeprecationLevel.ERROR
    )
    fun get(): Int = status.rawValue

    @Deprecated(
        "Use file variable instead.",
        ReplaceWith("file?.path", "edu.missouristate.mars.venus.KFileStatus.Companion.file"),
        DeprecationLevel.ERROR
    )
    fun getPathName() = file?.path

    @Deprecated(
        "Use file variable instead.",
        ReplaceWith("file?.name", "edu.missouristate.mars.venus.KFileStatus.Companion.file"),
        DeprecationLevel.ERROR
    )
    fun getFileName() = file?.name

    @Deprecated(
        "Use file variable instead.",
        ReplaceWith("file?.parent", "edu.missouristate.mars.venus.KFileStatus.Companion.file"),
        DeprecationLevel.ERROR
    )
    fun getParent() = file?.parent

    @Deprecated(
        "This function is a no-op; remove all usages.",
        replaceWith = ReplaceWith(""),
        DeprecationLevel.ERROR
    )
    fun updateStaticFileStatus() {}
}