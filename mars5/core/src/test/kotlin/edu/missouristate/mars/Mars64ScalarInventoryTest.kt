package edu.missouristate.mars

import edu.missouristate.mars.api.MarsTarget
import edu.missouristate.mars.assembler.release6HardwareRequirement
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class Mars64ScalarInventoryTest {
  private fun inventory() =
      checkNotNull(javaClass.getResourceAsStream("/mips64-scalar-forms.tsv"))
          .bufferedReader()
          .useLines { lines ->
            lines.filterNot { it.isBlank() || it.startsWith('#') }.map { it.split('\t') }.toList()
          }

  @Test
  fun `each implemented scalar form emits its Release 6 word`(@TempDir directory: Path) {
    val forms = inventory().filter { it[3].startsWith("implemented") }.map { it[4] to it[5] }
    assertEquals(331, forms.size)
    val source =
        Files.writeString(
            directory.resolve("release6.mars"),
            forms.joinToString("\n", prefix = ".text\n", postfix = "\ntarget: nop\n") { it.first },
        )
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val statements = checkNotNull(machine.assemble(listOf(source)).machineList)
      assertEquals(forms.size + 1, statements.size)
      for ((index, form) in forms.withIndex()) {
        val (example, expected) = form
        val word = statements[index].binaryStatement.toUInt().toString(16).padStart(8, '0')
        assertEquals(expected, word, example)
      }
    }
  }

  @Test
  fun `each unavailable scalar facility is identified during assembly`(@TempDir directory: Path) {
    val rows = inventory().filter { it[3].startsWith("requires-") }
    assertEquals(53, rows.size)
    val names = rows.map { it.first() }.distinct()
    val file = Files.writeString(directory.resolve("hardware.mars"), names.joinToString("\n"))
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(file)) }
      val diagnostics = failure.errors()!!.generateErrorReport()
      for (name in names) {
        val requirement = checkNotNull(release6HardwareRequirement(name)) { name }
        assertTrue(
            diagnostics.contains("\"$name\" requires $requirement, which MARS64 does not simulate"),
            name,
        )
      }
    }
  }

  @Test
  fun `removed scalar mnemonics explain how to migrate`(@TempDir directory: Path) {
    val removed = inventory().filter { it[3] == "removed" }
    assertEquals(119, removed.size)
    // These spellings have a retained Release 6 form or a compatibility expansion.
    val retainedNames =
        setOf(
            "mul",
            "div",
            "divu",
            "ddiv",
            "ddivu",
            "addi",
            "teqi",
            "tgei",
            "tgeiu",
            "tlti",
            "tltiu",
            "tnei",
        )
    val conditions =
        listOf(
            "f",
            "un",
            "eq",
            "ueq",
            "olt",
            "ult",
            "ole",
            "ule",
            "sf",
            "ngle",
            "seq",
            "ngl",
            "lt",
            "nge",
            "le",
            "ngt",
        )
    val names =
        removed
            .map { it.first() }
            .distinct()
            .filterNot { it in retainedNames }
            .flatMap { name ->
              if (name.startsWith("c.cond.")) conditions.map { name.replace("cond", it) }
              else listOf(name)
            }
    val file = Files.writeString(directory.resolve("removed.mars"), names.joinToString("\n"))
    TestSessions.create().use { machine ->
      machine.selectTarget(MarsTarget.MARS64)
      val failure = assertThrows(ProcessingException::class.java) { machine.assemble(listOf(file)) }
      val diagnostics = failure.errors()!!.generateErrorReport()
      for (name in names) assertTrue(diagnostics.contains("Release 6 removed \"$name\"."), name)
    }
  }
}
