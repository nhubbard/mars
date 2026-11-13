package edu.missouristate.mars

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class PcRelativeInstructionTest {
    @Test
    fun `PC relative instructions use this instruction address and byte displacements`(@TempDir directory: Path) {
        val source = directory.resolve("pc.s")
        TestSessions.create().use { machine ->
            Files.writeString(source, $$"""
                .text
                addiupc $t0,-4
                lwpc $t1,8
                auipc $t2,1
                aluipc $t3,1
            """.trimIndent())
            val program = machine.assemble(listOf(source))
            val instructions = program.machineList!!
            val address = instructions.first().address
            val decoded = ProgramStatement(instructions.first().binaryStatement, address, machine.instructionSet)
            assertEquals(-4, decoded.getOperands()!![1])
            assertTrue(program.simulate(10))
            assertEquals(address - 4, machine.registerFile.getValue(8))
            assertEquals(instructions.last().binaryStatement, machine.registerFile.getValue(9))
            assertEquals(address + 8 + 0x10000, machine.registerFile.getValue(10))
            assertEquals((address + 12 + 0x10000) and -0x10000, machine.registerFile.getValue(11))
            machine.backStepper.backStep()
            assertEquals(0, machine.registerFile.getValue(11))

            Files.writeString(source, $$"addiupc $t0, 5")
            val problem = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertTrue(problem.errors()!!.generateErrorReport().contains("multiple of four"))
        }
    }
}
