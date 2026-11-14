package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.STATUS
import edu.missouristate.mars.simulator.Exceptions.EXTERNAL_INTERRUPT_DISPLAY
import edu.missouristate.mars.simulator.Exceptions.EXTERNAL_INTERRUPT_KEYBOARD
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64InterruptControlTest {
    @Test
    fun `interrupt controls defer delivery and preserve masked device requests`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("interrupt.s"), $$"""
            di $9
            addiu $12,$zero,7
            ei $10
            addiu $13,$zero,8
            di
            ei
            nop
            .ktext 0x80000180
            mfc0 $11,$13
            eret
        """.trimIndent())
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            assertEquals(listOf(0x41696000, 0x416a6020, 0x41606000, 0x41606020),
                program.machineList!!.filter { it.instruction?.name in setOf("di", "ei") }.map(ProgramStatement::binaryStatement))
            val status = 0x80000101.toInt()
            session.editCoprocessor0Register(STATUS, status)
            program.simulate(1)
            assertEquals(status.toLong(), session.registerFile.getLongValueNoNotify(9))
            assertEquals(status and -2, session.coprocessor0.getValue(STATUS))
            session.backStepper.backStep()
            assertEquals(status, session.coprocessor0.getValue(STATUS))
            program.simulate(1)

            val pending = EXTERNAL_INTERRUPT_KEYBOARD or EXTERNAL_INTERRUPT_DISPLAY
            session.setDeviceInterrupts(pending, pending)
            program.simulate(2)
            assertEquals(pending, session.pendingDeviceInterrupts())
            assertEquals((status and -2).toLong(), session.registerFile.getLongValueNoNotify(10))
            assertEquals(7, session.registerFile.getValueNoNotify(12))
            program.simulate(4)
            assertEquals(EXTERNAL_INTERRUPT_KEYBOARD shl 2, session.registerFile.getValueNoNotify(11))
            assertEquals(EXTERNAL_INTERRUPT_DISPLAY, session.pendingDeviceInterrupts())
            assertEquals(8, session.registerFile.getValueNoNotify(13))
        }
    }
}
