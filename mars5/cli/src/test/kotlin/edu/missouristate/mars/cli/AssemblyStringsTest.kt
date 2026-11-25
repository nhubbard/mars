package edu.missouristate.mars.cli

import edu.missouristate.mars.api.AssemblySourceSnapshot
import edu.missouristate.mars.cli.grading.Assignment
import edu.missouristate.mars.di.MarsComponent
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class AssemblyStringsTest {
  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "C:\\temp\\new\\runner\\fixture.bin",
              "C:\\fixtures\\quoted \"name\".bin",
              "relative\\nested\\file.bin",
              "line\nreturn\rtab\tback\bform\u000c.bin",
          ],
  )
  fun `assembly fixture paths reach virtual syscalls without host-specific escaping`(path: String) {
    val entry = Path.of("escaped-path.asm").toAbsolutePath()
    val source =
        $$"""
        .data
        path: .asciiz $${assemblyStringLiteral(path)}
        buffer: .space 2
        .text
        dla $a0,path
        li $a1,0
        li $v0,13
        syscall
        move $s0,$v0
        move $a0,$s0
        dla $a1,buffer
        li $a2,2
        li $v0,14
        syscall
        move $a0,$s0
        li $v0,16
        syscall
        li $v0,10
        syscall
    """
            .trimIndent()
    val assignment = Assignment(AssemblySourceSnapshot(listOf(entry), mapOf(entry to source)))
    assignment.case("literal path") {
      val files = provideFiles(mapOf(path to byteArrayOf(7, -128)))
      runToExit()
      expectBytes("buffer", 7, 128)
      files.expectAllClosed()
      assertEquals(path, files.snapshot().first().path)
    }
    assertNull(assignment.results.single().failure)
  }

  @Test
  fun `quoted literals preserve embedded NUL and delimiters in assembled memory`() {
    val text = "before\u0000after\\\"\n\r\t\b\u000c"
    val entry = Path.of("escaped-string.asm").toAbsolutePath()
    val source = ".data\nvalue: .asciiz ${assemblyStringLiteral(text)}\n.text\nnop\n"
    MarsComponent.build { guiProxy(HeadlessGuiProxy()) }
        .context()
        .use { machine ->
          val program =
              machine.assembleSnapshot(
                  AssemblySourceSnapshot(listOf(entry), mapOf(entry to source)),
                  null,
                  true,
                  false,
              )
          val address = checkNotNull(program.localSymbolTable).getAddress("value")
          assertEquals(
              text.map(Char::code) + 0,
              (0..text.length).map { machine.memory.getByte(address + it) },
          )
        }
  }
}
