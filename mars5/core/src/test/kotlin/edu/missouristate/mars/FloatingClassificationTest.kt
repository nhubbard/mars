package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FloatingClassificationTest {
  @Test
  fun `both CLASS widths distinguish all ten result bits and preserve the source payload`() {
    val singles =
        listOf(
            0x7f800001u,
            0x7fc00001u,
            0xff800000u,
            0xbf800000u,
            0x80000001u,
            0x80000000u,
            0x7f800000u,
            0x3f800000u,
            0x00000001u,
            0x00000000u,
        )
    val doubles =
        listOf(
            0x7ff0000000000001uL,
            0x7ff8000000000001uL,
            0xfff0000000000000uL,
            0xbff0000000000000uL,
            0x8000000000000001uL,
            0x8000000000000000uL,
            0x7ff0000000000000uL,
            0x3ff0000000000000uL,
            0x0000000000000001uL,
            0x0000000000000000uL,
        )
    TestSessions.create().use { machine ->
      val execution = InstructionContext(machine, machine.syscallEnvironment)
      val fpu = machine.coprocessor1
      for (index in doubles.indices) {
        fpu.updateRegister(4, singles[index].toInt())
        execution.execute(
            ProgramStatement(0x4600209b, machine.registerFile.getPC(), machine.instructionSet),
        )
        assertEquals(1 shl index, fpu.getValue(2))
        assertEquals(singles[index].toInt(), fpu.getValue(4))
        fpu.setRegisterPairToLong(4, doubles[index].toLong())
        execution.execute(
            ProgramStatement(0x4620209b, machine.registerFile.getPC(), machine.instructionSet),
        )
        assertEquals(1L shl index, fpu.getLongFromRegisterPair(2))
        assertEquals(doubles[index].toLong(), fpu.getLongFromRegisterPair(4))
      }
    }
  }
}
