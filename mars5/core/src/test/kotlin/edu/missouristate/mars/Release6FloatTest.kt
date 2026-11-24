package edu.missouristate.mars

import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.pow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Release6FloatTest {
  @Test
  fun `fused instructions round only once in both precisions`(@TempDir directory: Path) {
    val file =
        Files.writeString(
            directory.resolve("fused.s"),
            $$"""
            maddf.s $f0,$f4,$f6
            msubf.s $f2,$f4,$f6
            maddf.d $f8,$f12,$f14
            msubf.d $f10,$f12,$f14
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      val program = machine.assemble(listOf(file))
      val fpu = machine.coprocessor1
      val singleDelta = 2f.pow(-13)
      val doubleDelta = 2.0.pow(-27)
      fpu.setRegisterToFloat(0, -1f)
      fpu.setRegisterToFloat(2, 1f)
      fpu.setRegisterToFloat(4, 1f + singleDelta)
      fpu.setRegisterToFloat(6, 1f - singleDelta)
      fpu.setRegisterPairToDouble(8, -1.0)
      fpu.setRegisterPairToDouble(10, 1.0)
      fpu.setRegisterPairToDouble(12, 1.0 + doubleDelta)
      fpu.setRegisterPairToDouble(14, 1.0 - doubleDelta)
      assertTrue(program.simulate(5))
      assertEquals(-(2f.pow(-26)), fpu.getFloatFromRegister(0))
      assertEquals(2f.pow(-26), fpu.getFloatFromRegister(2))
      assertEquals(-(2.0.pow(-54)), fpu.getDoubleFromRegisterPair(8))
      assertEquals(2.0.pow(-54), fpu.getDoubleFromRegisterPair(10))
    }
  }

  @Test
  fun `selection preserves NaN payloads and uses only the condition bit`(@TempDir directory: Path) {
    val file =
        Files.writeString(
            directory.resolve("select.s"),
            $$"""
            min.s $f0,$f4,$f6
            max.s $f2,$f4,$f8
            sel.s $f10,$f4,$f6
            seleqz.s $f12,$f4,$f8
            selnez.s $f14,$f6,$f10
            mina.d $f16,$f20,$f22
            maxa.d $f18,$f20,$f22
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      val program = machine.assemble(listOf(file))
      val fpu = machine.coprocessor1
      fpu.setRegisterToInt(4, 0x7fc00012)
      fpu.setRegisterToInt(6, 0x7fc00035)
      fpu.setRegisterToFloat(8, -0f)
      fpu.setRegisterToInt(10, 1)
      fpu.setRegisterPairToDouble(20, -0.0)
      fpu.setRegisterPairToDouble(22, 0.0)
      assertTrue(program.simulate(8))
      assertEquals(0x7fc00012, fpu.getValue(0))
      assertEquals(Int.MIN_VALUE, fpu.getValue(2))
      assertEquals(0x7fc00035, fpu.getValue(10))
      assertEquals(0x7fc00012, fpu.getValue(12))
      assertEquals(0x7fc00035, fpu.getValue(14))
      assertEquals(Long.MIN_VALUE, fpu.getLongFromRegisterPair(16))
      assertEquals(0L, fpu.getLongFromRegisterPair(18))
    }
  }

  @Test
  fun `double precision operations require register pairs`(@TempDir directory: Path) {
    val file = Files.writeString(directory.resolve("odd.s"), $$"max.d $f2,$f5,$f6")
    TestSessions.create().use { machine ->
      val program = machine.assemble(listOf(file))
      val failure = assertThrows(ProcessingException::class.java) { program.simulate(1) }
      assertTrue(failure.message!!.contains("all registers must be even-numbered"))
    }
  }
}
