package edu.missouristate.mars

import edu.missouristate.mars.mips.instructions.BasicInstruction
import edu.missouristate.mars.mips.instructions.basic.InstrAddD
import edu.missouristate.mars.mips.instructions.basic.InstrAddS
import edu.missouristate.mars.mips.instructions.basic.InstrMovD
import edu.missouristate.mars.mips.instructions.basic.InstrMovS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FloatingPointBitsTest {
    @Test
    fun `moves preserve NaN payloads and arithmetic canonicalizes them`() {
        TestSessions.create().use { session ->
            val execution = TestSessions.execution(session)
            val fp = session.coprocessor1
            val doublePayload = 0x7ff8123456789abcL
            val singlePayload = 0x7fc01234
            fp.setRegisterPairToDouble(2, Double.fromBits(doublePayload))
            fp.setRegisterPairToDouble(4, 1.0)
            fp.setRegisterToFloat(6, Float.fromBits(singlePayload))
            fp.setRegisterToFloat(7, 1.0f)

            fun execute(instruction: BasicInstruction, vararg operands: Int) {
                val statement = ProgramStatement(
                    null, instruction.exampleFormat, null, null, instruction, session.registerFile.getPC(), 1,
                )
                operands.copyInto(statement.operands)
                execution.execute(statement)
            }

            execute(InstrMovD(), 8, 2)
            execute(InstrMovS(), 10, 6)
            assertEquals(doublePayload, fp.getLongFromRegisterPair(8))
            assertEquals(singlePayload, fp.getValue(10))

            execute(InstrAddD(), 8, 2, 4)
            execute(InstrAddS(), 10, 6, 7)
            assertEquals(0x7ff8000000000000L, fp.getLongFromRegisterPair(8))
            assertEquals(0x7fc00000, fp.getValue(10))
        }
    }
}
