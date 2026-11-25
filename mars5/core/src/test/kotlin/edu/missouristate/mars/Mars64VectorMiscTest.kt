package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.VectorRegister
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class Mars64VectorMiscTest {
  @ParameterizedTest
  @ValueSource(strings = ["b", "h", "w", "d"])
  fun `division remainder and absolute extrema use each lane independently`(
      suffix: String,
      @TempDir directory: Path,
  ) {
    val format = "bhwd".indexOf(suffix)
    val bits = 8 shl format
    val min = -1L shl (bits - 1)
    val mask = -1L ushr (64 - bits)
    val cases =
        listOf(
            Triple("div_s", 0x7a051fd2, -2L to min),
            Triple("div_u", 0x7a851fd2, ((mask - 6).toULong() / 3u).toLong() to 0L),
            Triple("mod_s", 0x7b051fd2, -1L to 0L),
            Triple("mod_u", 0x7b851fd2, ((mask - 6).toULong() % 3u).toLong() to (min and mask)),
            Triple("max_a", 0x7b051fce, -7L to min),
            Triple("min_a", 0x7b851fce, 3L to -1L),
        )
    val file =
        Files.writeString(
            directory.resolve("numeric.s"),
            cases.joinToString("\n") { (name) ->
              $$"$$name.$$suffix $w31,$w3,$w5"
            },
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(file), null, extended = false, warnings = false)
      val fpu = session.coprocessor1
      fpu.updateVector(3, vector(bits, -7, min))
      fpu.updateVector(5, vector(bits, 3, -1))
      for ((index, case) in cases.withIndex()) {
        val (name, word, result) = case
        assertEquals(word or (format shl 21), program.machineList!![index].binaryStatement, name)
        program.simulate(1)
        assertEquals(vector(bits, result.first, result.second), fpu.readVector(31), name)
      }
      Files.writeString(file, $$"div_s.$$suffix $w31,$w3,$w5\nmod_u.$$suffix $w31,$w3,$w5")
      val zero = session.assemble(listOf(file), null, extended = false, warnings = false)
      fpu.updateVector(3, vector(bits, -7, min))
      repeat(2) {
        zero.simulate(1)
        assertEquals(VectorRegister(0L, 0L), fpu.readVector(31))
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = ["b", "h", "w", "d"])
  fun `load insert and saturation honor signed immediates and preserve other lanes`(
      suffix: String,
      @TempDir directory: Path,
  ) {
    val format = "bhwd".indexOf(suffix)
    val bits = 8 shl format
    val file =
        Files.writeString(
            directory.resolve("transfer.s"),
            $$"""
            ldi.$$suffix $w31,-1
            insve.$$suffix $w31[1],$w3[0]
            sat_s.$$suffix $w5,$w7,3
            sat_u.$$suffix $w6,$w7,3
        """
                .trimIndent(),
        )
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(file), null, extended = false, warnings = false)
      val sizeField = listOf(0x700000, 0x600000, 0x400000, 0)[format]
      val insertField = listOf(0, 0x20, 0x30, 0x38)[format] shl 16
      assertEquals(
          listOf(
              0x7b1fffc7 or (format shl 21),
              0x79411fd9 or insertField,
              0x7803394a or sizeField,
              0x7883398a or sizeField,
          ),
          program.machineList!!.map { it.binaryStatement },
      )
      val ldi = program.machineList!!.first()
      assertEquals(
          -1,
          ProgramStatement(ldi.binaryStatement, ldi.address, session.instructionSet).operands[1],
      )
      val fpu = session.coprocessor1
      fpu.updateVector(3, vector(bits, 0x12, 0))
      fpu.updateVector(7, vector(bits, -100, 100))
      program.simulate(1)
      assertEquals(VectorRegister(-1L, -1L), fpu.readVector(31))
      program.simulate(1)
      val mask = -1L ushr (64 - bits)
      val expected =
          if (bits == 64) VectorRegister(-1L, 0x12)
          else VectorRegister((-1L and (mask shl bits).inv()) or (0x12L shl bits), -1L)
      assertEquals(expected, fpu.readVector(31))
      program.simulate(2)
      assertEquals(vector(bits, -8, 7), fpu.readVector(5))
      assertEquals(vector(bits, 15, 15), fpu.readVector(6))
      Files.writeString(file, $$"insve.$$suffix $w31[0],$w3[1]")
      assertThrows<ProcessingException> {
        session.assemble(listOf(file), null, extended = false, warnings = false)
      }
    }
  }

  private fun vector(bits: Int, low: Long, high: Long): VectorRegister {
    val mask = -1L ushr (64 - bits)
    fun repeat(lane: Long) =
        (0 until 64 step bits).fold(0L) { acc, shift -> acc or ((lane and mask) shl shift) }
    return VectorRegister(repeat(low), repeat(high))
  }
}
