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

package edu.missouristate.mars.mips.instructions.syscalls;

import org.jetbrains.annotations.NotNull;

/**
 * Represents User override of default syscall number assignment.
 * Such overrides are specified in the config.txt file read when
 * MARS starts up.
 */

public class SyscallNumberOverride {
    private final String serviceName;
    private int newServiceNumber;

    /**
     * Constructor is called with two strings: service name and desired
     * number.  Will throw an exception is number is malformed, but does
     * not check validity of the service name or number.
     *
     * @param serviceName a String containing syscall service mnemonic.
     * @param value       a String containing its reassigned syscall service number.
     *                    If this number is previously assigned to a different syscall which does not
     *                    also receive a new number, then an error for duplicate numbers will
     *                    be issued at MARS launch.
     */

    public SyscallNumberOverride(String serviceName, @NotNull String value) {
        this.serviceName = serviceName;
        try {
            this.newServiceNumber = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            System.out.println("Error processing Syscall number override: '" + value.trim() + "' is not a valid integer");
            System.exit(0);
        }
    }


    /**
     * Get the service name as a String.
     *
     * @return the service name
     */
    public String getName() {
        return serviceName;
    }

    /**
     * Get the new service number as an int.
     *
     * @return the service number
     */
    public int getNumber() {
        return newServiceNumber;
    }

}
 
   	
   	
