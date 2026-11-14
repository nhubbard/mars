package edu.missouristate.mars.cli;

import static org.junit.jupiter.api.Assertions.*;

import edu.missouristate.mars.mips.dump.DumpFormatLoader;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Exercises the standalone distribution in a separate JVM. */
class PackagedCliTest {
  @TempDir Path directory;

  @Test
  void showsHelpAndRunsQuietly() throws Exception {
    var help = launch("h");
    assertEquals(0, help.status());
    assertTrue(help.out().contains("Usage:"));
    assertTrue(help.out().contains("dump"));
    assertTrue(help.out().contains("ae"));
    assertEquals("", help.err());
    Path file = source("entry.s", "nop\n");
    assertProgramResult(List.of(file.toString()), "", 0, "");
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

    var counted = launch("ic", file.toString(), "pa", "ME", "NC");
    assertEquals(0, counted.status(), counted.err());
    assertEquals("", counted.err());
    assertEquals(List.of("MENC", "9"), counted.out().stripTrailing().lines().toList());
  }

  @Test
  void dumpFilenamesThatLookLikeOutputFlagsStayFilenames() throws Exception {
    Path file = source("dump-flags.s", "nop\n");
    var result = launch("ad",
        "dump", ".text", "HexText", "me",
        "dump", ".text", "HexText", "nc", file.toString());
    assertEquals(0, result.status(), result.err());
    assertEquals("", result.err());
    assertTrue(result.out().contains("ASSEMBLY BEGINS"));
    for (String name : List.of("me", "nc")) {
      assertEquals(List.of("00000000"), Files.readAllLines(directory.resolve(name)));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"d", "ad", "da", "a"})
  void traceAndAssembleOnlyModesReportExpectedPhases(String flag) throws Exception {
    Path file = source("trace.s", "li $a0,42\nli $v0,1\nsyscall\nli $v0,10\nsyscall\n");
    var arguments = List.of("nc", flag, "me", file.toString());
    var standalone = launch("", arguments);
    assertEquals(0, standalone.status());
    assertEquals(flag.equals("d") ? "42" : "", standalone.out().strip());
    assertEquals(flag.equals("d") ? 4 : flag.equals("a") ? 0 : 3, phaseLines(standalone).size());
  }

  @Test
  void graderJarRegistersItsKotlinScriptTemplate() throws Exception {
    var archive = Path.of(System.getProperty("mars.cli.jar"));
    try (var jar = new ZipFile(archive.toFile());
        var loader = URLClassLoader.newInstance(
            new URL[] {archive.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
      var template = "edu.missouristate.mars.cli.grading.AssignmentScript";
      assertNotNull(jar.getEntry("META-INF/kotlin/script/templates/" + template + ".classname"));
      var thread = Thread.currentThread();
      var previous = thread.getContextClassLoader();
      try {
        thread.setContextClassLoader(null);
        var annotation = Arrays.stream(Class.forName(template, true, loader).getDeclaredAnnotations())
            .filter(candidate -> candidate.annotationType().getSimpleName().equals("KotlinScript"))
            .findFirst().orElseThrow();
        var configuration = (Class<?>) annotation.annotationType().getMethod("compilationConfiguration").invoke(annotation);
        assertNotNull(configuration.getField("INSTANCE").get(null));
      } finally {
        thread.setContextClassLoader(previous);
      }
    }
  }

  @Test
  void educationalWideTargetIsExplicitAndReportsFullWidthRegisters() throws Exception {
    var entry = source("wide.s", """
        daddiu $t0, $zero, 1
        dsll32 $t0, $t0, 0
        daddiu $t0, $t0, -1
        li $v0, 10
        syscall
        """);
    var narrow = launch(entry.toString());
    assertEquals(1, narrow.status());
    assertTrue(narrow.err().contains("daddiu"), narrow.err());
    var wide = launch("--target", "mars64", "--register", "t0", entry.toString());
    assertEquals(0, wide.status(), wide.err());
    assertTrue(wide.out().contains("$t0\t0x00000000ffffffff"), wide.out());
  }

  @Test
  void runsSourceAssembledAboveFourGiB() throws Exception {
    var entry = source("high-address.s", """
        .data 0x100000000
        pointer: .dword main
        .text 0x100400000
        .globl main
        main:
            dla $a0,pointer
            ld $t0,0($a0)
            li $v0,10
            syscall
        """);
    var run = launch("--target", "mars64", "--start-at-main", "--register", "t0", entry.toString());
    assertEquals(0, run.status(), run.err());
    assertTrue(run.out().contains("$t0\t0x0000000100400000"), run.out());
  }

  @Test
  void dumpsHighTextAndDataWithoutTruncatingAddresses() throws Exception {
    var entry = source("high-dump.s", """
        .data 0x100000000
        .word 0x41424344
        .text 0x100400000
        .globl main
        main: addiu $v0,$zero,10
        syscall
        """);
    var data = directory.resolve("high-data.txt");
    var text = directory.resolve("high-text.txt");
    var run = launch("--target", "mars64", "--start-at-main",
        "dump", "0x100000000-0x100000000", "HexText", data.toString(),
        "dump", "0x100400000-0x100400000", "SegmentWindow", text.toString(),
        "0x100000000-0x100000000", entry.toString());
    assertEquals(0, run.status(), run.err());
    assertEquals("41424344", Files.readString(data).strip());
    assertTrue(Files.readString(text).contains("0x0000000100400000  0x2402000a"));
    assertTrue(run.out().contains("Mem[0x0000000100000000]"), run.out());
  }

  @Test
  void runsAnMsaLoadComputeAndStoreProgram() throws Exception {
    var entry = source("vector.s", """
        .data
        input: .word 1, 2, 3, 4
        output: .space 16
        .text
        la $t0,input
        ld.w $w1,0($t0)
        slli.w $w1,$w1,1
        addv.w $w2,$w1,$w1
        st.w $w2,16($t0)
        lw $a0,24($t0)
        li $v0,1
        syscall
        li $v0,10
        syscall
        """);
    var run = launch("nc", "--target", "mars64", entry.toString());
    assertEquals(0, run.status(), run.err());
    assertEquals("12", run.out().strip());
  }

  @Test
  void reportsBothPrecisionsForAnOddMARS64FloatingRegister() throws Exception {
    var entry = source("wide-fpu.s", """
        lui $t0, 0x4004
        dsll32 $t0, $t0, 0
        dmtc1 $t0, $f31
        li $v0, 10
        syscall
        """);
    var wide = launch("--target", "mars64", "--register", "f31", entry.toString());
    assertEquals(0, wide.status(), wide.err());
    assertTrue(wide.out().contains("$f31\t0x00000000\t0x4004000000000000"), wide.out());
  }

  @Test
  void kotlinAssignmentCanAssertFullMARS64Registers() throws Exception {
    var script = source("assignment64.kts", """
        import edu.missouristate.mars.api.MarsTarget

        case("wide result", target = MarsTarget.MARS64) {
            setRegister("a0", 0x100000000L)
            runUntil("done")
            expectRegister("v0", 0x100000003L)
            expectVector("w1", low = -1L, high = 0L)
            expectVectorLane("${'$'}w1", lane = 7, width = 8, expected = 255)
            expectVectorLane("w1", lane = 8, width = 8, expected = 0)
            expectVectorLane("w1", lane = 0, width = 64, expected = -1L)
            runToExit()
        }
        """);
    var entry = source("wide.s", """
        daddiu $v0, $a0, 3
        ldi.d $w1,0
        daddiu $t0,$zero,-1
        dmtc1 $t0,$f1
        done: li $v0, 10
        syscall
        """);
    var result = launch("grade", script.toString(), entry.toString());
    assertEquals(0, result.status(), result.err());
    assertTrue(result.out().contains("PASS wide result"), result.out());
  }

  @Test
  void kotlinAssignmentCanStartStepAndInspectMemoryAboveFourGiB() throws Exception {
    var script = source("high-address checks.kts", """
        import edu.missouristate.mars.api.MarsTarget

        case("high routine", target = MarsTarget.MARS64) {
            requireTextLabel("routine")
            requireDataLabel("cell")
            setWord("cell", 41, offset = 4)
            setRegister("a0", 0x100000004L)
            startAt("routine")
            runUntil("store")
            expectRegister("t0", 42)
            expectWord("cell", 41, offset = 4)
            step()
            expectWord("cell", 42, offset = 4)
            expectBytes("cell", 42, 0, 0, 0, offset = 4)
            runToExit()
        }
        """);
    var entry = source("high-routine.mars", """
        .data 0x100000000
        cell: .word 0, 0
        .text 0x100400000
        routine:
            lw $t0,0($a0)
            addiu $t0,$t0,1
        store:
            sw $t0,0($a0)
            addiu $v0,$zero,10
            syscall
        """);
    var result = launch("grade", script.toString(), entry.toString());
    assertEquals(0, result.status(), result.err() + result.out());
    assertTrue(result.out().contains("PASS high routine"), result.out());
  }

  @Test
  void gradingChecksStructureAndIntermediateStateAndIsolatesCases() throws Exception {
    var script = source("linked-list checks.kts", """
        case("double a word", instructionLimit = 8) {
            requireTextLabel("double")
            requireDataLabel("cell")
            setWord("cell", 21)
            startAt("double")
            runUntil("combine")
            expectRegister("t0", 21)
            step()
            expectRegister("a0", 42)
            runToExit()
            expectOutput("42")
        }
        case("UTF-8 fixture", input = "é\\n", utf8Text = true) {
            startAt("read_input")
            runToExit(3)
            expectBytes("buffer", 0xc3, 0xa9, 10, 0)
        }
        case("fresh machine") {
            expectWord("cell", 0)
            expectRegister("t0", 0)
        }
        case("missing structure") {
            requireTextLabel("missing")
        }
        case("instruction budget across slices", instructionLimit = 2) {
            startAt("loop")
            step()
            runToExit()
        }
        case("file access") {
            startAt("file_access")
            runToExit()
        }
        """);
    var entry = source("grading.s", """
        .include "storage.inc"
        .text
        double: lw $t0, cell
        combine: addu $a0, $t0, $t0
        li $v0, 1
        syscall
        li $v0, 10
        syscall
        loop: j loop
        file_access: li $v0, 13
        syscall
        read_input: la $a0, buffer
        li $a1, 4
        li $v0, 8
        syscall
        li $a0, 3
        li $v0, 17
        syscall
        """);
    var include = source("storage.inc", ".data\ncell: .word 0\nbuffer: .space 4\n");
    var result = launch("grade", script.toString(), entry.toString(), "--include", include.toString());
    assertEquals(1, result.status(), result.err());
    assertTrue(result.out().contains("PASS double a word\nPASS UTF-8 fixture\nPASS fresh machine\n"), result.out());
    assertTrue(result.out().contains("FAIL missing structure: Missing label: missing"), result.out());
    assertTrue(result.out().contains("FAIL instruction budget across slices: Expected execution to reach NORMAL_EXIT, stopped at INSTRUCTION_LIMIT"), result.out());
    assertTrue(result.out().contains("syscall 13 is disabled by the host"), result.out());

    var withoutInclude = launch("grade", script.toString(), entry.toString());
    assertEquals(1, withoutInclude.status(), withoutInclude.err());
    assertTrue(withoutInclude.out().contains("storage.inc"), withoutInclude.out());
  }

  @Test
  void gradingReportsScriptErrorsWithASeparateExitCode() throws Exception {
    var script = source("assignment2.kts", "case(\"broken\") { missingFunction() }\n");
    var entry = source("entry.s", "nop\n");
    var result = launch("grade", script.toString(), entry.toString());
    assertEquals(2, result.status(), result.err());
    assertTrue(result.err().contains("missingFunction"), result.err());
  }

  @Test
  void gradingBoundsAssemblerExpansionAndProgramOutput() throws Exception {
    var script = source("assignment3.kts", "case(\"bounded\") { runToExit() }\n");
    var largeData = source("large.s", ".data\n.byte 1:2000000\n.text\nnop\n");
    var assemblyLimit = launch("grade", script.toString(), largeData.toString());
    assertEquals(1, assemblyLimit.status(), assemblyLimit.err());
    assertTrue(assemblyLimit.out().contains("Submission exceeds the assembly work limit"), assemblyLimit.out());

    var largeOutput = source("output.s", """
        .data
        message: .asciiz "abcdefghijklmnopqrstuvwxyz0123456789"
        .text
        la $a0, message
        li $v0, 4
        loop: syscall
        j loop
        """);
    var outputLimit = launch("grade", script.toString(), largeOutput.toString());
    assertEquals(1, outputLimit.status(), outputLimit.err());
    assertTrue(outputLimit.out().contains("Program output exceeds 64 KiB"), outputLimit.out());
  }

  @Test
  void utf8TextModePrintsUnicodeAndTruncatesInputAtAByteBoundary() throws Exception {
    Path file = source("unicode.mars", """
        .data
        greeting: .asciiz "鯉: "
        buffer: .byte 99, 99, 99, 99, 99, 99
        .text
        la $a0, greeting
        li $v0, 4
        syscall
        la $a0, buffer
        li $a1, 6
        li $v0, 8
        syscall
        li $v0, 4
        syscall
        li $v0, 10
        syscall
        """);
    assertProgramResult(List.of("--utf8-text", file.toString()), "é🐟X\n", 0, "鯉: é");
    assertProgramResult(List.of("--utf8-text", file.toString()), "🐟X\n", 0, "鯉: 🐟X");
    assertProgramResult(List.of("--utf8-text", file.toString()), "é\n", 0, "鯉: é");
  }

  @Test
  void legacyTextModeKeepsOneByteStrings() throws Exception {
    Path file = source("legacy.mars", """
        .data
        greeting: .asciiz "é"
        .text
        la $a0, greeting
        li $v0, 4
        syscall
        li $v0, 10
        syscall
        """);
    assertProgramResult(List.of(file.toString()), "", 0, "é");
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
  void projectModeDiscoversMarsSourcesAndLoadsIncludeFilesOnDemand() throws Exception {
    Path main =
        source(
            "main.mars",
            """
            jal increment
            move $a0,$t0
            li $v0,1
            syscall
            li $v0,10
            syscall
            """);
    source("values.inc", ".eqv BASE 41\n");
    source(
        "math.MARS",
        """
        .include "values.inc"
        .globl increment
        increment: li $t0,BASE
        addiu $t0,$t0,1
        jr $ra
        """);
    source("unused.inc", "this fragment must not be assembled\n");
    assertProgramResult(List.of("nc", "p", main.toString()), "", 0, "42");
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
  void entrySelectionAndBranchDelaySlotsChangeExecution() throws Exception {
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
  void diagnosticsAndExplicitErrorStatusesApplyAcrossPhases() throws Exception {
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
  void reportsDefaultFailureStatuses() throws Exception {
    Path assembly = source("assembly-error.s", "bogus\n");
    Path simulation = source("simulation-error.s", "li $v0,999\nsyscall\n");
    for (Path file : List.of(assembly, simulation)) {
      var result = launch("nc", "me", file.toString());
      assertEquals(1, result.status());
      assertTrue(result.err().startsWith("Error"));
    }
    for (List<String> arguments : List.of(
        List.of("--unknown-option", "nc", "me"),
        List.of("nc", "me", "mc"),
        List.of("nc", "me", "$notARegister", simulation.toString()),
        List.of("nc", "me"))) {
      var failure = launch("", arguments);
      assertEquals(2, failure.status(), arguments.toString());
      assertFalse(failure.err().isBlank(), arguments.toString());
    }
  }

  @Test
  void selfModifyingCodeHonorsTheRuntimeOption() throws Exception {
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
  void registerAndMemoryReportsUseTheSelectedStream(String options) throws Exception {
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
    var standalone = launch("", arguments);
    assertEquals(0, standalone.status());
    boolean reportsToError = options.endsWith(" me");
    assertTrue(standalone.out().startsWith("7"));
    String report = reportsToError ? standalone.err() : standalone.out();
    if (options.contains(" b")) {
      assertFalse(report.contains("Mem["));
      assertFalse(report.contains("$f0"));
      assertTrue(report.lines().map(String::stripTrailing).anyMatch("1\t2\t3\t4"::equals));
    } else {
      assertTrue(report.contains("Mem["));
      assertTrue(report.contains("$f0"));
    }
    if (reportsToError) {
      assertEquals("7", standalone.out());
    } else {
      assertEquals("", standalone.err());
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void repeatedDumpsWriteEveryFormatAndIncludeExplicitRangeEndpoints(boolean assembleOnly)
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
        DumpFormatLoader.FORMATS.stream()
            .map(format -> Objects.requireNonNull(format.getCommandDescriptor(), format.getDescription()))
            .toList();
    var arguments = new ArrayList<>(List.of("nc", "me"));
    if (assembleOnly) {
      arguments.add("a");
    }
    for (String descriptor : descriptors) {
      Objects.requireNonNull(descriptor, "Expected a dump format descriptor");
      arguments.addAll(List.of("dump", ".text", descriptor, dump(descriptor).toString()));
    }
    arguments.addAll(List.of(
        "dump", ".data", "HexText", dump("data").toString(),
        "dump", "0x10010000-0x10010004", "HexText", dump("range").toString(),
        "dump", "0x10010004-0x10010004", "HexText", dump("single-word").toString(),
        file.toString()));
    var result = launch("", arguments);
    assertEquals(0, result.status(), result.err());
    assertTrue(result.err().isBlank(), result.err());
    for (String descriptor : descriptors) {
      assertTrue(Files.size(dump(descriptor)) > 0, descriptor);
    }
    String first = assembleOnly ? "0000000b" : "0000002a";
    var data = Files.readAllLines(dump("data"));
    assertEquals(List.of(first, "00000016"), data.subList(0, 2));
    assertTrue(data.subList(2, data.size()).stream().allMatch("00000000"::equals));
    assertEquals(List.of(first, "00000016"), Files.readAllLines(dump("range")));
    assertEquals(List.of("00000016"), Files.readAllLines(dump("single-word")));
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
    var standalone = launch("", arguments);
    assertEquals(0, standalone.status(), standalone.err());
    assertEquals(List.of("Mem[0x7ffffffc]\t0x00000000\t0x241a0007", "Mem[0xfffffffc]\t0x00000000"),
        standalone.err().lines().filter(line -> !line.isBlank()).map(String::stripTrailing).toList());
    for (String range : List.of("0x80000000-0x7ffffffc", "4294967296-4294967296", "0x80000001-0x80000004")) {
      var failure = launch("nc", "me", range, file.toString());
      assertEquals(2, failure.status(), range);
      assertTrue(failure.err().contains(range), failure.err());
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
    Path existing = Files.writeString(dump("existing"), "previous successful dump\n");
    Path missing = dump("missing");
    for (int status : List.of(19, 0)) {
      var result = launch("nc", "me", "ae" + status,
          "dump", ".data", "HexText", existing.toString(),
          "dump", ".data", "HexText", missing.toString(), file.toString());
      assertEquals(status, result.status(), result.err());
      assertTrue(result.err().contains("addiu"), result.err());
      assertEquals("previous successful dump\n", Files.readString(existing));
      assertFalse(Files.exists(missing));
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
    Path output = dump("simulation");
    var result = launch("nc", "me", "se23",
        "dump", "0x10010000-0x10010000", "HexText", output.toString(), file.toString());
    assertEquals(23, result.status(), result.err());
    assertTrue(result.err().contains("syscall"), result.err());
    assertEquals(List.of("0000002a"), Files.readAllLines(output));
  }

  @Test
  void compactTextLoopCountsEveryStepAndReportsTheStepLimitStatus() throws Exception {
    Path file = source("loop.s", "loop: beq $zero,$zero,loop\n");
    for (String limit : List.of("3", "32")) {
      var arguments = List.of("nc", "ic", "mc", "CompactTextAtZero", limit, file.toString());
      var standalone = launch("", arguments);
        assertEquals(3, standalone.status());
      assertEquals(limit, standalone.out().strip());
      assertTrue(standalone.err().contains("step limit"));
    }

    Path exitsAtLimit = source("exits-at-limit.s", "li $a0,17\nli $v0,17\nsyscall\n");
    assertProgramResult(List.of("nc", "3", exitsAtLimit.toString()), "", 17, "");
  }

  private void assertProgramResult(List<String> arguments, String input, int status, String output)
      throws Exception {
    var result = launch(input, arguments);
    assertEquals(status, result.status(), result.err());
    assertEquals(output, result.out().strip());
    assertEquals("", result.err());
  }

  private void assertDiagnostics(List<String> flags, int status, String expected) throws Exception {
    var arguments = new ArrayList<>(List.of("nc", "me"));
    arguments.addAll(flags);
    var standalone = launch("", arguments);
    assertEquals(status, standalone.status(), standalone.err());
    var diagnostic = standalone.err().lines().filter(line -> !line.isBlank()).toList();
    assertTrue(diagnostic.stream().anyMatch(line -> line.contains(expected)), standalone.err());
  }

  private static List<String> phaseLines(Result result) {
    return result.err().lines().filter(line -> line.startsWith("--------")).toList();
  }

  private Path source(String name, String contents) throws Exception {
    Path file = directory.resolve(name);
    Files.createDirectories(file.getParent());
    return Files.writeString(file, contents);
  }

  private Path dump(String descriptor) {
    return directory.resolve("dump-" + descriptor);
  }

  private Result launch(String... arguments) throws Exception {
    return launch("", List.of(arguments));
  }

  private Result launch(String input, List<String> arguments) throws Exception {
    Path bin = Path.of(System.getProperty("java.home"), "bin");
    Path java = bin.resolve(Files.exists(bin.resolve("java.exe")) ? "java.exe" : "java");
    var command =
        new ArrayList<>(
            List.of(
                java.toString(),
                "-jar",
                System.getProperty("mars.cli.jar")));
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
      assertTrue(process.waitFor(20, TimeUnit.SECONDS), () -> "CLI timed out: " + command);
      return new Result(process.exitValue(), Files.readString(stdout), Files.readString(stderr));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    }
  }

  private record Result(int status, String out, String err) {}
}
