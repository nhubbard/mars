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

package edu.missouristate.mars.mips.instructions;

import edu.missouristate.mars.Globals;
import edu.missouristate.mars.mips.instructions.syscalls.Syscall;
import edu.missouristate.mars.mips.instructions.syscalls.SyscallNumberOverride;
import edu.missouristate.mars.util.FilenameFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;

/****************************************************************************/
/* This class provides functionality to bring external Syscall definitions
 * into MARS.  This permits anyone with knowledge of the Mars public interfaces,
 * in particular of the Memory and Register classes, to write custom MIPS syscall
 * functions. This is adapted from the ToolLoader class, which is in turn adapted
 * from Bret Barker's GameServer class from the book "Developing Games In Java".
 */

class SyscallLoader {

    private static final String CLASS_PREFIX = "edu.missouristate.mars.mips.instructions.syscalls.";
    private static final String SYSCALLS_DIRECTORY_PATH = "edu/missouristate/mars/mips/instructions/syscalls";
    private static final String SYSCALL_INTERFACE = "Syscall.class";
    private static final String SYSCALL_ABSTRACT = "AbstractSyscall.class";
    private static final String CLASS_EXTENSION = "class";

    private ArrayList<Syscall> syscallList;

    /*
     *  Dynamically loads Syscalls into an ArrayList.  This method is adapted from
     *  the loadGameControllers() method in Bret Barker's GameServer class.
     *  Barker (bret@hypefiend.com) is co-author of the book "Developing Games
     *  in Java".  Also see the "loadMarsTools()" method from ToolLoader class.
     */
    void loadSyscalls() {
        syscallList = new ArrayList<>();
        // grab all class files in the same directory as Syscall
        ArrayList<String> candidates = FilenameFinder.getFilenameList(this.getClass().getClassLoader(),
                SYSCALLS_DIRECTORY_PATH, CLASS_EXTENSION);
        HashMap<String, String> syscalls = new HashMap<>();
        for (String candidate : candidates) {
            // Do not add class if already encountered (happens if run in MARS development directory)
            if (syscalls.containsKey(candidate)) {
                continue;
            } else {
                syscalls.put(candidate, candidate);
            }
            if ((!candidate.equals(SYSCALL_INTERFACE)) &&
                    (!candidate.equals(SYSCALL_ABSTRACT))) {
                try {
                    // grab the class, make sure it implements Syscall, instantiate, add to list
                    String syscallClassName = CLASS_PREFIX + candidate.substring(0, candidate.indexOf(CLASS_EXTENSION) - 1);
                    Class<?> clas = Class.forName(syscallClassName);
                    if (!Syscall.class.isAssignableFrom(clas)) {
                        continue;
                    }
                    Syscall syscall = (Syscall) clas.getDeclaredConstructor().newInstance();
                    if (findSyscall(syscall.getNumber()) == null) {
                        syscallList.add(syscall);
                    } else {
                        throw new Exception("Duplicate service number: " + syscall.getNumber() +
                                " already registered to " +
                                findSyscall(syscall.getNumber()).getName());
                    }
                } catch (Exception e) {
                    System.out.println("Error instantiating Syscall from file " + candidate + ": " + e);
                    System.exit(0);
                }
            }
        }
        syscallList = processSyscallNumberOverrides(syscallList);
    }

    // Will get any syscall number override specifications from MARS config file and
    // process them.  This will alter syscallList entry for affected names.
    private @NotNull ArrayList<Syscall> processSyscallNumberOverrides(@NotNull ArrayList<Syscall> syscallList) {
        ArrayList<SyscallNumberOverride> overrides = new Globals().getSyscallOverrides();
        SyscallNumberOverride override;
        Syscall syscall;
        for (SyscallNumberOverride object : overrides) {
            override = object;
            boolean match = false;
            for (Syscall o : syscallList) {
                syscall = o;
                if (override.getName().equals(syscall.getName())) {
                    // we have a match to service name, assign new number
                    syscall.setNumber(override.getNumber());
                    match = true;
                }
            }
            if (!match) {
                System.out.println("Error: syscall name '" + override.getName() +
                        "' in config file does not match any name in syscall list");
                System.exit(0);
            }
        }
        // Wait until end to check for duplicate numbers.  To do so earlier
        // would disallow for instance the exchange of numbers between two
        // services.  This is N-squared operation but N is small.
        // This will also detect duplicates that accidently occur from addition
        // of a new Syscall subclass to the collection, even if the config file
        // does not contain any overrides.
        Syscall syscallA, syscallB;
        boolean duplicates = false;
        for (int i = 0; i < syscallList.size(); i++) {
            syscallA = syscallList.get(i);
            for (int j = i + 1; j < syscallList.size(); j++) {
                syscallB = syscallList.get(j);
                if (syscallA.getNumber() == syscallB.getNumber()) {
                    System.out.println("Error: syscalls " + syscallA.getName() + " and " +
                            syscallB.getName() + " are both assigned same number " + syscallA.getNumber());
                    duplicates = true;
                }
            }
        }
        if (duplicates) {
            System.exit(0);
        }
        return syscallList;
    }

    /*
     * Method to find Syscall object associated with given service number.
     * Returns null if no associated object found.
     */
    @Nullable Syscall findSyscall(int number) {
        // linear search is OK since number of syscalls is small.
        Syscall service, match = null;
        if (syscallList == null) {
            loadSyscalls();
        }
        for (Syscall o : syscallList) {
            service = o;
            if (service.getNumber() == number) {
                match = service;
            }
        }
        return match;
    }
}
