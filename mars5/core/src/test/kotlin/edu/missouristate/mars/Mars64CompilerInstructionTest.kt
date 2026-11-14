package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64CompilerInstructionTest {
    @Test
    fun `unsuffixed shifts select the upper encoding for counts above 31`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("shifts.mars"), $$"""
            dsll $9,$10,32
            dsrl $9,$10,63
            dsra $9,$10,63
            drotr $9,$10,57
        """.trimIndent())
        val input = 0x8123_4567_89ab_cdefUL.toLong()
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(file), null, false, false)
            val words = checkNotNull(program.machineList).map(ProgramStatement::binaryStatement)
            assertEquals(listOf(0x000a483c, 0x000a4ffe, 0x000a4fff, 0x002a4e7e), words)
            assertEquals(listOf("dsll32", "dsrl32", "dsra32", "drotr32"), words.map { machine.instructionSet.findByBinaryCode(it)?.name })
            machine.editIntegerRegister(10, input)
            for (result in listOf(input shl 32, 1L, -1L, input.rotateRight(57))) {
                program.simulate(1)
                assertEquals(result, machine.registerFile.getLongValueNoNotify(9))
            }
            Files.writeString(file, $$"drotr $9,$10,64")
            val invalid = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(file)) }
            assertTrue(invalid.errors()!!.generateErrorReport().contains("shift count must be from 0 through 63"))
        }
    }

    @Test
    fun `compact register jump aliases encode without an offset or delay slot`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("calls.mars"), $$"""
            dla $t9,callee
            jalrc $t9
            addiu $t0,$t0,2
            li $v0,10
            syscall
            callee: jrc $ra
            addiu $t0,$t0,4
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(file))
            val words = checkNotNull(program.machineList).map(ProgramStatement::binaryStatement)
            assertTrue(0xf8190000.toInt() in words)
            assertTrue(0xd81f0000.toInt() in words)
            program.simulate(64)
            assertEquals(2L, machine.registerFile.getLongValueNoNotify(8))
        }
    }
}
