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

package edu.missouristate.mars.assembler;

/**
 * Represents a MIPS program identifier to be stored in the symbol table.
 *
 * @author Jason Bumgarner, Jason Shrewsbury
 * @version June 2003
 **/
public class Symbol {
    private final String name;
    private int address;
    private final boolean data; // boolean true if data symbol false if text symbol.
    public static final boolean TEXT_SYMBOL = false;
    public static final boolean DATA_SYMBOL = true;

    /**
     * Basic constructor, creates a symbol object.
     *
     * @param name    The name of the Symbol.
     * @param address The memroy address that the Symbol refers to.
     * @param data    The type of Symbol that it is.
     **/
    public Symbol(String name, int address, boolean data) {
        this.name = name;
        this.address = address;
        this.data = data;
    }

    /**
     * Returns the address of the the Symbol.
     *
     * @return The address of the Symbol.
     **/
    public int getAddress() {
        return this.address;
    }

    /**
     * Returns the label of the the Symbol.
     *
     * @return The label of the Symbol.
     **/
    public String getName() {
        return this.name;
    }

    /**
     * Finds the type of symbol, text or data.
     *
     * @return The type of the data.
     **/
    public boolean getType() {
        return this.data;
    }

    /**
     * Sets (replaces) the address of the the Symbol.
     *
     * @param newAddress The revised address of the Symbol.
     **/
    public void setAddress(int newAddress) {
        this.address = newAddress;
    }
}