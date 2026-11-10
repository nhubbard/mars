package edu.missouristate.mars.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

  @ParameterizedTest
  @ValueSource(strings = {"p", "--project"})
  void assemblesSiblingsAndExtraSourcesWithTheFirstFileAsEntry(String projectFlag)
      throws Exception {
    var main = directory.resolve("z-entry.s");
    var sibling = directory.resolve("a-library.asm");
    var outside = Files.createDirectory(directory.resolve("nested")).resolve("extra.txt");
    Files.writeString(main, ".text\njal library\nli $v0,17\nsyscall\n");
    Files.writeString(sibling, ".text\n.globl library\nlibrary: j extra\n");
    Files.writeString(outside, ".text\n.globl extra\nextra: li $a0,42\njr $ra\n");
    Files.writeString(directory.resolve("ignored.txt"), "invalid\n");
    Files.writeString(outside.resolveSibling("ignored.s"), "invalid\n");
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    assertEquals(1, execute(new String[] {main.toString(), outside.toString()}, output, error));
    assertTrue(error.toString().contains("library"));
    error.reset();
    assertEquals(
        42,
        execute(
            new String[] {projectFlag, main.toString(), sibling.toString(), outside.toString()},
            output,
            error));
    assertEquals("", error.toString());
  }

  @Test
  void appliesErrorStatusesOnlyToTheirExecutionPhaseAndKeepsProgramExitAndStepLimit()
      throws Exception {
    var file = directory.resolve("status.s");
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    Files.writeString(file, "invalid\n");
    assertEquals(19, execute(new String[] {"AE0x13", "se23", file.toString()}, output, error));
    assertTrue(error.toString().contains("invalid"));
    assertEquals(
        19,
        execute(new String[] {"--assembly-error-exit-code", "19", file.toString()}, output, error));
    error.reset();

    Files.writeString(file, "li $v0,999\nsyscall\n");
    assertEquals(
        23,
        execute(
            new String[] {"ae19", "--simulation-error-exit-code", "027", file.toString()},
            output,
            error));
    assertTrue(error.toString().contains("syscall"));
    error.reset();
    assertEquals(0, execute(new String[] {"a", "se23", file.toString()}, output, error));

    Files.writeString(file, "li $a0,37\nli $v0,17\nsyscall\n");
    assertEquals(37, execute(new String[] {"ae19", "se23", "-1", file.toString()}, output, error));
    assertEquals(3, execute(new String[] {"ae19", "se23", "0x1", file.toString()}, output, error));
    assertTrue(error.toString().contains("step limit"));
    error.reset();
    assertEquals(2, execute(new String[] {"--assembly-error-exit-code"}, output, error));
    assertTrue(error.toString().contains("Missing assembly error exit code"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"smc", "--self-modifying-code"})
  void selfModifyingCodeIsEnabledOnlyForTheRequestedInvocation(String flag) throws Exception {
    var file = directory.resolve("patch.s");
    Files.writeString(
        file,
        """
        la $t0, patch
        li $t1, 0x2404002a
        sw $t1, 0($t0)
        patch: nop
        li $v0,17
        syscall
        """);
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    assertEquals(42, execute(new String[] {flag, file.toString()}, output, error));
    assertEquals("", error.toString());
    assertEquals(1, execute(new String[] {file.toString()}, output, error));
    assertTrue(error.toString().contains("text segment"));
  }

  private static int execute(String[] args, ByteArrayOutputStream out, ByteArrayOutputStream err) {
    return MarsCli.execute(
        args, InputStream.nullInputStream(), new PrintStream(out), new PrintStream(err));
  }
}
