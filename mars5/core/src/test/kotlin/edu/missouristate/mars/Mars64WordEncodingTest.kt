package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64WordEncodingTest {
    @Test
    fun `leading counts decode only their target's encoding`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("count.s"), "clz \$9,\$10\nclo \$11,\$12\n")
        TestSessions.create().use { session ->
            assertNull(session.instructionSet.findByBinaryCode(0x01404850))
            assertEquals("clz", session.instructionSet.findByBinaryCode(0x71404820)?.name)
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val words = listOf(0x01404850, 0x01805851)
            assertEquals(words, program.machineList!!.map(ProgramStatement::binaryStatement))
            assertEquals(listOf("clz", "clo"), words.map { session.instructionSet.findByBinaryCode(it)?.name })
            assertNull(session.instructionSet.findByBinaryCode(0x71404820))
            session.editIntegerRegister(10, 0x00ff_ffffL)
            session.editIntegerRegister(12, -1)
            program.simulate(2)
            assertEquals(8L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(32L, session.registerFile.getLongValueNoNotify(11))
        }
    }

    @Test
    fun `AUI and LUI encode the full immediate field and sign extend their word results`(@TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("upper.s"), "aui \$9,\$10,65535\nlui \$11,0xffff\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            assertEquals(listOf(0x3d49ffff, 0x3c0bffff), program.machineList!!.map(ProgramStatement::binaryStatement))
            assertEquals("lui", session.instructionSet.findByBinaryCode(0x3c0bffff)?.name)
            session.editIntegerRegister(10, 0x8000)
            program.simulate(2)
            assertEquals(-32768L, session.registerFile.getLongValueNoNotify(9))
            assertEquals(-65536L, session.registerFile.getLongValueNoNotify(11))
        }
    }
}
