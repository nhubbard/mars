package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64FloatingRegisterTest {
    @ParameterizedTest
    @CsvSource(
        "sel.s,460728d0,1,2", "sel.d,462728d0,1,2",
        "seleqz.s,460728d4,1,0", "seleqz.d,462728d4,1,0",
        "selnez.s,460728d7,0,1", "selnez.d,462728d7,0,1",
    )
    fun `floating select uses bit zero and copies raw bits without exceptions`(
        mnemonic: String, encoding: String, resultWithClearBit: Int, resultWithSetBit: Int, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("select.s"), "$mnemonic \$f3,\$f5,\$f7\n".repeat(2))
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val word = encoding.toInt(16)
            assertEquals(listOf(word, word), program.machineList!!.map(ProgramStatement::binaryStatement))
            assertEquals(mnemonic, session.instructionSet.findByBinaryCode(word)?.name)
            val double = mnemonic.endsWith(".d")
            val signalingNan = if (double) 0x7ff0_0000_0000_0002L else 0x7f80_0002L
            val negativeZero = if (double) Long.MIN_VALUE else 0x8000_0000L
            val initialStatus = session.coprocessor1.readControl(31)
            session.editCoprocessor1Pair(5, signalingNan)

            for ((bit, selection) in listOf(0L to resultWithClearBit, 1L to resultWithSetBit)) {
                session.editCoprocessor1Pair(3, 0x20L or bit)
                session.editCoprocessor1Pair(7, negativeZero or bit)
                program.simulate(1)
                val result = session.coprocessor1.getDoublewordNoNotify(3)
                assertEquals(listOf(0L, signalingNan, negativeZero or bit)[selection],
                    if (double) result else result and 0xffff_ffffL)
                assertEquals(initialStatus, session.coprocessor1.readControl(31))
            }
        }
    }

    @Test
    fun `word and doubleword moves address one floating-point register and undo all its bits`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("moves.s"), $$"""
            dmtc1 $9,$f31
            mthc1 $10,$f31
            dmfc1 $11,$f31
            mfhc1 $12,$f31
            mtc1 $13,$f31
            mfc1 $14,$f31
        """.trimIndent())
        TestSessions.create().use { session ->
            val words = listOf(0x44a9f800, 0x44eaf800, 0x442bf800, 0x446cf800, 0x448df800, 0x440ef800)
            assertNull(session.instructionSet.findByBinaryCode(words.first()))
            assertNull(session.instructionSet.findByBinaryCode(words[2]))
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            assertEquals(words, program.machineList!!.map(ProgramStatement::binaryStatement))
            val original = 0x1234_5678_9abc_def0L
            session.editIntegerRegister(9, original)
            session.editIntegerRegister(10, 0xfedc_ba98.toInt())
            session.editIntegerRegister(13, 0x8123_4567.toInt())
            session.editCoprocessor1Pair(30, 0x1122_3344_5566_7788L)

            program.simulate(1)
            assertEquals(original, session.coprocessor1.getDoublewordNoNotify(31))
            program.simulate(3)
            val mixed = 0xfedc_ba98_9abc_def0uL.toLong()
            assertEquals(mixed, session.registerFile.getLongValueNoNotify(11))
            assertEquals(0xfedc_ba98.toInt().toLong(), session.registerFile.getLongValueNoNotify(12))
            program.simulate(2)
            assertEquals(0x8123_4567.toInt().toLong(), session.registerFile.getLongValueNoNotify(14))
            assertEquals(0x1122_3344_5566_7788L, session.coprocessor1.getDoublewordNoNotify(30))
            session.backStepper.backStep()
            session.backStepper.backStep()
            assertEquals(mixed, session.coprocessor1.getDoublewordNoNotify(31))

            session.selectTarget(MarsTarget.MARS32)
            assertNull(session.instructionSet.findByBinaryCode(words.first()))
            assertEquals(0L, session.coprocessor1.getDoublewordNoNotify(30))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `doubleword memory and arithmetic use odd registers in either byte order`(littleEndian: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("double.s"), $$"""
            .data
            .dword 0x4004000000000000,0
            .text
            ldc1 $f31,0($10)
            sdc1 $f31,8($10)
            add.d $f29,$f31,$f1
            mov.d $f3,$f29
            class.d $f5,$f3
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            session.memory.byteOrder = littleEndian
            val program = session.assemble(listOf(source), null, false, false)
            assertEquals(listOf(0xd55f0000.toInt(), 0xf55f0008.toInt(), 0x4621ff40),
                program.machineList!!.take(3).map(ProgramStatement::binaryStatement))
            val address = session.memory.dataBaseAddress
            session.editIntegerRegister(10, address)
            session.editCoprocessor1Pair(1, 4.5.toRawBits())

            program.simulate(1)
            assertEquals(2.5.toRawBits(), session.coprocessor1.getDoublewordNoNotify(31))
            program.simulate(1)
            for (word in listOf(0, 4)) {
                assertEquals(session.memory.getWord(address + word), session.memory.getWord(address + word + 8))
            }
            program.simulate(3)
            assertEquals(7.0.toRawBits(), session.coprocessor1.getDoublewordNoNotify(29))
            assertEquals(7.0.toRawBits(), session.coprocessor1.getDoublewordNoNotify(3))
            assertEquals(1L shl 7, session.coprocessor1.getDoublewordNoNotify(5))
            repeat(4) { session.backStepper.backStep() }
            assertEquals(0, session.memory.getWord(address + 8))
            assertEquals(0, session.memory.getWord(address + 12))
            assertEquals(0L, session.coprocessor1.getDoublewordNoNotify(29))
        }
    }
}
