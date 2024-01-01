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

package edu.missouristate.mars

import edu.missouristate.mars.assembler.*
import edu.missouristate.mars.mips.hardware.RegisterFile
import edu.missouristate.mars.simulator.BackStepper
import edu.missouristate.mars.simulator.Simulator
import java.io.File
import javax.swing.AbstractAction

/**
 * Internal representations of MIPS program.
 * Connects the source code, parsed tokens, and machine code.
 * Having all these structures available facilitates construction of good messages,
 * debugging, and easy simulation.
 *
 * @author Pete Sanderson
 * @version August 2003
 */
class MIPSProgram {
    private var steppedExecution = false
    private lateinit var filename: String
    private lateinit var sourceList: ArrayList<String>
    private lateinit var tokenList: ArrayList<TokenList>
    private lateinit var parsedList: ArrayList<ProgramStatement>
    private lateinit var machineList: ArrayList<ProgramStatement>
    private var backStepper: BackStepper? = null
    private lateinit var localSymbolTable: SymbolTable
    private lateinit var macroPool: MacroPool
    private lateinit var sourceLineList: ArrayList<SourceLine>
    private lateinit var tokenizer: Tokenizer

    /**
     * Produce a list of source statements that comprise the program.
     *
     * @return ArrayList of String representing lines of MIPS source code.
     */
    fun getSourceList() = sourceList

    /**
     * Set a list of source statements that comprise the program.
     *
     * @param sourceLineList ArrayList of SourceLine objects. Each object represents one line of MIPS source code.
     */
    fun setSourceLineList(sourceLineList: ArrayList<SourceLine>) {
        this.sourceLineList = sourceLineList
        sourceList = arrayListOf()
        sourceList.addAll(sourceLineList.map(SourceLine::source))
    }

    /**
     * Retrieve a list of source statements that comprise the program.
     *
     * @return ArrayList of SourceLine. Each SourceLine represents one line of MIPS source code.
     */
    fun getSourceLineList() = sourceLineList

    /**
     * Produces the name of the associated source code file.
     *
     * @return File name as String
     */
    fun getFilename() = filename

    /**
     * Produces a list of tokens that comprise the program.
     *
     * @return ArrayList of TokenList. Each TokenList is a list of tokens generated by the corresponding line of MIPS
     * source code.
     * @see TokenList
     */
    fun getTokenList() = tokenList

    /**
     * @return The Tokenizer for the program.
     */
    fun getTokenizer() = tokenizer

    /**
     * Produces a new empty list to hold parsed source code statements.
     *
     * @return ArrayList of ProgramStatement. Each ProgramStatement represents a parsed MIPS statement.
     * @see ProgramStatement
     */
    fun createParsedList(): ArrayList<ProgramStatement> {
        parsedList = arrayListOf()
        return parsedList
    }

    /**
     * Produces an existing list of parsed source code statements.
     *
     * @return ArrayList of ProgramStatement.  Each ProgramStatement represents a parsed
     * MIPS statement.
     * @see ProgramStatement
     */
    fun getParsedList() = parsedList

    /**
     * Produces a list of machine statements that are assembled from the program.
     *
     * @return ArrayList of ProgramStatement.  Each ProgramStatement represents an assembled
     * basic MIPS instruction.
     * @see ProgramStatement
     */
    fun getMachineList() = machineList

    /**
     * Returns BackStepper associated with this program.  It is created upon successful assembly.
     *
     * @return BackStepper object, null if there is none.
     */
    fun getBackStepper(): BackStepper? = backStepper

    /**
     * @return The SymbolTable associated with this program.  It is created at assembly time,
     * and stores local labels (those not declared using .globl directive).
     */
    fun getLocalSymbolTable() = localSymbolTable

    /**
     * Returns status of BackStepper associated with this program.
     *
     * @return true if enabled, false if disabled or non-existent.
     */
    fun backSteppingEnabled(): Boolean = backStepper?.isEnabled ?: false

    /**
     * Produces specified line of MIPS source program.
     *
     * @param i Line number of MIPS source program to get.  Line 1 is the first line.
     * @return Returns specified line of MIPS source.  If outside the line range,
     * it returns null.
     * Line 1 is the first line.
     */
    fun getSourceLine(i: Int): String? = sourceList.getOrNull(i - 1)

    /**
     * Reads MIPS source code from file into structure.  Will always read from file.
     * It is the GUI's responsibility to ensure that source edits are written to file
     * when the user selects the compile or run/step options.
     *
     * @param file String containing the name of MIPS source code file.
     * @throws ProcessingException Will throw exception if there is any problem reading the file.
     */
    @Throws(ProcessingException::class)
    fun readSource(file: String) {
        this.filename = file
        this.sourceList = arrayListOf()
        val errors: ErrorList
        try {
            sourceList.addAll(File(file).readLines())
        } catch (e: Exception) {
            errors = ErrorList()
            errors.add(ErrorMessage(null, 0, 0, e.toString()))
            throw ProcessingException(errors)
        }
    }

    /**
     * Tokenizes the MIPS source program. The program must have already been read from the file.
     *
     * @throws ProcessingException Will throw exception if errors occur while tokenizing.
     */
    @Throws(ProcessingException::class)
    fun tokenize() {
        this.tokenizer = Tokenizer()
        this.tokenList = tokenizer.tokenize(this)
        this.localSymbolTable = SymbolTable(this.filename)
    }

    /**
     * Prepares the given list of files for assembly.  This involves
     * reading and tokenizing all the source files.  There may be only one.
     *
     * @param filenames        ArrayList containing the source file name(s) in no particular order
     * @param leadFilename     String containing the name of source file that needs to go first and
     *                         will be represented by "this" MIPSProgram object.
     * @param exceptionHandler String containing the name of source file containing exception
     *                         handler.  This will be assembled first, even ahead of leadFilename, to allow it to
     *                         include "startup" instructions loaded beginning at 0x00400000.  Specify null or
     *                         empty String to indicate there is no such designated exception handler.
     * @return ArrayList containing one MIPSProgram object for each file to assemble.
     * objects for any additional files (send ArrayList to assembler)
     * @throws ProcessingException Will throw exception if any errors occur while reading or tokenizing.
     */
    @Throws(ProcessingException::class)
    fun prepareFilesForAssembly(filenames: ArrayList<String>, leadFilename: String, exceptionHandler: String?): ArrayList<MIPSProgram> {
        val mipsProgramsToAssemble = arrayListOf<MIPSProgram>()
        var leadFilePosition = 0
        if (!exceptionHandler.isNullOrEmpty()) {
            filenames.addFirst(exceptionHandler)
            leadFilePosition = 1
        }
        for (filename in filenames) {
            val currentProgram = if (filename == leadFilename) this else MIPSProgram()
            currentProgram.readSource(filename)
            currentProgram.tokenize()
            // I want "this" KMIPSProgram to be the first in the list, not the exception handler (if present)
            if (currentProgram == this && mipsProgramsToAssemble.isNotEmpty()) {
                mipsProgramsToAssemble.add(leadFilePosition, currentProgram)
            } else {
                mipsProgramsToAssemble.add(currentProgram)
            }
        }
        return mipsProgramsToAssemble
    }

    /**
     * Assembles the MIPS source program. All files comprising the program must have
     * already been tokenized.
     *
     * @param mipsProgramsToAssemble   ArrayList of MIPSProgram objects, each representing a tokenized source file.
     * @param extendedAssemblerEnabled True means extended (pseudo) instructions
     *                                 are permitted in source code and false means they are to be flagged as errors
     * @param warningsAreErrors        True means assembler warnings will be considered errors and terminate
     *                                 the assembly process.
     *                                 False means the assembler will produce a warning message but otherwise ignore
     *                                 warnings.
     * @return ErrorList containing nothing or only warnings (otherwise would have thrown exception).
     * @throws ProcessingException Will throw exception if errors occur while assembling.
     */
    @Throws(ProcessingException::class)
    @JvmOverloads
    fun assemble(
        mipsProgramsToAssemble: ArrayList<MIPSProgram>,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean = false
    ): ErrorList {
        this.backStepper = null
        val asm = Assembler()
        this.machineList = asm.assemble(mipsProgramsToAssemble, extendedAssemblerEnabled, warningsAreErrors)!!
        this.backStepper = BackStepper()
        return asm.errors
    }

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Starts the simulation at the beginning of the text segment and continues to completion.
     *
     * @param breakPoints int array of breakpoints (PC addresses).  Can be null.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw exception if errors occur while simulating.
     */
    @Throws(ProcessingException::class)
    fun simulate(breakPoints: IntArray): Boolean = simulateFromPC(breakPoints, -1, null)

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Begins simulation at the beginning of the text segment and continues to completion, or
     * until the specified maximum number of execution steps is completed.
     *
     * @param maxSteps maximum number of steps to simulate.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw exception if errors occur while simulating.
     */
    @Throws(ProcessingException::class)
    fun simulate(maxSteps: Int): Boolean = simulateFromPC(null, maxSteps, null)

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Begins simulation at current program counter (PC) address and continues until stopped,
     * paused, the maximum number of steps is exceeded, or an exception is thrown.
     *
     * @param breakPoints int array of breakpoints (PC addresses).  Can be null.
     * @param maxSteps    maximum number of instruction executions.  Default -1 means no maximum.
     * @param a           the GUI component responsible for this call (GO normally).  set to null if none.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw an exception if errors occur while simulating.
     */
    @Throws(ProcessingException::class)
    fun simulateFromPC(breakPoints: IntArray?, maxSteps: Int, a: AbstractAction?): Boolean {
        steppedExecution = false
        val sim = Simulator.getInstance()
        return sim.simulate(this, RegisterFile.programCounter.getValue(), maxSteps, breakPoints, a)
    }

    /**
     * Simulates execution of the MIPS program.
     * The program must have already been assembled.
     * Begins simulation at current program counter (PC) address and executes one step.
     *
     * @param a the GUI component responsible for this call (STEP normally). Set to null if none.
     * @return true if execution completed and false otherwise
     * @throws ProcessingException Will throw exception if errors occur while simulating.
     */
    @Throws(ProcessingException::class)
    fun simulateStepAtPC(a: AbstractAction?): Boolean {
        steppedExecution = true
        val sim = Simulator.getInstance()
        return sim.simulate(this, RegisterFile.programCounter.getValue(), 1, null, a)
    }

    /**
     * Will be true only while in the process of simulating a program statement
     * in step mode (e.g., returning to GUI after each step).
     * This is used to prevent spurious AccessNotices from being sent from the Memory and Register simulator
     * to observers at other times (e.g., while updating the data and register
     * displays, while assembling the program data segment, etc.).
     */
    fun inSteppedExecution() = steppedExecution

    /**
     * Instantiates a new [MacroPool] and sends reference of this [MIPSProgram] to it.
     *
     * @return The created instance of [MacroPool]
     */
    fun createMacroPool(): MacroPool {
        macroPool = MacroPool(this)
        return macroPool
    }

    /**
     * Get the local [MacroPool] for this program.
     *
     * @return The current [MacroPool]
     */
    fun getLocalMacroPool() = macroPool

    /**
     * Set the local [MacroPool] for this program
     *
     * @param macroPool The reference to the new [MacroPool]
     */
    fun setLocalMacroPool(macroPool: MacroPool) {
        this.macroPool = macroPool
    }
}