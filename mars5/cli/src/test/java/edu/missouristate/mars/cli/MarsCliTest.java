package edu.missouristate.mars.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.nio.file.*;
import java.util.List;
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

  @Test
  void dumpsSegmentsAndInclusiveRangesFromAssemblyAndFinishedExecution() throws Exception {
    var source = directory.resolve("dump.s");
    var assembled = directory.resolve("assembled.txt");
    var ran = directory.resolve("ran.txt");
    var text = directory.resolve("text.txt");
    Files.writeString(assembled, "old contents\n");
    Files.writeString(
        source,
        """
        .data
        item: .word 11, 22
        .text
        li $t0,42
        sw $t0,item
        li $v0,10
        syscall
        """);
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    assertEquals(
        0,
        execute(new String[] {
            "a", "dump", "0x10010000-0x10010004", "HexText", assembled.toString(), source.toString()
        }, output, error));
    assertEquals(List.of("0000000b", "00000016"), Files.readAllLines(assembled));
    assertEquals(
        0,
        execute(new String[] {
            "--dump", "0x10010000-0x10010000", "HexText", ran.toString(),
            "dump", ".text", "SegmentWindow", text.toString(), source.toString()
        }, output, error));
    assertEquals(List.of("0000002a"), Files.readAllLines(ran));
    assertTrue(Files.readString(text).contains("sw $t0,item"));
    assertEquals("", output.toString());
    assertEquals("", error.toString());
  }

  @Test
  void dumpFailuresLeaveTheExistingDestinationIntactAndAllowOtherDumps() throws Exception {
    var source = directory.resolve("empty.s");
    var destination = directory.resolve("existing.txt");
    var text = directory.resolve("text.txt");
    Files.writeString(source, "nop\n");
    Files.writeString(destination, "keep this file\n");
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    assertEquals(
        0,
        execute(new String[] {
            "a", "dump", ".data", "HexText", destination.toString(),
            "dump", ".text", "HexText", directory.resolve("missing/out.txt").toString(),
            "dump", ".text", "HexText", text.toString(), source.toString()
        }, output, error));
    assertEquals("keep this file\n", Files.readString(destination));
    assertEquals(List.of("00000000"), Files.readAllLines(text));
    assertTrue(error.toString().contains("no initialized words"));
    assertTrue(error.toString().contains("missing"));
    error.reset();

    assertEquals(2, execute(new String[] {"dump", ".text", "HexText"}, output, error));
    assertTrue(error.toString().contains("Missing dump filename"));
    error.reset();
    assertEquals(
        2,
        execute(
            new String[] {"dump", ".text", "Bogus", destination.toString(), source.toString()},
            output,
            error));
    assertTrue(error.toString().contains("Unknown memory dump format"));
    error.reset();
    assertEquals(
        2,
        execute(
            new String[] {
              "dump", "0x10010001-0x10010004", "HexText", destination.toString(), source.toString()
            },
            output,
            error));
    assertTrue(error.toString().contains("Invalid or unaligned memory range"));
    assertEquals("keep this file\n", Files.readString(destination));
  }

  @Test
  void dumpsKernelMemoryOnSimulationErrorAndSkipsExportOnAssemblyError() throws Exception {
    var source = directory.resolve("kernel.s");
    var kernel = directory.resolve("kernel.hex");
    var window = directory.resolve("kernel-window.txt");
    Files.writeString(source, ".kdata\n.word 0xabcd\n.text\nli $v0,999\nsyscall\n");
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();
    var arguments = new String[] {
        "ae19", "se23", "dump", "0x90000000-0x90000000", "HexText", kernel.toString(),
        "dump", "0x90000000-0x90000000", "SegmentWindow", window.toString(), source.toString()
    };

    assertEquals(23, execute(arguments, output, error));
    assertEquals(List.of("0000abcd"), Files.readAllLines(kernel));
    assertFalse(Files.exists(window));
    assertTrue(error.toString().contains("SegmentWindow requires a range within user text or user data"));
    assertTrue(error.toString().contains("syscall"));
    Files.delete(kernel);
    Files.writeString(source, "invalid\n");
    error.reset();

    assertEquals(19, execute(arguments, output, error));
    assertTrue(error.toString().contains("invalid"));
    assertFalse(Files.exists(kernel));
    assertFalse(Files.exists(window));
  }

  private static int execute(String[] args, ByteArrayOutputStream out, ByteArrayOutputStream err) {
    return MarsCli.execute(
        args, InputStream.nullInputStream(), new PrintStream(out), new PrintStream(err));
  }
}
