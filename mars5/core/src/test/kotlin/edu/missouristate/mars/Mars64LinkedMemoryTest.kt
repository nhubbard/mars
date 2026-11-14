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
    @CsvSource("ll,7d49fc36", "sc,7d49fc26", "lld,7d49fc37", "scd,7d49fc27")
    fun `linked instructions use signed Release 6 offsets`(mnemonic: String, encoding: String, @TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("encoding.s"), "$mnemonic \$9,-8(\$10)\n")
        TestSessions.create().use { session ->
            val word = encoding.toInt(16)
            assertNull(session.instructionSet.findByBinaryCode(word))
            session.selectTarget(MarsTarget.MARS64)
            val statement = session.assemble(listOf(file), null, false, false).machineList!!.single()
            assertEquals(word, statement.binaryStatement)
            val decoded = ProgramStatement(word, statement.address, session.instructionSet)
            assertEquals(mnemonic, decoded.instruction?.name)
            assertEquals(listOf(9, -8, 10), decoded.operands.take(3))
            for (offset in listOf(-257, 256)) {
                Files.writeString(file, "$mnemonic \$9,$offset(\$10)\n")
                assertThrows(ProcessingException::class.java) { session.assemble(listOf(file), null, false, false) }
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
            val program = session.assemble(listOf(file), null, false, false)
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
        val file = Files.writeString(directory.resolve("fault.s"), "$mnemonic \$9,1(\$10)\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
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
            val program = session.assemble(listOf(file), null, false, false)
            val address = session.memory.dataBaseAddress
            session.editIntegerRegister(10, address.toLong())
            program.simulate(2)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            session.editIntegerRegister(9, -1L)
            program.simulate(1)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            session.registerFile.setProgramCounter(session.memory.textBaseAddress)
            program.simulate(1)
            val fresh = session.assemble(listOf(file), null, false, false)
            session.editIntegerRegister(10, address.toLong())
            session.editIntegerRegister(9, -1L)
            session.registerFile.setProgramCounter(fresh.machineList!!.last().address)
            fresh.simulate(1)
            assertEquals(0L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(0x9abc_def0u.toInt(), session.memory.getWord(address))
        }
    }
}
