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
    fun `wide multiplication division rotations and leading counts match machine encodings`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("wide-integer.s"), $$"""
            drotr $8,$4,1
            drotr32 $9,$4,0
            drotrv $10,$4,$5
            dclz $11,$4
            dclo $12,$4
            dmul $15,$4,$5
            dmuh $16,$4,$5
            dmulu $17,$4,$5
            dmuhu $18,$4,$5
            ddiv $19,$4,$5
            ddivu $20,$4,$5
            dmod $21,$4,$5
            dmodu $22,$4,$5
        """.trimIndent())
        val words = listOf(
            0x0024407a, 0x0024483e, 0x00a45056, 0x00805852, 0x00806053,
            0x0085789c, 0x008580dc, 0x0085889d, 0x008590dd, 0x0085989e, 0x0085a09f, 0x0085a8de, 0x0085b0df,
        )
        TestSessions.create().use { machine ->
            assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            words.forEach { assertNull(machine.instructionSet.findByBinaryCode(it)) }
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            assertEquals(words, program.machineList!!.map { it.binaryStatement })
            assertEquals(
                Files.readAllLines(source).map { it.substringBefore(' ') },
                words.map { machine.instructionSet.findByBinaryCode(it)?.name },
            )
            machine.editIntegerRegister(4, 0xfedc_ba09_8765_4321uL.toLong())
            machine.editIntegerRegister(5, 3)
            machine.editIntegerRegister(22, 0x1_0000_0000L)
            assertTrue(program.simulate(20))
            val expected = mapOf(
                8 to 0xff6e_5d04_c3b2_a190uL.toLong(), 9 to 0x8765_4321_fedc_ba09uL.toLong(),
                10 to 0x3fdb_9741_30ec_a864L, 11 to 0L, 12 to 7L,
                15 to 0xfc96_2e1c_962f_c963uL.toLong(), 16 to -1L, 17 to 0xfc96_2e1c_962f_c963uL.toLong(),
                18 to 2L, 19 to -27_328_714_370_159_690L, 20 to 6_121_585_976_866_357_515L, 21 to -1L, 22 to 0L,
            )
            for ((register, result) in expected) assertEquals(result, machine.registerFile.getLongValueNoNotify(register), "register $register")
            machine.backStepper.backStep()
            assertEquals(0x1_0000_0000L, machine.registerFile.getLongValueNoNotify(22))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["ddiv", "ddivu", "dmod", "dmodu"])
    fun `wide division by zero reports a trap without storing`(mnemonic: String, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("zero-divisor.s"), $$"$$mnemonic $t0,$a0,$zero\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(8, Long.MIN_VALUE)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("division by zero"))
            assertEquals(Long.MIN_VALUE, machine.registerFile.getLongValueNoNotify(8))
        }
    }

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
                assertFalse(program.simulate(program.instructionCount()))
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
