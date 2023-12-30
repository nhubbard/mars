/*
 * Copyright (c) 2003-2023, Pete Sanderson and Kenneth Vollmar
 * Copyright (c) 2023-present, Nicholas Hubbard
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

package edu.missouristate.mars

import edu.missouristate.mars.assembler.SymbolTable
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.instructions.InstructionSet
import edu.missouristate.mars.mips.instructions.syscalls.SyscallNumberOverride
import edu.missouristate.mars.util.PropertiesFile
import edu.missouristate.mars.venus.VenusUI
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
    lateinit var instructionSet: InstructionSet

    /** The program currently being worked on. Used by GUI only, not the command line. */
    @JvmStatic
    lateinit var program: MIPSProgram

    /** The symbol table for the file currently being assembled. */
    @JvmStatic
    lateinit var symbolTable: SymbolTable

    /** The simulated MIPS memory component. */
    @JvmStatic
    lateinit var memory: Memory

    /** Lock variable used at the head of the synchronized block to guard MIPS memory and registers. */
    @JvmStatic
    val memoryAndRegistersLock: ReentrantLock = ReentrantLock()

    /** Flag to determine whether to produce internal debugging information. */
    @JvmStatic
    var debug: Boolean = false

    /** Instance of Settings that can be accessed and modified internally. */
    @JvmStatic
    lateinit var settings: Settings
        private set

    /** String to GUI's RunI/O text area when echoing user input from the pop-up dialog. */
    const val userInputAlert = "**** user input : "

    /** Path to folder that contains images */
    const val imagesPath = "/images/"

    /** Path to folder that contains help */
    const val helpPath = "/help/"

    /** Flag that indicates whether [instructionSet] has been initialized */
    @JvmStatic
    private var initialized: Boolean = false

    /** The GUI being used (if any) with the simulator. */
    @JvmStatic
    var gui: VenusUI? = null

    /** The current MARS version number; cannot wait until `initialize` is called to set it. */
    const val version = "4.5"

    /** List of accepted file extensions for MIPS assembly source files. */
    @JvmStatic
    val fileExtensions: ArrayList<String> = getFileExtensions()

    /** Maximum length of the scrolled message window (MARS Messages and Run I/O). */
    @JvmStatic
    val maximumMessageCharacters: Int = getIntegerProperty("MessageLimit", 1000000)

    /** Maximum number of assembler errors produced by one assemble operation. */
    @JvmStatic
    val maximumErrorMessages: Int = getIntegerProperty("ErrorLimit", 200)

    /** Maximum number of back-step operations to buffer. */
    @JvmStatic
    val maximumBacksteps: Int = getIntegerProperty("BackstepLimit", 1000)

    /** MARS copyright years. */
    @JvmStatic
    val copyrightYears: String = "2003 - 2023"

    /** MARS copyright holders. */
    @JvmStatic
    val copyrightHolders: String = "Pete Sanderson, Kenneth Vollmar, and Nicholas Hubbard"

    /** Placeholder for non-printable ASCII codes. */
    @JvmStatic
    val ASCII_NON_PRINT: String = getAsciiNonPrint()

    /** Array of strings to display for ASCII codes in ASCII data segment. Codes 0-255 is array index. */
    @JvmStatic
    val ASCII_TABLE: Array<String> = getAsciiStrings()

    /** MARS exit code; useful with SYSCALL 17 when running from command line (not GUI) */
    @JvmStatic
    var exitCode: Int = 0

    @JvmStatic
    var runSpeedPanelExists: Boolean = false

    /**
     * Function called once upon system initialization to create the global data structures.
     *
     * @note `gui` argument has been removed. It did nothing.
     */
    @JvmStatic
    fun initialize() {
        if (!initialized) {
            // Clients can use Memory.getInstance() instead of Globals.memory
            memory = Memory.instance
            instructionSet = InstructionSet
            instructionSet.populate()
            symbolTable = SymbolTable("global")
            settings = Settings()
            initialized = true
            debug = false
            // Will establish memory configuration from settings
            memory.clear()
        }
    }

    /**
     * Read the ASCII default display character for non-printable characters from the properties file.
     */
    @JvmStatic
    private fun getAsciiNonPrint(): String {
        val anp = getPropertyEntry(configPropertiesFile, "AsciiNonPrint")
        return if (anp == null) "." else (if (anp == "space") " " else anp)
    }

    /**
     * Read ASCII strings for codes 0 to 255 from the properties file.
     * If string value is "null," substitute value of `ASCII_NON_PRINT`.
     * If string is "space", substitute string containing one space character.
     */
    @JvmStatic
    private fun getAsciiStrings(): Array<String> {
        val let = getPropertyEntry(configPropertiesFile, "AsciiTable")
        val placeHolder = getAsciiNonPrint()
        val lets = let!!.split(" +").toMutableList()
        var maxLength = 0
        for (i in lets.indices) {
            if (lets[i] == "null") lets[i] = placeHolder
            if (lets[i] == "space") lets[i] = " "
            if (lets[i].length > maxLength) maxLength = lets[i].length
        }
        val padding = "        "
        maxLength++
        for (i in 0..<lets.size)
            lets[i] = padding.substring(0, maxLength - lets[i].length) + lets[i]
        return lets.toTypedArray()
    }

    /**
     * Read assembly language file extensions from the properties file.
     * The resulting string is tokenized into an ArrayList (assume StringTokenizer default delimiters).
     */
    @JvmStatic
    @JvmName("internalGetFileExtensions")
    private fun getFileExtensions(): ArrayList<String> {
        val extensionList = arrayListOf<String>()
        getPropertyEntry(configPropertiesFile, "Extensions")?.let {
            val st = StringTokenizer(it)
            while (st.hasMoreTokens()) extensionList.add(st.nextToken())
        }
        return extensionList
    }

    /**
     * Read and return integer property value for given file and property name.
     * The default value is returned if the property file or the key is not found.
     */
    @JvmStatic
    private fun getIntegerProperty(propertyName: String, defaultValue: Int): Int {
        val properties = PropertiesFile.loadPropertiesFromFile(configPropertiesFile)
        return properties.getProperty(propertyName, defaultValue.toString()).toIntOrNull() ?: defaultValue
    }

    /**
     * Read and return a property file value (if any) for the requested property.
     *
     * @param propertiesFile Name of the properties file (don't include an extension)
     * @param propertyName The property key name
     * @return The associated value, or null if the property isn't found
     */
    @JvmStatic
    fun getPropertyEntry(propertiesFile: String, propertyName: String): String? =
        PropertiesFile.loadPropertiesFromFile(propertiesFile).getProperty(propertyName)

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