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

class Mars64SectionAlignmentTest {
    @Test
    fun `alignment initializes data padding and emits executable text padding at wide addresses`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("aligned.mars"), $$"""
            .data 0x100000000
            .byte 0x55
            .balign 8
            cell: .dword 10
            .text 0x100400000
            .globl main
            main: addiu $8,$zero,7
            .p2align 4
            aligned: addiu $8,$8,3
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            machine.settings.setStartAtMainEnabled(true)
            machine.memory.byteOrder = MemoryState.LITTLE_ENDIAN
            val program = machine.assemble(listOf(file))
            val code = program.machineList!!
            assertEquals(listOf(0x1_0040_0000L, 0x1_0040_0004L, 0x1_0040_0008L, 0x1_0040_000cL, 0x1_0040_0010L),
                code.map(ProgramStatement::addressBits))
            assertEquals(listOf(0x24080007, 0, 0, 0, 0x25080003), code.map(ProgramStatement::binaryStatement))
            assertEquals(List(7) { 0 }, (1L..7L).map { machine.memory.inspectDataByte(0x1_0000_0000L + it) })
            assertEquals(0x55, machine.memory.inspectDataByte(0x1_0000_0000L))
            assertEquals(10, machine.memory.inspectDataByte(0x1_0000_0008L))
            assertEquals(0x1_0000_0008L, program.localSymbolTable!!.getSymbol("cell")!!.addressBits)
            assertEquals(0x1_0040_0010L, program.localSymbolTable!!.getSymbol("aligned")!!.addressBits)
            program.simulate(5)
            assertEquals(10L, machine.registerFile.getLongValueNoNotify(8))
        }
    }

    @Test
    fun `invalid text alignment is diagnosed before the assembler emits padding`(@TempDir directory: Path) {
        val file = directory.resolve("invalid.mars")
        TestSessions.create().use { machine ->
            Files.writeString(file, ".p2align 3\nnop\n")
            fun errors() = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(file)) }
                .errors()!!.generateErrorReport()
            assertTrue(errors().contains(".p2align requires the MARS64 target"))
            machine.selectTarget(MarsTarget.MARS64)
            Files.writeString(file, "nop\n.p2align 31\n")
            assertTrue(errors().contains(".p2align crosses the bounds of the active segment"))
            Files.writeString(file, "nop\n.balign 3\n")
            assertTrue(errors().contains(".balign must align text using whole instruction words"))
            Files.writeString(file, ".p2align 32\n")
            assertTrue(errors().contains("an exponent from 0 through 31"))
        }
    }
}
