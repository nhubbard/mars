package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_LOAD
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class MarsAddressWidthTest {
    @ParameterizedTest
    @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
    fun `doubleword address forms use one full register in MARS64`(littleEndian: Boolean, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("wide-memory.s"), $$"""
            .data
            cell: .dword 0x123456789abcdef0
            copy: .dword 0
            .text
            ld $t0,cell
            sd $t0,cell+8($zero)
            la $a0,copy
            ld $t2,($a0)
            ld $t3,copy($zero)
            ld $t4,cell+8
            ld $t5,0x10010008
            ld $t6,65544($a1)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.memory.byteOrder = littleEndian
            val program = machine.assemble(listOf(source))
            assertEquals(6, program.machineList!!.count { it.instruction?.name == "ld" })
            assertEquals(1, program.machineList!!.count { it.instruction?.name == "sd" })
            machine.editIntegerRegister(5, machine.memory.dataBaseAddress - 65536)
            machine.editIntegerRegister(9, 42)
            assertTrue(program.simulate(30))
            for (register in listOf(8, 10, 11, 12, 13, 14)) {
                assertEquals(0x1234_5678_9abc_def0L, machine.registerFile.getLongValueNoNotify(register), "register $register")
            }
            assertEquals(42L, machine.registerFile.getLongValueNoNotify(9))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "lb \$t0,0(\$a0)", "lbu \$t0,0(\$a0)", "lh \$t0,0(\$a0)", "lhu \$t0,0(\$a0)",
        "lw \$t0,0(\$a0)", "lwu \$t0,0(\$a0)", "ll \$t0,0(\$a0)", "ulw \$t0,1(\$a0)", "ulhu \$t0,1(\$a0)",
        "ld \$t0,0(\$a0)", "lwc1 \$f0,0(\$a0)", "ldc1 \$f0,0(\$a0)",
        "sb \$t0,0(\$a0)", "sh \$t0,0(\$a0)", "sw \$t0,0(\$a0)", "sc \$t0,0(\$a0)", "usw \$t0,1(\$a0)",
        "ush \$t0,1(\$a0)", "sd \$t0,0(\$a0)", "swc1 \$f0,0(\$a0)", "sdc1 \$f0,0(\$a0)",
    ])
    fun `unmapped high bits cannot alias mapped data`(instruction: String, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("data.s"), ".data\n.word 42,0\n.text\n$instruction\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            val mapped = machine.memory.dataBaseAddress
            val unmapped = mapped.toLong() + 0x1_0000_0000L
            machine.editIntegerRegister(4, unmapped)
            machine.editIntegerRegister(8, 99)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            val effectiveAddress = unmapped + if (instruction.startsWith('u')) 1 else 0
            assertTrue(failure.message!!.contains("0x" + effectiveAddress.toString(16).padStart(16, '0') + " is outside mapped memory"))
            val expectedCause = if (instruction.removePrefix("u").startsWith('s')) ADDRESS_EXCEPTION_STORE else ADDRESS_EXCEPTION_LOAD
            assertEquals(expectedCause, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)
            assertEquals(42, machine.memory.getWord(mapped))
            assertEquals(99, machine.registerFile.getValue(8))
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["jr \$a0", "jalr \$a0", "jalr \$ra,\$a0", "jic \$a0,4", "jialc \$a0,4"])
    fun `unmapped high bits cannot alias text or change a link`(instruction: String, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("jump.s"), "$instruction\nnop\n")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(4, 0x1_0000_0000L + machine.memory.textBaseAddress)
            machine.editIntegerRegister(31, 0x2_0000_0000L)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("outside mapped memory"))
            assertEquals(0x2_0000_0000L, machine.registerFile.getLongValueNoNotify(31))
            assertEquals(ADDRESS_EXCEPTION_LOAD, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)
        }
    }

    @Test
    fun `effective address applies the signed offset before checking the map`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("offset.s"), $$"""
            .data
            cell: .word 42,0
            .text
            lw $t0,-4($a0)
            ld $t1,-4($a0)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source))
            machine.editIntegerRegister(4, machine.memory.dataBaseAddress + 4)
            assertTrue(program.simulate(3))
            assertEquals(42, machine.registerFile.getValue(8))
            assertEquals(42L, machine.registerFile.getLongValueNoNotify(9))
        }
    }
}
