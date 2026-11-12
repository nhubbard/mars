package edu.missouristate.mars.assembler

import edu.missouristate.mars.ErrorList
import edu.missouristate.mars.MIPSprogram
import edu.missouristate.mars.ProgramStatement
import edu.missouristate.mars.core.AssemblyContext
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.Instruction.Companion.INSTRUCTION_LENGTH

/** Expands a pseudo-instruction using the session's memory layout and branch policy. */
internal class PseudoInstructionExpander(private val context: AssemblyContext) {
    fun expand(statement: ProgramStatement, program: MIPSprogram, errors: ErrorList): ArrayList<ProgramStatement> {
        val instruction = statement.instruction as ExtendedInstruction
        val templates = checkNotNull(if (compact(context, statement)) {
            instruction.compactBasicIntructionTemplateList
        } else {
            instruction.basicIntructionTemplateList
        })
        val tokenizer = context.tokenizer(program)
        val line = statement.sourceLine
        val operands = tokenizer.tokenizeLine(line, statement.basicAssemblyStatement, errors, false)
        val result = ArrayList<ProgramStatement>(templates.size)
        var address = statement.address
        for ((index, template) in templates.withIndex()) {
            val expanded = ExtendedInstruction.makeTemplateSubstitutions(program, template, operands)
            if (expanded.isEmpty()) continue
            val tokens = tokenizer.tokenizeLine(line, expanded, errors, false)
            val matches = context.instructions().matchOperator(tokens[0].value)
            val basic = OperandFormat.bestOperandMatch(tokens, matches, context.bareMachineEnabled())
            val generated = ProgramStatement(
                program, if (index == 0) statement.source else "", tokens, tokens, basic, address,
                line, statement.instructionOrigin, statement.sourceOperands,
            )
            generated.buildBasicStatementFromBasicInstruction(errors)
            result.add(generated)
            address += INSTRUCTION_LENGTH
        }
        return result
    }

    companion object {
        @JvmStatic
        fun compact(context: AssemblyContext, statement: ProgramStatement): Boolean {
            val instruction = statement.instruction as? ExtendedInstruction ?: return false
            return context.memory().usingCompactMemoryConfiguration() && instruction.hasCompactTranslation()
        }
    }
}
