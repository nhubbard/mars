package edu.missouristate.mars

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.ExtendedInstruction
import edu.missouristate.mars.mips.instructions.Instruction
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * Characterization tests: verify that every instruction in the instruction set has valid, non-null
 * metadata. These tests pin the structure of the instruction set so that future refactoring (e.g.,
 * splitting InstructionSet.java into data-driven definitions) cannot silently drop or corrupt an
 * instruction.
 */
internal class InstructionSetTest {
    @Test
    fun instructionListIsNotEmpty() {
        Assertions.assertThat<Instruction?>(allInstructions).isNotEmpty()
    }

    @Test
    fun basicInstructionCountIsStable() {
        // Pin the exact count so accidental addition or deletion is immediately visible.
        // Adjust this value only when intentionally adding or removing basic instructions.
        Assertions.assertThat(basicInstructions)
            .`as`("Expected exactly 213 basic instructions; adjust if intentionally changed")
            .hasSize(213)
    }

    @Test
    fun extendedInstructionCountIsStable() {
        Assertions.assertThat<ExtendedInstruction?>(extendedInstructions)
            .`as`("Extended (pseudo) instructions should be present")
            .isNotEmpty()
    }

    @Test
    fun everyInstructionHasNonNullName() {
        for (instr in allInstructions!!) {
            Assertions.assertThat(instr.name)
                .`as`("Instruction at index %d has null name", allInstructions!!.indexOf(instr))
                .isNotNull()
                .isNotEmpty()
        }
    }

    @Test
    fun everyInstructionHasNonNullExampleFormat() {
        for (instr in allInstructions!!) {
            Assertions.assertThat(instr.exampleFormat)
                .`as`("Instruction '%s' has null exampleFormat", instr.name)
                .isNotNull()
                .isNotEmpty()
        }
    }

    @Test
    fun everyInstructionHasNonNullDescription() {
        for (instr in allInstructions!!) {
            Assertions.assertThat(instr.description)
                .`as`("Instruction '%s' has null description", instr.name)
                .isNotNull()
                .isNotEmpty()
        }
    }

    @Test
    fun everyBasicInstructionHasNonNullOperationMask() {
        for (instr in basicInstructions!!) {
            Assertions.assertThat(instr.operationMask)
                .`as`("BasicInstruction '%s' has null operationMask", instr.name)
                .isNotNull()
                .isNotEmpty()
        }
    }

    @Test
    fun knownDuplicateMnemonicsAreStable() {
        // MARS intentionally registers multiple BasicInstruction entries under the same mnemonic
        // for instructions that have both register and immediate forms (e.g. trap instructions
        // teq/tne/tlt/tltu/tge/tgeu which have both R-type and I-type encodings). Pin this count.
        val distinctNames: Long =
            basicInstructions!!.stream().map<String?>(Instruction::name).distinct().count()
        val duplicates: Long = basicInstructions!!.size - distinctNames
        Assertions.assertThat(duplicates)
            .`as`(
                "Expected exactly 16 duplicate mnemonics (R-type + I-type pairs); adjust if changed",
            )
            .isEqualTo(16)
    }

    @Test
    fun coreInstructionsArePresent() {
        val coreInstructions: MutableList<String?> =
            mutableListOf(
                "add",
                "addi",
                "sub",
                "and",
                "or",
                "nor",
                "slt",
                "slti",
                "lw",
                "sw",
                "beq",
                "bne",
                "j",
                "jal",
                "jr",
                "sll",
                "srl",
                "sra",
                "mult",
                "div",
                "mfhi",
                "mflo",
            )
        val names = checkNotNull(allInstructions).map(Instruction::name)
        for (core in coreInstructions) {
            Assertions.assertThat<String?>(names)
                .`as`("Core instruction '%s' is missing from instruction set", core)
                .contains(core)
        }
    }

    companion object {
        // Pinned at initialization time so regressions in instruction count are visible.
        private var allInstructions: MutableList<Instruction>? = null
        private var basicInstructions: MutableList<BasicInstruction>? = null
        private var extendedInstructions: MutableList<ExtendedInstruction>? = null

        @JvmStatic
        @BeforeAll
        fun initMars() {
            TestSessions.create().use { session ->
                allInstructions = ArrayList(session.instructionSet.instructionList)
            }
            basicInstructions = ArrayList()
            extendedInstructions = ArrayList()
            for (instruction in allInstructions!!) {
                if (instruction is BasicInstruction) {
                    basicInstructions!!.add(instruction)
                } else if (instruction is ExtendedInstruction) {
                    extendedInstructions!!.add(instruction)
                }
            }
        }
    }
}
