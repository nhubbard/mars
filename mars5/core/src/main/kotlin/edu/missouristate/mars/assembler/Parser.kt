package edu.missouristate.mars.assembler

import edu.missouristate.mars.mips.instructions.WideAddressMemory
import edu.missouristate.mars.util.Binary
import edu.missouristate.mars.ErrorMessage
import edu.missouristate.mars.ErrorMessage.Companion.WARNING
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.api.AssemblySymbolMap.MacroCall
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken
import edu.missouristate.mars.api.InstructionSourceMap.Location
import edu.missouristate.mars.api.InstructionSourceMap.Origin
import edu.missouristate.mars.api.InstructionSourceMap.SourceOperand
import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.basic.InstrCodedTrap
import edu.missouristate.mars.mips.instructions.Release6ImmediateTrap
import edu.missouristate.mars.mips.instructions.Instruction
import edu.missouristate.mars.mips.instructions.Instruction.Companion.coprocessor0Transfers
import java.nio.file.Path

/*
Copyright (c) 2003-2012,  Pete Sanderson and Kenneth Vollmar

Developed by Pete Sanderson (psanderson@otterbein.edu)
and Kenneth Vollmar (kenvollmar@missouristate.edu)

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject
to the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR
ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF
CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

(MIT license, http://www.opensource.org/licenses/mit-license.html)
*/

/** Parses a line in the first pass and expands any macro invocations with their source origins. */
internal class Parser(private val pass: FirstPassProcessor) {
    fun parseLine(tokens: TokenList, physicalLine: SourceLine, extendedAssemblerEnabled: Boolean): ArrayList<ProgramStatement>? =
        parseLine(tokens, physicalLine.source, physicalLine.lineNumber, extendedAssemblerEnabled, physicalLine, null, emptySet())

    private fun parseLine(
        tokenList: TokenList,
        source: String,
        sourceLineNumber: Int,
        extendedAssemblerEnabled: Boolean,
        physicalLine: SourceLine,
        caller: Origin?,
        renamedLabels: Set<String>,
    ): ArrayList<ProgramStatement>? {
        val tokens = stripComment(tokenList)
        val macroPool = pass.fileCurrentlyBeingAssembled.localMacroPool!!
        if (pass.inMacroSegment) {
            pass.detectLabels(tokens, macroPool.current)
        } else {
            pass.stripLabels(tokens, physicalLine, caller, renamedLabels)
        }
        if (tokens.isEmpty) return null

        val operator = tokens[0]
        val type = operator.type
        if (type == TokenTypes.DIRECTIVE) {
            pass.executeDirective(tokens, physicalLine, caller, renamedLabels)
            return null
        }
        if (pass.inMacroSegment) return null

        val macroTokens: TokenList
        val closing: Token?
        if (Macro.hasCallParentheses(tokens)) {
            macroTokens = tokens.clone() as TokenList
            closing = tokens[tokens.size() - 1]
            macroTokens.remove(tokens.size() - 1)
            macroTokens.remove(1)
        } else {
            macroTokens = tokens
            closing = null
        }
        val macro = macroPool.getMatchingMacro(macroTokens, sourceLineNumber)
        if (macro != null) {
            return expandMacro(
                macroPool, macro, macroTokens, operator, closing, sourceLineNumber, extendedAssemblerEnabled,
                physicalLine, caller, renamedLabels,
            )
        }

        if (type == TokenTypes.IDENTIFIER && operator.value[0] == '.') {
            pass.errors.add(ErrorMessage(WARNING, operator, "MARS does not recognize the ${operator.value} directive.  Ignored."))
            return null
        }

        if (pass.inDataSegment) {
            if (TokenTypes.startsDataContinuation(type)) pass.executeDirectiveContinuation(tokens)
            return null
        }

        val matches = pass.matchInstruction(operator) ?: return ArrayList()
        if (pass.context.session.target == MarsTarget.MARS64) {
            val removedOperandForm = release6DivOperandMigration(tokens)
            if (removedOperandForm != null) {
                pass.errors.add(ErrorMessage(operator, removedOperandForm))
                return null
            }
            if (!parseAddressRelocations(tokens, pass.numericLabels, pass.errors)) return null
            bindNumericReferences(tokens, matches)
        }
        val instruction = OperandFormat.bestOperandMatch(tokens, matches, pass.context.bareMachineEnabled())!!
        if (instruction is ExtendedInstruction && !extendedAssemblerEnabled) {
            pass.errors.add(ErrorMessage(operator, "Extended (pseudo) instruction or format not permitted.  See Settings."))
        }
        if (!OperandFormat.tokenOperandMatch(tokens, instruction, pass.errors, pass.context.bareMachineEnabled())) return null
        if (instruction is InstrCodedTrap && Binary.stringToInt(tokens[3].value) !in 0..InstrCodedTrap.MAX_CODE) {
            pass.errors.add(ErrorMessage(tokens[3], "Trap code must be an unsigned 10-bit integer"))
            return null
        }
        if (instruction.name == "dli") {
            try {
                Binary.stringToDoubleword(tokens[2].value)
            } catch (_: NumberFormatException) {
                pass.errors.add(ErrorMessage(tokens[2], "Invalid 64-bit integer constant"))
                return null
            }
        }
        if (pass.context.session.target == MarsTarget.MARS64 && instruction.name == "addi") {
            val scratch = (1..2).map(tokens::get).firstOrNull { RegisterNames.integer(it.value)?.number == 1 }
            if (scratch != null) {
                pass.errors.add(ErrorMessage(scratch, $$"This addi pseudo-instruction uses $at. Load the immediate into a different temporary and use add."))
                return null
            }
        }
        if (instruction is Release6ImmediateTrap && RegisterNames.integer(tokens[1].value)?.number == 1) {
            pass.errors.add(ErrorMessage(tokens[1], $$"This immediate trap uses $at. Use a different temporary and a register trap."))
            return null
        }
        if (instruction is WideAddressMemory) {
            val baseIsTemporary = instruction.baseToken != 0 && RegisterNames.integer(tokens[instruction.baseToken].value)?.number == 1
            val sourceIsTemporary = instruction.stores && RegisterNames.integer(tokens[1].value)?.number == 1
            if (baseIsTemporary || sourceIsTemporary) {
                val operand = if (baseIsTemporary) instruction.baseToken else 1
                pass.errors.add(ErrorMessage(tokens[operand], $$"This address expansion uses $at. Use a different register or write the address calculation explicitly."))
                return null
            }
        }

        val instructionOrigin = origin(physicalLine, caller)
        val statement = ProgramStatement(
            pass.fileCurrentlyBeingAssembled, source, tokenList, tokens, instruction, pass.textAddress.get().toInt(),
            sourceLineNumber, instructionOrigin, sourceOperands(tokens, instruction, instructionOrigin.location), pass.textAddress.get(),
        )
        val delayedBranching = pass.context.settings().isDelayedBranchingEnabled
        var length = if (instruction is ExtendedInstruction) instruction.getInstructionLength(delayedBranching) else instruction.instructionLength
        if (pass.compactTranslationCanBeApplied(statement) && instruction is ExtendedInstruction) {
            length = instruction.getCompactInstructionLength(delayedBranching)
        }
        pass.textAddress.increment(length)
        return arrayListOf(statement)
    }

    private fun bindNumericReferences(tokens: TokenList, matches: List<Instruction>) {
        for (index in 1 until tokens.size()) {
            val value = tokens[index].value
            if (value.lastOrNull() != 'f' && value.lastOrNull() != 'b') continue
            if (matches.none { it.tokenList.size() == tokens.size() && it.tokenList[index].type == TokenTypes.IDENTIFIER }) continue
            val reference = pass.numericLabels.reference(tokens[index]) ?: continue
            tokens[index] = reference
        }
    }

    private fun expandMacro(
        pool: MacroPool,
        macro: Macro,
        tokens: TokenList,
        operator: Token,
        closing: Token?,
        sourceLineNumber: Int,
        extendedAssemblerEnabled: Boolean,
        physicalLine: SourceLine,
        caller: Origin?,
        renamedLabels: Set<String>,
    ): ArrayList<ProgramStatement> {
        val statements = ArrayList<ProgramStatement>()
        val counter = pool.getNextCounter().toLong()
        if (pool.pushOnCallStack(operator)) {
            pass.errors.add(ErrorMessage(operator, "Detected a macro expansion loop (recursive reference). "))
            return statements
        }

        try {
            val invocation = origin(physicalLine, caller)
            val physicalLines = pass.fileCurrentlyBeingAssembled.sourceLineList!!
            val call = SourceToken(invocation.location, operator.writtenOrigin().originalStartPos)
            val spelling = operator.equivalenceSource() ?: operator.diagnosticSite()
            val argumentColumns = (1 until tokens.size()).map { tokens[it].writtenOrigin().originalStartPos }
            val occurrence = MacroCall(
                macro.definitionSite(), macro.name, macro.args, argumentColumns, closing?.writtenOrigin()?.originalStartPos,
                spelling.takeUnless { it == call },
            )
            pass.macroCalls.getOrPut(call, ::HashSet).add(occurrence)
            for (line in (macro.fromLine + 1) until macro.toLine) {
                pass.checkCancellation()
                if (pass.errors.errorLimitExceeded()) break
                val previousErrors = pass.errors.errorCount()
                val expanded = macro.expandLine(line, tokens, counter, renamedLabels, pass.errors)
                val expandedTokens = pass.fileCurrentlyBeingAssembled.tokenizer!!
                    .tokenizeMacroLine(line, expanded, pass.errors, pass.checkCancellation)
                if (pass.errors.errorCount() > previousErrors) continue
                val substituted = expandedTokens.processedLine.ifEmpty { expanded.text }.trim { it <= ' ' }
                val displayLine = line - macro.fromLine + macro.originalFromLine
                val expandedStatements = parseLine(
                    expandedTokens, "<$displayLine> $substituted", sourceLineNumber, extendedAssemblerEnabled,
                    physicalLines[line - 1], invocation, expanded.renamedLabels,
                )
                if (expandedStatements != null) statements.addAll(expandedStatements)
            }
        } finally {
            pool.popFromCallStack()
        }
        return statements
    }

    private fun sourceOperands(tokens: TokenList, instruction: Instruction, location: Location): List<SourceOperand> {
        var operands: ArrayList<SourceOperand>? = null
        for (index in 1 until tokens.size()) {
            val token = tokens[index]
            val written = token.writtenOrigin()
            val arguments = token.argumentSites()
            if (arguments.isEmpty() && token.equivalenceSource() == null && token.value == written.value) continue
            val site = SourceToken(location, written.originalStartPos)
            val systemRegister = index == 2 && instruction.name in coprocessor0Transfers
            val result = operands ?: ArrayList<SourceOperand>().also { operands = it }
            result.add(SourceOperand(site, arguments, token.value, token.type, systemRegister))
        }
        return operands ?: emptyList()
    }

    private fun stripComment(tokens: TokenList): TokenList {
        if (tokens.isEmpty) return tokens
        val copy = tokens.clone() as TokenList
        val last = copy.size() - 1
        if (copy[last].type == TokenTypes.COMMENT) copy.remove(last)
        return copy
    }

    companion object {
        @JvmStatic
        fun origin(line: SourceLine, caller: Origin?): Origin =
            Origin(Location(Path.of(line.filename), line.lineNumber), caller)
    }
}
