package edu.missouristate.mars.desktop;

import static org.junit.jupiter.api.Assertions.*;

import edu.missouristate.mars.mips.dump.DumpFormat;
import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises both distributable JAR entry points with the same assembly inputs. */
class LauncherParityTest {
  @TempDir Path directory;

  @Test
  void bothLaunchersShowHelpAndHonorQuietMode() throws Exception {
    for (String launcher : List.of("cli", "desktop")) {
      var help = launch(launcher, "", "h");
      assertEquals(0, help.status());
      assertTrue(help.out().contains("Usage:"));
      assertTrue(help.out().contains("dump"));
      assertTrue(help.out().contains("ae"));
      assertEquals("", help.err());
    }
    Path file = source("entry.s", "nop\n");
    assertTrue(launch("desktop", "", file.toString()).out().contains("Copyright"));
    assertEquals("", launch("cli", "", file.toString()).out());
    assertProgramResult(List.of("nc", file.toString()), "", 0, "");
  }

  @Test
  void programArgumentsThatLookLikeOutputFlagsStayProgramArguments() throws Exception {
    Path file = source("flags.s", """
        move $s0,$a1
        lw $a0,0($s0)
        li $v0,4
        syscall
        lw $a0,4($s0)
        li $v0,4
        syscall
        li $v0,10
        syscall
        """);
    assertProgramResult(List.of("nc", file.toString(), "pa", "me", "nc"), "", 0, "menc");

    var desktop = launch("desktop", "", "ic", file.toString(), "pa", "ME", "NC");
    assertEquals(0, desktop.status(), desktop.err());
    assertEquals("", desktop.err());
    assertTrue(desktop.out().contains("Copyright"));
    assertTrue(desktop.out().contains("MENC"));
    assertTrue(desktop.out().lines().anyMatch("9"::equals));
  }

  @Test
  void dumpFilenamesThatLookLikeOutputFlagsStayFilenames() throws Exception {
    Path file = source("dump-flags.s", "nop\n");
    var desktop = launch("desktop", "", "ad",
        "dump", ".text", "HexText", "me",
        "dump", ".text", "HexText", "nc", file.toString());
    assertEquals(0, desktop.status(), desktop.err());
    assertEquals("", desktop.err());
    assertTrue(desktop.out().contains("Copyright"));
    assertTrue(desktop.out().contains("ASSEMBLY BEGINS"));
    for (String name : List.of("me", "nc")) {
      assertEquals(List.of("00000000"), Files.readAllLines(directory.resolve(name)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "ad", "da", "a"})
  void traceAndAssembleOnlyModesReachTheSamePhases(String flag) throws Exception {
    Path file = source("trace.s", "li $a0,42\nli $v0,1\nsyscall\nli $v0,10\nsyscall\n");
    var arguments = List.of("nc", flag, "me", file.toString());
    var standalone = launch("cli", "", arguments);
    var desktop = launch("desktop", "", arguments);
    assertEquals(0, standalone.status());
    assertEquals(0, desktop.status());
    assertEquals(flag.equals("d") ? "42" : "", standalone.out().strip());
    assertEquals(standalone.out().strip(), desktop.out().strip());
    assertEquals(phaseLines(desktop), phaseLines(standalone));
    assertEquals(flag.equals("d") ? 4 : flag.equals("a") ? 0 : 3, phaseLines(standalone).size());
  }

  @Test
  void projectModeCombinesSiblingAndExplicitSourcesWithInputAndArguments() throws Exception {
    Path main =
        source(
            "z-entry.s",
            """
            move $s0,$a0
            lw $a0,0($a1)
            jal print
            li $v0,5
            syscall
            move $a0,$v0
            jal number
            move $a0,$s0
            li $v0,17
            syscall
            """);
    source("a-library.asm", ".text\n.globl print\nprint: li $v0,4\nsyscall\njr $ra\n");
    Path extra =
        source("nested/number.s", ".text\n.globl number\nnumber: li $v0,1\nsyscall\njr $ra\n");
    source("ignored.txt", "not assembly\n");
    assertProgramResult(
        List.of("nc", "p", main.toString(), extra.toString(), "pa", "value=", "d"),
        "42\n",
        2,
        "value=42");
  }

  @Test
  void explicitExtensionlessSourcesRunWithoutDirectoryDiscovery() throws Exception {
    Path entry = source("entry", """
        jal helper
        move $a0,$t0
        li $v0,1
        syscall
        li $v0,10
        syscall
        """);
    Path helper = source(".asm", """
        .globl helper
        helper: li $t0,42
        jr $ra
        """);
    assertProgramResult(List.of("nc", entry.toString(), helper.toString()), "", 0, "42");
  }

  @Test
  void projectModeKeepsExtensionlessEntryAndExplicitSourcesAlongsideSiblings() throws Exception {
    Path entry = source("entry", """
        jal helper
        jal sibling
        addu $a0,$t0,$t1
        li $v0,1
        syscall
        li $v0,10
        syscall
        """);
    Path helper = source(".s", """
        .globl helper
        helper: li $t0,42
        jr $ra
        """);
    Path sibling = source(".local.S", """
        .globl sibling
        sibling: li $t1,7
        jr $ra
        """);
    source("unselected", "not assembly\n");
    assertProgramResult(List.of("nc", "p", entry.toString(), helper.toString()), "", 0, "49");
    assertProgramResult(List.of("nc", "p", entry.toString(), helper.toString(), sibling.toString()), "", 0, "49");
  }

  @Test
  void entrySelectionAndBranchDelaySlotsChangeExecutionInBothLaunchers() throws Exception {
    Path file =
        source(
            "entry.s",
            """
            .text
            .globl main
            addiu $a0,$zero,11
            addiu $v0,$zero,17
            syscall
            main:
            addiu $a0,$zero,19
            beq $zero,$zero,exit
            addiu $a0,$a0,1
            exit:
            addiu $v0,$zero,17
            syscall
            """);
    assertProgramResult(List.of("nc", file.toString()), "", 11, "");
    assertProgramResult(List.of("nc", "sm", file.toString()), "", 19, "");
    assertProgramResult(List.of("nc", "sm", "db", file.toString()), "", 20, "");
  }

  @Test
  void diagnosticsAndExplicitErrorStatusesMatchAcrossPhases() throws Exception {
    Path warning = source("warning.s", ".data\n.byte 1024\n.text\nnop\n");
    assertDiagnostics(List.of("a", warning.toString()), 0, "possibly truncated");
    assertDiagnostics(List.of("a", "we", "ae19", warning.toString()), 19, "possibly truncated");

    Path pseudo = source("pseudo.s", "li $v0,999\nsyscall\n");
    assertDiagnostics(List.of("np", "ae19", pseudo.toString()), 19, "li");
    assertDiagnostics(List.of("se23", pseudo.toString()), 23, "syscall");
    assertDiagnostics(List.of("np", "ae0", pseudo.toString()), 0, "li");
    assertDiagnostics(List.of("se0", pseudo.toString()), 0, "syscall");
  }

  @Test
  void bothLaunchersReportDefaultFailureStatuses() throws Exception {
    Path assembly = source("assembly-error.s", "bogus\n");
    Path simulation = source("simulation-error.s", "li $v0,999\nsyscall\n");
    for (Path file : List.of(assembly, simulation)) {
      var standalone = launch("cli", "", "nc", "me", file.toString());
      var desktop = launch("desktop", "", "nc", "me", file.toString());
      assertEquals(1, standalone.status());
      assertEquals(1, desktop.status());
      assertTrue(standalone.err().startsWith("Error"));
      assertTrue(desktop.err().startsWith("Error"));
    }
    for (String launcher : List.of("cli", "desktop")) {
      for (List<String> arguments : List.of(
          List.of("--unknown-option", "nc", "me"),
          List.of("nc", "me", "mc"),
          List.of("nc", "me", "$notARegister", simulation.toString()),
          List.of("nc", "me"))) {
        var failure = launch(launcher, "", arguments);
        assertEquals(2, failure.status(), launcher + " " + arguments);
        assertFalse(failure.err().isBlank(), launcher + " " + arguments);
      }
    }
  }

  @Test
  void selfModifyingCodeUsesTheSameRuntimeOptionInBothLaunchers() throws Exception {
    Path file =
        source(
            "patch.s",
            """
            la $t0,patch
            li $t1,0x2404002a
            sw $t1,0($t0)
            patch: nop
            li $v0,17
            syscall
            """);
    assertProgramResult(List.of("nc", "smc", file.toString()), "", 42, "");
    assertDiagnostics(List.of("se23", file.toString()), 23, "text segment");
  }

  @ParameterizedTest
  @ValueSource(strings = {"hex", "dec b", "ascii me"})
  void registerAndMemoryReportsMatchOnBothStreams(String options) throws Exception {
    Path file =
        source(
            "reports.s",
            """
            .data
            .word 1,2,3,4,5
            .text
            li $t0,0x41424344
            mtc1 $t0,$f0
            mtc1 $t0,$f1
            li $a0,7
            li $v0,1
            syscall
            li $v0,10
            syscall
            """);
    var arguments = new ArrayList<>(List.of("nc"));
    arguments.addAll(Arrays.asList(options.split(" ")));
    arguments.addAll(
        List.of(
            "$8", "f0", "$f1", "0x10010000-0x10010010", "0x00400000-0x00400000", file.toString()));
    var standalone = launch("cli", "", arguments);
    assertEquals(0, standalone.status());
    assertTrue(standalone.out().startsWith("7"));
    assertEquals(standalone, launch("desktop", "", arguments));
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void repeatedDumpsMatchAcrossAllFormatsAndIncludeExplicitRangeEndpoints(boolean assembleOnly)
      throws Exception {
    Path file =
        source(
            "dump.s",
            """
            .data
            word: .word 11,22
            .text
            li $t0,42
            sw $t0,word
            li $v0,10
            syscall
            """);
    var descriptors =
        DumpFormatLoader.FORMATS.stream().map(DumpFormat::getCommandDescriptor).toList();
    for (String launcher : List.of("cli", "desktop")) {
      var arguments = new ArrayList<>(List.of("nc", "me"));
      if (assembleOnly) {
        arguments.add("a");
      }
      for (String descriptor : descriptors) {
        arguments.addAll(
            List.of("dump", ".text", descriptor, dump(launcher, descriptor).toString()));
      }
      arguments.addAll(
          List.of(
              "dump",
              ".data",
              "HexText",
              dump(launcher, "data").toString(),
              "dump",
              "0x10010000-0x10010004",
              "HexText",
              dump(launcher, "range").toString(),
              "dump",
              "0x10010004-0x10010004",
              "HexText",
              dump(launcher, "single-word").toString(),
              file.toString()));
      var result = launch(launcher, "", arguments);
      assertEquals(0, result.status(), result.err());
      assertTrue(result.err().isBlank(), result.err());
    }
    for (String descriptor : descriptors) {
      assertArrayEquals(
          Files.readAllBytes(dump("desktop", descriptor)),
          Files.readAllBytes(dump("cli", descriptor)),
          descriptor);
    }
    assertArrayEquals(
        Files.readAllBytes(dump("desktop", "data")), Files.readAllBytes(dump("cli", "data")));
    String first = assembleOnly ? "0000000b" : "0000002a";
    for (String launcher : List.of("cli", "desktop")) {
      assertEquals(List.of(first, "00000016"), Files.readAllLines(dump(launcher, "range")));
      assertEquals(List.of("00000016"), Files.readAllLines(dump(launcher, "single-word")));
    }
  }

  @Test
  void memoryReportsUseUnsignedAddressOrderWithoutWrapping() throws Exception {
    Path file = source("kernel-range.s", """
        .ktext 0x80000000
        addiu $k0,$zero,7
        .text
        li $v0,10
        syscall
        """);
    var arguments = List.of("nc", "me", "0x7ffffffc-0x80000000", "0xfffffffc-0xfffffffc", file.toString());
    var standalone = launch("cli", "", arguments);
    assertEquals(0, standalone.status(), standalone.err());
    assertEquals(List.of("Mem[0x7ffffffc]\t0x00000000\t0x241a0007", "Mem[0xfffffffc]\t0x00000000"),
        standalone.err().lines().filter(line -> !line.isBlank()).map(String::stripTrailing).toList());
    assertEquals(standalone, launch("desktop", "", arguments));
    for (String launcher : List.of("cli", "desktop")) {
      for (String range : List.of("0x80000000-0x7ffffffc", "4294967296-4294967296", "0x80000001-0x80000004")) {
        var failure = launch(launcher, "", "nc", "me", range, file.toString());
        assertEquals(2, failure.status(), launcher + " " + range);
        assertTrue(failure.err().contains(range), failure.err());
      }
    }
  }

  @Test
  void assemblyFailuresLeaveExistingAndMissingDumpDestinationsAlone() throws Exception {
    Path file = source("partial-data.s", """
        .data
        .word 11
        .text
        addiu $t0,$zero
        """);
    for (String launcher : List.of("cli", "desktop")) {
      Path existing = Files.writeString(dump(launcher, "existing"), "previous successful dump\n");
      Path missing = dump(launcher, "missing");
      for (int status : List.of(19, 0)) {
        var result = launch(launcher, "", "nc", "me", "ae" + status,
            "dump", ".data", "HexText", existing.toString(),
            "dump", ".data", "HexText", missing.toString(), file.toString());
        assertEquals(status, result.status(), result.err());
        assertTrue(result.err().contains("addiu"), result.err());
        assertEquals("previous successful dump\n", Files.readString(existing), launcher);
        assertFalse(Files.exists(missing), launcher);
      }
    }
  }

  @Test
  void simulationFailuresStillExportTheFinalMachineState() throws Exception {
    Path file = source("simulation-dump.s", """
        .data
        word: .word 11
        .text
        li $t0,42
        sw $t0,word
        li $v0,999
        syscall
        """);
    for (String launcher : List.of("cli", "desktop")) {
      Path output = dump(launcher, "simulation");
      var result = launch(launcher, "", "nc", "me", "se23",
          "dump", "0x10010000-0x10010000", "HexText", output.toString(), file.toString());
      assertEquals(23, result.status(), result.err());
      assertTrue(result.err().contains("syscall"), result.err());
      assertEquals(List.of("0000002a"), Files.readAllLines(output), launcher);
    }
  }

  @Test
  void compactTextLoopCountsEveryStepAndReportsTheStepLimitStatus() throws Exception {
    Path file = source("loop.s", "loop: beq $zero,$zero,loop\n");
    for (String limit : List.of("3", "32")) {
      var arguments = List.of("nc", "ic", "mc", "CompactTextAtZero", limit, file.toString());
      var standalone = launch("cli", "", arguments);
      var desktop = launch("desktop", "", arguments);
      assertEquals(3, standalone.status());
      assertEquals(3, desktop.status());
      assertEquals(limit, standalone.out().strip());
      assertTrue(desktop.out().lines().anyMatch(limit::equals));
      assertTrue(standalone.err().contains("step limit"));
      assertTrue(desktop.out().contains("step limit " + limit));
    }

    Path exitsAtLimit = source("exits-at-limit.s", "li $a0,17\nli $v0,17\nsyscall\n");
    assertProgramResult(List.of("nc", "3", exitsAtLimit.toString()), "", 17, "");
  }

  private void assertProgramResult(List<String> arguments, String input, int status, String output)
      throws Exception {
    for (String launcher : List.of("cli", "desktop")) {
      var result = launch(launcher, input, arguments);
      assertEquals(status, result.status(), launcher + ": " + result.err());
      assertEquals(output, result.out().strip());
      assertEquals("", result.err());
    }
  }

  private void assertDiagnostics(List<String> flags, int status, String expected) throws Exception {
    var arguments = new ArrayList<>(List.of("nc", "me"));
    arguments.addAll(flags);
    var standalone = launch("cli", "", arguments);
    var desktop = launch("desktop", "", arguments);
    assertEquals(status, standalone.status(), standalone.err());
    assertEquals(status, desktop.status(), desktop.err());
    var diagnostic = standalone.err().lines().filter(line -> !line.isBlank()).toList();
    assertTrue(diagnostic.stream().anyMatch(line -> line.contains(expected)), standalone.err());
    assertEquals(
        diagnostic,
        desktop
            .err()
            .lines()
            .filter(line -> !line.isBlank() && !line.equals("Processing terminated due to errors."))
            .toList());
  }

  private static List<String> phaseLines(Result result) {
    return result.err().lines().filter(line -> line.startsWith("--------")).toList();
  }

  private Path source(String name, String contents) throws Exception {
    Path file = directory.resolve(name);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, contents);
  }

  private Path dump(String launcher, String descriptor) {
    return directory.resolve(launcher + "-" + descriptor);
  }

  private Result launch(String launcher, String input, String... arguments) throws Exception {
    return launch(launcher, input, List.of(arguments));
  }

  private Result launch(String launcher, String input, List<String> arguments) throws Exception {
    Path bin = Path.of(System.getProperty("java.home"), "bin");
    Path java = bin.resolve(Files.exists(bin.resolve("java.exe")) ? "java.exe" : "java");
    var command =
        new ArrayList<>(
            List.of(
                java.toString(),
                "-Djava.awt.headless=true",
                "-jar",
                System.getProperty("mars." + launcher + ".jar")));
    command.addAll(arguments);
    Path stdout = Files.createTempFile(directory, "stdout-", ".txt");
    Path stderr = Files.createTempFile(directory, "stderr-", ".txt");
    Path stdin = Files.writeString(Files.createTempFile(directory, "stdin-", ".txt"), input);
    var process =
        new ProcessBuilder(command)
            .directory(directory.toFile())
            .redirectInput(stdin.toFile())
            .redirectOutput(stdout.toFile())
            .redirectError(stderr.toFile())
            .start();
    try {
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), () -> "Launcher timed out: " + command);
      return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private record Result(int status, String out, String err) {}
}
