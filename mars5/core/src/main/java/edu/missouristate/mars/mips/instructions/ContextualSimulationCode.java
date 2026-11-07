package edu.missouristate.mars.mips.instructions;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.ProgramStatement;
import edu.missouristate.mars.core.InstructionContext;

/** Instruction implementation that receives its execution dependencies explicitly. */
@FunctionalInterface
public interface ContextualSimulationCode extends SimulationCode {
  @Override
  void simulate(ProgramStatement statement, InstructionContext context) throws ProcessingException;

  @Override
  default void simulate(ProgramStatement statement) throws ProcessingException {
    simulate(statement, InstructionContext.legacy());
  }
}
