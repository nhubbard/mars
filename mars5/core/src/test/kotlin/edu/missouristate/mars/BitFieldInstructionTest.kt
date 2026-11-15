package edu.missouristate.mars

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BitFieldInstructionTest {
    @Test
    fun `four operand instructions preserve written quantities through encoding`(@TempDir directory: Path) {
        val source = directory.resolve("fields.s")
        TestSessions.create().use { machine ->
            for ((assembly, word, operands) in listOf(
                Triple($$"align $t1,$t2,$t3,0", 0x7d4b4a20, intArrayOf(9, 10, 11, 0)),
                Triple($$"lsa $t1,$t2,$t3,1", 0x014b4805, intArrayOf(9, 10, 11, 1)),
                Triple($$"ext $t1,$t2,0,32", 0x7d49f800, intArrayOf(9, 10, 0, 32)),
                Triple($$"ins $t1,$t2,31,1", 0x7d49ffc4, intArrayOf(9, 10, 31, 1)),
            )) {
                Files.writeString(source, ".text\n$assembly\n")
                val assembled = machine.assemble(listOf(source)).machineList!!.single()
                assertEquals(word, assembled.binaryStatement, assembly)
                assertArrayEquals(operands, assembled.getOperands(), assembly)
                val decoded = ProgramStatement(word, assembled.address, machine.instructionSet)
                assertArrayEquals(operands, decoded.getOperands(), assembly)
            }
            assertNull(machine.instructionSet.findByBinaryCode(0x7d49ffc0))
            assertNull(machine.instructionSet.findByBinaryCode(0x7d4907c4))

            for (assembly in listOf(
                $$"align $t1,$t2,$t3,4", $$"lsa $t1,$t2,$t3,0",
                $$"ext $t1,$t2,31,2", $$"ins $t1,$t2,2,0",
            )) {
                Files.writeString(source, ".text\n$assembly\n")
                val problem = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
                assertTrue(problem.errors()!!.generateErrorReport().contains("must be from"), assembly)
            }
        }
    }
}
