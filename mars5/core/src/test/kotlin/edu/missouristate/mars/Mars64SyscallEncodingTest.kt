package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64SyscallEncodingTest {
    @ParameterizedTest
    @CsvSource("syscall,0000000c", "syscall 0,0000000c", "syscall 1048575,03ffffcc")
    fun `syscall encodes the software parameter and still dispatches by v0`(
        assembly: String, encoding: String, @TempDir directory: Path,
    ) {
        val source = Files.writeString(directory.resolve("syscall.s"), "$assembly\nbreak\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val program = session.assemble(listOf(source), null, false, false)
            val first = program.machineList!!.first()
            assertEquals(encoding.toInt(16), first.binaryStatement)
            val decoded = ProgramStatement(first.binaryStatement, first.address, session.instructionSet)
            assertEquals("syscall", decoded.instruction?.name)
            assertEquals(if (first.binaryStatement == 0x0c) "syscall" else "syscall 1048575", decoded.instruction?.exampleFormat)
            if (first.binaryStatement != 0x0c) assertEquals(0xfffff, decoded.operands[0])
            session.editIntegerRegister(2, 10)
            assertTrue(program.simulate(2))
        }
    }

    @ParameterizedTest
    @ValueSource(ints = [-1, 1048576])
    fun `syscall rejects values outside the code field`(code: Int, @TempDir directory: Path) {
        val source = Files.writeString(directory.resolve("invalid.s"), "syscall $code\n")
        TestSessions.create().use { session ->
            session.selectTarget(MarsTarget.MARS64)
            val failure = assertThrows<ProcessingException> { session.assemble(listOf(source), null, false, false) }
            assertTrue(failure.errors()!!.generateErrorReport().contains("unsigned 20-bit value"))
        }
    }
}
