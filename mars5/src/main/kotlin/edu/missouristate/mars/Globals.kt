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

package edu.missouristate.mars

import com.uchuhimo.konf.Config
import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.mips.instructions.syscalls.SyscallNumberOverride
import edu.missouristate.mars.util.ExcludeFromJacocoGeneratedReport
import edu.missouristate.mars.util.PropertiesFile
import java.util.*
import java.util.concurrent.locks.ReentrantLock

/**
 * Collection of globally-available data structures.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
object Globals {
    private const val configPropertiesFile = "Config"
    private const val syscallPropertiesFile = "Syscall"

    /** The set of implemented MIPS instructions. */
    @JvmStatic
    var instructionSet: InstructionSet = InstructionSet

    /** The program currently being worked on. Used by GUI only, not the command line. */
    @JvmStatic
    var program: MIPSProgram = MIPSProgram()

    /** The symbol table for the file currently being assembled. */
    @JvmStatic
    var symbolTable: SymbolTable = SymbolTable("globals")

    /** The simulated MIPS memory component. */
    @JvmStatic
    var memory: Memory = Memory.instance

    /** Lock variable used at the head of the synchronized block to guard MIPS memory and registers. */
    @JvmStatic
    val memoryAndRegistersLock: ReentrantLock = ReentrantLock()

    /** Flag to determine whether to produce internal debugging information. */
    @JvmStatic
    var debug: Boolean = false

    /** Instance of Settings that can be accessed and modified internally. */
    @JvmStatic
    var settings: CoreSettings = CoreSettings()

    /**
     * New settings class.
     */
    @JvmStatic
    val config = Config { addSpec(CoreSpec) }
        .from.env()
        .from.systemProperties()

    /** String to GUI's RunI/O text area when echoing user input from the pop-up dialog. */
    const val userInputAlert = "**** user input : "

    /** Path to folder that contains images */
    const val imagesPath = "/images/"

    /** Path to folder that contains help */
    const val helpPath = "/help/"

    /** Flag that indicates whether [instructionSet] has been initialized */
    @JvmStatic
    private var initialized: Boolean = false

    /** The current MARS version number; cannot wait until `initialize` is called to set it. */
    const val version = "4.5"

    /** List of accepted file extensions for MIPS assembly source files. */
    @JvmStatic
    val fileExtensions: ArrayList<String> = arrayListOf(*config[CoreSpec.fileExtensions])

    /** Maximum length of the scrolled message window (MARS Messages and Run I/O). */
    @JvmStatic
    val maximumMessageCharacters: Int = config[CoreSpec.messageLimit]

    /** Maximum number of assembler errors produced by one assemble operation. */
    @JvmStatic
    val maximumErrorMessages: Int = config[CoreSpec.errorLimit]

    /** Maximum number of back-step operations to buffer. */
    @JvmStatic
    val maximumBacksteps: Int = config[CoreSpec.backstepLimit]

    /** MARS copyright years. */
    @JvmStatic
    val copyrightYears: String = "2003 - 2023"

    /** MARS copyright holders. */
    @JvmStatic
    val copyrightHolders: String = "Pete Sanderson, Kenneth Vollmar, and Nicholas Hubbard"

    /** Placeholder for non-printable ASCII codes. */
    @JvmStatic
    val ASCII_NON_PRINT: String = "."

    /** Array of strings to display for ASCII codes in ASCII data segment. Codes 0-255 is array index. */
    @JvmStatic
    val ASCII_TABLE: Array<String> = config[CoreSpec.asciiTable].map { it.toString() }.toTypedArray()

    /** MARS exit code; useful with SYSCALL 17 when running from command line (not GUI) */
    @JvmStatic
    var exitCode: Int = 0

    @JvmStatic
    var runSpeedPanelExists: Boolean = false

    @JvmStatic
    private var isRunningTest: Boolean? = null

    /**
     * Function called once upon system initialization to create the global data structures.
     *
     * @note `gui` argument has been removed. It did nothing.
     */
    @JvmStatic
    fun initialize() {
        if (!initialized) {
            memory = Memory.instance
            instructionSet = InstructionSet
            instructionSet.populate()
            symbolTable = SymbolTable("global")
            settings = CoreSettings()
            initialized = true
            debug = false
            // Will establish memory configuration from settings
            memory.clear()
        }
    }

    @ExcludeFromJacocoGeneratedReport
    @JvmStatic
    fun isRunningTest(): Boolean {
        if (isRunningTest == null) {
            isRunningTest = try {
                Class.forName("org.junit.jupiter.api.Test")
                true
            } catch (e: ClassNotFoundException) { false }
        }
        return isRunningTest!!
    }

    @ExcludeFromJacocoGeneratedReport
    @JvmStatic
    fun resetInitialized() {
        if (isRunningTest()) {
            initialized = false
        } else {
            throw IllegalStateException("This method is unavailable outside of tests. Do NOT use it!")
        }
    }

    /**
     * Read any syscall number assignment overrides from the config file.
     *
     * @return ArrayList of SyscallNumberOverride objects
     */
    fun getSyscallOverrides(): ArrayList<SyscallNumberOverride> {
        val overrides = arrayListOf<SyscallNumberOverride>()
        val properties = PropertiesFile.loadPropertiesFromFile(syscallPropertiesFile)
        val keys = properties.keys()
        for (key in keys) {
            key as String
            overrides.add(SyscallNumberOverride(key, properties.getProperty(key)))
        }
        return overrides
    }
}