package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64PermutationTest {
    @ParameterizedTest(name = "{0}")
    @MethodSource("operations")
    fun `wide permutations and scaled addressing match Release 6`(
        source: String, word: Int, expected: Long, @TempDir directory: Path,
    ) {
        val file = Files.writeString(directory.resolve("wide.s"), "$source\n")
        TestSessions.create().use { session ->
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, false, false) }
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
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
    @ValueSource(strings = ["dlsa \$9,\$10,\$11,0", "dlsa \$9,\$10,\$11,5", "dalign \$9,\$10,\$11,8"])
    fun `out of range immediates are rejected`(source: String, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("invalid.s"), "$source\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, false, false) }
        }
    }

    companion object {
        // Instruction words from LLVM for mips64r6. Results are written as full-width bit patterns.
        @JvmStatic
        fun operations() = listOf(
            Arguments.of("dbitswap \$9,\$10", 0x7c0a4824, 0x80c4_a2e6_91d5_b3f7uL.toLong()),
            Arguments.of("dsbh \$9,\$10", 0x7c0a48a4, 0x2301_6745_ab89_efcdL),
            Arguments.of("dshd \$9,\$10", 0x7c0a4964, 0xcdef_89ab_4567_0123uL.toLong()),
            Arguments.of("dlsa \$9,\$10,\$11,2", 0x014b4855, 0x0369_d036_9d03_69ccL),
            Arguments.of("dalign \$9,\$10,\$11,0", 0x7d4b4a24, 0xfedc_ba98_7654_3210uL.toLong()),
            Arguments.of("dalign \$9,\$10,\$11,2", 0x7d4b4aa4, 0xba98_7654_3210_0123uL.toLong()),
            Arguments.of("dalign \$9,\$10,\$11,7", 0x7d4b4be4, 0x1001_2345_6789_abcdL),
        )
    }
}
