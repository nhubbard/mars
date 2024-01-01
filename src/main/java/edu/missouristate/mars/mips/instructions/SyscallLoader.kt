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

package edu.missouristate.mars.mips.instructions

import edu.missouristate.mars.Globals
import edu.missouristate.mars.mips.instructions.syscalls.Syscall
import edu.missouristate.mars.util.FilenameFinder
import kotlin.reflect.full.createInstance
import kotlin.system.exitProcess

/**
 * This class provides functionality to bring external Syscall definitions
 * into MARS.  This permits anyone with knowledge of the Mars public interfaces,
 * in particular of the Memory and Register classes, to write custom MIPS syscall
 * functions.
 */
class SyscallLoader {
    companion object {
        private const val CLASS_PREFIX = "edu.missouristate.mars.mips.instructions.syscalls."
        private const val SYSCALLS_DIRECTORY_PATH = "edu/missouristate/mars/mips/instructions/syscalls"
        private const val SYSCALL_INTERFACE = "SyscallKt.class"
        private const val SYSCALL_ABSTRACT = "AbstractSyscallKt.class"
        private const val CLASS_EXTENSION = "class"
    }

    private var syscallList: ArrayList<Syscall>? = null

    /**
     * Dynamically load syscalls into an ArrayList.
     */
    fun loadSyscalls() {
        syscallList = arrayListOf()
        // Grab all class files in the same directory as Syscall
        val candidates = FilenameFinder.getFilenameList(javaClass.classLoader, SYSCALLS_DIRECTORY_PATH, CLASS_EXTENSION)
        val syscalls = hashMapOf<String, String>()
        for (file in candidates) {
            // Do not add class if already encountered (happens if run in MARS development directory)
            syscalls.putIfAbsent(file, file)
            if (file != SYSCALL_INTERFACE && file != SYSCALL_ABSTRACT) {
                try {
                    // Grab the class, make sure it implements Syscall, instantiate it, and add it to the list.
                    val syscallClassName = CLASS_PREFIX + file.substringBeforeLast(CLASS_EXTENSION).dropLast(1)
                    val kClass = Class.forName(syscallClassName).kotlin
                    if (Syscall::class.java.isAssignableFrom(kClass.java) && !kClass.isAbstract && !kClass.java.isInterface) {
                        // Create instance and add to list
                        val instance = kClass.createInstance() as Syscall
                        if (findSyscall(instance.number) == null) {
                            syscallList!!.add(instance)
                        } else {
                            throw Exception("Duplicate service number: ${instance.number} already registered to ${findSyscall(instance.number)!!.name}!")
                        }
                    }
                } catch (e: Exception) {
                    println("Error instantiating Syscall from file $file: $e")
                    exitProcess(1)
                }
            }
        }
        syscallList = processSyscallNumberOverrides(syscallList!!)
    }

    /**
     * Process any overrides for existing Syscall numbers.
     */
    private fun processSyscallNumberOverrides(syscallList: ArrayList<Syscall>): ArrayList<Syscall> {
        val overrides = Globals.getSyscallOverrides()
        for (override in overrides) {
            var match = false
            for (syscall in syscallList) {
                if (override.name == syscall.name) {
                    // We have a match to the service name; assign a new number.
                    syscall.number = override.number
                    match = true
                }
            }
            if (!match) {
                println("Error: override for syscall '${override.name}' in config file does not match any name in syscall list!")
                exitProcess(1)
            }
        }
        // Wait until the end to check for duplicate numbers.
        // To do so earlier would disallow, for instance, the exchange of numbers between two services.
        // This is an n-squared operation, but the value of n is small.
        // This will also detect duplicates that accidentally occur from the addition of a new Syscall subclass to the
        // collection, even if the config file does not contain any overrides.
        var duplicates = false
        for (syscall1 in syscallList) {
            for (syscall2 in syscallList) {
                if (syscall1.number == syscall2.number) {
                    println("Error: syscalls ${syscall1.name} and ${syscall2.name} are both assigned the same number (${syscall1.number})!")
                    duplicates = true
                }
            }
        }
        if (duplicates) exitProcess(1)
        return syscallList
    }

    /**
     * Find a syscall given its service number.
     * Returns null if no associated object is found.
     */
    fun findSyscall(number: Int): Syscall? {
        if (syscallList == null) loadSyscalls()
        return syscallList!!.firstOrNull { it.number == number }
    }
}