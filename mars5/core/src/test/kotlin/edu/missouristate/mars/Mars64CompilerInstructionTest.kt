package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.MemoryState
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64CompilerInstructionTest {
    @Test
    fun `common data and global directive spellings match the canonical MARS directives`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("aliases.mars"), """
            .data
            .global values
            values:
            .2byte 7
            .short 8
            .4byte 9
            .long 10
            .8byte 11
            .quad 12
            .asciz "two"
            .string "three"
            .text
            nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val memory = machine.memory
            memory.byteOrder = MemoryState.LITTLE_ENDIAN
            val program = machine.assemble(listOf(file))
            val base = memory.dataBaseAddress
            assertEquals(base.toLong(), program.symbolMap().globals().getValue("values").addressBits)
            assertEquals(listOf(0x00080007, 9, 10, 0, 11, 0, 12, 0), (0 until 8).map { memory.getWordNoNotify(base + it * 4) })
            assertEquals("two\u0000three\u0000".toList().map(Char::code), (32..41).map { memory.getByte(base + it) })
        }
    }

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
    fun `Clang builds a pointer to static data above four GiB using all four relocation operators`(@TempDir directory: Path) {
        val callee = directory.resolve("lookup.s")
        checkNotNull(javaClass.getResourceAsStream("/compiler/lookup.s")).use { Files.copy(it, callee) }
        val caller = Files.writeString(directory.resolve("caller.mars"), $$"""
            .data 0x100008000
            .text
            addiu $a0,$zero,0
            jal lookup
            nop
            move $s0,$v0
            addiu $a0,$zero,1
            jal lookup
            nop
            move $s1,$v0
            addiu $v0,$zero,10
            syscall
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(caller, callee))
            // Linked by lld with .rodata at 0x100008000, then read by llvm-objdump.
            val linked = listOf(0x30810001u, 0x000108f8u, 0x3c020000u, 0x64420001u,
                0x00021438u, 0x64420001u, 0x00021438u, 0x64428000u, 0x0041082du,
                0xdc210000u, 0x03e00009u, 0x64220001u)
            assertEquals(linked, program.machineList!!.takeLast(linked.size).map { it.binaryStatement.toUInt() })
            assertTrue(program.simulate(100))
            assertEquals(0x0123456789abcdf0L, machine.registerFile.getLongValueNoNotify(16))
            assertEquals(0xfedcba9876543211UL.toLong(), machine.registerFile.getLongValueNoNotify(17))
        }
    }

    @Test
    fun `relocation diagnostics identify malformed expressions and missing labels`(@TempDir directory: Path) {
        val file = directory.resolve("invalid.mars")
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            for ((source, message) in listOf(
                $$"lui $t0,%hi(45)" to "requires a label in parentheses",
                $$"lui $t0,%hi(main+end)" to "requires an integer addend",
                $$"lui $t0,%hi(main+1" to "requires a closing parenthesis",
                $$"lui $t0,%hi(missing)" to "Symbol \"missing\" not found",
                $$"dsll $t0,$t0,%lo(main)" to "operand is of incorrect type",
            )) {
                Files.writeString(file, "$source\nmain: nop\n")
                val invalid = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(file)) }
                assertTrue(invalid.errors()!!.generateErrorReport().contains(message), source)
            }
        }
    }

    @Test
    fun `a relocation in a macro retains parentheses around the memory base and resolves addends`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("relocations.mars"), $$"""
            .data 0x100008000
            data: .8byte 27, 63
            .text
            .macro read (%dst)
                ld %dst,%lo(data+8)($t0)
            .end_macro
            dli $t0,0x100010000
            read($t1)
            daddiu $t2,$t0,%lo(data-8)
            nop
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(file))
            assertTrue(program.simulate(100))
            assertEquals(63L, machine.registerFile.getLongValueNoNotify(9))
            assertEquals(0x1_0001_7ff8L, machine.registerFile.getLongValueNoNotify(10))
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
            val program = machine.assemble(listOf(file), null, extended = false, warnings = false)
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
