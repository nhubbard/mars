package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64LinkedMemoryTest {
    @ParameterizedTest
    @CsvSource(
        "llwp,scwp,7d495876,7d495866,false,false", "llwp,scwp,7d495876,7d495866,false,true",
        "lldp,scdp,7d495877,7d495867,true,false", "lldp,scdp,7d495877,7d495867,true,true",
    )
    fun `paired forms address one aligned reservation and store both registers`(
        load: String, store: String, loadWord: String, storeWord: String,
        doubleword: Boolean, littleEndian: Boolean, @TempDir directory: Path,
    ) {
        val file = Files.writeString(directory.resolve("pair.s"), $$"""
            .data
            .word -2147483647,2,3,4
            .text
            $$load $9,$11,($10)
            $$store $9,$11,($10)
            $$store $9,$11,($10)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            session.memory.byteOrder = littleEndian
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val instructions = program.machineList!!
            assertEquals(loadWord.toInt(16), instructions[0].binaryStatement)
            assertEquals(storeWord.toInt(16), instructions[1].binaryStatement)
            for (statement in instructions.take(2)) {
                val decoded = ProgramStatement(statement.binaryStatement, statement.address, session.instructionSet)
                assertEquals(statement.operands.toList(), decoded.operands.toList())
            }
            val address = session.memory.dataBaseAddress
            val bytes = if (doubleword) 16 else 8
            session.editIntegerRegister(10, address.toLong())
            program.simulate(1)
            val expected = when {
                doubleword && littleEndian -> listOf(0x0000_0002_8000_0001L, 0x0000_0004_0000_0003L)
                doubleword -> listOf(0x0000_0003_0000_0004L, 0x8000_0001_0000_0002uL.toLong())
                littleEndian -> listOf(-2_147_483_647L, 2L)
                else -> listOf(2L, -2_147_483_647L)
            }
            assertEquals(expected, listOf(9, 11).map(session.registerFile::getLongValueNoNotify))
            session.editIntegerRegister(9, 0x2222_2222_1111_1111L)
            session.editIntegerRegister(11, 0x4444_4444_3333_3333L)
            fun memoryWords() = (0 until bytes step 4).map { session.memory.getWord(address + it) }
            val original = memoryWords()
            val words = when {
                doubleword && littleEndian -> listOf(0x1111_1111, 0x2222_2222, 0x3333_3333, 0x4444_4444)
                doubleword -> listOf(0x4444_4444, 0x3333_3333, 0x2222_2222, 0x1111_1111)
                littleEndian -> listOf(0x1111_1111, 0x3333_3333)
                else -> listOf(0x3333_3333, 0x1111_1111)
            }
            program.simulate(1)
            assertEquals(1L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(words, memoryWords())
            session.backStepper.backStep()
            assertEquals(original, memoryWords())
            program.simulate(2)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(words, memoryWords())
        }
    }

    @ParameterizedTest
    @CsvSource("ll,7d49fc36", "sc,7d49fc26", "lld,7d49fc37", "scd,7d49fc27")
    fun `linked instructions use signed Release 6 offsets`(mnemonic: String, encoding: String, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("encoding.s"), "$mnemonic $9,-8($10)\n")
        TestSessions.create().use { session ->
            val word = encoding.toInt(16)
            assertNull(session.instructionSet.findByBinaryCode(word))
            session.selectTarget(MarsTarget.MARS64)
            val statement = session.assemble(listOf(file), null, extended = false, warnings = false).machineList!!.single()
            assertEquals(word, statement.binaryStatement)
            val decoded = ProgramStatement(word, statement.address, session.instructionSet)
            assertEquals(mnemonic, decoded.instruction?.name)
            assertEquals(listOf(9, -8, 10), decoded.operands.take(3))
            for (offset in listOf(-257, 256)) {
                Files.writeString(file, "$mnemonic $9,$offset($10)\n")
                assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, extended = false, warnings = false) }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `doubleword conditional stores can be replayed after backstep`(littleEndian: Boolean, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("double.s"), $$"""
            .data
            cell: .dword 0xfedcba9876543210
            .text
            lld $9,-8($10)
            daddiu $9,$9,1
            scd $9,-8($10)
            scd $9,-8($10)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            session.memory.byteOrder = littleEndian
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val address = session.memory.dataBaseAddress
            session.editIntegerRegister(10, address.toLong() + 8)
            program.simulate(2)
            val result = 0xfedc_ba98_7654_3211uL.toLong()
            assertEquals(result, session.registerFile.getLongValueNoNotify(9))
            val low = address + if (littleEndian) 0 else 4
            repeat(2) {
                program.simulate(1)
                assertEquals(1L, session.registerFile.getLongValueNoNotify(9))
                assertEquals(0x7654_3211, session.memory.getWord(low))
                session.backStepper.backStep()
                assertEquals(result, session.registerFile.getLongValueNoNotify(9))
                assertEquals(0x7654_3210, session.memory.getWord(low))
            }
            program.simulate(2)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(0x7654_3211, session.memory.getWord(low))
        }
    }

    @Test
    fun `an overlapping byte store invalidates a word reservation and backstep restores it`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("word.s"), $$"""
            .data
            cell: .word -2,0
            .text
            ll $9,cell
            sb $zero,3($10)
            sc $9,0($10)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file))
            val address = session.memory.dataBaseAddress
            session.editIntegerRegister(10, address.toLong())
            program.simulate(program.machineList!!.size - 2)
            assertEquals(-2L, session.registerFile.getLongValueNoNotify(9))
            program.simulate(2)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            session.backStepper.backStep()
            session.backStepper.backStep()
            assertEquals(-2, session.memory.getWord(address))
            session.memory.setByte(address + 4, 1)
            session.registerFile.setProgramCounter(program.machineList!!.last().address)
            program.simulate(1)
            assertEquals(1L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(-2, session.memory.getWord(address))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ll", "sc", "lld", "scd"])
    fun `misaligned linked accesses fault even without a reservation`(mnemonic: String, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("fault.s"), "$mnemonic $9,1($10)\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            session.editIntegerRegister(10, session.memory.dataBaseAddress.toLong())
            session.editIntegerRegister(9, 42)
            assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertEquals(42L, session.registerFile.getLongValueNoNotify(9))
        }
    }

    @Test
    fun `a mismatched conditional store consumes the reservation and a new assembly clears it`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("mismatch.s"), $$"""
            .data
            .dword 0x123456789abcdef0
            .text
            lld $9,0($10)
            sc $9,0($10)
            scd $9,0($10)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val address = session.memory.dataBaseAddress
            session.editIntegerRegister(10, address.toLong())
            program.simulate(2)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            session.editIntegerRegister(9, -1L)
            program.simulate(1)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            session.registerFile.setProgramCounter(session.memory.textBaseAddress)
            program.simulate(1)
            val fresh = session.assemble(listOf(file), null, extended = false, warnings = false)
            session.editIntegerRegister(10, address.toLong())
            session.editIntegerRegister(9, -1L)
            session.registerFile.setProgramCounter(fresh.machineList!!.last().address)
            fresh.simulate(1)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(0x9abc_def0u.toInt(), session.memory.getWord(address))
        }
    }
}
