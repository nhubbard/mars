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

package edu.missouristate.mars.mips.hardware;

import org.jetbrains.annotations.NotNull;

/**
 * Object provided to Observers of runtime access to MIPS memory or registers.
 * The access types READ and WRITE defined here; use subclasses defined for
 * MemoryAccessNotice and RegisterAccessNotice.  This is abstract class.
 *
 * @author Pete Sanderson
 * @version July 2005
 */


public abstract class AccessNotice {
    /**
     * Indicates the purpose of access was to read.
     */
    public static final int READ = 0;
    /**
     * Indicates the purpose of access was to write.
     */
    public static final int WRITE = 1;

    private final int accessType;
    private final @NotNull Thread thread;

    protected AccessNotice(int type) {
        if (type != READ && type != WRITE) {
            throw new IllegalArgumentException();
        }
        accessType = type;
        thread = Thread.currentThread();
    }

    /**
     * Get the access type: READ or WRITE.
     *
     * @return Access type, either {@code AccessNotice.READ} or {@code AccessNotice.WRITE}
     */
    public int getAccessType() {
        return accessType;
    }

    /**
     * Get reference to the thread that created this notice
     *
     * @return Return reference to the thread that created this notice.
     */
    public @NotNull Thread getThread() {
        return thread;
    }

    /**
     * Query whether the access originated from MARS GUI (AWT event queue)
     *
     * @return true if this access originated from MARS GUI, false otherwise
     */
    // 'A' is the first character of the main AWT event queue thread name.
    // "AWT-EventQueue-0"
    public boolean accessIsFromGUI() {
        return thread.getName().startsWith("AWT");
    }

    /**
     * Query whether the access originated from executing MIPS program
     *
     * @return true if this access originated from executing MIPS program, false otherwise
     */
    // Thread to execute the MIPS program is instantiated in SwingWorker.java.
    // There it is given the name "MIPS" to replace the default "Thread-x".
    public boolean accessIsFromMIPS() {
        return thread.getName().startsWith("MIPS");
    }

}