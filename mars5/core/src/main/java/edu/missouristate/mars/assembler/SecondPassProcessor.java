package edu.missouristate.mars.assembler;

import edu.missouristate.mars.*;
import edu.missouristate.mars.core.AssemblyContext;
import edu.missouristate.mars.mips.hardware.AddressErrorException;
import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.util.NumberDisplayBase;
import java.util.ArrayList;

/** Resolves operands, expands pseudos, emits machine words and verifies text-address uniqueness. */
final class SecondPassProcessor {
  private final AssemblyContext context;
  private final Runnable checkCancellation;

  SecondPassProcessor(AssemblyContext context, Runnable checkCancellation) {
    this.context = context;
    this.checkCancellation = checkCancellation;
  }

  ArrayList<ProgramStatement> process(
      ArrayList<MIPSprogram> programs, ErrorList errors, boolean warningsAreErrors)
      throws ProcessingException {
    var machine = new ArrayList<ProgramStatement>();
    var expander = new PseudoInstructionExpander(context);
    for (var program : programs) {
      if (errors.errorLimitExceeded()) {
        break;
      }
      for (var statement : program.getParsedList()) {
        checkCancellation.run();
        statement.buildBasicStatementFromBasicInstruction(errors);
        if (errors.errorsOccurred()) {
          throw new ProcessingException(errors);
        }
        if (statement.getInstruction() instanceof BasicInstruction) {
          machine.add(statement);
        } else {
          machine.addAll(expander.expand(statement, program, errors));
        }
      }
    }
    for (var statement : machine) {
      checkCancellation.run();
      if (errors.errorLimitExceeded()) {
        break;
      }
      statement.buildMachineStatementFromBasicStatement(errors);
      try {
        context.memory().setStatement(statement.getAddress(), statement);
      } catch (AddressErrorException error) {
        Token token = statement.getOriginalTokenList().get(0);
        errors.add(
            new ErrorMessage(
                token.getSourceMIPSprogram(),
                token.getSourceLine(),
                token.getStartPos(),
                "Invalid address for text segment: " + error.getAddress()));
      }
    }
    machine.sort((first, second) -> {
      checkCancellation.run();
      return Integer.compareUnsigned(first.getAddress(), second.getAddress());
    });
    catchDuplicateAddresses(machine, errors);
    if (errors.errorsOccurred() || errors.warningsOccurred() && warningsAreErrors) {
      throw new ProcessingException(errors);
    }
    return machine;
  }

  private void catchDuplicateAddresses(ArrayList<ProgramStatement> instructions, ErrorList errors) {
    for (int i = 0; i < instructions.size() - 1; i++) {
      checkCancellation.run();
      ProgramStatement ps1 = instructions.get(i);
      ProgramStatement ps2 = instructions.get(i + 1);
      if (ps1.getAddress() == ps2.getAddress()) {
        errors.add(
            new ErrorMessage(
                ps2.getSourceMIPSprogram(),
                ps2.getSourceLine(),
                0,
                "Duplicate text segment address: "
                    + NumberDisplayBase.formatUnsignedInteger(
                        ps2.getAddress(), (context.displayAddressesInHex()) ? 16 : 10)
                    + " already occupied by "
                    + ps1.getSourceFile()
                    + " line "
                    + ps1.getSourceLine()
                    + " (caused by use of "
                    + ((context.memory().inTextSegment(ps2.getAddress())) ? ".text" : ".ktext")
                    + " operand)"));
      }
    }
  }
}
