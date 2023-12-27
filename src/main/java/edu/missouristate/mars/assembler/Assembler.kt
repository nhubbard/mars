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

package edu.missouristate.mars.assembler

import edu.missouristate.mars.*
import edu.missouristate.mars.mips.hardware.AddressErrorException
import edu.missouristate.mars.mips.hardware.Memory
import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.util.SystemIO
import edu.missouristate.mars.venus.NumberDisplayBaseChooser
import kotlin.math.pow

/**
 * An Assembler is capable of assembling a MIPS program. It has only one public method, `assemble`, which implements a
 * two-pass assembler.
 * It translates MIPS source code into binary machine code.
 */
class Assembler {
    lateinit var errors: ErrorList
        private set

    @Deprecated("Use errors accessor instead.", ReplaceWith("errors"))
    val errorList: ErrorList get() = errors

    private var inDataSegment: Boolean = false
    private var inMacroSegment: Boolean = false
    private var externAddress: Int = 0
    private var autoAlign: Boolean = false
    private lateinit var currentDirective: Directives
    private lateinit var dataDirective: Directives
    private lateinit var fileCurrentlyBeingAssembled: MIPSProgram
    private lateinit var globalDeclarationList: TokenList
    private lateinit var textAddress: UserKernelAddressSpace
    private lateinit var dataAddress: UserKernelAddressSpace
    private lateinit var currentFileDataSegmentForwardReferences: DataSegmentForwardReferences

    /**
     * Parse and generate machine code for the given MIPS program. It must have
     * already been tokenized.
     *
     * @param p                        A MIPSProgram object representing the program source.
     * @param extendedAssemblerEnabled A boolean value that if true permits use of extended (pseudo)
     *                                 instructions in the source code. If false, these are flagged
     *                                 as errors.
     * @param warningsAreErrors        `true` means assembler warnings will be
     *                                 considered errors and terminate the assembly process; `false` means the
     *                                 assembler will produce warning messages but otherwise ignore
     *                                 warnings.
     * @return An ArrayList representing the assembled program. Each member of
     * the list is a ProgramStatement object containing the source,
     * intermediate, and machine binary representations of a program
     * statement.
     * @see ProgramStatement
     */
    @Throws(ProcessingException::class)
    @JvmOverloads
    fun assemble(
        p: MIPSProgram,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean = false
    ): ArrayList<ProgramStatement>? = assemble(arrayListOf(p), extendedAssemblerEnabled, warningsAreErrors)

    /**
     * Parse and generate machine code for the given MIPS program. All source
     * files must have already been tokenized.
     *
     * @param tokenizedProgramFiles    An ArrayList of MIPSProgram objects, each produced from a
     *                                 different source code file, representing the program source.
     * @param extendedAssemblerEnabled A boolean value that if true permits use of extended (pseudo)
     *                                 instructions in the source code. If false, these are flagged
     *                                 as errors.
     * @param warningsAreErrors        A true means assembler warnings will be
     *                                 considered errors and terminate the assembly process; false means the
     *                                 assembler will produce a warning message but otherwise ignore
     *                                 warnings. Defaults to false.
     * @return An ArrayList representing the assembled program. Each member of
     * the list is a ProgramStatement object containing the source,
     * intermediate, and machine binary representations of a program
     * statement. Returns null if incoming array list is null or empty.
     * @see ProgramStatement
     */
    @Throws(ProcessingException::class)
    @JvmOverloads
    fun assemble(
        tokenizedProgramFiles: ArrayList<MIPSProgram>?,
        extendedAssemblerEnabled: Boolean,
        warningsAreErrors: Boolean = false
    ): ArrayList<ProgramStatement>? {
        if (tokenizedProgramFiles.isNullOrEmpty()) return null
        textAddress = UserKernelAddressSpace(Memory.textBaseAddress, Memory.kernelTextBaseAddress)
        dataAddress = UserKernelAddressSpace(Memory.dataBaseAddress, Memory.kernelDataBaseAddress)
        externAddress = Memory.externBaseAddress
        currentFileDataSegmentForwardReferences = DataSegmentForwardReferences()
        val accumulatedDataSegmentForwardReferences = DataSegmentForwardReferences()
        Globals.symbolTable.clear()
        Globals.memory.clear()
        val machineList = arrayListOf<ProgramStatement>()
        errors = ErrorList()
        if (Globals.debug) println("Assembler first pass started")

        // Process the first assembly pass for all source files before proceeding to the second pass.
        // This assures all symbol tables are correctly built.
        // There is one global symbol table (for identifiers declared .globl),
        // plus one local symbol table for each source file.
        for (tokenizedProgramFile in tokenizedProgramFiles) {
            // Ensure we haven't exceeded the maximum number of errors
            if (errors.isErrorLimitExceeded) break
            fileCurrentlyBeingAssembled = tokenizedProgramFile
            // List of labels declared ".globl". New list for each file assembled.
            globalDeclarationList = TokenList()
            // Parser begins by default in the text segment unless directed otherwise.
            inDataSegment = false
            // Macro segment must be started by .macro directive
            inMacroSegment = false
            // The default data directive is `.word` for 4 byte data items
            dataDirective = Directives.WORD
            // Clear out (initialize) symbol table-related structures
            fileCurrentlyBeingAssembled.getLocalSymbolTable().clear()
            currentFileDataSegmentForwardReferences.clear()
            // sourceList is an ArrayList of String objects, one per source line.
            // tokenList is an ArrayList of TokenList objects, one per source line;
            // each ArrayList in tokenList consists of Token objects.
            val sourceLineList = fileCurrentlyBeingAssembled.getSourceLineList()
            val tokenList = fileCurrentlyBeingAssembled.getTokenList()
            val parsedList = fileCurrentlyBeingAssembled.createParsedList()
            // Each file keeps its own macro definitions
            fileCurrentlyBeingAssembled.createMacroPool()
            // First pass of assembler verifies syntax, generates symbol table, and initializes the data segment.
            for (i in tokenList.indices) {
                if (errors.isErrorLimitExceeded) break
                for (z in tokenList[i].indices) {
                    val t = tokenList[i][z]
                    // Record this token's original source program and line number.
                    // Differs from final output if .include is used.
                    t.setOriginal(
                        sourceLineList[i].mipsProgram!!,
                        sourceLineList[i].lineNumber
                    )
                }
                parseLine(tokenList[i], sourceLineList[i].source, sourceLineList[i].lineNumber, extendedAssemblerEnabled)?.let {
                    parsedList.addAll(it)
                }
            }
            if (inMacroSegment)
                errors.add(ErrorMessage(
                    fileCurrentlyBeingAssembled,
                    fileCurrentlyBeingAssembled.getLocalMacroPool().current!!.fromLine,
                    0,
                    "Macro started, but not ended (missing .end_macro directive)!"
                ))
            transferGlobals()
            // Attempt to resolve forward label references that were discovered in operand fields
            // of data segment directives in the current file.
            // Those that are not resolved after this call are either references to global labels not seen yet,
            // or are undefined.
            // Cannot determine which until all files are parsed, so copy unresolved entries
            // into an accumulated list and clear out this one for re-use with the next source file.
            currentFileDataSegmentForwardReferences.resolve(fileCurrentlyBeingAssembled.getLocalSymbolTable())
            accumulatedDataSegmentForwardReferences.add(currentFileDataSegmentForwardReferences)
            currentFileDataSegmentForwardReferences.clear()
        }
        // Have processed all source files. Attempt to resolve any remaining forward label
        // references from global symbol table. Those that remain unresolved are undefined
        // and generate error messages.
        accumulatedDataSegmentForwardReferences.resolve(Globals.symbolTable)
        accumulatedDataSegmentForwardReferences.generateErrorMessages(errors)

        // Throw the collection of errors accumulated through the first pass.
        if (errors.hasErrors) throw ProcessingException(errors)
        if (Globals.debug) println("Assembler second pass begins")
        // Generate basic assembler statements
        for (tokenizedProgramFile in tokenizedProgramFiles) {
            if (errors.isErrorLimitExceeded) break
            fileCurrentlyBeingAssembled = tokenizedProgramFile
            val parsedList = fileCurrentlyBeingAssembled.getParsedList()
            var statement: ProgramStatement
            for (programStatement in parsedList) {
                statement = programStatement
                statement.buildBasicStatementFromBasicInstruction(errors)
                if (errors.hasErrors) throw ProcessingException(errors)
                if (statement.getInstruction() is BasicInstruction) machineList.add(statement)
                else {
                    /*
                     It is a pseudo-instruction.
                     1. Fetch its basic instruction template list
                     2. For each template in the list,
                     2a. substitute operands from source statement
                     2b. tokenize the statement generated by 2a.
                     2d. call parseLine() to generate basic instruction
                     2e. add returned programStatement to the list
                     The templates and the instructions generated by filling
                     in the templates are specified
                     in basic format (e.g., mnemonic register reference $zero
                     already translated to $0).
                     So the values substituted into the templates need to be
                     in this format. Since those
                     values come from the original source statement, they need
                     to be translated before
                     substituting. The next method call will perform this
                     translation on the original
                     source statement. Despite the fact that the original
                     statement is a pseudo
                     instruction, this method performs the necessary
                     translation correctly.
                    */
                    val inst = statement.getInstruction() as ExtendedInstruction
                    val basicAssembly = statement.getBasicAssemblyStatement() ?: "nop"
                    val sourceLine = statement.getSourceLine()
                    val theTokenList = Tokenizer().tokenizeLine(sourceLine, basicAssembly, errors, false)
                    // If we are using compact memory config and there is a compact expansion, then use it
                    val templateList = if (compactTranslationCanBeApplied(statement))
                        inst.compactBasicInstructionTemplateList else inst.basicInstructionTemplateList
                    // The subsequent ProgramStatement constructor needs the correct text segment address.
                    textAddress.set(statement.getAddress())
                    // Generate one basic instruction for each template in the list.
                    for (instrNumber in templateList!!.indices) {
                        val instruction = ExtendedInstruction.makeTemplateSubstitutions(
                            fileCurrentlyBeingAssembled, templateList[instrNumber], theTokenList)
                        // Template substitution can result in no output. If this is the case, skip the rest of this
                        // iteration.
                        // This should only happen if template substitution was for "nop" instruction, but delayed
                        // branching is disabled, so the "nop" is not generated.
                        if (instruction.isEmpty()) continue
                        // All substitutions have been made, so we have generated a valid basic instruction.
                        if (Globals.debug) println("Generated pseudo-instruction: $instruction")
                        // For generated instruction: tokenize, build the program statement, and add to the list.
                        val newTokenList = Tokenizer().tokenizeLine(sourceLine, instruction, errors, false)
                        val instrMatches = matchInstruction(newTokenList.first())
                        val instr = OperandFormat.bestOperandMatch(newTokenList, instrMatches)!!
                        val ps = ProgramStatement(fileCurrentlyBeingAssembled, if (instrNumber == 0) statement.getSource() else "", newTokenList, newTokenList, instr, textAddress.get(), statement.getSourceLine())
                        textAddress.increment(Instruction.INSTRUCTION_LENGTH)
                        ps.buildBasicStatementFromBasicInstruction(errors)
                        machineList.add(ps)
                    }
                }
            }
        }
        if (Globals.debug) println("Starting code generation")
        var statement: ProgramStatement
        for (programStatement in machineList) {
            if (errors.isErrorLimitExceeded) break
            statement = programStatement
            statement.buildMachineStatementFromBasicStatement(errors)
            if (Globals.debug) println(statement)
            try {
                Globals.memory.setStatement(statement.getAddress(), statement)
            } catch (e: AddressErrorException) {
                val t = statement.getOriginalTokenList()!!.first()
                errors.add(ErrorMessage(
                    t.sourceMipsProgram,
                    t.sourceLine,
                    t.startPosition,
                    "Invalid address ${e.address} for text segment!"
                ))
            }
        }
        // Ensure that I/O file descriptors are initialized for a new program run.
        SystemIO.resetFiles()
        // The ProgramStatements are now sorted by address value.
        machineList.sortWith(ProgramStatementComparator())
        catchDuplicateAddresses(machineList, errors)
        if (errors.hasErrors || errors.hasWarnings && warningsAreErrors) throw ProcessingException(errors)
        return machineList
    }

    /**
     * Check for duplicate text addresses, which can happen inadvertently when using an operand on a `.text` directive.
     * This will generate an error message for each occurrence.
     */
    private fun catchDuplicateAddresses(instructions: ArrayList<ProgramStatement>, errors: ErrorList) {
        for (i in 0..<(instructions.size - 1)) {
            val ps1 = instructions[i]
            val ps2 = instructions[i + 1]
            if (ps1.getAddress() == ps2.getAddress())
                errors.add(ErrorMessage(
                    ps2.getSourceMipsProgram(),
                    ps2.getSourceLine(),
                    0,
                    "Duplicate text segment address: ${NumberDisplayBaseChooser.formatUnsignedInteger(ps2.getAddress(), if (Globals.settings.getBooleanSetting(Settings.DISPLAY_ADDRESSES_IN_HEX)) 16 else 10)} already occupied by ${ps1.getSourceFile()} line ${ps1.getSourceLine()} (caused by use of ${if (Memory.inTextSegment(ps2.getAddress())) ".text" else ".ktext"} operand)"
                ))
        }
    }

    /**
     * This method parses one line of MIPS source code. It works with the list
     * of tokens, but the original source is also provided. It also carries out
     * directives, which includes initializing the data segment. This method is
     * invoked in the assembler's first pass.
     *
     * @return ArrayList of ProgramStatements because parsing a macro expansion
     * request will return a list of ProgramStatements expanded, or null if (a) there are no
     * tokens in the TokenList, (b) this line is a directive or macro in any way, or (c) execution falls
     * through somehow.
     */
    private fun parseLine(
        tokenList: TokenList,
        source: String,
        sourceLineNumber: Int,
        extendedAssemblerEnabled: Boolean
    ): ArrayList<ProgramStatement>? {
        val ret = arrayListOf<ProgramStatement>()
        val programStatement: ProgramStatement
        var tokens = stripComment(tokenList)
        // Labels should not be processed in a macro definition segment.
        val macroPool = fileCurrentlyBeingAssembled.getLocalMacroPool()
        if (inMacroSegment) detectLabels(tokens, macroPool.current) else stripLabels(tokens)
        if (tokens.isEmpty()) return null
        // Grab the first (operator) token.
        val token = tokens.first()
        val tokenType = token.type
        // Handle any directives.
        if (tokenType == TokenTypes.DIRECTIVE) {
            executeDirective(tokens)
            return null
        }
        // Don't parse if in a macro.
        if (inMacroSegment) return null
        // Enable SPIM-style macro calling.
        var parenFreeTokens = tokens
        if (tokens.size > 2 && tokens[1].type == TokenTypes.LEFT_PAREN && tokens.last().type == TokenTypes.RIGHT_PAREN) {
            parenFreeTokens = tokens.clone() as TokenList
            parenFreeTokens.removeAt(tokens.size - 1)
            parenFreeTokens.removeAt(1)
        }
        // Expand macro if this line is a macro expansion call.
        macroPool.getMatchingMacro(parenFreeTokens)?.let {
            tokens = parenFreeTokens
            // Get the unique ID for this expansion
            val counter = macroPool.getNextCounter()
            if (macroPool.pushOnCallStack(token)) {
                errors.add(ErrorMessage(
                    fileCurrentlyBeingAssembled,
                    tokens.first().sourceLine,
                    0,
                    "Recursive macro expansion detected!"
                ))
            } else {
                for (i in (it.fromLine + 1)..<it.toLine) {
                    var substituted = it.getSubstitutedLine(i, tokens, counter, errors)
                    val tokenList2 = fileCurrentlyBeingAssembled.getTokenizer().tokenizeLine(i, substituted, errors)
                    // If token list getProcessedLine() is not empty, then .eqv was performed,
                    // and it contains the modified source.
                    // Put it into the line to be parsed, so it will be displayed properly in the text segment display.
                    if (tokenList2.processedLine.isNotEmpty()) substituted = tokenList2.processedLine
                    // Recursively parse the lines of the expanded macro.
                    parseLine(
                        tokenList2,
                        "<${i - it.fromLine + it.originalFromLine}> ${substituted.trim()}",
                        sourceLineNumber,
                        extendedAssemblerEnabled
                    )?.let { statements -> ret.addAll(statements) }
                }
                macroPool.popFromCallStack()
            }
            return ret
        }
        // Detect unrecognized directives.
        if (tokenType == TokenTypes.IDENTIFIER && token.value[0] == '.') {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "MARS does not recognize the ${token.value} directive; it will be ignored.",
                true
            ))
            return null
        }
        // Directives with lists are able to extend themselves over several lines.
        // Handle this condition.
        if (inDataSegment && (tokenType == TokenTypes.PLUS || tokenType == TokenTypes.MINUS || tokenType == TokenTypes.QUOTED_STRING || tokenType == TokenTypes.IDENTIFIER || tokenType.isIntegerTokenType || tokenType.isFloatTokenType)) {
            executeDirectiveContinuation(tokens)
            return null
        }
        // If we are in the text segment, the variable "token" must now refer to an OPERATOR token. If not, then it is
        // a syntax error, or the specified operator is not yet implemented.
        if (!inDataSegment) {
            val instrMatches = matchInstruction(token) ?: return ret
            // We've matched an operator. Check the operands.
            val inst = OperandFormat.bestOperandMatch(tokens, instrMatches) ?: return ret
            // Here's the place to flag use of extended/pseudo-instructions when their use is disabled.
            if (inst is ExtendedInstruction && !extendedAssemblerEnabled) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "Extended (pseudo) instructions are disabled. Check MARS settings."
                ))
                return ret
            } else {
                if (OperandFormat.tokenOperandMatch(tokens, inst, errors)) {
                    programStatement = ProgramStatement(fileCurrentlyBeingAssembled, source, tokenList, tokens, inst, textAddress.get(), sourceLineNumber)
                    var instLength = inst.instructionLength
                    if (compactTranslationCanBeApplied(programStatement)) {
                        inst as ExtendedInstruction
                        instLength = inst.compactInstructionLength
                    }
                    textAddress.increment(instLength)
                    ret.add(programStatement)
                    return ret
                }
            }
        }
        return null
    }

    /**
     * Detect labels in a TokenList for Macro processing.
     */
    private fun detectLabels(tokens: TokenList, current: Macro?) {
        if (tokenListBeginsWithLabel(tokens)) current?.addLabel(tokens.first().value)
    }

    /**
     * Determine whether a compact (16-bit) translation from
     * pseudo-instruction to basic instruction can be applied. If
     * the argument is a basic instruction, obviously not. If an
     * extended instruction, we have to be operating under a 16-bit
     * memory model and the instruction has to have defined an
     * alternate compact translation.
     */
    private fun compactTranslationCanBeApplied(statement: ProgramStatement): Boolean =
        Globals.memory.usingCompactMemoryConfiguration &&
            (statement.getInstruction() as? ExtendedInstruction)?.hasCompactTranslation ?: false

    /**
     * Pre-process the token list for a statement by stripping off any comments.
     * Unlike [stripLabels], the TokenList parameter is **not** modified; a new one is cloned and returned.
     */
    private fun stripComment(tokenList: TokenList): TokenList {
        if (tokenList.isEmpty()) return tokenList
        val tokens = tokenList.clone() as TokenList
        // If there is a comment, strip it off.
        val last = tokens.size - 1
        if (tokens[last].type == TokenTypes.COMMENT) tokens.removeAt(last)
        return tokens
    }

    /**
     * Pre-process the token list for a statement by stripping off any label, if either is present.
     * Any label definition will be recorded in the symbol table.
     * This function mutates the TokenList parameter.
     */
    private fun stripLabels(tokens: TokenList) {
        // If there is a label, handle it here and strip it off.
        val thereWasLabel = parseAndRecordLabel(tokens)
        if (thereWasLabel) {
            tokens.removeFirst() // Remove the IDENTIFIER token.
            tokens.removeFirst() // Remove the COLON token, shifted to 0 by the previous call to removeFirst.
        }
    }

    /**
     * Parse and record a label if there is one.
     * Note that the identifier and its colon are two separate tokens,
     * since they may be separated by spaces in source code.
     */
    private fun parseAndRecordLabel(tokens: TokenList): Boolean {
        if (tokens.size < 2) return false
        else {
            val token = tokens.first()
            if (tokenListBeginsWithLabel(tokens)) {
                // An instruction name was used as a label, so change it's token type.
                if (token.type == TokenTypes.OPERATOR) token.type = TokenTypes.IDENTIFIER
                fileCurrentlyBeingAssembled.getLocalSymbolTable().addSymbol(
                    token,
                    if (inDataSegment) dataAddress.get() else textAddress.get(),
                    inDataSegment,
                    errors
                )
                return true
            } else return false
        }
    }

    /**
     * Determine if a token list begins with a label.
     */
    private fun tokenListBeginsWithLabel(tokens: TokenList): Boolean {
        if (tokens.size < 2) return false
        return (tokens[0].type == TokenTypes.IDENTIFIER || tokens[0].type == TokenTypes.OPERATOR) &&
                tokens[1].type == TokenTypes.COLON
    }

    /**
     * This source code line is a directive, not a MIPS instruction. Execute the directive.
     *
     * The original function was monolithic; all directives were implemented in a giant `if` statement.
     * It has been broken up into separate functions for each directive.
     */
    private fun executeDirective(tokens: TokenList) {
        val token = tokens.first()
        val direct = Directives.matchDirective(token.value)
        if (Globals.debug) println("Line ${token.sourceLine} is directive $direct")
        direct?.let {
            if (it == Directives.EQV) {
                // .eqv is processed during tokenizing.
            } else if (it == Directives.MACRO) {
                if (executeStartMacroDirective(tokens, token)) return
            } else if (it == Directives.END_MACRO) {
                if (executeEndMacroDirective(tokens, token)) return
            } else if (inMacroSegment) {
                // Parsing a directive within a macro is not an implemented piece of functionality.
            } else if (it == Directives.DATA || it == Directives.KDATA) {
                executeDataDirective(tokens, it)
            } else if (it == Directives.TEXT || it == Directives.KTEXT) {
                executeTextDirective(tokens, it)
            } else if (it == Directives.WORD || it == Directives.HALF || it == Directives.BYTE || it == Directives.FLOAT || it == Directives.DOUBLE) {
                executeNumericDirective(tokens, token, it)
            } else if (it == Directives.ASCII || it == Directives.ASCIIZ) {
                executeAsciiDirective(tokens, token, it)
            } else if (it == Directives.ALIGN) {
                if (executeAlignDirective(tokens, token)) return
            } else if (it == Directives.SPACE) {
                if (executeSpaceDirective(tokens, token)) return
            } else if (it == Directives.EXTERN) {
                if (executeExternDirective(tokens, token)) return
            } else if (it == Directives.SET) {
                executeSetDirective(token)
            } else if (it == Directives.GLOBL) {
                if (executeGlobalDirective(tokens, token)) return
            } else {
                // This branch should never happen because Directives are now an enum.
                // But I want to retain original functionality, even in edge cases like this.
                // I also made it into a warning, since it doesn't matter nowadays.
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "The directive \"${token.value}\" was recognized, but it has not been implemented yet.",
                    true
                ))
            }
        } ?: errors.add(ErrorMessage(
            token.sourceMipsProgram,
            token.sourceLine,
            token.startPosition,
            "\"${token.value}\" directive is invalid or not implemented in MARS!"
        ))
    }

    /**
     * Execute a `.macro` directive.
     *
     * @return `true` if an error occurred, which should be checked for at the call site; `false` if successful.
     */
    private fun executeStartMacroDirective(tokens: TokenList, firstToken: Token): Boolean {
        if (tokens.size < 2) {
            errors.add(ErrorMessage(
                firstToken.sourceMipsProgram,
                firstToken.sourceLine,
                firstToken.startPosition,
                "\"${firstToken.value}\" directive requires at least one argument!"
            ))
            return true
        }
        if (tokens[1].type != TokenTypes.IDENTIFIER) {
            errors.add(ErrorMessage(
                firstToken.sourceMipsProgram,
                firstToken.sourceLine,
                tokens[1].startPosition,
                "Invalid macro name \"${tokens[1].value}\"!"
            ))
            return true
        }
        if (inMacroSegment) {
            errors.add(ErrorMessage(
                firstToken.sourceMipsProgram,
                firstToken.sourceLine,
                firstToken.startPosition,
                "Nested macros are not allowed!"
            ))
            return true
        }
        inMacroSegment = true
        val pool = fileCurrentlyBeingAssembled.getLocalMacroPool()
        pool.beginMacro(tokens[1])
        for (i in 2..<tokens.size) {
            val arg = tokens[i]
            if (arg.type == TokenTypes.RIGHT_PAREN || arg.type == TokenTypes.LEFT_PAREN) continue
            if (!Macro.tokenIsMacroParameter(arg.value, true)) {
                errors.add(ErrorMessage(
                    arg.sourceMipsProgram,
                    arg.sourceLine,
                    arg.startPosition,
                    "Invalid macro argument \"${arg.value}\"!"
                ))
                return true
            }
            pool.current!!.addArg(arg.value)
        }
        return false
    }

    /**
     * Execute an `.end_macro` directive.
     *
     * @return `true` if an error occurred, which should be checked for at the call site; `false` if successful.
     */
    private fun executeEndMacroDirective(tokens: TokenList, token: Token): Boolean {
        if (tokens.size > 1) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "Invalid token after .end_macro!"
            ))
            return true
        }
        if (!inMacroSegment) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "Found invalid .end_macro directive without preceding .macro directive!"
            ))
            return true
        }
        inMacroSegment = false
        fileCurrentlyBeingAssembled.getLocalMacroPool().commitMacro(token)
        return false
    }

    /**
     * Execute a `.data` or `.kdata` directive.
     */
    private fun executeDataDirective(tokens: TokenList, direct: Directives) {
        inDataSegment = true
        autoAlign = true
        dataAddress.currentAddressSpace =
            if (direct == Directives.DATA) UserKernelAddressSpace.AddressSpace.USER
            else UserKernelAddressSpace.AddressSpace.KERNEL
        if (tokens.size > 1 && tokens[1].type.isIntegerTokenType)
            dataAddress.set(Binary.stringToInt(tokens[1].value))
    }

    /**
     * Execute a `.text` or `.ktext` directive.
     */
    private fun executeTextDirective(tokens: TokenList, direct: Directives) {
        inDataSegment = false
        textAddress.currentAddressSpace =
            if (direct == Directives.TEXT) UserKernelAddressSpace.AddressSpace.USER
        else UserKernelAddressSpace.AddressSpace.KERNEL
        if (tokens.size > 1 && tokens[1].type.isIntegerTokenType)
            textAddress.set(Binary.stringToInt(tokens[1].value))
    }

    /**
     * Execute a numeric (`.word`, `.half`, `.byte`, `.float`, or `.double`) directive.
     */
    private fun executeNumericDirective(tokens: TokenList, token: Token, direct: Directives) {
        dataDirective = direct
        if (passesDataSegmentCheck(token) && tokens.size > 1)
            storeNumeric(tokens, direct, errors)
    }

    /**
     * Execute an ASCII (`.ascii` or `.asciiz`) directive.
     */
    private fun executeAsciiDirective(tokens: TokenList, token: Token, direct: Directives) {
        dataDirective = direct
        if (passesDataSegmentCheck(token)) storeStrings(tokens, direct, errors)
    }

    /**
     * Execute an `.align` directive.
     *
     * @return `true` if an error occurred, which should be checked for at the call site; `false` if successful.
     */
    private fun executeAlignDirective(tokens: TokenList, token: Token): Boolean {
        if (passesDataSegmentCheck(token)) {
            if (tokens.size != 2) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" requires one operand!"
                ))
                return true
            }
            if (!tokens[1].type.isIntegerTokenType || Binary.stringToInt(tokens[1].value) < 0) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" requires a non-negative integer!"
                ))
                return true
            }
            val value = Binary.stringToInt(tokens[1].value)
            if (value == 0) autoAlign = false
            else dataAddress.set(alignToBoundary(dataAddress.get(), 2.0.pow(value.toDouble()).toInt()))
            return false
        } else {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "The data segment check for the token \"${token.value}\" failed!"
            ))
            return true
        }
    }

    /**
     * Execute a `.space` directive.
     *
     * @return `true` if an error occurred, which should be checked for at the call site; `false` if successful.
     */
    private fun executeSpaceDirective(tokens: TokenList, token: Token): Boolean {
        if (passesDataSegmentCheck(token)) {
            if (tokens.size != 2) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" requires one operand!"
                ))
                return true
            }
            val argument = tokens[1]
            if (!argument.type.isIntegerTokenType || Binary.stringToInt(argument.value) < 0) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" requires a non-negative integer operand!"
                ))
                return true
            }
            val value = Binary.stringToInt(argument.value)
            dataAddress.increment(value)
            return false
        } else {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "The data segment check for the token \"${token.value}\" failed!"
            ))
            return true
        }
    }

    /**
     * Execute an `.extern` directive.
     *
     * @return `true` if an error occurred, which should be checked for at the call site; `false` if successful.
     */
    private fun executeExternDirective(tokens: TokenList, token: Token): Boolean {
        if (tokens.size != 3) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${token.value}\" directive requires both a label and size operand!"
            ))
            return true
        }
        val firstArg = tokens[1]
        val secondArg = tokens[2]
        if (!secondArg.type.isIntegerTokenType || Binary.stringToInt(secondArg.value) < 0) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${token.value}\"'s second operand must be a non-negative integer size!"
            ))
            return true
        }
        val size = Binary.stringToInt(secondArg.value)
        // If the label is already in the global symbol table, do nothing. If not, add it right now.
        Globals.symbolTable.getAddressOrNull(firstArg.value) ?: run {
            Globals.symbolTable.addSymbol(firstArg, externAddress, true, errors)
            externAddress += size
        }
        return false
    }

    /**
     * Execute a `.set` directive.
     */
    private fun executeSetDirective(token: Token) {
        errors.add(ErrorMessage(
            token.sourceMipsProgram,
            token.sourceLine,
            token.startPosition,
            "The `.set` directive is ignored.",
            true
        ))
    }

    /**
     * Execute a `.globl` directive.
     *
     * @return `true` if an error occurred, which should be checked for at the call site; `false` if successful.
     */
    private fun executeGlobalDirective(tokens: TokenList, token: Token): Boolean {
        if (tokens.size < 2) {
            errors.add(
                ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" directive requires at least one argument!"
                )
            )
            return true
        }
        // SPIM limits `.globl`'s list to one label; why not extend it to a list?
        for (i in 1..<tokens.size) {
            // Add it to a list of labels to be processed at the end of the pass.
            // At that point, transfer matching symbol definitions from the local
            // symbol table to the global symbol table.
            val label = tokens[i]
            if (label.type != TokenTypes.IDENTIFIER) {
                errors.add(
                    ErrorMessage(
                        token.sourceMipsProgram,
                        token.sourceLine,
                        token.startPosition,
                        "The \"${token.value}\" directive argument must be a label!"
                    )
                )
                return true
            }
            globalDeclarationList.add(label)
        }
        return false
    }

    /**
     * Process the list of .globl labels, if any, declared and defined in this file.
     * We'll just move their symbol table entries from local symbol table to global
     * symbol table at the end of the first assembly pass.
     */
    private fun transferGlobals() {
        for (label in globalDeclarationList) {
            val tableEntry = fileCurrentlyBeingAssembled.getLocalSymbolTable().getSymbol(label.value)
            if (tableEntry == null) {
                errors.add(ErrorMessage(
                    fileCurrentlyBeingAssembled,
                    label.sourceLine,
                    label.startPosition,
                    "\"${label.value}\" declared an undefined global label!"
                ))
            } else {
                Globals.symbolTable.getAddressOrNull(label.value)?.let {
                    errors.add(ErrorMessage(
                        fileCurrentlyBeingAssembled,
                        label.sourceLine,
                        label.startPosition,
                        "\"${label.value}\" is already defined as a global label in a different file!"
                    ))
                } ?: run {
                    fileCurrentlyBeingAssembled.getLocalSymbolTable().removeSymbol(label)
                    Globals.symbolTable.addSymbol(label, tableEntry.address, tableEntry.isData, errors)
                }
            }
        }
    }

    /**
     * This source code, if syntactically correct, is a continuation of a directive list that started on a previous line.
     */
    private fun executeDirectiveContinuation(tokens: TokenList) {
        val direct = dataDirective
        if (direct == Directives.WORD || direct == Directives.HALF || direct == Directives.BYTE || direct == Directives.FLOAT || direct == Directives.DOUBLE) {
            if (tokens.isNotEmpty()) storeNumeric(tokens, direct, errors)
        } else if (direct == Directives.ASCII || direct == Directives.ASCIIZ) {
            if (passesDataSegmentCheck(tokens.first())) storeStrings(tokens, direct, errors)
        }
    }

    /**
     * Given a token, find the corresponding Instruction object.
     * If the token was not recognized as OPERATOR, there is a problem.
     */
    private fun matchInstruction(token: Token): ArrayList<Instruction>? {
        if (token.type != TokenTypes.OPERATOR) {
            if (token.sourceMipsProgram?.getLocalMacroPool()?.matchesAnyMacroName(token.value) == true) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "Found illegal forward reference or invalid parameters for macro \"${token.value}\"!"
                ))
            } else {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" is not a recognized operator!"
                ))
            }
            return null
        }
        val inst = Globals.instructionSet.matchOperator(token.value)
        if (inst == null) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "Internal Assembly Error: \"${token.value}\" tokenized OPERATOR, then not recognized!"
            ))
        }
        return inst
    }

    /**
     * Processes the .word/.half/.byte/.float/.double directive.
     * Can also handle "directive continuations", e.g., second or subsequent line
     * of a multiline list, which does not contain the directive token. Pass the
     * current directive as argument.
     */
    private fun storeNumeric(tokens: TokenList, directive: Directives, errors: ErrorList) {
        var token = tokens.first()
        // Double check: this should have already been caught.
        if (!passesDataSegmentCheck(token)) return
        // Correctly handles the case where this is a "directive continuation" line.
        var tokenStart = 0
        if (token.type == TokenTypes.DIRECTIVE) tokenStart = 1
        // Set byte length in memory of each number (e.g., WORD is 4, BYTE is 1, etc.)
        val lengthInBytes = DataTypes.getLengthInBytes(directive)
        // Handle the "value : n" format, which replicates the value "n" times.
        if (tokens.size == 4 && tokens[2].type == TokenTypes.COLON) {
            val valueToken = tokens[1]
            val repetitionsToken = tokens[3]
            // Allow ":" for repetition of all numeric directives
            // Must be in the following format to work:
            // (integer directive AND integer value OR floating directive AND
            // (integer value OR floating value)) AND integer repetition value
            if (!(Directives.isIntegerDirective(directive) && valueToken.type.isIntegerTokenType || Directives.isFloatingDirective(directive) && (valueToken.type.isIntegerTokenType || valueToken.type.isFloatTokenType)) || !repetitionsToken.type.isIntegerTokenType) {
                errors.add(ErrorMessage(fileCurrentlyBeingAssembled, valueToken.sourceLine, valueToken.startPosition, "Malformed repetition expression!"))
                return
            }
            val repetitions = Binary.stringToInt(repetitionsToken.value)
            if (repetitions <= 0) {
                errors.add(ErrorMessage(fileCurrentlyBeingAssembled, repetitionsToken.sourceLine, repetitionsToken.startPosition, "Repetition factor must be positive!"))
                return
            }
            if (inDataSegment) {
                if (autoAlign) dataAddress.set(alignToBoundary(dataAddress.get(), lengthInBytes))
                for (i in 0..<repetitions) {
                    if (Directives.isIntegerDirective(directive))
                        storeInteger(valueToken, directive, errors)
                    else storeRealNumber(valueToken, directive, errors)
                }
            }
            return
        }
        for (i in tokenStart..<tokens.size) {
            token = tokens[i]
            if (Directives.isIntegerDirective(directive))
                storeInteger(token, directive, errors)
            else if (Directives.isFloatingDirective(directive))
                storeRealNumber(token, directive, errors)
        }
    }

    /**
     * Store integer value given an integer (word, half, or byte) directive.
     * Called by storeNumeric().
     *
     * @note The token itself may be a label, in which case the correct action is to store the address of that label
     * into however many bytes are specified.
     */
    private fun storeInteger(token: Token, directive: Directives, errors: ErrorList) {
        val lengthInBytes = DataTypes.getLengthInBytes(directive)
        if (token.type.isIntegerTokenType) {
            var value = Binary.stringToInt(token.value)
            val fullValue = value
            // If value is out of range for the directive, this will truncate the leading bits, including the sign bits.
            // This is what SPIM does, but it will issue a warning, not an error, which SPIM does not do.
            if (directive == Directives.BYTE) {
                value = value and 0x000000FF
            } else if (directive == Directives.HALF) {
                value = value and 0x0000FFFF
            }
            if (DataTypes.outOfRange(directive, fullValue)) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" is out-of-range for a signed value and possibly truncated!",
                    true
                ))
            }
            if (inDataSegment) writeToDataSegment(value, lengthInBytes, token, errors)
            else {
                // The try statement below will always throw an exception. You cannot use Memory.set() with text segment
                // addresses, which makes the "not valid address" error produced here misleading.
                // The data segment check this else statement is attached to should prevent this from happening, but
                // in case MARS gains the capability of writing to the text segment,
                // such as the ability to disassemble a binary value into its corresponding MIPS instruction, this
                // check should remain in place.
                try {
                    Globals.memory.set(textAddress.get(), value, lengthInBytes)
                } catch (e: AddressErrorException) {
                    errors.add(ErrorMessage(
                        token.sourceMipsProgram,
                        token.sourceLine,
                        token.startPosition,
                        "\"${textAddress.get()}\" is not a valid text segment address!"
                    ))
                    return
                }
                textAddress.increment(lengthInBytes)
            }
        } else if (token.type == TokenTypes.IDENTIFIER) {
            if (inDataSegment) {
                fileCurrentlyBeingAssembled.getLocalSymbolTable().getLocalOrGlobalAddressOrNull(token.value)?.let {
                    writeToDataSegment(it, lengthInBytes, token, errors)
                } ?: run {
                    val dataAddress = writeToDataSegment(0, lengthInBytes, token, errors)
                    currentFileDataSegmentForwardReferences.add(dataAddress, lengthInBytes, token)
                }
            } else {
                // The data segment check was done previously, so this "else" will not execute.
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" label as directive operand not permitted in text segment!"
                ))
            }
        } else {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${token.value}\" is not a valid integer constant or label!"
            ))
        }
    }

    /**
     * Store real (fixed or floating point) value given a real number (float or double) directive.
     * Called by storeNumeric.
     */
    private fun storeRealNumber(token: Token, directive: Directives, errors: ErrorList) {
        val lengthInBytes = DataTypes.getLengthInBytes(directive)
        val value: Double

        if (token.type.isIntegerTokenType || token.type.isFloatTokenType) {
            try {
                value = token.value.toDouble()
            } catch (e: NumberFormatException) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" is not a valid floating point constant!"
                ))
                return
            }
            if (DataTypes.outOfRange(directive, value)) {
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" is out of range for the directive ${directive.name}!"
                ))
                return
            }
        } else {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${token.value}\" is not a valid floating point constant!"
            ))
            return
        }
        // The value has been validated for storage in the provided directive.
        if (directive == Directives.FLOAT)
            writeToDataSegment(java.lang.Float.floatToIntBits(value.toFloat()), lengthInBytes, token, errors)
        if (directive == Directives.DOUBLE) writeDoubleToDataSegment(value, token, errors)
    }

    /**
     * Use directive argument to distinguish between ASCII and ASCIIZ. The
     * latter stores a terminating null byte. Can handle a list of one or more
     * strings on a single line.
     */
    private fun storeStrings(tokens: TokenList, direct: Directives, errors: ErrorList) {
        var token: Token
        var tokenStart = 0
        if (tokens.first().type == TokenTypes.DIRECTIVE) tokenStart = 1
        for (i in tokenStart..<tokens.size) {
            token = tokens[i]
            if (token.type != TokenTypes.QUOTED_STRING)
                errors.add(ErrorMessage(
                    token.sourceMipsProgram,
                    token.sourceLine,
                    token.startPosition,
                    "\"${token.value}\" is not a valid character string!"
                ))
            else {
                val quote = token.value
                var theChar: Char
                var j = 1
                while (j < quote.length - 1) {
                    theChar = quote[j]
                    if (theChar == '\\') {
                        theChar = quote[++j]
                        theChar = when (theChar) {
                            'n' -> '\n'
                            't' -> '\t'
                            'r' -> '\r'
                            'b' -> '\b'
                            // Note: Kotlin does not recognize \f!
                            'f' -> '\u000c'
                            '0' -> '\u0000'
                            '\\', '\'', '"' -> theChar
                            else -> theChar
                            /*
                             Not implemented: \ n = octal character (n is number)
                             \ x n = hex character (n is number)
                             \ u n = unicode character (n is number)
                             There are of course no spaces in these escape
                             codes...
                            */
                        }
                    }
                    try {
                        Globals.memory.set(dataAddress.get(), theChar.digitToInt(), DataTypes.CHAR_SIZE)
                    } catch (e: AddressErrorException) {
                        errors.add(ErrorMessage(
                            token.sourceMipsProgram,
                            token.sourceLine,
                            token.startPosition,
                            "\"${dataAddress.get()}\" is not a valid data segment address!"
                        ))
                    }
                    dataAddress.increment(DataTypes.CHAR_SIZE)
                    j++
                }
                if (direct == Directives.ASCIIZ) {
                    try {
                        Globals.memory.set(dataAddress.get(), 0, DataTypes.CHAR_SIZE)
                    } catch (e: AddressErrorException) {
                        errors.add(ErrorMessage(
                            token.sourceMipsProgram,
                            token.sourceLine,
                            token.startPosition,
                            "\"${dataAddress.get()}\" is not a valid data segment address!"
                        ))
                    }
                    dataAddress.increment(DataTypes.CHAR_SIZE)
                }
            }
        }
    }

    /**
     * Check to see if we are in the data segment. Generate error if not.
     */
    private fun passesDataSegmentCheck(token: Token): Boolean {
        if (!inDataSegment) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${token.value}\" directive cannot appear in text segment!"
            ))
            return false
        } else return true
    }

    /**
     * Writes the given int value into the current data segment address. Works for
     * all the integer types plus float (caller is responsible for doing floatToIntBits).
     * Returns address at which the value was stored.
     */
    private fun writeToDataSegment(value: Int, lengthInBytes: Int, token: Token, errors: ErrorList): Int {
        if (autoAlign) dataAddress.set(alignToBoundary(dataAddress.get(), lengthInBytes))
        try {
            Globals.memory.set(dataAddress.get(), value, lengthInBytes)
        } catch (e: AddressErrorException) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${dataAddress.get()}\" is not a valid data segment address!"
            ))
            return dataAddress.get()
        }
        val address = dataAddress.get()
        dataAddress.increment(lengthInBytes)
        return address
    }

    /**
     * Writes the given double value into the current data segment address.
     * Works only for DOUBLE floating point values; Memory class doesn't have a method for writing eight bytes, so
     * use setWord twice.
     */
    private fun writeDoubleToDataSegment(value: Double, token: Token, errors: ErrorList) {
        val lengthInBytes = DataTypes.DOUBLE_SIZE
        if (autoAlign) dataAddress.set(alignToBoundary(dataAddress.get(), lengthInBytes))
        try {
            Globals.memory.setDouble(dataAddress.get(), value)
        } catch (e: AddressErrorException) {
            errors.add(ErrorMessage(
                token.sourceMipsProgram,
                token.sourceLine,
                token.startPosition,
                "\"${dataAddress.get()}\" is not a valid data segment address!"
            ))
            return
        }
        dataAddress.increment(lengthInBytes)
    }

    /**
     * If the address is multiple of byte boundary, return the address.
     * Otherwise, returns the address which is next higher multiple of the byte boundary.
     * Used for aligning data segment.
     * For instance, if args are 6 and 4, returns 8 (next multiple of 4 higher than 6).
     * NOTE: it will fix any symbol table entries for this address too. See else part.
     */
    private fun alignToBoundary(address: Int, byteBoundary: Int): Int {
        val remainder = address % byteBoundary
        if (remainder == 0) return address
        val alignedAddress = address + byteBoundary - remainder
        fileCurrentlyBeingAssembled.getLocalSymbolTable().fixSymbolTableAddress(address, alignedAddress)
        return alignedAddress
    }

    /**
     * Private class used as Comparator to sort the final ArrayList of ProgramStatements.
     * Sorting is based on unsigned integer value of ProgramStatement.getAddress().
     */
    private class ProgramStatementComparator: Comparator<ProgramStatement> {
        /**
         * Will be used to sort the collection.
         * Unsigned int compare, because all kernel 32-bit addresses have one in the high-order bit,
         * which makes the int negative.
         * "Unsigned" compare is needed when signs of the two operands differ.
         */
        override fun compare(o1: ProgramStatement?, o2: ProgramStatement?): Int {
            val addr1 = o1?.getAddress() ?: throw ClassCastException()
            val addr2 = o2?.getAddress() ?: throw ClassCastException()
            return if (addr1 < 0 && addr2 >= 0 || addr1 >= 0 && addr2 < 0) addr2 else addr1 - addr2
        }
    }

    /**
     * Private class to track addresses in both user and kernel address spaces.
     * Instantiate one for the data segment and one for the text segment.
     */
    private class UserKernelAddressSpace(userBase: Int, kernelBase: Int) {
        val address: HashMap<AddressSpace, Int>
        var currentAddressSpace: AddressSpace

        init {
            address = hashMapOf(AddressSpace.USER to userBase, AddressSpace.KERNEL to kernelBase)
            currentAddressSpace = AddressSpace.USER
        }

        fun get(): Int = address[currentAddressSpace]!!

        fun set(value: Int) {
            address[currentAddressSpace] = value
        }

        fun increment(by: Int) {
            address[currentAddressSpace] = address[currentAddressSpace]!! + by
        }

        @Deprecated("Use currentAddressSpace setter instead.", ReplaceWith("currentAddressSpace = addressSpace"))
        fun setAddressSpace(addressSpace: AddressSpace) {
            currentAddressSpace = addressSpace
        }

        enum class AddressSpace(val rawValue: Int) {
            USER(0), KERNEL(1);
        }
    }

    /**
     * Handy class to handle forward label references appearing as data segment operands.
     * This is needed because the data segment is completely processed by the end of the first assembly pass, and its
     * directives may contain labels as operands.
     * When this occurs, the label's associated address becomes the operand value.
     * If it is a forward reference, we will save the necessary information in this object for finding and patching in
     * the correct address at the end of the first pass for this file or for all files if there is more than one.
     * If such a parsed label refers to a local or global label not defined yet, pertinent information is added to this
     * object:
     * - memory address that needs the label's address,
     * - number of bytes (addresses are 4 bytes but may be used with any of the integer directives: .word, .half, .byte)
     * - the label's token.
     * Normally need only the name but the error message needs more.
     */
    private class DataSegmentForwardReferences {
        private val forwardReferenceList = arrayListOf<DataSegmentForwardReference>()

        fun size() = forwardReferenceList.size

        /**
         * Add a new forward reference entry.
         *
         * @param patchAddress The memory address that receives the label's address once resolved
         * @param length The number of address bytes to store (1 for .byte, 2 for .half, 4 for .word)
         * @param token The label's token
         */
        fun add(patchAddress: Int, length: Int, token: Token) {
            forwardReferenceList.add(DataSegmentForwardReference(patchAddress, length, token))
        }

        /**
         * Add the entries from another DataSegmentForwardReferences instance.
         * Can be used at the end of each source file to dump all unresolved references into a common list to be
         * processed after all source files are parsed.
         */
        fun add(other: DataSegmentForwardReferences) {
            forwardReferenceList.addAll(other.forwardReferenceList)
        }

        /**
         * Clear the list. Allows re-use.
         */
        fun clear() {
            forwardReferenceList.clear()
        }

        /**
         * Will traverse the list of forward references, attempting to resolve them.
         * For each entry, it will first search the provided local symbol table and
         * failing that, the global one. If passed the global symbol table, it will
         * perform a second, redundant, search. If search is successful, the patch
         * is applied and the forward reference removed. If search is not successful,
         * the forward reference remains (it is either undefined or a global label
         * defined in a file not yet parsed).
         */
        fun resolve(localTable: SymbolTable): Int {
            var count = 0
            var entry: DataSegmentForwardReference
            var i = 0
            while (i < forwardReferenceList.size) {
                entry = forwardReferenceList[i]
                localTable.getLocalOrGlobalAddressOrNull(entry.token.value)?.let {
                    // Patch address has to be valid because we've already stored it there...
                    try {
                        Globals.memory.set(entry.patchAddress, it, entry.length)
                    } catch (ignored: AddressErrorException) {}
                    forwardReferenceList.removeAt(i)
                    // Necessary because the removal shifted the remaining list indices down.
                    i--
                    count++
                }
                i++
            }
            return count
        }

        /**
         * Call this function only after you're confident that remaining list entries are to undefined labels.
         */
        fun generateErrorMessages(errors: ErrorList) {
            for (entry in forwardReferenceList) {
                errors.add(ErrorMessage(
                    entry.token.sourceMipsProgram,
                    entry.token.sourceLine,
                    entry.token.startPosition,
                    "Symbol \"${entry.token.value}\" not found in symbol table!"
                ))
            }
        }

        private data class DataSegmentForwardReference(val patchAddress: Int, val length: Int, val token: Token)
    }
}