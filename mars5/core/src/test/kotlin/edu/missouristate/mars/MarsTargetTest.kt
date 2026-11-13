package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.MemoryState
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarsTargetTest {
    @Test
    fun `wide shifts and zero extending loads use all register bits`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("operations.s"), $$"""
            .data
            cell: .word -1
            .text
            la $s0,cell
            lwu $t0,0($s0)
            daddiu $t1,$zero,97
            dsllv $t2,$t0,$t1
            dsrlv $t3,$t2,$t1
            dsrav $t4,$t2,$t1
            dadd $t5,$t3,$t3
            dsub $t6,$t5,$t3
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            assertTrue(program.simulate(20))
            val registers = machine.registerFile
            assertEquals(0xffff_ffffL, registers.getLongValueNoNotify(8))
            assertEquals(-0x2_0000_0000L, registers.getLongValueNoNotify(10))
            assertEquals(0x7fff_ffffL, registers.getLongValueNoNotify(11))
            assertEquals(-1L, registers.getLongValueNoNotify(12))
            assertEquals(0xffff_fffeL, registers.getLongValueNoNotify(13))
            assertEquals(0x7fff_ffffL, registers.getLongValueNoNotify(14))
        }
    }

    @Test
    fun `trapping wide arithmetic preserves the destination on overflow`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("overflow.s"), $$"dadd $t0,$a0,$a1\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(4, Long.MAX_VALUE)
            machine.editIntegerRegister(5, 1)
            machine.editIntegerRegister(8, 42)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("arithmetic overflow"))
            assertEquals(42L, machine.registerFile.getLongValueNoNotify(8))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
    fun `doubleword data uses the selected byte order and supports continuation`(littleEndian: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("data.s"), $$"""
            .data
            .byte 1
            value: .dword 0x123456789abcdef0
            0xffffffffffffffff, 9223372036854775808
            .dword -7:2
            .text
            la $t0,value
            ld $t1,0($t0)
            ld $t2,8($t0)
            ld $t3,16($t0)
            ld $t4,24($t0)
            ld $t5,32($t0)
        """.trimIndent())
        TestSessions.create().use { machine ->
            val narrow = Files.writeString(directory.resolve("narrow.s"), ".data\n.dword 1\n")
            val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(narrow)) }
            assertTrue(failure.errors()!!.generateErrorReport().contains(".dword requires the MARS64 target"))
            machine.selectTarget(MarsTarget.MARS64)
            machine.memory.byteOrder = littleEndian
            val program = machine.assemble(listOf(source))
            assertTrue(program.simulate(20))
            assertEquals(machine.memory.dataBaseAddress + 8, machine.registerFile.getValue(8))
            assertEquals(0x1234_5678_9abc_def0L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(-1L, machine.registerFile.getLongValueNoNotify(10))
            assertEquals(Long.MIN_VALUE, machine.registerFile.getLongValueNoNotify(11))
            assertEquals(-7L, machine.registerFile.getLongValueNoNotify(12))
            assertEquals(-7L, machine.registerFile.getLongValueNoNotify(13))
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
    fun `wide instructions assemble run and reverse through an image`(littleEndian: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("wide.s"), $$"""
            .data
            cell: .word 0, 0
            .text
            daddiu $t0,$zero,1
            dsll32 $t0,$t0,0
            daddu $t1,$t0,$t0
            dsubu $t1,$t1,$t0
            daddiu $t1,$t1,-1
            la $t2,cell
            sd $t1,0($t2)
            ld $t3,0($t2)
            addiu $t3,$t3,1
        """.trimIndent())
        TestSessions.create().use { assembler ->
            assertEquals(MarsTarget.MARS32, assembler.target)
            assertNull(assembler.instructionSet.findByBinaryCode(0x64080001))
            assertThrows(ProcessingException::class.java) { assembler.assemble(listOf(source)) }
            assembler.selectTarget(MarsTarget.MARS64)
            assembler.memory.byteOrder = littleEndian
            val assembled = assembler.assemble(listOf(source))
            assertEquals(0x64080001, assembled.machineList!!.first().binaryStatement)
            assertEquals("daddiu", assembler.instructionSet.findByBinaryCode(0x64080001)?.name)
            val image = assembler.captureAssemblyImage {}
            TestSessions.create().use { machine ->
                machine.loadAssemblyImage(image) {}
                machine.memory.byteOrder = littleEndian
                assertEquals(MarsTarget.MARS64, machine.target)
                val program = requireNotNull(machine.program)
                assertFalse(program.simulate(10))
                val cell = machine.registerFile.getValue(10)
                assertEquals(0xffff_ffffL, machine.registerFile.getLongValueNoNotify(9))
                assertEquals(0L, machine.registerFile.getLongValueNoNotify(11))
                assertEquals(if (littleEndian) -1 else 0, machine.memory.getWord(cell))
                assertEquals(if (littleEndian) 0 else -1, machine.memory.getWord(cell + 4))
                machine.backStepper.backStep()
                assertEquals(0xffff_ffffL, machine.registerFile.getLongValueNoNotify(11))
                machine.backStepper.backStep()
                assertEquals(0L, machine.registerFile.getLongValueNoNotify(11))
                machine.backStepper.backStep()
                assertEquals(0, machine.memory.getWord(cell))
                assertEquals(0, machine.memory.getWord(cell + 4))
                machine.selectTarget(MarsTarget.MARS32)
                assertNull(machine.program)
                assertNull(machine.instructionSet.findByBinaryCode(0x64080001))
            }
        }
    }
}
