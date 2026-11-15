package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.EPC
import edu.missouristate.mars.simulator.Exceptions.MSA_FLOATING_POINT_EXCEPTION
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64VectorControlTest {
    @Test
    fun `MSA control is independent of scalar state and traps after a write`(@TempDir directory: Path) {
        val file = Files.writeString(directory.resolve("control.s"), $$"""
            ctcmsa $1,$9
            cfcmsa $9,$1
            ctcmsa $0,$9
            cfcmsa $10,$0
            ctcmsa $2,$9
            cfcmsa $11,$2
            ctcmsa $1,$12
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(file), null, extended = false, warnings = false)
            val statements = program.machineList!!
            assertEquals(0x783e4859, statements[0].binaryStatement)
            assertEquals(0x787e0a59, statements[1].binaryStatement)
            session.editIntegerRegister(9, 0x81040002.toInt())
            session.editIntegerRegister(12, 0x1000800 or 0x10000)
            program.simulate(6)
            assertEquals(0x1040002, session.registerFile.getValueNoNotify(9))
            assertEquals(0, session.registerFile.getValueNoNotify(10))
            assertEquals(0, session.registerFile.getValueNoNotify(11))
            assertEquals(0x000c0000, session.coprocessor1.floatingStatus)
            assertThrows(ProcessingException::class.java) { program.simulate(1) }
            assertEquals(0x1010800, session.coprocessor1.msaStatus)
            assertEquals(MSA_FLOATING_POINT_EXCEPTION, session.coprocessor0.getValue(CAUSE) ushr 2 and 31)
            assertEquals(statements.last().address, session.coprocessor0.getValue(EPC))
            session.backStepper.backStep()
            assertEquals(0x1040002, session.coprocessor1.msaStatus)
            session.assemble(listOf(file), null, extended = false, warnings = false)
            assertEquals(0, session.coprocessor1.msaStatus)
        }
    }
}
