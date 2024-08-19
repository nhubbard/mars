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
 * Object provided to Observers of runtime access to MIPS memory.
 * Observer can get the access type (R/W), address and length in bytes (4,2,1).
 *
 * @author Pete Sanderson
 * @version July 2005
 */

public class MemoryAccessNotice extends AccessNotice {
    private final int address;
    private final int length;
    private final int value;

    /**
     * Constructor will be called only within this package, so assume
     * address and length are in valid ranges.
     */
    MemoryAccessNotice(int type, int address, int length, int value) {
        super(type);
        this.address = address;
        this.length = length;
        this.value = value;
    }

    /**
     * Constructor will be called only within this package, so assume
     * address is in valid range.
     */
    public MemoryAccessNotice(int type, int address, int value) {
        super(type);
        this.address = address;
        this.length = Memory.WORD_LENGTH_BYTES;
        this.value = value;
    }

    /**
     * Fetch the memory address that was accessed.
     */
    public int getAddress() {
        return address;
    }

    /**
     * Fetch the length in bytes of the access operation (4,2,1).
     */
    public int getLength() {
        return length;
    }

    /**
     * Fetch the value of the access operation (the value read or written).
     */
    public int getValue() {
        return value;
    }

    /**
     * String representation indicates access type, address and length in bytes
     */
    public @NotNull String toString() {
        return ((this.getAccessType() == AccessNotice.READ) ? "R " : "W ") +
                "Mem " + address + " " + length + "B = " + value;
    }
}