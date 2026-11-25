package edu.missouristate.mars

import edu.missouristate.mars.assembler.SourceLine
import java.nio.file.Path
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

class ProgramLifecycleTest {
  @Test
  fun `assembly preparation is validated before exposing a tokenized snapshot`(
      @TempDir directory: Path,
  ) {
    TestSessions.create().use { machine ->
      val program = MIPSprogram(machine)
      assertThrows<IllegalStateException> { program.tokenizedSource() }
      assertThrows<IllegalStateException> { program.tokenize() }
      val source = directory.resolve("program.s")
      source.writeText($$"addiu $t0, $zero, 7\n")
      program.readSource(source.toString())
      assertThrows<IllegalStateException> { program.tokenizedSource() }
      program.tokenize()
      val prepared = program.tokenizedSource()
      assertEquals(source.toString(), prepared.filename)
      assertSame(program.sourceLineList, prepared.lines)
      assertSame(program.tokenList, prepared.tokens)
      assertSame(program.localSymbolTable, prepared.symbols)
      assertSame(program.tokenizer, prepared.tokenizer)
      program.assemble(arrayListOf(program), true)
      assertEquals(1, program.instructionCount())
      assertNotNull(program.assemblyDiagnostics)
      assertEquals(1, prepared.tokens.size)
    }
  }

  @Test
  fun `clearing nullable source lines clears written lines and decoded absence stays public`() {
    TestSessions.create().use { machine ->
      val program = MIPSprogram(machine)
      program.sourceLineList = arrayListOf(SourceLine("nop", program, 1))
      assertEquals(listOf("nop"), program.sourceList)
      program.sourceLineList = null
      assertNull(program.sourceLineList)
      assertNull(program.sourceList)
      val invalid = ProgramStatement(-1, 0, machine.instructionSet)
      assertNull(invalid.getOperands())
      assertThrows<IllegalStateException> { invalid.captureImage() }
      val valid = ProgramStatement(0, 0, machine.instructionSet)
      assertNotNull(valid.getOperands())
      val image = valid.captureImage()
      val restored = image.load(machine.instructionSet, machine.settings)
      assertArrayEquals(valid.getOperands(), restored.getOperands())
      val hiddenOperands =
          object : ProgramStatement(0, 0, machine.instructionSet) {
            override fun getOperands(): IntArray? = null
          }
      assertNotNull(
          hiddenOperands
              .captureImage()
              .load(machine.instructionSet, machine.settings)
              .getOperands(),
      )
    }
  }
}
