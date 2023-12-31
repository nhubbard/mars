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

@file:Suppress("DEPRECATION")

package edu.missouristate.mars

import edu.missouristate.mars.mips.dump.DumpFormatLoader
import edu.missouristate.mars.mips.hardware.*
import edu.missouristate.mars.simulator.ProgramArgumentList
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.FilenameFinder
import edu.missouristate.mars.util.MemoryDump
import edu.missouristate.mars.venus.VenusUI
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.PrintStream
import java.util.*
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

/**
 * Launch the MARS application.
 *
 * @author Pete Sanderson
 * @version December 2009
 *
 * Main takes a number of command line arguments.
 * Usage:  `Mars  {options} filename`
 * Valid options (case-insensitive, separate by spaces) are:
 * a  -- assemble only, do not simulate
 * ad  -- both a and d
 * ae<n>  -- terminate MARS with integer exit code <n> if an assembly error occurs.
 * ascii  -- display memory or register contents interpreted as ASCII
 * b  -- brief - do not display register/memory address along with contents
 * d  -- print debugging statements
 * da  -- both a and d
 * db  -- MIPS delayed branching is enabled.
 * dec  -- display memory or register contents in decimal.
 * dump -- dump memory contents to file.  Option has three arguments, e.g.
 * <tt>dump &lt;segment&gt; &lt;format&gt; &lt;file&gt;</tt>. Also supports
 * an address range (see <i>m-n</i> below).  Current supported
 * segments are <tt>.text</tt> and <tt>.data</tt>.  Current supported dump formats
 * are <tt>Binary</tt>, <tt>HexText</tt>, <tt>BinaryText</tt>.
 * h -- display help. Use by itself and with no filename</br>
 * hex -- display memory or register contents in hexadecimal (default)
 * ic -- display count of MIPS basic instructions 'executed');
 * mc -- set memory configuration.  Option has one argument, e.g.
 * <tt>mc &lt;config$gt;</tt>, where &lt;config$gt; is <tt>Default</tt>
 * for the MARS default 32-bit address space, <tt>CompactDataAtZero</tt> for
 * a 32KB address space with data segment at address 0, or <tt>CompactTextAtZero</tt>
 * for a 32KB address space with text segment at address 0.
 * me -- display MARS messages to standard err instead of standard out. Can separate via redirection.</br>
 * nc -- do not display copyright notice (for cleaner redirected/piped output).</br>
 * np -- No Pseudo-instructions allowed ("ne" will work also).
 * p -- Project mode - assemble all files in the same directory as given file.
 * se<n>  -- terminate MARS with integer exit code <n> if a simulation (run) error occurs.
 * sm  -- Start execution at Main - Execution will start at program statement globally labeled main.
 * smc  -- Self Modifying Code - Program can write and branch to either text or data segment
 * we  -- assembler Warnings will be considered Errors
 * <n>  -- where <n> is an integer maximum count of steps to simulate.
 * If 0, negative or not specified, there is no maximum.
 * $<reg> -- where <reg> is number or name (e.g., 5, t3, f10) of register whose
 * content to display at the end of a run.  Option may be repeated.
 * <reg_name> -- where <reg_name> is name (e.g., t3, f10) of register whose
 * content to display at the end of run.  Option may be repeated. $ not required.
 * <m>-<n> -- memory address range from <m> to <n> to
 * display the contents of after the run is complete. <m> and <n> may be hex or decimal,
 * <m> <= <n>, both must be on word boundary.  Option may be repeated.
 * pa -- Program Arguments follow in a space-separated list.  This
 * option must be placed AFTER ALL FILE NAMES, because everything
 * that follows it is interpreted as a program argument to be
 * made available to the MIPS program at runtime.
 */
class MarsLaunch(args: Array<String>) {
    private var simulate: Boolean = false
    private lateinit var displayFormat: DisplayFormat
    private var verbose: Boolean = false
    private var assembleProject: Boolean = false
    private var pseudo: Boolean = false
    private var delayedBranching: Boolean = false
    private var warningsAreErrors: Boolean = false
    private var startAtMain: Boolean = false
    private var countInstructions: Boolean = false
    private var selfModifyingCode: Boolean = false

    companion object {
        private const val rangeSeparator: String = "-"
        private const val splashDuration: Int = 2000
        private const val memoryWordsPerLine: Int = 4
    }

    private enum class DisplayFormat(val rawValue: Int) {
        DECIMAL(0),
        HEXADECIMAL(1),
        ASCII(2);

        fun formatInt(value: Int): String =
            when (this) {
                DECIMAL -> value.toString()
                ASCII -> Binary.intToAscii(value)
                HEXADECIMAL -> Binary.intToHexString(value)
            }
    }

    private lateinit var registerDisplayList: ArrayList<String>
    private lateinit var memoryDisplayList: ArrayList<String>
    private lateinit var filenameList: ArrayList<String>
    private lateinit var code: MIPSProgram
    private var maxSteps: Int = -1
    private var instructionCount: Int = -1
    private lateinit var out: PrintStream
    private var dumpTriples: ArrayList<Triple<String, String, String>>? = null
    private var programArgumentList: ArrayList<String>? = null
    private var assembleErrorExitCode: Int = -1
    private var simulateErrorExitCode: Int = -1

    init {
        val gui = args.isEmpty()
        Globals.initialize()
        if (gui) {
            launchIDE()
        } else {
            // MARS is running from the command line
            // Prevent AWT from initializing windows in a headless environment
            System.setProperty("java.awt.headless", "true")
            simulate = true
            displayFormat = DisplayFormat.HEXADECIMAL
            verbose = true
            assembleProject = false
            pseudo = true
            delayedBranching = false
            warningsAreErrors = false
            startAtMain = false
            countInstructions = false
            selfModifyingCode = false
            instructionCount = 0
            assembleErrorExitCode = 0
            simulateErrorExitCode = 0
            registerDisplayList = arrayListOf()
            memoryDisplayList = arrayListOf()
            filenameList = arrayListOf()
            MemoryConfigurations.setCurrentConfiguration(MemoryConfigurations.getDefaultConfiguration())
            // Do not use Globals.program for command-line MARS; it triggers a backstep log.
            code = MIPSProgram()
            maxSteps = -1
            out = System.out
            if (parseCommandArgs(args)) {
                if (runCommand()) {
                    displayMiscellaneousPostMortem()
                    displayRegistersPostMortem()
                    displayMemoryPostMortem()
                }
                dumpSegments()
            }
            exitProcess(Globals.exitCode)
        }
    }

    /**
     * Perform and specified dump operations. See the "dump" option.
     */
    private fun dumpSegments() {
        if (dumpTriples == null) return
        for ((segment, format, filename) in dumpTriples!!) {
            val file = File(filename)
            var segInfo = MemoryDump.getSegmentBounds(segment)
            // If not a segment name, see if it is address range instead.
            if (segInfo == null) {
                try {
                    val memoryRange = checkMemoryAddressRange(segment)
                    segInfo = Array(2) { 0 }
                    segInfo[0] = Binary.stringToInt(memoryRange[0]) // Low end of range
                    segInfo[1] = Binary.stringToInt(memoryRange[1]) // High end of range
                } catch (e: NumberFormatException) {
                    segInfo = null
                } catch (e: NullPointerException) {
                    segInfo = null
                }
            }
            if (segInfo == null) {
                out.println("Error while attempting to save dump: segment/address-range $segment is invalid!")
                continue
            }
            val loader = DumpFormatLoader()
            val dumpFormats = loader.loadDumpFormats()
            val dumpFormat = DumpFormatLoader.findDumpFormatGivenCommandDescriptor(dumpFormats, format)
            if (dumpFormat == null) {
                out.println("Error while attempting to save dump: format $format was not found!")
                continue
            }
            try {
                val highAddress =
                    Globals.memory.getAddressOfFirstNull(segInfo[0], segInfo[1]) - Memory.WORD_LENGTH_BYTES
                if (highAddress < segInfo[0]) {
                    out.println("This segment has not been written to, there is nothing to dump.")
                    continue
                }
                dumpFormat.dumpMemoryRange(file, segInfo[0], highAddress)
            } catch (e: FileNotFoundException) {
                out.println("Error while attempting to save dump to file $file! File not found!")
            } catch (e: AddressErrorException) {
                out.println("Error while attempting to save dump to file $file! Could not access address: ${e.address}!")
            } catch (e: IOException) {
                out.println("Error while attempting to save dump to file $file! Disk IO failed!")
            }
        }
    }

    /**
     * There are no command arguments, so run in interactive mode by launching the integrated development environment.
     */
    private fun launchIDE() {
        MarsSplashScreen(splashDuration).showSplash()
        SwingUtilities.invokeLater { VenusUI("MARS ${Globals.version}") }
    }

    /**
     * Parse command-line arguments.
     * The initial parsing has already been done,
     * since each space-separated argument is already in a String array element.
     * Here, we check for validity, set switch variables as appropriate, and build data structures.
     * For the help option (h), display the help.
     * Returns true if command arguments parse OK, or false if an error occurred.
     */
    private fun parseCommandArgs(args: Array<String>): Boolean {
        val noCopyrightSwitch = "nc"
        val displayMessagesToErrorSwitch = "me"
        var argsOK = true
        var inProgramArgumentList = false
        programArgumentList = null
        // This should be impossible, but we'll still check for it.
        if (args.isEmpty()) return true
        // If the option to display MARS messages to standard error is used,
        // it must be processed before any others (since messages may be generated
        // during option parsing).
        processDisplayMessagesToErrorSwitch(args)
        // ...or not.
        displayCopyright(args)
        if (args.size == 1 && args[0] == "h") {
            displayHelp()
            return false
        }
        for (i in args.indices) {
            // We have seen the "pa" switch, so all remaining args are program args that will become "argc" and "argv"
            // for the MIPS program.
            if (inProgramArgumentList) {
                if (programArgumentList == null) programArgumentList = arrayListOf()
                programArgumentList!!.add(args[i])
                continue
            }
            // Once we hit "pa", all remaining command-line arguments are assumed to be program arguments.
            if (args[i].equals("pa", true)) {
                inProgramArgumentList = true
                continue
            }
            // The "Display messages to standard error" switch has already been processed, so ignore.
            if (args[i].lowercase() == displayMessagesToErrorSwitch) continue
            // The "No copyright" switch has already been processed, so ignore.
            if (args[i].lowercase() == noCopyrightSwitch) continue
            if (args[i].equals("dump", true)) {
                if (args.size <= (i + 3)) {
                    out.println("Dump command line argument requires a segment, format, and file name.")
                    argsOK = false
                } else {
                    if (dumpTriples == null) dumpTriples = arrayListOf()
                    dumpTriples!!.add(Triple(args[i + 1], args[i + 2], args[i + 3]))
                }
                continue
            }
            if (args[i].equals("mc", true)) {
                val configName = args[i + 1]
                val config = MemoryConfigurations.getConfigurationByName(configName)
                if (config == null) {
                    out.println("Invalid memory configuration: $configName")
                    argsOK = false
                } else {
                    MemoryConfigurations.setCurrentConfiguration(config)
                }
                continue
            }
            // Set MARS exit code for assembly error
            if (args[i].lowercase().startsWith("ae")) {
                val s = args[i].substring(2)
                try {
                    assembleErrorExitCode = Integer.decode(s)
                    continue
                } catch (nfe: NumberFormatException) {
                    // Let it fall through and get handled by catch-all
                }
            }
            // Set MARS exit code for simulation error
            if (args[i].lowercase().startsWith("se")) {
                val s = args[i].substring(2)
                try {
                    simulateErrorExitCode = Integer.decode(s)
                    continue
                } catch (nfe: NumberFormatException) {
                    // Let it fall through and get handled by catch-all
                }
            }
            if (args[i].equals("d", true)) {
                Globals.debug = true
                continue
            }
            if (args[i].equals("a", true)) {
                simulate = false
                continue
            }
            if (args[i].equals("ad", true) || args[i].equals("da", true)) {
                Globals.debug = true
                simulate = false
                continue
            }
            if (args[i].equals("p", true)) {
                assembleProject = true
                continue
            }
            if (args[i].equals("dec", true)) {
                displayFormat = DisplayFormat.DECIMAL
                continue
            }
            if (args[i].equals("hex", true)) {
                displayFormat = DisplayFormat.HEXADECIMAL
                continue
            }
            if (args[i].equals("ascii", true)) {
                displayFormat = DisplayFormat.ASCII
                continue
            }
            if (args[i].equals("b", true)) {
                verbose = false
                continue
            }
            if (args[i].equals("db", true)) {
                delayedBranching = true
                continue
            }
            if (args[i].equals("np", true) || args[i].equals("ne", true)) {
                pseudo = false
                continue
            }
            if (args[i].equals("we", true)) {
                warningsAreErrors = true
                continue
            }
            if (args[i].equals("sm", true)) {
                startAtMain = true
                continue
            }
            if (args[i].equals("smc", true)) {
                selfModifyingCode = true
                continue
            }
            if (args[i].equals("ic", true)) {
                countInstructions = true
                continue
            }
            if (args[i].startsWith("$")) {
                if (RegisterFile.getUserRegister(args[i]) == null && Coprocessor1.getRegister(args[i]) == null) {
                    out.println("Invalid register name: ${args[i]}")
                } else {
                    registerDisplayList.add(args[i])
                }
                continue
            }
            // Check for register name without $.
            if (RegisterFile.getUserRegister(args[i]) != null || Coprocessor1.getRegister(args[i]) != null) {
                registerDisplayList.add(args[i])
                continue
            }
            if (File(args[i]).exists()) {
                filenameList.add(args[i])
                continue
            }
            // Check for standalone integer for the maximum execution steps option
            try {
                maxSteps = Integer.decode(args[i])
                continue
            } catch (ignored: NumberFormatException) {
            }
            // Check for integer address range (m-n)
            try {
                val memoryRange = checkMemoryAddressRange(args[i])
                memoryDisplayList.add(memoryRange[0])
                memoryDisplayList.add(memoryRange[1])
                continue
            } catch (nfe: NumberFormatException) {
                out.println("Invalid/unaligned address or invalid range: ${args[i]}")
                argsOK = false
                continue
            } catch (ignored: NullPointerException) {
                // Do nothing. The next statement will handle it.
            }
            out.println("Invalid command argument: ${args[i]}")
            argsOK = false
        }
        return argsOK
    }

    /**
     * Carry out the MARS command: assemble, then optionally run.
     * @return False if no simulation/run occurs, true otherwise.
     */
    private fun runCommand(): Boolean {
        var programRan = false
        if (filenameList.isEmpty()) return false
        try {
            Globals.settings.setBooleanSettingNonPersistent(Settings.DELAYED_BRANCHING_ENABLED, delayedBranching)
            Globals.settings.setBooleanSettingNonPersistent(Settings.ENABLE_SELF_MODIFYING_CODE, selfModifyingCode)
            val mainFile = File(filenameList.first()).absoluteFile
            val filesToAssemble: ArrayList<String>
            if (assembleProject) {
                filesToAssemble = FilenameFinder.getFilenameList(mainFile.parent, Globals.fileExtensions)
                if (filenameList.size > 1) {
                    // Using "p" project option and listing more than one filename on command line.
                    // Add the additional files, avoiding duplicates.
                    filenameList.removeFirst()
                    val moreFilesToAssemble =
                        FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS)
                    // Remove any duplicates and merge the two lists.
                    var index2 = 0
                    while (index2 in moreFilesToAssemble.indices) {
                        for (o in filesToAssemble) {
                            if (o == moreFilesToAssemble[index2]) {
                                moreFilesToAssemble.removeAt(index2)
                                index2--
                                break
                            }
                        }
                        index2++
                    }
                    filesToAssemble.addAll(moreFilesToAssemble)
                }
            } else {
                filesToAssemble = FilenameFinder.getFilenameList(filenameList, FilenameFinder.MATCH_ALL_EXTENSIONS)
            }
            if (Globals.debug) out.println("Tokenizing")
            val mipsProgramsToAssemble = code.prepareFilesForAssembly(filesToAssemble, mainFile.absolutePath, null)
            if (Globals.debug) out.println("Assembling")
            val warnings = code.assemble(mipsProgramsToAssemble, pseudo, warningsAreErrors)
            if (warnings.hasWarnings) out.println(warnings.generateReport(true))
            RegisterFile.initializeProgramCounter(startAtMain)
            if (simulate) {
                // Store program arguments in MIPS memory
                ProgramArgumentList(programArgumentList!!).storeProgramArguments()
                // Establish observer if specified
                establishObserver()
                if (Globals.debug) out.println("Simulating")
                programRan = true
                val done = code.simulate(maxSteps)
                if (!done) out.println("\nProgram terminated because maximum step limit $maxSteps was reached.")
            }
            if (Globals.debug) out.println("All processing complete!")
        } catch (e: ProcessingException) {
            Globals.exitCode = if (programRan) simulateErrorExitCode else assembleErrorExitCode
            out.println(e.errors()?.generateErrorAndWarningReport())
            out.println("Processing terminated due to errors.")
        }
        return programRan
    }

    /**
     * Check for memory address subrange. Has to be two integers separated by "-"; no embedded spaces.
     * (e.g., 0x00400000-0x00400010)
     * If the number is not a multiple of 4, it will be rounded up to the next highest number.
     */
    private fun checkMemoryAddressRange(arg: String): Array<String> {
        var memoryRange: Array<String>? = null
        val index = arg.indexOf(rangeSeparator)
        if (index in 0..<(arg.length - 1)) {
            // Assume the correct format of two numbers separated by a dash with no embedded spaces.
            // It that doesn't work, it's invalid.
            memoryRange = arrayOf(
                arg.substring(0, index),
                arg.substring(index + 1)
            )
            // Use the home-grown decoder,
            // since Integer.decode will throw an exception on addresses higher than 0x7FFFFFFF (e.g., sign bit is 1).
            if (Binary.stringToInt(memoryRange[0]) > Binary.stringToInt(memoryRange[1]) ||
                !Memory.wordAligned(Binary.stringToInt(memoryRange[0])) ||
                !Memory.wordAligned(Binary.stringToInt(memoryRange[1]))
            )
                throw NumberFormatException()
        }
        return memoryRange ?: throw IllegalStateException()
    }

    /**
     * Required for counting the number of instructions executed, if that option is specified.
     */
    private fun establishObserver() {
        if (countInstructions) {
            val instructionCounter = object : Observer {
                private var lastAddress: Int = 0

                override fun update(o: Observable?, notice: Any?) {
                    if (notice is AccessNotice) {
                        if (!notice.accessIsFromMIPS) return
                        if (notice.accessType != AccessNotice.AccessType.READ) return
                        notice as MemoryAccessNotice
                        val a = notice.address
                        if (a == lastAddress) return
                        lastAddress = a
                        instructionCount++
                    }
                }
            }
            try {
                Globals.memory.addObserver(instructionCounter, Memory.textBaseAddress, Memory.textLimitAddress)
            } catch (aee: AddressErrorException) {
                out.println("Internal error: MarsLaunch used the incorrect text segment address for instruction observer!")
            }
        }
    }

    /**
     * Displays any specified runtime properties. Initially just the instruction count.
     */
    private fun displayMiscellaneousPostMortem() {
        if (countInstructions) out.println("\n$instructionCount")
    }

    /**
     * Display requested register(s).
     */
    private fun displayRegistersPostMortem() {
        // Handy local variable to use through the next couple of loops
        var value: Int
        // Display requested register contents
        out.println()
        for (reg in registerDisplayList) {
            if (RegisterFile.getUserRegister(reg) != null) {
                // Integer register
                if (verbose) out.print("$reg\t")
                value = RegisterFile.getUserRegister(reg)?.getValue() ?: 0
                out.println(displayFormat.formatInt(value))
            } else {
                // Floating point register
                val fValue = Coprocessor1.getFloatFromRegister(reg)
                val iValue = Coprocessor1.getIntFromRegister(reg)
                var dValue = Double.NaN
                var lValue = 0L
                var hasDouble = false
                try {
                    dValue = Coprocessor1.getDoubleFromRegisterPair(reg)
                    lValue = Coprocessor1.getLongFromRegisterPair(reg)
                    hasDouble = true
                } catch (ignored: InvalidRegisterAccessException) {
                }
                if (verbose) out.print("$reg\t")
                if (displayFormat == DisplayFormat.HEXADECIMAL) {
                    // Display float (and double, if applicable) in hex
                    out.print(Binary.intToHexString(iValue))
                    if (hasDouble) out.println("\t${Binary.longToHexString(lValue)}") else out.println()
                } else if (displayFormat == DisplayFormat.DECIMAL) {
                    // Display float (and double, if applicable) in decimal
                    out.print(fValue)
                    if (hasDouble) out.println("\t$dValue") else out.println()
                } else {
                    out.print(Binary.intToAscii(iValue))
                    if (hasDouble)
                        out.println(
                            "\t${Binary.intToAscii(Binary.highOrderLongToInt(lValue))}${
                                Binary.intToAscii(
                                    Binary.lowOrderLongToInt(
                                        lValue
                                    )
                                )
                            }"
                        )
                    else out.println()
                }
            }
        }
    }

    /**
     * Display requested memory range(s)
     */
    private fun displayMemoryPostMortem() {
        var value: Int
        // Display requested memory range contents
        val memIter = memoryDisplayList.iterator()
        var addressStart = 0
        var addressEnd = 0
        while (memIter.hasNext()) {
            try {
                // This will succeed; error would have been caught during command argument parsing.
                addressStart = Binary.stringToInt(memIter.next())
                addressEnd = Binary.stringToInt(memIter.next())
            } catch (ignored: NumberFormatException) {
            }
            var valuesDisplayed = 0
            var addr = addressStart
            while (addr <= addressEnd) {
                if (addr < 0 && addressEnd > 0) break // Happens only if addressEnd is 0x7ffffffc
                if (valuesDisplayed % memoryWordsPerLine == 0) {
                    out.print(if (valuesDisplayed > 0) "\n" else "")
                    if (verbose) out.print("Mem[${Binary.intToHexString(addr)}]\t")
                }
                try {
                    // Allow display of binary text segment (machine code)
                    value = if (Memory.inTextSegment(addr) || Memory.inKernelTextSegment(addr)) {
                        Globals.memory.getRawWordOrNull(addr) ?: 0
                    } else Globals.memory.getWord(addr)
                    out.print("${displayFormat.formatInt(value)}\t")
                } catch (aee: AddressErrorException) {
                    out.print("Invalid address: $addr\t")
                }
                valuesDisplayed++
                addr += Memory.WORD_LENGTH_BYTES
            }
            out.println()
        }
    }

    /**
     * If the option to display MARS messages to standard error (System.err) is present,
     * it must be processed before all other options, since messages may be printed while parsing
     * the other options.
     */
    private fun processDisplayMessagesToErrorSwitch(args: Array<String>) {
        if (args.any { it.lowercase() == "me" }) {
            out = System.err
        }
    }

    /**
     * Decide whether copyright should be displayed, and display it if so.
     */
    private fun displayCopyright(args: Array<String>) {
        if (args.any { it.lowercase() == "nc" }) return
        out.println("MARS ${Globals.version} | Copyright (C) ${Globals.copyrightYears} ${Globals.copyrightHolders}")
    }

    /**
     * Display command line help text.
     */
    private fun displayHelp() {
        val segments = MemoryDump.segmentNames.joinToString(", ")
        val formats = DumpFormatLoader().loadDumpFormats().joinToString(", ") { it.commandDescriptor.toString() }
        out.printf(
            """
        Usage:  Mars  [options] filename [additional filenames]
        Valid options (not case sensitive, separate by spaces) are:
              a  -- assemble only, do not simulate
          ae<n>  -- terminate MARS with integer exit code <n> if an assemble error occurs.
          ascii  -- display memory or register contents interpreted as ASCII codes.
              b  -- brief - do not display register/memory address along with contents
              d  -- display MARS debugging statements
             db  -- MIPS delayed branching is enabled
            dec  -- display memory or register contents in decimal.
           dump <segment> <format> <file> -- memory dump of specified memory segment
                    in specified format to specified file.  Option may be repeated.
                    Dump occurs at the end of simulation unless 'a' option is used.
                    Segment and format are case-sensitive and possible values are:
                    <segment> = %s
                    <format> = %s
              h  -- display this help.  Use by itself with no filename.
            hex  -- display memory or register contents in hexadecimal (default)
             ic  -- display count of MIPS basic instructions 'executed'
             mc <config>  -- set memory configuration.  Argument <config> is
                    case-sensitive and possible values are: Default for the default
                    32-bit address space, CompactDataAtZero for a 32KB memory with
                    data segment at address 0, or CompactTextAtZero for a 32KB
                    memory with text segment at address 0.
             me  -- display MARS messages to standard err instead of standard out.
                    Can separate messages from program output using redirection
             nc  -- do not display copyright notice (for cleaner redirected/piped output).
             np  -- use of pseudo instructions and formats not permitted
              p  -- Project mode - assemble all files in the same directory as given file.
          se<n>  -- terminate MARS with integer exit code <n> if a simulation (run) error occurs.
             sm  -- start execution at statement with global label main, if defined
            smc  -- Self Modifying Code - Program can write and branch to either text or data segment
            <n>  -- where <n> is an integer maximum count of steps to simulate.
                    If 0, negative or not specified, there is no maximum.
         ${'$'}<reg>  -- where <reg> is number or name (e.g. 5, t3, f10) of register whose
                    content to display at end of run.  Option may be repeated.
        <reg_name>  -- where <reg_name> is name (e.g. t3, f10) of register whose
                    content to display at end of run.  Option may be repeated.
                    The ${'$'} is not required.
        <m>-<n>  -- memory address range from <m> to <n> whose contents to
                    display at end of run. <m> and <n> may be hex or decimal,
                    must be on word boundary, <m> <= <n>.  Option may be repeated.
             pa  -- Program Arguments follow in a space-separated list.  This
                    option must be placed AFTER ALL FILE NAMES, because everything
                    that follows it is interpreted as a program argument to be
                    made available to the MIPS program at runtime.
        If more than one filename is listed, the first is assumed to be the main
        unless the global statement label 'main' is defined in one of the files.
        Exception handler not automatically assembled.  Add it to the file list.
        Options used here do not affect MARS Settings menu values and vice versa
        """.trimIndent(), segments, formats
        )
    }
}