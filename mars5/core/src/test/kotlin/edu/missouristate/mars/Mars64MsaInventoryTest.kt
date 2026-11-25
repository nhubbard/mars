package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.CAUSE
import edu.missouristate.mars.mips.hardware.Coprocessor0State.Companion.VADDR
import edu.missouristate.mars.mips.hardware.MemoryState
import edu.missouristate.mars.simulator.Exceptions.ADDRESS_EXCEPTION_STORE
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource

class Mars64MsaInventoryTest {
  private data class Form(val mnemonic: String, val source: String, val word: Int)

  @Test
  fun `every form in the MSA instruction reference assembles and decodes`(
      @TempDir directory: Path,
  ) {
    val forms =
        checkNotNull(javaClass.getResourceAsStream("/mips64-msa-forms.tsv"))
            .bufferedReader()
            .useLines { lines ->
              lines
                  .filterNot { it.isBlank() || it.startsWith('#') }
                  .map { row ->
                    val fields = row.split('\t')
                    val (mnemonic, operands) = fields
                    val source =
                        operands.split(',').joinToString(",") {
                          checkNotNull(operandExamples[it]) { row }
                        }
                    Form(mnemonic, "$mnemonic $source", fields.last().toUInt(16).toInt())
                  }
                  .toList()
            }
    assertEquals(531, forms.size)
    val source =
        Files.writeString(
            directory.resolve("msa.mars"),
            forms.joinToString(
                "\n",
                prefix = ".text\n",
                postfix = "\ntarget: nop\n",
                transform = Form::source,
            ),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val program = machine.assemble(listOf(source), null, extended = false, warnings = false)
      val statements = checkNotNull(program.machineList)
      assertEquals(forms.size + 1, statements.size)
      for ((index, form) in forms.withIndex()) {
        val word = statements[index].binaryStatement
        assertEquals(form.word.toUInt().toString(16), word.toUInt().toString(16), form.source)
        assertEquals(
            form.mnemonic,
            machine.instructionSet.findByBinaryCode(word)?.name,
            form.source,
        )
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = [MemoryState.LITTLE_ENDIAN, MemoryState.BIG_ENDIAN])
  fun `vector word transfers agree with scalar words and bytes across address ranges`(
      littleEndian: Boolean,
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("vector-memory.mars"),
            $$"""
            .data
            input: .word 0x01234567, 0x89abcdef, 0xfedcba98, 0x76543210
            output: .space 16
            .data 0x100000000
            wide: .space 16
            .text
            la $t0, input
            dla $t1, wide
            ld.w $w0, 0($t0)
            st.w $w0, 0($t1)
            ld.w $w1, 0($t1)
            st.w $w1, 16($t0)
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val memory = machine.memory
      memory.byteOrder = littleEndian
      val program = machine.assemble(listOf(source))
      assertTrue(program.simulate(50))

      val words = listOf(0x01234567, 0x89abcdef.toInt(), 0xfedcba98.toInt(), 0x76543210)
      for (base in
          listOf(memory.dataBaseAddress.toLong(), 0x1_0000_0000L, memory.dataBaseAddress + 16L)) {
        words.forEachIndexed { index, word ->
          val address = base + index * 4
          assertEquals(word, memory.inspectWord(address))
          for (byte in 0..3) {
            val shift = (if (littleEndian) byte else 3 - byte) * 8
            assertEquals((word ushr shift) and 0xff, memory.inspectDataByte(address + byte))
          }
        }
      }
    }
  }

  @ParameterizedTest
  @CsvSource("true, true", "true, false", "false, true", "false, false")
  fun `vector stores can reach the last stack byte and reject crossing it before writing`(
      littleEndian: Boolean,
      crossesTop: Boolean,
      @TempDir directory: Path,
  ) {
    val source =
        Files.writeString(
            directory.resolve("vector-stack.mars"),
            $$"""
            .text
            ld.b $w0, 0($5)
            st.b $w0, 0($4)
            """
                .trimIndent(),
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val memory = machine.memory
      memory.byteOrder = littleEndian
      val program = machine.assemble(listOf(source))
      val firstByte = memory.stackBaseAddress - if (crossesTop) 3 else 12
      val writable = firstByte..memory.stackBaseAddress + 3
      for (address in writable) memory.setByte(address, 0x5a)
      for (offset in 0 until 16) memory.setByte(memory.dataBaseAddress + offset, 0x40 + offset)
      machine.editIntegerRegister(4, firstByte)
      machine.editIntegerRegister(5, memory.dataBaseAddress)

      if (crossesTop) {
        assertThrows<ProcessingException> { program.simulate(2) }
        assertEquals(ADDRESS_EXCEPTION_STORE, (machine.coprocessor0.getValue(CAUSE) ushr 2) and 31)
        assertEquals(firstByte, machine.coprocessor0.getValue(VADDR))
      } else {
        assertTrue(program.simulate(8))
        for ((offset, address) in writable.withIndex()) assertEquals(
            0x40 + offset,
            memory.getByte(address),
        )
        machine.backStepper.backStep()
      }
      for (address in writable) assertEquals(
          0x5a,
          memory.getByte(address),
          "Byte at 0x${address.toString(16)}",
      )
    }
  }

  private companion object {
    val operandExamples =
        mapOf(
            "rd" to "$9",
            "rs" to "$10",
            "rt" to "$11",
            "sa" to "2",
            "wd" to $$"$w8",
            "ws" to $$"$w10",
            "wt" to $$"$w13",
            "cd" to "$1",
            "cs" to "$1",
            "wd[n]" to $$"$w8[1]",
            "ws[n]" to $$"$w10[1]",
            "ws[0]" to $$"$w10[0]",
            "ws[rt]" to $$"$w10[$11]",
            "s5" to "-2",
            "u5" to "2",
            "i8" to "2",
            "m" to "1",
            "s10" to "2",
            "s10(rs)" to "8($10)",
            "s16" to "target",
        )
  }
}
