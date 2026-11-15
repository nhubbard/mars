package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Mars64WordEncodingTest {
    @ParameterizedTest
    @CsvSource(
        "add $9,$10,$11;014b4820;b35801a9",
        "addiu $9,$10,-7;2549fff9;a123ab7a",
        "addu $9,$10,$11;014b4821;b35801a9",
        "align $9,$10,$11,0;7d4b4a20;12345628",
        "align $9,$10,$11,3;7d4b4ae0;28a123ab",
        "bitswap $9,$10;7c0a4820;85c4d581",
        "lsa $9,$10,$11,4;014b48c5;246f0e38",
        "nop;00000000;00000000",
        "rotr $9,$10,8;002a4a02;81a123ab",
        "rotrv $9,$10,$11;016a4846;81a123ab",
        "seb $9,$10;7c0a4c20;ffffff81",
        "seh $9,$10;7c0a4e20;ffffab81",
        "sll $9,$10,8;000a4a00;23ab8100",
        "sllv $9,$10,$11;016a4804;23ab8100",
        "sra $9,$10,8;000a4a03;ffa123ab",
        "srav $9,$10,$11;016a4807;ffa123ab",
        "srl $9,$10,8;000a4a02;00a123ab",
        "srlv $9,$10,$11;016a4806;00a123ab",
        "sub $9,$10,$11;014b4822;8eef5559",
        "subu $9,$10,$11;014b4823;8eef5559",
        "wsbh $9,$10;7c0a48a0;23a181ab",
        delimiter = ';',
    )
    fun `shared word operations assemble decode and sign extend`(
        assembly: String, encoding: String, result: String, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("word.s"), "$assembly\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            val word = encoding.toUInt(16).toInt()
            assertEquals(word, program.machineList!!.single().binaryStatement)
            assertEquals(assembly.substringBefore(' '), session.instructionSet.findByBinaryCode(word)?.name)
            session.editIntegerRegister(10, 0xa123_ab81u.toInt().toLong())
            session.editIntegerRegister(11, 0x1234_5628)
            program.simulate(1)
            assertEquals(result.toUInt(16).toInt().toLong(), session.registerFile.getLongValueNoNotify(9))
        }
    }

    @Test
    fun `NOR uses both halves of each source register`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("nor.s"), "nor $9,$10,$11\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            val word = 0x014b4827
            assertEquals(word, program.machineList!!.single().binaryStatement)
            assertEquals("nor", session.instructionSet.findByBinaryCode(word)?.name)
            session.editIntegerRegister(10, 0x0123_4567_0000_00ff)
            session.editIntegerRegister(11, 0x4000_0000_ffff_0000)
            program.simulate(1)
            assertEquals(0xbedc_ba98_0000_ff00uL.toLong(), session.registerFile.getLongValueNoNotify(9))
        }
    }

    @ParameterizedTest
    @CsvSource("add,1", "sub,-1")
    fun `trapping word arithmetic preserves the destination on overflow`(
        mnemonic: String, right: Long, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("overflow.s"), "$mnemonic $9,$10,$11\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            session.editIntegerRegister(9, 0x1234_5678_0000_002a)
            session.editIntegerRegister(10, Int.MAX_VALUE.toLong())
            session.editIntegerRegister(11, right)
            assertThrows<ProcessingException> { program.simulate(1) }
            assertEquals(0x1234_5678_0000_002a, session.registerFile.getLongValueNoNotify(9))
        }
    }

    @Test
    fun `leading counts decode only their target's encoding`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("count.s"), "clz $9,$10\nclo $11,$12\n")
        TestSessions.create().use { session ->
            assertNull(session.instructionSet.findByBinaryCode(0x01404850))
            assertEquals("clz", session.instructionSet.findByBinaryCode(0x71404820)?.name)
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            val words = listOf(0x01404850, 0x01805851)
            assertEquals(words, program.machineList!!.map(ProgramStatement::binaryStatement))
            assertEquals(listOf("clz", "clo"), words.map { session.instructionSet.findByBinaryCode(it)?.name })
            assertNull(session.instructionSet.findByBinaryCode(0x71404820))
            session.editIntegerRegister(10, 0x00ff_ffffL)
            session.editIntegerRegister(12, -1)
            program.simulate(2)
            assertEquals(8L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(32L, session.registerFile.getLongValueNoNotify(11))
        }
    }

    @Test
    fun `AUI and LUI encode the full immediate field and sign extend their word results`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("upper.s"), "aui $9,$10,65535\nlui $11,0xffff\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, extended = false, warnings = false)
            assertEquals(listOf(0x3d49ffff, 0x3c0bffff), program.machineList!!.map(ProgramStatement::binaryStatement))
            assertEquals("lui", session.instructionSet.findByBinaryCode(0x3c0bffff)?.name)
            session.editIntegerRegister(10, 0x8000)
            program.simulate(2)
            assertEquals(-32768L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(-65536L, session.registerFile.getLongValueNoNotify(11))
        }
    }
}
