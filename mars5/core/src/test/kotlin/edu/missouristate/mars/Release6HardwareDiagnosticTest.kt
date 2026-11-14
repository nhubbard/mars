package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Release6HardwareDiagnosticTest {
    @Test
    fun `hardware forms explain the unavailable facility only for MARS64`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("hardware.s"), $$"""
            MFC2 $8,$9
            lbe $8,0($9)
            tlbp
            rdpgpr $8,$9
            wait
            sdbbp 1
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
            )) {
                assertTrue(errors.contains("\"$name\" requires $requirement, which MARS64 does not simulate"), errors)
                assertNull(machine.instructionSet.matchOperator(name))
            }
            assertTrue(errors.contains("\"unknown_instruction\" is not a recognized operator"), errors)
        }
    }
}
