package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.AssemblyContext;
import edu.missouristate.mars.mips.instructions.*;
import java.util.ArrayList;

/** Expands one pseudo-instruction using the owning session's layout and branch policy. */
final class PseudoInstructionExpander {
  private final AssemblyContext context;

  PseudoInstructionExpander(AssemblyContext context) {
    this.context = context;
  }

  static boolean compact(AssemblyContext context, ProgramStatement statement) {
    return statement.getInstruction() instanceof ExtendedInstruction instruction
        && context.memory().usingCompactMemoryConfiguration()
        && instruction.hasCompactTranslation();
  }

  ArrayList<ProgramStatement> expand(
      ProgramStatement statement, MIPSprogram program, ErrorList errors) {
    var instruction = (ExtendedInstruction) statement.getInstruction();
    var templates =
        compact(context, statement)
            ? instruction.getCompactBasicIntructionTemplateList()
            : instruction.getBasicIntructionTemplateList();
    var tokenizer = context.tokenizer(program);
    var operands =
        tokenizer.tokenizeLine(
            statement.getSourceLine(), statement.getBasicAssemblyStatement(), errors, false);
    var result = new ArrayList<ProgramStatement>();
    int address = statement.getAddress();
    for (int index = 0; index < templates.size(); index++) {
      String expanded =
          ExtendedInstruction.makeTemplateSubstitutions(program, templates.get(index), operands);
      if (expanded == null || expanded.isEmpty()) {
        continue;
      }
      var tokens = tokenizer.tokenizeLine(statement.getSourceLine(), expanded, errors, false);
      var matches = context.instructions().matchOperator(tokens.get(0).getValue());
      var basic = OperandFormat.bestOperandMatch(tokens, matches, context.bareMachineEnabled());
      var generated =
          new ProgramStatement(
              program,
              index == 0 ? statement.getSource() : "",
              tokens,
              tokens,
              basic,
              address,
              statement.getSourceLine(),
              statement.getInstructionOrigin(),
              statement.getSourceOperands());
      generated.buildBasicStatementFromBasicInstruction(errors);
      result.add(generated);
      address += Instruction.INSTRUCTION_LENGTH;
    }
    return result;
  }
}
