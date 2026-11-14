package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EPC
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64RegimmTest {
    @Test
    fun `BAL and NAL preserve zero-source encodings and always link past a delay slot`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("link.s"), $$"""
            bal target
            addiu $t0,$t0,1
            addiu $t0,$t0,8
            target: nal
            addiu $t1,$t1,1
            nop
        """.trimIndent())
        TestSessions.create().use { session ->
            for (word in listOf(0x05100000, 0x05110000)) assertNotNull(session.instructionSet.findByBinaryCode(word))
            session.selectTarget(MarsTarget.MARS64)
            for (word in listOf(0x05100000, 0x05110000)) assertNull(session.instructionSet.findByBinaryCode(word))
            val program = session.assemble(listOf(file), null, false, false)
            val statements = program.machineList!!
            val address = statements[0].address
            val link = statements[3].address
            assertEquals(0x04110002, statements[0].binaryStatement)
            assertEquals(0x04100000, statements[3].binaryStatement)
            assertEquals("nal", session.instructionSet.findByBinaryCode(0x0410ffff)?.name)
            assertEquals(-2, ProgramStatement(0x0411fffe, address, session.instructionSet).operands[0])
            program.simulate(2)
            assertEquals(1, session.registerFile.getValueNoNotify(8))
            assertEquals(link, session.registerFile.getPC())
            assertEquals((address + 8).toLong(), session.registerFile.getLongValueNoNotify(31))

            program.simulate(1)
            assertEquals((link + 8).toLong(), session.registerFile.getLongValueNoNotify(31))
            assertTrue(session.delayedBranch.isTriggered)
            program.simulate(1)
            assertEquals(1, session.registerFile.getValueNoNotify(9))
            assertEquals(link + 8, session.registerFile.getPC())
            repeat(2) { session.backStepper.backStep() }
            assertEquals((address + 8).toLong(), session.registerFile.getLongValueNoNotify(31))
            assertEquals(link, session.registerFile.getPC())
        }
    }

    @Test
    fun `SIGRIE signals a reserved instruction exception with its software code`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("reserved.s"), "sigrie 65535\n")
        TestSessions.create().use { session ->
            val binary = 0x0417ffff
            assertNull(session.instructionSet.findByBinaryCode(binary))
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, false, false)
            val statement = program.machineList!!.single()
            assertEquals(binary, statement.binaryStatement)
            assertEquals(65535, ProgramStatement(binary, statement.address, session.instructionSet).operands[0])
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("sigrie code 65535"))
            assertEquals(RESERVED_INSTRUCTION_EXCEPTION, session.coprocessor0.getValue(CAUSE) ushr 2 and 31)
            assertEquals(statement.address, session.coprocessor0.getValue(EPC))
        }
    }
}
