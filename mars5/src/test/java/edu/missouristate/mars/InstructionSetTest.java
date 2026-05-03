package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.mips.instructions.BasicInstruction;
import edu.missouristate.mars.mips.instructions.ExtendedInstruction;
import edu.missouristate.mars.mips.instructions.Instruction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests: verify that every instruction in the instruction set has valid, non-null
 * metadata. These tests pin the structure of the instruction set so that future refactoring (e.g.,
 * splitting InstructionSet.java into data-driven definitions) cannot silently drop or corrupt an
 * instruction.
 */
class InstructionSetTest {

  // Pinned at initialization time so regressions in instruction count are visible.
  private static List<Instruction> allInstructions;
  private static List<BasicInstruction> basicInstructions;
  private static List<ExtendedInstruction> extendedInstructions;

  @BeforeAll
  static void initMars() {
    Globals.initialize(false);
    ArrayList<Instruction> raw = Globals.instructionSet.getInstructionList();
    allInstructions = new ArrayList<>(raw);
    basicInstructions = new ArrayList<>();
    extendedInstructions = new ArrayList<>();
    for (Object o : raw) {
      if (o instanceof BasicInstruction b) basicInstructions.add(b);
      else if (o instanceof ExtendedInstruction e) extendedInstructions.add(e);
    }
  }

  @Test
  void instructionListIsNotEmpty() {
    assertThat(allInstructions).isNotEmpty();
  }

  @Test
  void basicInstructionCountIsStable() {
    // Pin the exact count so accidental addition or deletion is immediately visible.
    // Adjust this value only when intentionally adding or removing basic instructions.
    assertThat(basicInstructions)
        .as("Expected exactly 155 basic instructions; adjust if intentionally changed")
        .hasSize(155);
  }

  @Test
  void extendedInstructionCountIsStable() {
    assertThat(extendedInstructions)
        .as("Extended (pseudo) instructions should be present")
        .isNotEmpty();
  }

  @Test
  void everyInstructionHasNonNullName() {
    for (Instruction instr : allInstructions) {
      assertThat(instr.getName())
          .as("Instruction at index %d has null name", allInstructions.indexOf(instr))
          .isNotNull()
          .isNotEmpty();
    }
  }

  @Test
  void everyInstructionHasNonNullExampleFormat() {
    for (Instruction instr : allInstructions) {
      assertThat(instr.getExampleFormat())
          .as("Instruction '%s' has null exampleFormat", instr.getName())
          .isNotNull()
          .isNotEmpty();
    }
  }

  @Test
  void everyInstructionHasNonNullDescription() {
    for (Instruction instr : allInstructions) {
      assertThat(instr.getDescription())
          .as("Instruction '%s' has null description", instr.getName())
          .isNotNull()
          .isNotEmpty();
    }
  }

  @Test
  void everyBasicInstructionHasNonNullOperationMask() {
    for (BasicInstruction instr : basicInstructions) {
      assertThat(instr.getOperationMask())
          .as("BasicInstruction '%s' has null operationMask", instr.getName())
          .isNotNull()
          .isNotEmpty();
    }
  }

  @Test
  void knownDuplicateMnemonicsAreStable() {
    // MARS intentionally registers multiple BasicInstruction entries under the same mnemonic
    // for instructions that have both register and immediate forms (e.g. trap instructions
    // teq/tne/tlt/tltu/tge/tgeu which have both R-type and I-type encodings). Pin this count.
    long distinctNames = basicInstructions.stream().map(Instruction::getName).distinct().count();
    long duplicates = basicInstructions.size() - distinctNames;
    assertThat(duplicates)
        .as("Expected exactly 16 duplicate mnemonics (R-type + I-type pairs); adjust if changed")
        .isEqualTo(16);
  }

  @Test
  void coreInstructionsArePresent() {
    List<String> coreInstructions =
        List.of(
            "add", "addi", "sub", "and", "or", "nor", "slt", "slti", "lw", "sw", "beq", "bne", "j",
            "jal", "jr", "sll", "srl", "sra", "mult", "div", "mfhi", "mflo");
    List<String> names = allInstructions.stream().map(Instruction::getName).toList();
    for (String core : coreInstructions) {
      assertThat(names)
          .as("Core instruction '%s' is missing from instruction set", core)
          .contains(core);
    }
  }
}
