package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SyscallMemoryBoundaryTest {
    @Test
    fun `print keeps characters emitted before a fault and open waits for the complete filename`() {
        TestSessions.create().use { session ->
            val output = StringBuilder()
            val environment = object : TestEnvironment() {
                override fun printChar(value: Char) {
                    output.append(value)
                }
            }
            val context = InstructionContext(session, environment)
            val address = session.memory.stackBaseAddress
            session.memory.setByte(address, 0xe9)
            session.registerFile.updateRegister(4, address)
            val statement = ProgramStatement(0, session.memory.textBaseAddress)

            assertThrows(ProcessingException::class.java) { session.syscalls.findByNumber(4)!!.simulate(statement, context) }
            assertEquals("é", output.toString())
            assertThrows(ProcessingException::class.java) { session.syscalls.findByNumber(13)!!.simulate(statement, context) }
        }
    }

    @Test
    fun `file write reads the byte after the requested range before writing to the host`() {
        TestSessions.create().use { session ->
            val context = InstructionContext(session, object : TestEnvironment() {})
            val address = session.memory.stackBaseAddress
            session.memory.setByte(address, 'A'.code)
            session.registerFile.updateRegister(4, 3)
            session.registerFile.updateRegister(5, address)
            session.registerFile.updateRegister(6, 1)
            val statement = ProgramStatement(0, session.memory.textBaseAddress)

            assertThrows(ProcessingException::class.java) { session.syscalls.findByNumber(15)!!.simulate(statement, context) }
        }
    }
}
