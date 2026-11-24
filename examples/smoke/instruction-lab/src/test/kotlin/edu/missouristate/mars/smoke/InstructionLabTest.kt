package edu.missouristate.mars.smoke

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

internal class InstructionLabTest {
  @Test
  fun executesTheBinaryWorkbench() {
    val input: String =
        """
        1
        -1
        2
        123456789
        3
        9
        9
        8
        -1
        4
        9
        10
        8
        0
        33
        5
        1
        1
        3
        64
        0
        0
        0
        0

        """
            .trimIndent()
    val bytes = ByteArrayOutputStream()
    PrintStream(bytes, true, StandardCharsets.UTF_8).use { output ->
      InstructionLab.runWorkbench(
          Path.of("src/main/mips/workbench.asm"),
          ByteArrayInputStream(input.toByteArray(StandardCharsets.UTF_8)),
          output,
      )
    }
    val result = bytes.toString(StandardCharsets.UTF_8)
    Assertions.assertEquals(
        mutableListOf<String?>("0xffffffff", "0x2528ffff", "0x012a4021", "0x80000000"),
        result.lineSequence().filter { it.startsWith("Hex: ") }.map { it.substring(5) }.toList(),
    )
    Assertions.assertTrue(result.contains("Unsigned decimal: 4294967295"), result)
    Assertions.assertTrue(result.contains("Set bits: 32"), result)
    Assertions.assertTrue(result.contains("CRC-32: 0xcbf43926\nBytes: 9"), result)
    Assertions.assertTrue(result.contains("A field is outside its permitted range."), result)
  }
}
