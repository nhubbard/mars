package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64ScalarInstructionTest {
    @ParameterizedTest
    @ValueSource(ints = [0x7d49ffc1, 0x7d49ffc2, 0x7d4907c7, 0x7d4907c6])
    fun `invalid doubleword bitfield encodings are rejected`(word: Int) {
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            assertNull(session.instructionSet.findByBinaryCode(word))
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    fun `wide scalar instructions match Release 6`(
        source: String, word: Int, expected: Long, @TempDir directory: Path,
    ) {
        val file = Files.writeString(directory.resolve("wide.s"), "$source\n")
        TestSessions.create().use { session ->
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, extended = false, warnings = false) }
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val statement = program.machineList!!.single()
            assertEquals(word, statement.binaryStatement)
            val decoded = ProgramStatement(word, statement.address, session.instructionSet)
            assertEquals(statement.operands.toList(), decoded.operands.toList())
            session.editIntegerRegister(9, 123)
            session.editIntegerRegister(10, 0x0123_4567_89ab_cdefL)
            session.editIntegerRegister(11, 0xfedc_ba98_7654_3210uL.toLong())
            program.simulate(1)
            assertEquals(expected, session.registerFile.getLongValueNoNotify(9))
            session.backStepper.backStep()
            assertEquals(123L, session.registerFile.getLongValueNoNotify(9))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "dlsa $9,$10,$11,0", "dlsa $9,$10,$11,5", "dalign $9,$10,$11,8",
        "dahi $9,$10,1", "dati $9,$10,1", $$"daui $9,$zero,1",
        "dext $9,$10,64,1", "dext $9,$10,0,0", "dins $9,$10,31,34",
        "dextm $9,$10,1,8", "dextu $9,$10,31,8",
        "dinsm $9,$10,1,8", "dinsu $9,$10,31,1",
    ])
    fun `invalid operands are rejected`(source: String, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("invalid.s"), "$source\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, extended = false, warnings = false) }
        }
    }

    companion object {
        // Instruction words from LLVM for mips64r6. Results are written as full-width bit patterns.
        @JvmStatic
        fun operations() = listOf(
            Arguments.of("dbitswap $9,$10", 0x7c0a4824, 0x80c4_a2e6_91d5_b3f7uL.toLong()),
            Arguments.of("dsbh $9,$10", 0x7c0a48a4, 0x2301_6745_ab89_efcdL),
            Arguments.of("dshd $9,$10", 0x7c0a4964, 0xcdef_89ab_4567_0123uL.toLong()),
            Arguments.of("dlsa $9,$10,$11,2", 0x014b4855, 0x0369_d036_9d03_69ccL),
            Arguments.of("dalign $9,$10,$11,0", 0x7d4b4a24, 0xfedc_ba98_7654_3210uL.toLong()),
            Arguments.of("dalign $9,$10,$11,2", 0x7d4b4aa4, 0xba98_7654_3210_0123uL.toLong()),
            Arguments.of("dalign $9,$10,$11,7", 0x7d4b4be4, 0x1001_2345_6789_abcdL),
            Arguments.of("dahi $9,$9,65535", 0x0526ffff, -0x1_0000_0000L + 123),
            Arguments.of("dati $9,$9,65535", 0x053effff, -0x1_0000_0000_0000L + 123),
            Arguments.of("daui $9,$10,65535", 0x7549ffff, 0x0123_4567_89aa_cdefL),
            Arguments.of("dext $9,$10,1,8", 0x7d493843, 0xf7L),
            Arguments.of("dext $9,$10,1,40", 0x7d493841, 0xb3_c4d5_e6f7L),
            Arguments.of("dext $9,$10,33,8", 0x7d493842, 0xb3L),
            Arguments.of("dext $9,$10,0,64", 0x7d49f801, 0x0123_4567_89ab_cdefL),
            Arguments.of("dext $9,$10,31,32", 0x7d49ffc3, 0x0246_8acfL),
            Arguments.of("dext $9,$10,63,1", 0x7d4907c2, 0L),
            Arguments.of("dextm $9,$10,1,40", 0x7d493841, 0xb3_c4d5_e6f7L),
            Arguments.of("dextu $9,$10,33,8", 0x7d493842, 0xb3L),
            Arguments.of("dins $9,$10,1,8", 0x7d494047, 0x1dfL),
            Arguments.of("dins $9,$10,1,40", 0x7d494045, 0xcf_1357_9bdfL),
            Arguments.of("dins $9,$10,33,8", 0x7d494046, 0x1de_0000_007bL),
            Arguments.of("dins $9,$10,0,64", 0x7d49f805, 0x0123_4567_89ab_cdefL),
            Arguments.of("dins $9,$10,31,2", 0x7d4907c5, 0x1_8000_007bL),
            Arguments.of("dins $9,$10,63,1", 0x7d49ffc6, Long.MIN_VALUE + 123),
            Arguments.of("dinsm $9,$10,1,40", 0x7d494045, 0xcf_1357_9bdfL),
            Arguments.of("dinsu $9,$10,33,8", 0x7d494046, 0x1de_0000_007bL),
        )
    }
}
