package edu.missouristate.mars.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MarsCliTest {
  @TempDir Path directory;

  @Test
  void runsWithInputAndArgumentsWithoutLegacyClasses() throws Exception {
    assertThrows(
        ClassNotFoundException.class, () -> Class.forName("edu.missouristate.mars.Globals"));
    var file = directory.resolve("input.s");
    Files.writeString(
        file,
        """
        .text
        move $s0,$a0
        li $v0,5
        syscall
        move $a0,$v0
        li $v0,1
        syscall
        move $a0,$s0
        li $v0,17
        syscall
        """);
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    int code =
        MarsCli.execute(
            new String[] {file.toString(), "--", "one", "two"},
            new ByteArrayInputStream("42\n".getBytes()),
            new PrintStream(output),
            new PrintStream(error));
    assertEquals(2, code);
    assertEquals("42", output.toString());
    assertEquals("", error.toString());
  }

  @Test
  void reportsAssemblyErrorsLimitsAndHelp() throws Exception {
    var file = directory.resolve("program.s");
    var error = new ByteArrayOutputStream();
    var output = new ByteArrayOutputStream();
    Files.writeString(file, ".text\nloop: b loop\n");
    assertEquals(3, execute(new String[] {"--max-steps", "4", file.toString()}, output, error));
    assertTrue(error.toString().contains("step limit"));
    assertEquals(0, execute(new String[] {"--assemble-only", file.toString()}, output, error));
    Files.writeString(file, ".text\ninvalid $t0\n");
    assertEquals(1, execute(new String[] {file.toString()}, output, error));
    assertTrue(error.toString().contains("invalid"));
    assertEquals(0, execute(new String[] {"--help"}, output, error));
    assertTrue(output.toString().contains("Usage:"));
    assertEquals(2, execute(new String[] {"--missing"}, output, error));
  }

  private static int execute(String[] args, ByteArrayOutputStream out, ByteArrayOutputStream err) {
    return MarsCli.execute(
        args, InputStream.nullInputStream(), new PrintStream(out), new PrintStream(err));
  }
}
