package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

class Mars64InstructionBaselineTest {
  @ParameterizedTest(name = "{0}")
  @MethodSource("encodingCases")
  fun `wide native forms agree with LLVM and round trip their operands`(
      source: String,
      word: Int,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("instruction.s"), ".text\n$source\n")
    TestSessions.create().use { session ->
      assertNull(session.instructionSet.findByBinaryCode(word))
      assertThrows(ProcessingException::class.java) {
        session.assemble(listOf(file), null, extended = false, warnings = false)
      }
      session.selectTarget(MarsTarget.MARS64)
      val statement =
          session
              .assemble(listOf(file), null, extended = false, warnings = false)
              .machineList!!
              .single()
      assertEquals(word, statement.binaryStatement)
      val decoded = ProgramStatement(word, statement.address, session.instructionSet)
      val instruction = checkNotNull(decoded.instruction)
      assertEquals(statement.instruction!!.exampleFormat, instruction.exampleFormat)
      val expectedOperands = statement.operands.copyOf()
      // The current decoder exposes the raw 16-bit field for these signed immediates.
      val immediate =
          when (instruction.name) {
            "daddiu" -> 2
            "lwu",
            "ld",
            "sd" -> 1
            else -> null
          }
      if (immediate != null) expectedOperands[immediate] = expectedOperands[immediate] and 0xffff
      assertArrayEquals(expectedOperands, decoded.operands)
    }
  }

  @Test
  fun `encoding fixture still covers 30 distinct original MARS64 native forms`() {
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val coveredForms = encodingVectors.map { (_, word) ->
        checkNotNull(session.instructionSet.findByBinaryCode(word)).exampleFormat
      }
      assertEquals(30, coveredForms.size)
      assertEquals(30, coveredForms.toSet().size)
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("arithmeticCases")
  fun `wide operations write full width results and backstep restores all bits`(
      source: String,
      expected: Long,
      @TempDir directory: Path,
  ) {
    val file = Files.writeString(directory.resolve("instruction.s"), ".text\n$source\n")
    TestSessions.create().use { session ->
      session.selectTarget(MarsTarget.MARS64)
      val program = session.assemble(listOf(file), null, extended = false, warnings = false)
      val previous = 0x1234_5678_9abc_def0L
      session.editIntegerRegister(9, previous)
      session.editIntegerRegister(10, -0x0001_0000_0000_0001L)
      // A variable shift uses the low six bits of 65.
      session.editIntegerRegister(11, 65)
      program.simulate(1)
      assertEquals(expected, session.registerFile.getLongValueNoNotify(9))
      session.backStepper.backStep()
      assertEquals(previous, session.registerFile.getLongValueNoNotify(9))
    }
  }

  companion object {
    private val encodingVectors by lazy {
      checkNotNull(
              Mars64InstructionBaselineTest::class
                  .java
                  .getResourceAsStream("/mars64-basic-encoding-vectors.tsv"),
          )
          .bufferedReader()
          .useLines { lines ->
            lines
                .filterNot { it.isBlank() || it.startsWith('#') }
                .map {
                  val (source, hex) = it.split('\t')
                  source to hex.toUInt(16).toInt()
                }
                .toList()
          }
    }

    @JvmStatic
    fun encodingCases() = encodingVectors.map { (source, word) -> Arguments.of(source, word) }

    @JvmStatic
    fun arithmeticCases() =
        listOf(
            Arguments.of("dadd $9,$10,$11", -281_474_976_710_592L),
            Arguments.of("daddu $9,$10,$11", -281_474_976_710_592L),
            Arguments.of("dsub $9,$10,$11", -281_474_976_710_722L),
            Arguments.of("dsubu $9,$10,$11", -281_474_976_710_722L),
            Arguments.of("daddiu $9,$10,-32768", -281_474_976_743_425L),
            Arguments.of("dsllv $9,$10,$11", -562_949_953_421_314L),
            Arguments.of("dsrlv $9,$10,$11", 9_223_231_299_366_420_479L),
            Arguments.of("dsrav $9,$10,$11", -140_737_488_355_329L),
            Arguments.of("dsll32 $9,$10,31", Long.MIN_VALUE),
            Arguments.of("dsrl32 $9,$10,31", 1L),
            Arguments.of("dsra32 $9,$10,31", -1L),
            Arguments.of("dmul $9,$10,$11", -18_295_873_486_192_705L),
            Arguments.of("dmuh $9,$10,$11", -1L),
            Arguments.of("dmulu $9,$10,$11", -18_295_873_486_192_705L),
            Arguments.of("dmuhu $9,$10,$11", 64L),
            Arguments.of("ddiv $9,$10,$11", -4_330_384_257_087L),
            Arguments.of("ddivu $9,$10,$11", 283_791_732_288_197_553L),
            Arguments.of("dmod $9,$10,$11", -2L),
            Arguments.of("dmodu $9,$10,$11", 14L),
            Arguments.of("dsll $9,$10,10", -288_230_376_151_712_768L),
            Arguments.of("dsrl $9,$10,10", 18_014_123_631_575_039L),
            Arguments.of("dsra $9,$10,10", -274_877_906_945L),
            Arguments.of("drotr $9,$10,10", -274_877_906_945L),
            Arguments.of("drotr32 $9,$10,10", -65L),
            Arguments.of("drotrv $9,$10,$11", -140_737_488_355_329L),
            Arguments.of("dclz $9,$10", 0L),
            Arguments.of("dclo $9,$10", 15L),
        )
  }
}
