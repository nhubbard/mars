package edu.missouristate.mars

import edu.missouristate.mars.core.InstructionContext
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class WideRegisterHistoryTest {
  @Test
  fun `undo retains the full register across narrow and wide writes`(@TempDir directory: Path) {
    TestSessions.create().use { machine ->
      val source = Files.writeString(directory.resolve("entry.s"), ".text\nnop\nnop\n")
      machine.assemble(listOf(source))
      val context = InstructionContext(machine, machine.syscallEnvironment)
      val wide = 0x1234_5678_8000_0001L
      val address = machine.registerFile.programCounter
      val statement = machine.memory.getStatementNoNotify(address)

      machine.backStepper.beginInstruction(address, statement)
      context.updateLongRegister(8, wide)
      context.updateLongRegister(0, wide)
      machine.backStepper.endInstruction()
      assertEquals(0x8000_0001u.toInt(), machine.registerFile.getValue(8))
      assertEquals(wide, machine.registerFile.getLongValue(8))
      assertEquals(0L, machine.registerFile.getLongValue(0))

      machine.backStepper.beginInstruction(
          address + 4,
          machine.memory.getStatementNoNotify(address + 4),
      )
      context.updateRegister(8, -7)
      machine.backStepper.endInstruction()
      assertEquals(-7L, machine.registerFile.getLongValueNoNotify(8))

      machine.backStepper.backStep()
      assertEquals(wide, machine.registerFile.getLongValueNoNotify(8))
      machine.backStepper.backStep()
      assertEquals(0L, machine.registerFile.getLongValueNoNotify(8))
      assertEquals(address, machine.registerFile.programCounter)
    }
  }
}
