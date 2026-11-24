package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class Mars64VectorBranchTest {
  @ParameterizedTest
  @CsvSource("b,471f0002", "h,473f0002", "w,475f0002", "d,477f0002", "v,457f0002")
  fun `vector branches distinguish any zero lane from a wholly zero register`(
      suffix: String,
      zeroEncoding: String,
      @TempDir directory: Path,
  ) {
    val file = directory.resolve("branch.s")
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      for (nonzero in listOf(false, true)) {
        val name = if (nonzero) "bnz" else "bz"
        Files.writeString(
            file,
            $$"""
                    $$name.$$suffix $w31,target
                    addiu $8,$8,1
                    addiu $9,$9,1
                    target: nop
                """
                .trimIndent(),
        )
        for ((vector, zero) in
            listOf(
                VectorRegister(0, 0) to true,
                VectorRegister(0, -1) to (suffix != "v"),
                VectorRegister(-1, -1) to false,
            )) {
          val program = session.assemble(listOf(file), null, extended = false, warnings = false)
          val statement = program.machineList!!.first()
          val bits = zeroEncoding.toInt(16) or if (nonzero) 0x00800000 else 0
          assertEquals(bits, statement.binaryStatement)
          val decoded = ProgramStatement(bits or 0xfffc, statement.address, session.instructionSet)
          assertEquals(listOf(31, -2), decoded.operands.take(2))
          session.coprocessor1.updateVector(31, vector)
          program.simulate(1)
          assertTrue(session.delayedBranch.isTriggered)
          program.simulate(1)
          assertEquals(1, session.registerFile.getValueNoNotify(8))
          assertEquals(
              statement.address + if (zero != nonzero) 12 else 8,
              session.registerFile.getPC(),
          )
          session.backStepper.backStep()
          assertTrue(session.delayedBranch.isTriggered)
        }
      }
    }
  }
}
