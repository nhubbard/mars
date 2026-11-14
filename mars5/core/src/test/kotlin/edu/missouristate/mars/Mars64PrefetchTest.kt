package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64PrefetchTest {
    @Test
    fun `pref uses Release 6 offsets and only reserved hints fault`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("prefetch.s"), $$"""
            pref 3,-256($8)
            pref 23,255($9)
            pref 24,0($9)
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, false, false)
            val statements = program.machineList!!
            assertEquals(listOf(0x7d038035, 0x7d377fb5, 0x7d380035), statements.map(ProgramStatement::binaryStatement))
            for ((statement, offset) in statements.zip(listOf(-256, 255, 0))) {
                val decoded = ProgramStatement(statement.binaryStatement, statement.address, machine.instructionSet)
                assertEquals("pref", decoded.instruction?.name)
                assertEquals(offset, decoded.operands[1])
            }
            machine.editIntegerRegister(8, Long.MAX_VALUE)
            machine.editIntegerRegister(9, Long.MIN_VALUE)
            program.simulate(2)
            assertEquals(Long.MAX_VALUE, machine.registerFile.getLongValueNoNotify(8))
            assertEquals(Long.MIN_VALUE, machine.registerFile.getLongValueNoNotify(9))
            assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertEquals(RESERVED_INSTRUCTION_EXCEPTION, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)

            Files.writeString(source, $$"pref 0,256($8)")
            val error = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertTrue(error.errors()!!.generateErrorReport().contains("range -256 through 255"))
        }
    }
}
