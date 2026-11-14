package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64BarrierTest {
    @Test
    fun `an untaken delayed branch still reserves its delay slot`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("branch.s"), $$"""
            bne $zero,$zero,target
            j target
            target: nop
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(2) }
            assertTrue(failure.message!!.contains("control transfer in a delay slot"))
        }
    }

    @Test
    fun `direct jumps link past and execute the architectural delay slot`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("direct.s"), $$"""
            jal target
            addiu $9,$9,1
            nop
            target: j end
            addiu $9,$9,2
            nop
            end: nop
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val words = program.machineList!!
            program.simulate(4)
            assertEquals(words[6].address, session.registerFile.programCounter)
            assertEquals(words[2].address, session.registerFile.getValueNoNotify(31))
            assertEquals(3, session.registerFile.getValueNoNotify(9))
        }
    }

    @Test
    fun `register jumps use Release 6 encoding and always execute a delay slot`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("jump.s"), $$"""
            jalr $9,$10
            addiu $11,$11,1
            returned: nop
            callee: jr $9
            addiu $11,$11,2
        """.trimIndent())
        TestSessions.create().use { session ->
            assertEquals("jr", session.instructionSet.findByBinaryCode(0x01200008)?.name)
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val statements = program.machineList!!
            assertNull(session.instructionSet.findByBinaryCode(0x01200008))
            assertEquals(0x01404809, statements[0].binaryStatement)
            assertEquals(0x01200009, statements[3].binaryStatement)
            assertEquals("jr", session.instructionSet.findByBinaryCode(0x01200009)?.name)
            session.editIntegerRegister(10, statements[3].address)
            program.simulate(4)
            assertEquals(statements[2].address, session.registerFile.programCounter)
            assertEquals(statements[2].address, session.registerFile.getValueNoNotify(9))
            assertEquals(3, session.registerFile.getValueNoNotify(11))
            session.backStepper.backStep()
            assertEquals(statements[4].address, session.registerFile.programCounter)
            assertEquals(1, session.registerFile.getValueNoNotify(11))
        }
    }

    @Test
    fun `barrier sequence executes an instruction written to text when enabled and reverses the write`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("rewrite.s"), $$"""
            sw $9,0($10)
            synci -8($11)
            sync
            sync 16
            ehb
            jr.hb $10
            addiu $12,$12,1
            replacement: nop
            nop
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            var program = session.assemble(listOf(source), null, false, false)
            val statements = program.machineList!!
            val replacement = statements[7].address
            assertEquals(listOf(0x057ffff8, 0x0000000f, 0x0000040f, 0x000000c0, 0x01400409),
                statements.subList(1, 6).map { it.binaryStatement })
            session.editIntegerRegister(9, 0x24080007)
            session.editIntegerRegister(10, replacement)
            session.editIntegerRegister(11, replacement + 8)
            assertThrows(ProcessingException::class.java) { program.simulate(1) }

            session.settings.setSelfModifyingCodeEnabled(true)
            program = session.assemble(listOf(source), null, false, false)
            session.editIntegerRegister(9, 0x24080007)
            session.editIntegerRegister(10, replacement)
            session.editIntegerRegister(11, replacement + 8)
            program.simulate(8)
            assertEquals(7, session.registerFile.getValueNoNotify(8))
            assertEquals(1, session.registerFile.getValueNoNotify(12))
            repeat(8) { session.backStepper.backStep() }
            assertEquals(0, session.memory.getStatementNoNotify(replacement)!!.binaryStatement)
            assertEquals(0, session.registerFile.getValueNoNotify(8))
        }
    }

    @Test
    fun `hazard barrier calls link past the delay slot and reject a repeated register`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("call.s"), $$"""
            jalr.hb $9,$10
            addiu $11,$11,1
            nop
            callee: jalr.hb $12
            nop
            nop
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val statements = program.machineList!!
            val address = statements[0].address
            assertEquals(0x01404c09, statements[0].binaryStatement)
            assertEquals(0x0180fc09, statements[3].binaryStatement)
            session.editIntegerRegister(10, statements[3].address)
            session.editIntegerRegister(12, statements[5].address)
            program.simulate(2)
            assertEquals(address + 8, session.registerFile.getValueNoNotify(9))
            assertEquals(1, session.registerFile.getValueNoNotify(11))
            program.simulate(2)
            assertEquals(statements[5].address, session.registerFile.getValueNoNotify(31))
            Files.writeString(source, $$"jalr.hb $9,$9\n")
            assertThrows(ProcessingException::class.java) { session.assemble(listOf(source), null, false, false) }
        }
    }
}
