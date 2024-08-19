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

package edu.missouristate.mars.mips.dump;

import edu.missouristate.mars.util.FilenameFinder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class provides functionality to bring external memory dump format definitions
 * into MARS.  This is adapted from the ToolLoader class, which is in turn adapted
 * from Bret Barker's GameServer class from the book "Developing Games In Java".
 */
public class DumpFormatLoader {
    private static final String CLASS_PREFIX = "edu.missouristate.mars.mips.dump.";
    private static final String DUMP_DIRECTORY_PATH = "edu/missouristate/mars/mips/dump";
    private static final String SYSCALL_INTERFACE = "DumpFormat.class";
    private static final String CLASS_EXTENSION = "class";
    private static @Nullable ArrayList<DumpFormat> formatList = null;

    /**
     * Dynamically loads dump formats into an ArrayList.  This method is adapted from
     * the loadGameControllers() method in Bret Barker's GameServer class.
     * Barker (bret@hypefiend.com) is co-author of the book "Developing Games
     * in Java".  Also see the ToolLoader and SyscallLoader classes elsewhere in MARS.
     */
    // TODO: Consider using a class indexing system with annotations instead?
    @NotNull
    public ArrayList<@NotNull DumpFormat> loadDumpFormats() {
        // The list will be populated only the first time this method is called.
        if (formatList == null) {
            formatList = new ArrayList<>();
            // grab all class files in the dump directory
            ArrayList<String> candidates = FilenameFinder.getFilenameList(this.getClass().getClassLoader(), DUMP_DIRECTORY_PATH, CLASS_EXTENSION);
            for (String file : candidates) {
                try {
                    // grab the class, make sure it implements DumpFormat, instantiate, add to list
                    String formatClassName = CLASS_PREFIX + file.substring(0, file.indexOf(CLASS_EXTENSION) - 1);
                    Class<?> clas = Class.forName(formatClassName);
                    if (DumpFormat.class.isAssignableFrom(clas) && !Modifier.isAbstract(clas.getModifiers())) {
                        formatList.add((DumpFormat) clas.getDeclaredConstructor().newInstance());
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return formatList;
    }

    @Nullable
    public static DumpFormat findDumpFormatGivenCommandDescriptor(@NotNull ArrayList<DumpFormat> formatList, String formatCommandDescriptor) {
        AtomicReference<@Nullable DumpFormat> match = new AtomicReference<>();
        for (DumpFormat dumpFormat : formatList) {
            Optional<String> descriptor = Optional.ofNullable(dumpFormat.getCommandDescriptor());
            if (descriptor.isPresent() && descriptor.get().equals(formatCommandDescriptor)) {
                match.set(dumpFormat);
                break;
            }
        }
        return match.get();
    }
}
