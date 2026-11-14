package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64UnalignedMemoryTest {
    @Test
    fun `aliases expand to ordinary accesses in Release 6 and legacy merge forms stay in MARS32`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("aliases.s"), $$"""
            ulw $9,32767($8)
            usw $9,1($8)
            ulh $10,($8)
            ulhu $11,-1($8)
            ush $10,3($8)
        """.trimIndent())
        val legacy = mapOf(
            "lwl" to 0x88280000.toInt(), "lwr" to 0x98280000.toInt(),
            "swl" to 0xa8280000.toInt(), "swr" to 0xb8280000.toInt(),
        )
        TestSessions.create().use { session ->
            val original = session.assemble(listOf(source), null, true, false).machineList!!
            assertEquals(listOf("lwl", "lwr", "swl", "swr"), original.mapNotNull { it.instruction?.name }.filter { it in legacy })
            for ((mnemonic, word) in legacy) {
                assertEquals(mnemonic, session.instructionSet.findByBinaryCode(word)?.name)
            }

            session.selectTarget(MarsTarget.MARS64)
            val statements = session.assemble(listOf(source), null, true, false).machineList!!
            assertEquals(listOf("lw", "sw", "lh", "lhu", "sh"), statements.map { it.instruction?.name })
            assertEquals(32767, statements.first().binaryStatement and 0xffff)
            for ((mnemonic, word) in legacy) {
                assertNull(session.instructionSet.matchOperator(mnemonic))
                assertNotEquals(mnemonic, session.instructionSet.findByBinaryCode(word)?.name)
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `ordinary scalar loads and stores support unaligned data in either byte order`(little: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("unaligned.s"), $$"""
            lh $9,1($8)
            lhu $10,1($8)
            lw $11,1($8)
            lwu $12,1($8)
            ld $13,1($8)
            lwc1 $f2,1($8)
            ldc1 $f3,1($8)
            sh $9,17($8)
            sw $11,21($8)
            sd $13,27($8)
            swc1 $f2,37($8)
            sdc1 $f3,43($8)
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val memory = session.memory
            memory.byteOrder = little
            val start = memory.dataBaseAddress
            val bytes = listOf(0x80, 0x91, 0xa2, 0xb3, 0xc4, 0xd5, 0xe6, 0xf7)
            bytes.forEachIndexed { index, value -> memory.setByte(start + 1 + index, value) }
            fun packed(size: Int): Long {
                val selected = bytes.take(size).let { if (little) it.reversed() else it }
                return selected.fold(0L) { bits, value -> (bits shl 8) or value.toLong() }
            }
            session.editIntegerRegister(8, start)
            program.simulate(7)
            assertEquals(packed(2).toShort().toLong(), session.registerFile.getLongValueNoNotify(9))
            assertEquals(packed(2), session.registerFile.getLongValueNoNotify(10))
            assertEquals(packed(4).toInt().toLong(), session.registerFile.getLongValueNoNotify(11))
            assertEquals(packed(4), session.registerFile.getLongValueNoNotify(12))
            assertEquals(packed(8), session.registerFile.getLongValueNoNotify(13))
            assertEquals(packed(4).toInt(), session.coprocessor1.getValue(2))
            assertEquals(packed(8), session.coprocessor1.getDoublewordNoNotify(3))
            program.simulate(5)
            val stores = listOf(17 to 2, 21 to 4, 27 to 8, 37 to 4, 43 to 8)
            for ((address, size) in stores) {
                assertEquals(bytes.take(size), (0 until size).map { memory.getByte(start + address + it) })
            }
            repeat(5) { session.backStepper.backStep() }
            for ((address, size) in stores) {
                assertEquals(List(size) { 0 }, (0 until size).map { memory.getByte(start + address + it) })
            }
        }
    }

    @Test
    fun `crossing a storage boundary faults before a store changes any bytes`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("boundary.s"), $$"sw $9,0($8)")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val memory = session.memory
            val end = memory.dataSegmentLimitAddress - 1
            memory.setByte(end, 7)
            session.editIntegerRegister(8, end)
            session.editIntegerRegister(9, -1)
            assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertEquals(7, memory.getByte(end))
        }
    }
}
