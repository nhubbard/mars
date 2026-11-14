package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.simulator.Exceptions.RESERVED_INSTRUCTION_EXCEPTION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Release6HardwareDiagnosticTest {
    @Test
    fun `hardware reads expose the single core and paired LL SC capability`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("capabilities.s"), $$"""
            rdhwr $9,$0
            rdhwr $10,$1,0
            rdhwr $11,$5
            ssnop
            rdhwr $9,$4,3
        """.trimIndent())
        TestSessions.create().use { machine ->
            machine.selectTarget(MarsTarget.MARS64)
            val program = machine.assemble(listOf(source), null, false, false)
            val words = listOf(0x7c09003b, 0x7c0a083b, 0x7c0b283b, 0x00000040, 0x7c0920fb)
            assertEquals(words, program.machineList!!.map(ProgramStatement::binaryStatement))
            assertEquals(listOf("rdhwr", "rdhwr", "rdhwr", "ssnop", "rdhwr"),
                words.map { machine.instructionSet.findByBinaryCode(it)?.name })
            for (register in 9..11) machine.editIntegerRegister(register, -1)
            program.simulate(4)
            for (register in 9..11) assertEquals(0L, machine.registerFile.getLongValueNoNotify(register))
            val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertTrue(failure.message!!.contains("hardware register 4, selector 3"))
            assertEquals(RESERVED_INSTRUCTION_EXCEPTION, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)

            Files.writeString(source, $$"rdhwr $9,$4,8")
            val invalid = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
            assertTrue(invalid.errors()!!.generateErrorReport().contains("selector must be in the range 0 through 7"))
        }
    }

    @Test
    fun `hardware forms explain the unavailable facility only for MARS64`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("hardware.s"), $$"""
            MFC2 $8,$9
            lbe $8,0($9)
            tlbp
            rdpgpr $8,$9
            wait
            sdbbp 1
            cache 0,0($9)
            mfhc0 $9,$2
            mthc0 $9,$3,0
            unknown_instruction
        """.trimIndent())
        TestSessions.create().use { machine ->
            fun report() = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(source)) }
                .errors()!!.generateErrorReport()

            val legacy = report()
            assertTrue(legacy.contains("\"MFC2\" is not a recognized operator"), legacy)
            machine.selectTarget(MarsTarget.MARS64)
            val errors = report()
            for ((name, requirement) in listOf(
                "MFC2" to "a platform-specific coprocessor 2",
                "lbe" to "privileged Enhanced Virtual Addressing",
                "tlbp" to "privileged address translation",
                "rdpgpr" to "shadow register sets",
                "wait" to "hardware interrupt and power management",
                "sdbbp" to "EJTAG Debug Mode",
                "cache" to "privileged cache state",
                "mfhc0" to "the optional CP0 Move High (MVH) feature",
                "mthc0" to "the optional CP0 Move High (MVH) feature",
            )) {
                assertTrue(errors.contains("\"$name\" requires $requirement, which MARS64 does not simulate"), errors)
                assertNull(machine.instructionSet.matchOperator(name))
            }
            assertTrue(errors.contains("\"unknown_instruction\" is not a recognized operator"), errors)
        }
    }
}
