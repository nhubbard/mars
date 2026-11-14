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
    fun `unmodified freestanding Clang output runs with a MARS64 caller`(@TempDir directory: Path) {
        val callee = directory.resolve("rotate-sum.s")
        checkNotNull(javaClass.getResourceAsStream("/compiler/rotate-sum.s")).use { Files.copy(it, callee) }
        val caller = Files.writeString(directory.resolve("caller.mars"), $$"""
            .data 0x100000000
            words: .dword 0x0123456789abcdef, 0xfedcba9876543210
            .text
            dla $a0,words
            addiu $a1,$zero,2
            jal rotate_sum
            nop
            move $s0,$v0
            or $a1,$zero,$zero
            jal rotate_sum
            nop
            move $s1,$v0
            addiu $v0,$zero,10
            syscall
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(caller, callee))
            assertTrue(program.simulate(100))
            assertEquals(-1L, machine.registerFile.getLongValueNoNotify(16))
            assertEquals(0L, machine.registerFile.getLongValueNoNotify(17))
        }
    }

    @Test
    fun `compiler sections select classroom storage and reject loader-dependent sections`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("sections.mars"), $$"""
            .section ".rodata.constants", "a", @progbits
            value: .dword 7
            .section .text.lookup, "ax", @progbits
            ld $v0,value
            .section ".note.GNU-stack", "", @progbits
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(file))
            assertTrue(program.simulate(30))
            assertEquals(7L, machine.registerFile.getLongValueNoNotify(2))
            Files.writeString(file, ".section .got,\"aw\",@progbits\n.nan legacy\n")
            val unsupported = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(file)) }
            val errors = unsupported.errors()!!.generateErrorReport()
            assertTrue(errors.contains("Unsupported section \".got\""))
            assertTrue(errors.contains("Only .nan 2008 is supported"))
        }
    }

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
