package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.api.AssemblySourceSnapshot;
import edu.missouristate.mars.api.AssemblySymbolMap.Definition;
import edu.missouristate.mars.api.AssemblySymbolMap.EquivalenceOccurrence;
import edu.missouristate.mars.api.AssemblySymbolMap.Kind;
import edu.missouristate.mars.api.AssemblySymbolMap.MacroDefinition;
import edu.missouristate.mars.api.AssemblySymbolMap.SourceToken;
import edu.missouristate.mars.api.AssemblySymbolMap.SymbolUse;
import edu.missouristate.mars.api.InstructionSourceMap.Location;
import edu.missouristate.mars.api.InstructionSourceMap.Origin;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.assembler.Assembler;
import edu.missouristate.mars.assembler.SourceLine;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.assembler.Token;
import edu.missouristate.mars.assembler.Tokenizer;
import edu.missouristate.mars.core.AssemblyImage;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.core.SessionAssembler;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import edu.missouristate.mars.mips.hardware.MemoryState;
import edu.missouristate.mars.simulator.ProgramArgumentList;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionAssemblyTest {
  @TempDir Path directory;

  @Test
  void twoProgramsAssembleAndRunWithoutGlobalSimulationObjects() throws Exception {

    var first = session("Default");
    var second = session("Default");
    var firstIo = new RecordingEnvironment();
    var secondIo = new RecordingEnvironment();
    var firstProgram = first.assemble(List.of(source("first.s", program(42, false))));
    int firstResult = first.symbolTable.getAddress("result");
    var secondProgram = second.assemble(List.of(source("second.s", program(99, true))));
    int secondResult = second.symbolTable.getAddress("result");
    assertThat(secondResult).isEqualTo(firstResult + 4);
    assertThat(first.memory.getWord(firstResult)).isEqualTo(42);
    firstProgram.setSyscallEnvironment(firstIo);
    secondProgram.setSyscallEnvironment(secondIo);
    assertThat(firstProgram.simulate(100)).isTrue();
    assertThat(secondProgram.simulate(100)).isTrue();
    assertThat(firstIo.printed).containsExactly(43);
    assertThat(secondIo.printed).containsExactly(100);
    assertThat(first.memory.getWord(firstResult)).isEqualTo(43);
    assertThat(second.memory.getWord(secondResult)).isEqualTo(100);
    assertThat(first.getProgram()).isSameAs(firstProgram);
    assertThat(second.getProgram()).isSameAs(secondProgram);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Default", "CompactDataAtZero", "CompactTextAtZero"})
  void detachedImagesPreserveAssemblyAndLoadIndependentMachines(String layout) throws Exception {
    var file = source("image.s", """
        .macro increment
          addiu $t0, $t0, 1
        .end_macro
        .text
          nop
        """ + program(42, false).replace("addiu $t0, $t0, 1", "increment"));
    var neverCancelled = (Runnable) () -> {};
    AssemblyImage image;
    int main;
    int result;
    MIPSprogram original;
    try (var builder = session(layout)) {
      builder.settings.setStartAtMainEnabled(true);
      builder.memory.setByteOrder(MemoryState.BIG_ENDIAN);
      assertThatThrownBy(() -> builder.captureAssemblyImage(neverCancelled))
          .isInstanceOf(IllegalStateException.class).hasMessage("No assembled program");
      original = builder.assemble(List.of(file));
      main = builder.registerFile.getProgramCounter();
      result = builder.symbolTable.getAddress("result");
      assertThat(main).isEqualTo(builder.memory.textBaseAddress + 4);
      image = builder.captureAssemblyImage(neverCancelled);
      builder.memory.setWord(result, 99);
      builder.memory.getStatementNoNotify(main).setBinaryStatement(0);
    }
    Files.delete(file);

    try (var first = session("Default"); var second = session("Default")) {
      first.loadAssemblyImage(image, neverCancelled);
      second.loadAssemblyImage(image, neverCancelled);
      var installed = first.getProgram();
      assertThat(installed.sourceMap()).isSameAs(original.sourceMap());
      assertThat(installed.symbolMap()).isSameAs(original.symbolMap());
      assertThat(first.memory.getConfiguration().configurationIdentifier()).isEqualTo(layout);
      assertThat(first.memory.getByteOrder()).isEqualTo(MemoryState.BIG_ENDIAN);
      assertThat(first.registerFile.getProgramCounter()).isEqualTo(main);
      assertThat(first.symbolTable.getAddress("result")).isEqualTo(result);
      assertThat(first.memory.getWord(result)).isEqualTo(42);
      assertThat(first.memory.getStatementNoNotify(main).getBinaryStatement()).isNotZero();
      var macroSite = installed.sourceMap().origins().values().stream()
          .filter(origin -> origin.caller() != null).findFirst().orElseThrow();
      assertThat(installed.instructionsAt(macroSite.location(), neverCancelled))
          .isEqualTo(original.instructionsAt(macroSite.location(), neverCancelled));
      var statement = installed.getMachineList().stream()
          .filter(row -> row.getInstructionOrigin() == macroSite).findFirst().orElseThrow();
      assertThat(new ErrorMessage(statement, "failure").getFilename()).isEqualTo(file.toString());

      var output = new RecordingEnvironment();
      installed.setSyscallEnvironment(output);
      assertThat(installed.simulate(100)).isTrue();
      assertThat(output.printed).containsExactly(43);
      assertThat(first.memory.getWord(result)).isEqualTo(43);
      assertThat(second.memory.getWord(result)).isEqualTo(42);
      assertThat(second.registerFile.getProgramCounter()).isEqualTo(main);
      assertThatThrownBy(() -> first.loadAssemblyImage(image, () -> {
        if (first.getProgram() == null) {
          throw new CancellationException();
        }
      })).isInstanceOf(CancellationException.class);
      assertThat(first.getProgram()).isNull();
      first.loadAssemblyImage(image, neverCancelled);
      assertThat(first.memory.getWord(result)).isEqualTo(42);
      assertThat(first.registerFile.getProgramCounter()).isEqualTo(main);
      assertThat(first.backStepper.canBackstep()).isFalse();
    }
  }

  @Test
  void launchEntryUsesOnlyGlobalMainInAProgramTextSegment() throws Exception {
    var programs = List.of(
        Map.entry(".text\nnop\n.globl main\nmain: nop\n", 0x00400004),
        Map.entry(".text\nnop\nmain: nop\n", 0x00400000),
        Map.entry(".data\n.globl main\nmain: .word 1\n.text\nnop\n", 0x00400000),
        Map.entry(".text\nnop\n.ktext 0x80000180\n.globl main\nmain: nop\n", 0x80000180),
        Map.entry(".text\nnop\n", 0x00400000));
    for (var scenario : programs) {
      try (var builder = session("Default"); var launch = session("Default")) {
        builder.assemble(List.of(source("launch.s", scenario.getKey())));
        var image = builder.captureAssemblyImage(() -> {});
        launch.loadAssemblyImage(image, true, () -> {});
        assertThat(launch.registerFile.getProgramCounter()).isEqualTo(scenario.getValue());
        launch.loadAssemblyImage(image, false, () -> {});
        assertThat(launch.registerFile.getProgramCounter()).isEqualTo(launch.memory.textBaseAddress);
      }
    }
  }

  @Test
  void argumentSentinelDoesNotOverlapTheLastStringAtTheDefaultStackPointer() throws Exception {
    try (var session = session("Default")) {
      var memory = session.memory;
      String argument = "x".repeat(memory.stackBaseAddress - memory.stackPointer - 1);
      new ProgramArgumentList(new String[] {argument}).storeProgramArguments(session);
      int stack = session.registerFile.getUserRegister("$sp").getValueNoNotify();
      int argv = session.registerFile.getUserRegister("$a1").getValueNoNotify();
      int string = memory.getWord(argv);
      assertThat(stack).isLessThan(memory.stackPointer);
      assertThat(memory.getWord(stack)).isEqualTo(1);
      assertThat(memory.getWord(argv + 4)).isZero();
      assertThat(memory.getByte(string)).isEqualTo((int) 'x');
      assertThat(memory.getByte(string + argument.length() - 1)).isEqualTo((int) 'x');
      assertThat(memory.getByte(string + argument.length())).isZero();
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"CompactDataAtZero", "CompactTextAtZero"})
  void oversizedArgumentFrameFailsBeforeWritingMemoryOrRegisters(String layout) throws Exception {
    try (var session = session(layout)) {
      var memory = session.memory;
      memory.set(memory.stackBaseAddress, 0x5a, 1);
      String argument = "x".repeat(memory.stackBaseAddress - memory.stackLimitAddress);
      assertThatThrownBy(() -> new ProgramArgumentList(new String[] {argument}).storeProgramArguments(session))
          .isInstanceOf(IllegalArgumentException.class).hasMessage("Program arguments exceed session stack memory");
      assertThat(memory.getByte(memory.stackBaseAddress)).isEqualTo(0x5a);
      assertThat(session.registerFile.getUserRegister("$sp").getValueNoNotify()).isEqualTo(memory.stackPointer);
      assertThat(session.registerFile.getUserRegister("$a0").getValueNoNotify()).isZero();
    }
  }

  static Stream<String> corpus() {
    return Stream.concat(AssemblerSmokeTest.allPrograms(), Stream.of("16_floating_point.asm"));
  }

  @ParameterizedTest
  @MethodSource("corpus")
  void existingCorpusAssemblesThroughTheOwnedProgramPath(String filename) throws Exception {
    var program =
        session("Default").assemble(List.of(Path.of(SimulatorSmokeTest.resourcePath(filename))));
    assertThat(program.getMachineList()).isNotEmpty();
  }

  @Test
  void independentAssembliesAndExecutionsCanRunConcurrently() throws Exception {
    var first = session("Default");
    var second = session("CompactDataAtZero");
    var firstSource = source("concurrent-first.s", program(42, false));
    var secondSource = source("concurrent-second.s", program(99, true));
    var gate = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var left =
          executor.submit(
              () -> {
                gate.await();
                return assembleAndRun(first, firstSource);
              });
      var right =
          executor.submit(
              () -> {
                gate.await();
                return assembleAndRun(second, secondSource);
              });
      gate.countDown();
      assertThat(left.get()).containsExactly(43);
      assertThat(right.get()).containsExactly(100);
    }
    assertThat(first.symbolTable.getAddress("main")).isEqualTo(0x400000);
    assertThat(second.symbolTable.getAddress("main")).isEqualTo(0x3000);
  }

  @ParameterizedTest
  @ValueSource(strings = {"Default", "CompactDataAtZero", "CompactTextAtZero"})
  void assemblyUsesTheSessionsConfiguredAddressLayout(String layout) throws Exception {
    var session = session(layout);
    var program = session.assemble(List.of(source(layout + ".s", program(12, false))));
    assertThat(session.symbolTable.getAddress("result")).isEqualTo(session.memory.dataBaseAddress);
    assertThat(session.symbolTable.getAddress("main")).isEqualTo(session.memory.textBaseAddress);
    var output = new RecordingEnvironment();
    program.setSyscallEnvironment(output);
    assertThat(program.simulate(100)).isTrue();
    assertThat(output.printed).containsExactly(13);
  }

  @Test
  void separateKernelHandlerDoesNotChangeTheDefaultSegmentOfUserSources() throws Exception {
    var handler =
        source(
            "handler.s",
            """
            .ktext 0x80000180
            li $a0, 42
            li $v0, 1
            syscall
            li $v0, 10
            syscall
            """);
    var lead = source("lead.s", "break\n");
    var sibling = source("sibling.s", "nop\n");
    try (var session = session("Default")) {
      var program = session.assemble(List.of(lead, sibling), handler, true, false);
      assertThat(program.sourceMap().breakpoints().get(new Location(lead, 1)))
          .containsExactly(session.memory.textBaseAddress);
      assertThat(program.sourceMap().breakpoints().get(new Location(sibling, 1)))
          .containsExactly(session.memory.textBaseAddress + 4);
      assertThat(program.sourceMap().breakpoints().get(new Location(handler, 2)))
          .containsExactly(session.memory.exceptionHandlerAddress);
      var output = new RecordingEnvironment();
      program.setSyscallEnvironment(output);
      assertThat(program.simulate(20)).isTrue();
      assertThat(output.printed).containsExactly(42);
    }
  }

  @Test
  void multiFileForwardReferencesResolveOnlyThroughTheOwningGlobalScope() throws Exception {
    var session = session("Default");
    var lead =
        source(
            "lead.s",
            """
            .data
            .globl ptr
            ptr: .word target
            .text
            .globl main
            main:
              la $t0, ptr
              lw $t1, 0($t0)
              lw $a0, 0($t1)
              li $v0, 1
              syscall
              li $v0, 10
              syscall
            """);
    var library =
        source(
            "library.s",
            """
            .data
            .globl target
            target: .word 73
            private_label: .word 99
            """);
    var program = session.assemble(List.of(lead, library));
    int target = session.symbolTable.getAddress("target");
    assertThat(session.memory.getWord(session.symbolTable.getAddress("ptr"))).isEqualTo(target);
    assertThat(program.getLocalSymbolTable().getAddressLocalOrGlobal("target")).isEqualTo(target);
    assertThat(
            program
                .getLocalSymbolTable()
                .getSymbolGivenAddressLocalOrGlobal(Integer.toString(target)))
        .isNotNull();
    assertThat(session.symbolTable.getAddress("private_label")).isEqualTo(SymbolTable.NOT_FOUND);
    var output = new RecordingEnvironment();
    program.setSyscallEnvironment(output);
    assertThat(program.simulate(100)).isTrue();
    assertThat(output.printed).containsExactly(73);
    var badLead = source("bad-lead.s", ".text\nj private_label\n");
    assertThatThrownBy(() -> session.assemble(List.of(badLead, library)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error ->
                assertThat(error.errors().generateErrorReport())
                    .contains("private_label", "not found"));
  }

  @Test
  void nestedIncludesMacrosAndEqvUseTheSameAssemblyOwner() throws Exception {
    Files.createDirectories(directory.resolve("defs"));
    source(
        "defs/inner.s",
        """
        .eqv VALUE 42
        .macro set_value (%reg)
          li %reg, VALUE
        .end_macro
        """);
    source("defs/outer.s", ".include \"inner.s\"\n");
    var main =
        source(
            "includes.s",
            """
            .include "defs/outer.s"
            .text
            .globl main
            main:
              set_value ($a0)
              li $v0, 1
              syscall
              li $v0, 10
              syscall
            """);
    assertThat(assembleAndRun(session("Default"), main)).containsExactly(42);
  }

  static Stream<Arguments> unicodeSourceEncodings() {
    return Stream.of(
        Arguments.of("efbbbf", StandardCharsets.UTF_8),
        Arguments.of("feff", StandardCharsets.UTF_16BE),
        Arguments.of("fffe", StandardCharsets.UTF_16LE),
        Arguments.of("0000feff", Charset.forName("UTF-32BE")),
        Arguments.of("fffe0000", Charset.forName("UTF-32LE")));
  }

  @ParameterizedTest
  @MethodSource("unicodeSourceEncodings")
  void readsUnicodeMarkedSourceAndIncludesWithoutShiftingLocations(String marker, Charset charset)
      throws Exception {
    var entry = directory.resolve("entrée.asm");
    var include = directory.resolve("pièce.s");
    Files.write(entry, encodedSource(marker, charset,
        "\r\n.include \"pièce.s\"\r\n.text\r\nentrée: addiu $t0, $zero, LIMIT"));
    Files.write(include, encodedSource(marker, charset,
        ".eqv LIMIT 7\r.data\rtexte: .asciiz \"é\uFEFF\""));

    try (var machine = TestSessions.create()) {
      var program = machine.assemble(List.of(entry));
      var instruction = program.getMachineList().getFirst();
      assertThat(instruction.getBinaryStatement()).isEqualTo(0x24080007);
      assertThat(program.sourceMap().origins().get(instruction.getAddress()).location())
          .isEqualTo(new Location(entry, 4));
      int data = machine.memory.dataBaseAddress;
      assertThat(machine.memory.getByte(data)).isEqualTo(0xe9);
      assertThat(machine.memory.getByte(data + 1)).isEqualTo(0xff);
      assertThat(machine.memory.getByte(data + 2)).isZero();
      assertThat(program.getLocalSymbolTable().getAddressLocalOrGlobal("entrée"))
          .isEqualTo(instruction.getAddress());

      var empty = directory.resolve("empty.s");
      Files.write(empty, HexFormat.of().parseHex(marker));
      var source = new MIPSprogram(machine);
      source.readSource(empty.toString());
      assertThat(source.getSourceList()).isEmpty();
    }
  }

  private static byte[] encodedSource(String marker, Charset charset, String text) {
    var bytes = new ByteArrayOutputStream();
    bytes.writeBytes(HexFormat.of().parseHex(marker));
    bytes.writeBytes(text.getBytes(charset));
    return bytes.toByteArray();
  }

  @Test
  void capturedSourcesAssembleNestedIncludesAndOrderedEntriesWithoutReadingDisk() throws Exception {
    var main = directory.resolve("main.s");
    var library = directory.resolve("library.s");
    var handler = directory.resolve("handler.s");
    var outer = directory.resolve("outer.s");
    var inner = source("inner.s", ".eqv VALUE 99\n");
    var entries = new ArrayList<>(List.of(main, library));
    var texts = new HashMap<Path, String>();
    texts.put(main, """
        .include "./outer.s"
        .text
        main:
          emit ($a0)
          li $v0, 1
          syscall
          jal finish
          nop
        """);
    texts.put(library, ".text\n.globl finish\nfinish: li $v0, 10\nsyscall\n");
    texts.put(handler, ".text\naddiu $s0, $zero, 17\n");
    texts.put(outer, ".include \"" + inner + "\"\n");
    texts.put(directory.resolve("unused/../inner.s"),
        ".eqv VALUE 42\r\n.macro emit (%reg)\r  li %reg, VALUE\n.end_macro");
    var snapshot = new AssemblySourceSnapshot(entries, texts);
    entries.clear();
    texts.clear();
    assertThatThrownBy(() -> snapshot.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> snapshot.texts().clear()).isInstanceOf(UnsupportedOperationException.class);

    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, handler, true, false);
      assertThat(result.errors()).isEmpty();
      assertThat(result.succeeded()).isTrue();
      var program = session.getProgram();
      assertThat(result.program()).isSameAs(program);
      assertThat(program.getFilename()).isEqualTo(main.toString());
      assertThat(program.sourceMap().breakpoints())
          .containsKeys(new Location(handler, 2), new Location(library, 3));
      assertThat(program.getSourceLineList())
          .anySatisfy(line -> assertThat(line.getFilename()).isEqualTo(inner.toString()));
      var output = new RecordingEnvironment();
      program.setSyscallEnvironment(output);
      assertThat(program.simulate(100)).isTrue();
      assertThat(output.printed).containsExactly(42);
      assertThat(session.registerFile.getValue(16)).isEqualTo(17);
    }
    assertThat(Files.readString(inner)).isEqualTo(".eqv VALUE 99\n");
    assertThat(main).doesNotExist();
    assertThat(handler).doesNotExist();
  }

  @Test
  void sourceMapBindsNestedIncludesMacroInvocationsAndPseudoInstructions() throws Exception {
    var main = directory.resolve("main.s");
    var outer = directory.resolve("outer.s");
    var definitions = directory.resolve("definitions.s");
    var calls = directory.resolve("calls.s");
    var plain = directory.resolve("plain.s");
    var snapshot = new AssemblySourceSnapshot(
        List.of(main),
        Map.of(main, """
                .include "outer.s"
                .text
                .include "calls.s"
                outer
                .include "plain.s"
                nop
                """,
            outer, ".include \"definitions.s\"\n",
            definitions, """
                .macro inner (%reg)
                  li %reg, 0x12345678
                  nop
                .end_macro
                .macro outer
                  inner ($t0)
                  nop
                .end_macro
                """,
            calls, "# A call in an included file.\nouter\n",
            plain, "addiu $t0, $zero, 1\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).isEmpty();
      var map = result.program().sourceMap();
      int pc = session.memory.textBaseAddress;
      var includedCall = new Origin(new Location(calls, 2), null);
      var innerCall = new Origin(new Location(definitions, 6), includedCall);
      assertThat(map.origins()).hasSize(10);
      assertThat(map.origins().get(pc)).isEqualTo(new Origin(new Location(definitions, 2), innerCall));
      assertThat(map.origins().get(pc + 4)).isSameAs(map.origins().get(pc));
      assertThat(map.origins().get(pc + 8)).isEqualTo(new Origin(new Location(definitions, 3), innerCall));
      assertThat(map.origins().get(pc + 12)).isEqualTo(new Origin(new Location(definitions, 7), includedCall));
      assertThat(map.origins().get(pc + 16).caller().caller())
          .isEqualTo(new Origin(new Location(main, 4), null));
      assertThat(map.origins().get(pc + 32)).isEqualTo(new Origin(new Location(plain, 1), null));
      assertThat(map.origins().get(pc + 36)).isEqualTo(new Origin(new Location(main, 6), null));
      assertThat(map.breakpoints()).containsExactlyInAnyOrderEntriesOf(Map.of(
          new Location(definitions, 2), List.of(pc, pc + 16),
          new Location(definitions, 3), List.of(pc + 8, pc + 24),
          new Location(definitions, 6), List.of(pc, pc + 16),
          new Location(definitions, 7), List.of(pc + 12, pc + 28),
          new Location(calls, 2), List.of(pc),
          new Location(main, 4), List.of(pc + 16),
          new Location(plain, 1), List.of(pc + 32),
          new Location(main, 6), List.of(pc + 36)));
      assertThatThrownBy(() -> map.origins().clear()).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> map.breakpoints().get(new Location(calls, 2)).clear())
          .isInstanceOf(UnsupportedOperationException.class);
      var expanded = result.program().instructionsAt(new Location(calls, 2), () -> {});
      assertThat(expanded).extracting(instruction -> instruction.address())
          .containsExactly(pc, pc + 4, pc + 8, pc + 12);
      assertThat(expanded.getFirst().assembly()).startsWith("lui ");
      assertThat(expanded.getFirst().encoding()).isEqualTo(0x3c011234);
      assertThat(expanded.getFirst().origin()).isSameAs(map.origins().get(pc));
      assertThat(map.pseudoAddresses()).containsExactlyInAnyOrder(pc, pc + 4, pc + 16, pc + 20);
      assertThatThrownBy(() -> map.pseudoAddresses().clear()).isInstanceOf(UnsupportedOperationException.class);
      assertThat(result.program().pseudoInstructions(() -> {})).containsExactly(
          expanded.get(0), expanded.get(1),
          result.program().instructionsAt(new Location(main, 4), () -> {}).get(0),
          result.program().instructionsAt(new Location(main, 4), () -> {}).get(1));
      assertThat(result.program().instructionsAt(new Location(definitions, 2), () -> {})).hasSize(4);
      assertThat(result.program().instructionsAt(new Location(main, 1), () -> {})).isEmpty();
      assertThatThrownBy(() -> result.program().instructionsAt(new Location(calls, 2), () -> {
        throw new CancellationException("stop inspection");
      })).isInstanceOf(CancellationException.class);
    }
  }

  @Test
  void symbolMapPreservesExpandedDefinitionsAcrossIncludesAndExports() throws Exception {
    var main = directory.resolve("main.s");
    var other = directory.resolve("other.s");
    var macros = directory.resolve("macros.s");
    var sources = new AssemblySourceSnapshot(
        List.of(main, other),
        Map.of(main, """
                .include "macros.s"
                .data
                .byte 1
                word: .word 2
                .extern storage 4
                .text
                outer (exported_M0)
                outer (privateLabel)
                .globl exported_M0
                reserve(shared)
                """,
            macros, """
                .macro inner (%entry)
                  %entry:
                  privateLoop: nop
                .end_macro
                .macro outer (%name)
                  inner (%name)
                .end_macro
                .eqv RESERVE .extern
                .macro reserve (%name)
                  RESERVE %name 4
                .end_macro
                """,
            other, ".text\nprivateLabel: j exported_M0\n.extern storage 64\n.extern nextCell 8\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(sources, null, true, false);
      assertThat(result.errors()).isEmpty();
      var map = result.program().symbolMap();
      int text = session.memory.textBaseAddress;
      var firstCall = new Origin(new Location(main, 7), null);
      var secondCall = new Origin(new Location(main, 8), null);
      var exported = new Origin(new Location(macros, 2), new Origin(new Location(macros, 6), firstCall));
      var local = new Origin(new Location(macros, 2), new Origin(new Location(macros, 6), secondCall));
      var storage = new Origin(new Location(main, 5), null);
      var reserved = new Origin(new Location(macros, 10), new Origin(new Location(main, 10), null));
      assertThat(map.globals()).containsExactlyInAnyOrderEntriesOf(Map.of(
          "exported_M0", new Definition(text, Kind.TEXT, exported, false, 3),
          "storage", new Definition(session.memory.externBaseAddress, Kind.EXTERNAL, storage, false, 9),
          "shared", new Definition(session.memory.externBaseAddress + 4, Kind.EXTERNAL, reserved, false, 11),
          "nextCell", new Definition(session.memory.externBaseAddress + 8, Kind.EXTERNAL,
              new Origin(new Location(other, 4), null), false, 9)));
      assertThat(map.symbolUses().get(new SourceToken(new Location(other, 3), 9)))
          .containsExactly(new SymbolUse(new SourceToken(new Location(main, 5), 9), "storage",
              session.memory.externBaseAddress));
      assertThat(map.locals().get(main)).containsExactlyInAnyOrderEntriesOf(Map.of(
          "word", new Definition(session.memory.dataBaseAddress + 4, Kind.DATA, new Origin(new Location(main, 4), null), false, 1),
          "privateLabel", new Definition(text + 4, Kind.TEXT, local, false, 3),
          "privateLoop_M1", new Definition(text, Kind.TEXT, new Origin(new Location(macros, 3), exported.caller()), true, 3),
          "privateLoop_M3", new Definition(text + 4, Kind.TEXT, new Origin(new Location(macros, 3), local.caller()), true, 3)));
      assertThat(map.locals().get(other)).containsExactlyEntriesOf(Map.of(
          "privateLabel", new Definition(text + 8, Kind.TEXT, new Origin(new Location(other, 2), null), false, 1)));
      assertThatThrownBy(() -> map.locals().get(main).clear()).isInstanceOf(UnsupportedOperationException.class);
      assertThatThrownBy(() -> map.globals().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void assemblyImageComparisonSeparatesMachineChangesFromSourceNames() throws Exception {
    var file = directory.resolve("compare.asm");
    var source = ".data\noriginal: .word 42\n.text\naddiu $a0, $zero, 7\n";
    try (var session = session("Default")) {
      var assembler = new SessionAssembler(session);
      var first = new AssemblySourceSnapshot(List.of(file), Map.of(file, source));
      assertThat(assembler.assembleSnapshot(first, null, true, false).errors()).isEmpty();
      var image = session.captureAssemblyImage(() -> {});
      for (var changed : List.of(source.replace("original", "renamed"), source.replace("42", "43"),
          source.replace(", 7", ", 8"), source + "nop\n")) {
        var snapshot = new AssemblySourceSnapshot(List.of(file), Map.of(file, changed));
        assertThat(assembler.assembleSnapshot(snapshot, null, true, false).errors()).isEmpty();
        var next = session.captureAssemblyImage(() -> {});
        assertThat(image.hasSameMachineImage(next, () -> {})).isEqualTo(changed.contains("renamed"));
      }
    }
  }

  @Test
  void symbolUsesKeepWrittenColumnsThroughMacroForwardingAndDataBackpatches() throws Exception {
    var main = directory.resolve("main.s");
    var other = directory.resolve("other.s");
    var macros = directory.resolve("macros.txt");
    var sources = new AssemblySourceSnapshot(List.of(main, other), Map.of(
        main, """
            .include "macros.txt"
            .eqv TARGET BASE
            .data
            first: .word target
            .text
            branch (target)
            j TARGET
            local: nop
            .data
            .word local
            .word future
            future: .word 0
            .word TARGET, TARGET
            .text
            branch (TARGET)
            """,
        other, ".text\ntarget: nop\n.globl target\n",
        macros, """
            .eqv BASE target
            .macro inner (%label)
              j %label
            .end_macro
            .macro branch (%label)
              inner(%label)
            .end_macro
            """));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(sources, null, true, false);
      assertThat(result.errors()).isEmpty();
      assertThat(result.program().symbolMap().macros()).containsExactlyInAnyOrderEntriesOf(Map.of(
          main, List.of(
              new MacroDefinition(new SourceToken(new Location(macros, 2), 8), "inner", List.of("%label")),
              new MacroDefinition(new SourceToken(new Location(macros, 5), 8), "branch", List.of("%label"))),
          other, List.of()));
      var symbols = result.program().symbolMap();
      var target = new SymbolUse(new SourceToken(new Location(other, 2), 1), "target",
          symbols.globals().get("target").address());
      var alias = new SourceToken(new Location(main, 2), 6);
      var base = new SourceToken(new Location(macros, 1), 6);
      var invocation = new SourceToken(new Location(main, 7), 3);
      assertThat(result.program().symbolMap().equivalences().get(main)).contains(
          new EquivalenceOccurrence(alias, alias, alias, "TARGET", true),
          new EquivalenceOccurrence(base, base, base, "BASE", true),
          new EquivalenceOccurrence(invocation, invocation, alias, "TARGET", false),
          new EquivalenceOccurrence(invocation, new SourceToken(new Location(main, 2), 13), base, "BASE", false));
      var aliasedTarget = new SymbolUse(target.definition(), target.name(), target.address(),
          new SourceToken(new Location(macros, 1), 11));
      var local = new SymbolUse(new SourceToken(new Location(main, 8), 1), "local",
          symbols.locals().get(main).get("local").address());
      var future = new SymbolUse(new SourceToken(new Location(main, 12), 1), "future",
          symbols.locals().get(main).get("future").address());
      var uses = symbols.symbolUses();
      assertThat(uses).containsExactlyInAnyOrderEntriesOf(Map.of(
          new SourceToken(new Location(main, 4), 14), Set.of(target),
          new SourceToken(new Location(main, 6), 9), Set.of(target),
          new SourceToken(new Location(main, 7), 3), Set.of(aliasedTarget),
          new SourceToken(new Location(main, 10), 7), Set.of(local),
          new SourceToken(new Location(main, 11), 7), Set.of(future),
          new SourceToken(new Location(main, 13), 7), Set.of(aliasedTarget),
          new SourceToken(new Location(main, 13), 15), Set.of(aliasedTarget),
          new SourceToken(new Location(main, 15), 9), Set.of(aliasedTarget),
          new SourceToken(new Location(other, 3), 8), Set.of(target)));
      assertThatThrownBy(() -> uses.get(new SourceToken(new Location(main, 4), 14)).clear())
          .isInstanceOf(UnsupportedOperationException.class);
    }
  }

  @Test
  void symbolUseKeepsFinalAddressAfterDataAlignment() throws Exception {
    var file = source("aligned-binding.s", ".data\n.byte 1\naligned: .word aligned\n.text\nnop\n");
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(
          new AssemblySourceSnapshot(List.of(file), Map.of(file, Files.readString(file))),
          null, true, false);
      assertThat(result.errors()).isEmpty();
      var uses = result.program().symbolMap().symbolUses();
      assertThat(uses.get(new SourceToken(new Location(file, 3), 16))).containsExactly(
          new SymbolUse(new SourceToken(new Location(file, 3), 1), "aligned", session.memory.dataBaseAddress + 4));
    }
  }

  @Test
  void macroInvocationBreakpointUsesFirstEmittedAddressEvenWhenTextAddressesAreReordered()
      throws Exception {
    var file = source("reordered.s", """
        .macro emit
          .text 0x00400020
          nop
          .text 0x00400000
          nop
        .end_macro
        emit
        """);
    try (var session = session("Default")) {
      var program = session.assemble(List.of(file));
      var map = program.sourceMap();
      assertThat(program.getMachineList()).extracting(ProgramStatement::getAddress)
          .containsExactly(0x00400000, 0x00400020);
      assertThat(map.breakpoints().get(new Location(file, 7))).containsExactly(0x00400020);
      assertThat(map.origins().get(0x00400000)).isEqualTo(
          new Origin(new Location(file, 5), new Origin(new Location(file, 7), null)));
    }
  }

  @Test
  void aRuntimeTextWriteHasNoSourceOriginInLiveMemory() throws Exception {
    var file = source("modified.s", ".text\naddiu $t0, $zero, 1\n");
    try (var session = session("Default")) {
      var program = session.assemble(List.of(file));
      int pc = session.memory.textBaseAddress;
      var original = program.sourceMap().origins().get(pc);
      assertThat(original).isEqualTo(new Origin(new Location(file, 2), null));
      assertThat(session.memory.getStatementNoNotify(pc).getInstructionOrigin()).isSameAs(original);
      session.settings.setSelfModifyingCodeEnabled(true);
      session.memory.setWord(pc, 0);
      assertThat(session.memory.getStatementNoNotify(pc).getInstructionOrigin()).isNull();
      assertThat(program.sourceMap().origins().get(pc)).isSameAs(original);
      assertThat(program.instructionsAt(original.location(), () -> {}).getFirst().encoding())
          .isEqualTo(0x24080001);
    }
  }

  @Test
  void capturedAssemblyReportsMissingSourcesAndNestedDiagnosticsAtTheirOriginalPaths() throws Exception {
    var main = source("disk-main.s", "nop\n");
    var outer = directory.resolve("outer.s");
    var inner = source("disk-inner.s", "nop\n");
    var entries = List.of(main);
    var mainText = "# comment\n.include \"outer.s\"\n";
    var outerText = "# comment\n\n.include \"disk-inner.s\"\n";
    try (var session = session("Default")) {
      var assembler = new SessionAssembler(session);
      var absentMain = assembler.assembleSnapshot(
          new AssemblySourceSnapshot(entries, Map.of()), null, true, false);
      assertThat(absentMain.succeeded()).isFalse();
      assertThat(absentMain.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(main);
        assertThat(error.message()).contains("Source was not captured");
      });
      var absentInclude = assembler.assembleSnapshot(
          new AssemblySourceSnapshot(entries, Map.of(main, mainText, outer, outerText)),
          null, true, false);
      assertThat(absentInclude.succeeded()).isFalse();
      assertThat(absentInclude.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(outer);
        assertThat(error.line()).isEqualTo(3);
        assertThat(error.message()).contains("Error reading include file", inner.toString());
      });
      var invalidInclude = assembler.assembleSnapshot(
          new AssemblySourceSnapshot(entries, Map.of(main, mainText, outer, outerText, inner, "# comment\r\n  j undefined\r\n")),
          null, true, false);
      assertThat(invalidInclude.succeeded()).isFalse();
      assertThat(invalidInclude.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(inner);
        assertThat(error.line()).isEqualTo(2);
        assertThat(error.message()).contains("undefined", "not found");
      });
      assertThat(assembler.assemble(entries).succeeded()).isTrue();
    }
  }

  @Test
  void malformedTokensOnReplacedIncludeLinesAreStillReportedOnce() {
    var main = directory.resolve("main.s");
    var part = directory.resolve("part.s");
    String directive = "bad%name .InClUdE \"part.s\" bad%name";
    var snapshot = new AssemblySourceSnapshot(
        List.of(main),
        Map.of(
            main, "# comment\n" + directive + "\n",
            part, "addiu $t0, $zero, 7\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.succeeded()).isFalse();
      assertThat(result.errors()).hasSize(2).allSatisfy(error -> {
        assertThat(error.file()).isEqualTo(main);
        assertThat(error.line()).isEqualTo(2);
        assertThat(error.message()).endsWith("Invalid language element: bad%name");
      });
      assertThat(result.errors()).extracting(error -> error.column())
          .containsExactly(1, directive.lastIndexOf("bad%name") + 1);
    }
  }

  @Test
  void unfinishedIncludePathsReportSourceDiagnosticsWithoutLoadingTruncatedFiles() {
    var main = directory.resolve("main.s");
    var part = directory.resolve("part.s");
    String bareQuote = ".include \"";
    String partialPath = "prefix: .InClUdE ,, \"part.sx";
    String completePath = "prefix: .InClUdE ,, \"part.s\"";
    var tokenizer = new Tokenizer();
    assertThat(tokenizer.findIncludePath(bareQuote)).isNull();
    assertThat(tokenizer.findIncludePath(partialPath)).isNull();
    assertThat(tokenizer.findIncludePath(completePath)).isEqualTo("part.s");
    assertThat(tokenizer.findInclude(completePath))
        .isEqualTo(new Tokenizer.IncludePath("part.s", completePath.indexOf('"'), completePath.indexOf('.')));
    assertThat(tokenizer.inspectInclude(partialPath))
        .isEqualTo(new Tokenizer.IncludePath(null, partialPath.indexOf('"'), partialPath.indexOf('.')));

    try (var session = session("Default")) {
      var assembler = new SessionAssembler(session);
      var unfinished =
          assembler.assembleSnapshot(
              new AssemblySourceSnapshot(
                  List.of(main),
                  Map.of(main, bareQuote + "\r\n" + partialPath + "\r\nnop\r\n", part, "j missing\n")),
              null,
              true,
              false);
      assertThat(unfinished.succeeded()).isFalse();
      assertThat(unfinished.errors()).satisfiesExactly(
          first -> {
            assertThat(first.file()).isEqualTo(main);
            assertThat(first.line()).isEqualTo(1);
            assertThat(first.column()).isEqualTo(bareQuote.indexOf('"') + 1);
            assertThat(first.message()).contains("Unterminated .include path");
          },
          second -> {
            assertThat(second.file()).isEqualTo(main);
            assertThat(second.line()).isEqualTo(2);
            assertThat(second.column()).isEqualTo(partialPath.indexOf('"') + 1);
            assertThat(second.message()).contains("Unterminated .include path");
          });
      var completed =
          assembler.assembleSnapshot(
              new AssemblySourceSnapshot(
                  List.of(main), Map.of(main, completePath + "\nj entry\n", part, "entry: nop\n")),
              null,
              true,
              false);
      assertThat(completed.succeeded()).isTrue();
    }
  }

  @Test
  void unfinishedDataStringsReportAtTheIncludedSourceAndPreserveClosedEscapes() throws Exception {
    var main = directory.resolve("main.s");
    var part = directory.resolve("strings.s");
    String entry = ".include \"strings.s\"\n.text\nnop\n";
    String unfinished = ".data\r\n.ascii \"\r\n.asciiz \"partial\r\n  \"trailing\\\r\n";
    try (var session = session("Default")) {
      var assembler = new SessionAssembler(session);
      var result = assembler.assembleSnapshot(
          new AssemblySourceSnapshot(List.of(main), Map.of(main, entry, part, unfinished)),
          null,
          true,
          false);
      assertThat(result.succeeded()).isFalse();
      assertThat(result.errors()).hasSize(3).allSatisfy(error -> {
        assertThat(error.file()).isEqualTo(part);
        assertThat(error.message()).isEqualTo("Unterminated string literal");
      });
      assertThat(result.errors()).extracting(error -> List.of(error.line(), error.column()))
          .containsExactly(List.of(2, 8), List.of(3, 9), List.of(4, 3));

      String completed = ".data\n.ascii \"\", \"OK\"\n.asciiz \"tail\\\"\n";
      var valid = assembler.assembleSnapshot(
          new AssemblySourceSnapshot(List.of(main), Map.of(main, entry, part, completed)),
          null,
          true,
          false);
      assertThat(valid.succeeded()).isTrue();
      int base = session.memory.dataBaseAddress;
      String expected = "OKtail\"\0";
      for (int i = 0; i < expected.length(); i++) {
        assertThat(session.memory.getByte(base + i)).isEqualTo((int) expected.charAt(i));
      }
    }
  }

  @Test
  void capturedIncludeRequestsKeepDistinctFilesystemTargets() {
    var main = directory.resolve("main.s");
    var local = directory.resolve("value.s");
    var remote = directory.resolve("remote/value.s");
    var throughLink = directory.resolve("shortcut/../value.s");
    var entries = List.of(main);
    var source = """
        .include "shortcut/../value.s"
        .include "value.s"
        .text
        addiu $t0, $zero, REMOTE
        addiu $t1, $zero, LOCAL
        """;
    var texts = Map.of(main, source, local, ".eqv LOCAL 2\n", remote, ".eqv REMOTE 7\n");
    var resolved = new HashMap<>(Map.of(local, local, throughLink, remote));
    var snapshot = new AssemblySourceSnapshot(entries, texts, resolved);
    resolved.clear();

    try (var session = session("Default")) {
      var assembler = new SessionAssembler(session);
      var result = assembler.assembleSnapshot(snapshot, null, true, false);
      assertThat(result.succeeded()).isTrue();
      assertThat(session.getProgram().getMachineList()).extracting(ProgramStatement::getBinaryStatement)
          .containsExactly(0x24080007, 0x24090002);
      assertThat(session.getProgram().getSourceLineList())
          .anySatisfy(line -> assertThat(line.getFilename()).isEqualTo(remote.toString()));

      var missing = new AssemblySourceSnapshot(entries, texts, Map.of(local, local));
      var failure = assembler.assembleSnapshot(missing, null, true, false);
      assertThat(failure.succeeded()).isFalse();
      assertThat(failure.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(main);
        assertThat(error.line()).isEqualTo(1);
        assertThat(error.message()).contains("Error reading include file", throughLink.toString());
      });
    }
  }

  @Test
  void diskIncludesReportTheDirectiveThatClosesACycle() throws Exception {
    var main = source("main.s", "# leading comment\n.include \"./main.s\"\n");
    try (var session = session("Default")) {
      var assembler = new SessionAssembler(session);
      var direct = assembler.assemble(List.of(main));
      assertThat(direct.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(main);
        assertThat(error.line()).isEqualTo(2);
        assertThat(error.message()).contains("Recursive include of file");
      });

      var parts = Files.createDirectory(directory.resolve("parts"));
      Files.createSymbolicLink(parts.resolve("through-root"), directory);
      Files.writeString(main, ".include \"parts/outer.s\"\n");
      var outer = Files.writeString(parts.resolve("outer.s"), "# outer\n.include \"through-root/main.s\"\n");
      var indirect = assembler.assemble(List.of(main));
      assertThat(indirect.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(outer);
        assertThat(error.line()).isEqualTo(2);
        assertThat(error.message()).contains("Recursive include of file", "through-root");
      });
    }
  }

  @Test
  void symlinkedSourceFilesKeepTheirOwnRelativeIncludeDirectories() throws Exception {
    var template = source("template.s", ".include \"value.s\"\n");
    var main = source("main.s", ".include \"left/template.s\"\n.include \"right/template.s\"\n");
    var left = Files.createDirectory(directory.resolve("left"));
    var right = Files.createDirectory(directory.resolve("right"));
    Files.createSymbolicLink(left.resolve("template.s"), template);
    Files.createSymbolicLink(right.resolve("template.s"), template);
    Files.writeString(left.resolve("value.s"), "addiu $t0, $zero, 7\n");
    Files.writeString(right.resolve("value.s"), "addiu $t1, $zero, 2\n");
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assemble(List.of(main));
      assertThat(result.succeeded()).isTrue();
      assertThat(session.getProgram().getMachineList()).extracting(ProgramStatement::getBinaryStatement)
          .containsExactly(0x24080007, 0x24090002);
    }
  }

  @Test
  void deeplyNestedCapturedIncludesKeepSourceOrderWithoutUsingTheCallStack() {
    var texts = new HashMap<Path, String>();
    var main = directory.resolve("main.s");
    texts.put(main, "addiu $t0, $zero, 1\n.include \"part0.s\"\naddiu $t2, $zero, 3\n");
    int depth = 5_000;
    for (int index = 0; index < depth; index++) {
      texts.put(directory.resolve("part" + index + ".s"), ".include \"part" + (index + 1) + ".s\"\n");
    }
    var leaf = directory.resolve("part" + depth + ".s");
    texts.put(leaf, "addiu $t1, $zero, 2\n");
    var snapshot = new AssemblySourceSnapshot(List.of(main), texts);
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.succeeded()).isTrue();
      assertThat(session.getProgram().getMachineList()).extracting(ProgramStatement::getBinaryStatement)
          .containsExactly(0x24080001, 0x24090002, 0x240a0003);
      assertThat(session.getProgram().getSourceLineList()).extracting(SourceLine::getFilename)
          .containsExactly(main.toString(), leaf.toString(), main.toString());
    }
  }

  @Test
  void capturedIncludeIdentitiesDetectCyclesWithoutResolvingPathsOnDisk() {
    var main = directory.resolve("main.s");
    var outer = directory.resolve("parts/outer.s");
    var alias = directory.resolve("parts/shortcut/main.s");
    var snapshot = new AssemblySourceSnapshot(
        List.of(main),
        Map.of(
            main, ".include \"parts/outer.s\"\n",
            outer, "# nested\n.include \"shortcut/main.s\"\n",
            alias, ".include \"parts/outer.s\"\n"),
        Map.of(outer, outer, alias, alias),
        Map.of(alias, main));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(outer);
        assertThat(error.line()).isEqualTo(2);
        assertThat(error.message()).contains("Recursive include of file", alias.toString());
      });
    }
  }

  @Test
  void capturedSourceAliasesCannotBypassIncludeRecursionChecks() {
    var main = directory.resolve("main.s");
    var snapshot = new AssemblySourceSnapshot(List.of(main),
        Map.of(main, ".include \"sub/.././main.s\"\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.succeeded()).isFalse();
      assertThat(result.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(main);
        assertThat(error.line()).isEqualTo(1);
        assertThat(error.message()).contains("Recursive include of file", main.toString());
      });
    }
    assertThatThrownBy(() -> new AssemblySourceSnapshot(List.of(main),
        Map.of(main, "", directory.resolve("./main.s"), "nop")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Multiple source texts");
    assertThatThrownBy(() -> new AssemblySourceSnapshot(List.of(), Map.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("At least one source file");
  }

  @Test
  void mappedEquivalenceTokensKeepOriginalPositionsAcrossNestedReplacements() {
    String source = "LABEL OP $t0, $zero, COUNT";
    var replacements = Map.of(
        "LABEL", "NAME :", "NAME", "entry", "OP", "NEXT", "NEXT", "addiu", "COUNT", "7");
    var errors = new ErrorList();
    var mapped = Tokenizer.tokenizeMappedLine(source, errors, replacements::get, () -> {});

    assertThat(errors.errorsOccurred()).isFalse();
    assertThat(mapped.expanded().definition()).isNull();
    assertThat(mapped.substitutionOffsets())
        .containsExactlyInAnyOrder(0, source.indexOf("OP"), source.indexOf("COUNT"));
    var tokens = IntStream.range(0, mapped.tokens().size()).mapToObj(mapped.tokens()::get).toList();
    assertThat(tokens).extracting(token -> token.getValue())
        .containsExactly("entry", ":", "addiu", "$t0", "$zero", "7");
    assertThat(tokens).extracting(token -> mapped.originalOffset(token.getStartPos() - 1))
        .containsExactly(0, 0, source.indexOf("OP"), source.indexOf("$t0"),
            source.indexOf("$zero"), source.indexOf("COUNT"));
    assertThat(tokens).extracting(Token::getOriginalStartPos)
        .containsExactly(1, 1, source.indexOf("OP") + 1, source.indexOf("$t0") + 1,
            source.indexOf("$zero") + 1, source.indexOf("COUNT") + 1);
  }

  @Test
  void mappedExpansionRetainsNewEquivalenceDefinitions() {
    var errors = new ErrorList();
    var replacements = Map.of("DEFINE", ".eqv", "BASE", "7");
    var mapped = Tokenizer.tokenizeMappedLine(
        "DEFINE PAIR BASE,   9", errors, replacements::get, () -> {});

    assertThat(errors.errorsOccurred()).isFalse();
    assertThat(mapped.expanded().definition())
        .isEqualTo(new Tokenizer.EquivalenceDefinition("PAIR", "BASE,   9"));
    assertThat(mapped.substitutionOffsets()).containsExactly(0);
    assertThat(mapped.originalOffset(mapped.tokens().get(0).getStartPos() - 1)).isZero();
  }

  @Test
  void mappedExpansionDistinguishesLexicalErrorsFromInterruptedSubstitution() {
    var replacements = Map.of("FORM", "%value", "FIRST", "SECOND", "SECOND", "FIRST");
    var lexicalErrors = new ErrorList();
    var lexical = Tokenizer.tokenizeMappedLine(".macro emit (FORM, @)", lexicalErrors,
        replacements::get, () -> {});
    assertThat(lexicalErrors.errorsOccurred()).isTrue();
    assertThat(lexical.tokens().hasCompleteEquivalenceExpansion()).isTrue();
    assertThat(lexical.tokens().getProcessedLine()).isEqualTo(".macro emit (%value, @)");

    var cycleErrors = new ErrorList();
    var cycle = Tokenizer.tokenizeMappedLine(".macro emit (FORM, FIRST)", cycleErrors,
        replacements::get, () -> {});
    assertThat(cycleErrors.errorsOccurred()).isTrue();
    assertThat(cycle.tokens().hasCompleteEquivalenceExpansion()).isFalse();
  }

  static Stream<Arguments> equivalenceDiagnosticCases() {
    return Stream.of(
        Arguments.of(".eqv DEST $f0\n.eqv ADDRESS OFFSET\n.eqv OFFSET -4(\n",
            "\tentry: lwc1 DEST, ADDRESS $s29)", "$s29", "operand is of incorrect type"),
        Arguments.of(".eqv BOTH -4($s29\n",
            "lwc1 $f0, BOTH)", "BOTH", "operand is of incorrect type"),
        Arguments.of(".eqv REGS $t0, $t1\n.eqv TARGET nop\n",
            "beq REGS, TARGET", "TARGET", "Symbol \"nop\" not found"),
        Arguments.of(".eqv DATA .word\n.eqv VALUE missing\n.data\n",
            "DATA VALUE", "VALUE", "Symbol \"missing\" not found"),
        Arguments.of(".eqv DEFINE .eqv\n.eqv VALUE 1\n",
            "DEFINE VALUE 2", "VALUE", "is already defined"));
  }

  @ParameterizedTest
  @MethodSource("equivalenceDiagnosticCases")
  void equivalenceSemanticErrorsUseWrittenColumnsInAnInclude(
      String definitions, String use, String target, String message) {
    var main = directory.resolve("main.s");
    var include = directory.resolve("body.s");
    var snapshot = new AssemblySourceSnapshot(List.of(main),
        Map.of(main, ".include \"body.s\"\n", include, definitions + use + "\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(include);
        assertThat(error.line()).isEqualTo(definitions.lines().count() + 1);
        assertThat(error.message()).contains(message);
        assertThat(error.column()).isEqualTo(use.indexOf(target) + 1);
      });
    }
  }

  static Stream<Arguments> macroDiagnosticCases() {
    return Stream.of(
        Arguments.of("lwc1 $f0, -4(%argument)", "outer (BAD)", true, "BAD",
            "operand is of incorrect type"),
        Arguments.of("lwc1 %argument, ADDRESS invalid)", "outer ($f0)", false, "invalid",
            "operand is of incorrect type"),
        Arguments.of("beq $zero, $zero, %argument", "outer (nop)", true, "nop",
            "Symbol \"nop\" not found"),
        Arguments.of("beq $zero, $zero, LATER", "outer (0)", false, "LATER",
            "Symbol \"missing\" not found"),
        Arguments.of("li $t0, CYCLE", "outer (0)", false, "CYCLE",
            "Circular .eqv expansion CYCLE -> NEXT -> CYCLE"),
        Arguments.of("MAKE EXISTING 2", "outer (0)", false, "EXISTING",
            "is already defined"));
  }

  @ParameterizedTest
  @MethodSource("macroDiagnosticCases")
  void nestedMacroErrorsPointToTheWrittenArgumentOrBody(
      String body, String call, boolean inCaller, String target, String message) {
    var main = directory.resolve("main.s");
    var include = directory.resolve("macros.s");
    var snapshot = new AssemblySourceSnapshot(List.of(main), Map.of(
        main, ".include \"macros.s\"\n.eqv LATER missing\n.eqv CYCLE NEXT\n.eqv NEXT CYCLE\n"
            + ".eqv MAKE .eqv\n.eqv EXISTING 1\n" + call + "\n",
        include, ".eqv BAD $s29\n.eqv ADDRESS -4(\n.macro inner (%argument)\n"
            + body + "\n.end_macro\n.macro outer (%forwarded)\n"
            + "inner (%forwarded)\n.end_macro\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).singleElement().satisfies(error -> {
        assertThat(error.message()).contains(message);
        assertThat(error.file()).isEqualTo(inCaller ? main : include);
        assertThat(error.line()).isEqualTo(inCaller ? 7 : 4);
        assertThat(error.column()).isEqualTo((inCaller ? call : body).indexOf(target) + 1);
      });
    }
  }

  @Test
  void equivalenceLexicalErrorsAreReportedOnceAtTheirWrittenTokensOrAliases() {
    String source = ".byte FIRST, SECOND, OUTER, 'ab', 'ab'";
    var replacements = Map.of(
        "FIRST", "100", "SECOND", "2000", "OUTER", "INNER", "INNER", "'ab', 'cd'");
    var errors = new ErrorList();
    var mapped = Tokenizer.tokenizeMappedLine(source, errors, replacements::get, () -> {});

    assertThat(mapped.tokens().getProcessedLine()).isEqualTo(".byte 100, 2000, 'ab', 'cd', 'ab', 'ab'");
    var diagnostics = errors.getErrorMessages();
    assertThat(diagnostics).extracting(ErrorMessage::getPosition).containsExactly(
        source.indexOf("'ab'") + 1, source.lastIndexOf("'ab'") + 1,
        source.indexOf("OUTER") + 1, source.indexOf("OUTER") + 1);
    assertThat(diagnostics).extracting(ErrorMessage::getMessage).containsExactly(
        source + "\nInvalid language element: 'ab'",
        source + "\nInvalid language element: 'ab'",
        source + "\nInvalid language element: 'ab'",
        source + "\nInvalid language element: 'cd'");
  }

  @Test
  void equivalencesPreserveWrittenLexicalErrorsWhenTheReplacementAbsorbsTheToken() {
    String source = ".ascii QUOTE bad%name\"";
    var errors = new ErrorList();
    var tokens = Tokenizer.tokenizeWithEquivalences(
        source, errors, Map.of("QUOTE", "\"prefix ")::get, () -> {});
    assertThat(tokens.getProcessedLine()).isEqualTo(".ascii \"prefix  bad%name\"");
    assertThat(errors.getErrorMessages()).singleElement().satisfies(error -> {
      assertThat(error.getPosition()).isEqualTo(source.indexOf("bad%name") + 1);
      assertThat(error.getMessage()).endsWith("Invalid language element: bad%name");
    });
  }

  @Test
  void aMalformedIntermediateExpansionDoesNotLeaveAStaleDiagnostic() {
    var errors = new ErrorList();
    var replacements = Map.of("OUTER", "QUOTE bad%name", "QUOTE", "\"prefix ");
    var tokens = Tokenizer.tokenizeWithEquivalences(
        ".ascii OUTER\"", errors, replacements::get, () -> {});
    assertThat(tokens.getProcessedLine()).isEqualTo(".ascii \"prefix  bad%name\"");
    assertThat(errors.getErrorMessages()).isEmpty();
  }

  @Test
  void circularEquivalenceExpansionReportsTheOriginalUseInAnInclude() {
    var main = directory.resolve("main.s");
    var include = directory.resolve("cyclic.s");
    var use = "\tOPERATION $t0, FIRST";
    var snapshot = new AssemblySourceSnapshot(
        List.of(main),
        Map.of(
            main, ".include \"cyclic.s\"\nnop\n",
            include, """
                .eqv OPERATION li
                .eqv FIRST SECOND
                .eqv SECOND FIRST + 1
                """ + use + "\n"));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.succeeded()).isFalse();
      assertThat(result.errors()).singleElement().satisfies(error -> {
        assertThat(error.file()).isEqualTo(include);
        assertThat(error.line()).isEqualTo(4);
        assertThat(error.column()).isEqualTo(use.indexOf("FIRST") + 1);
        assertThat(error.message()).contains("Circular .eqv expansion", "FIRST -> SECOND -> FIRST");
      });
    }
  }

  @Test
  void longEquivalenceChainsAndRepeatedAliasesAssembleWithoutRecursion() throws Exception {
    var main = directory.resolve("main.s");
    var text = new StringBuilder();
    for (int i = 0; i < 6_000; i++) {
      text.append(".eqv VALUE").append(i).append(" VALUE").append(i + 1).append('\n');
    }
    text.append("""
        .eqv VALUE6000 7
        .eqv VALUE VALUE6000
        .eqv PAIR VALUE, VALUE
        .eqv UNUSED_A UNUSED_B
        .eqv UNUSED_B UNUSED_A
        .data
        .word VALUE0, PAIR, VALUE
        .text
        nop
        """);
    var snapshot = new AssemblySourceSnapshot(List.of(main), Map.of(main, text.toString()));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).isEmpty();
      assertThat(result.succeeded()).isTrue();
      for (int i = 0; i < 4; i++) {
        assertThat(session.memory.getWordNoNotify(session.memory.dataBaseAddress + i * 4)).isEqualTo(7);
      }
    }
  }

  @Test
  void equivalencesPreserveCharacterLiteralSpellingBeforeRetokenizing() throws Exception {
    var main = directory.resolve("main.s");
    var source = """
        .eqv LETTER 'A', # trailing separator and comment
        .eqv LETTER 'A'
        .eqv NEWLINE '\\n'
        .eqv OCTAL '\\101'
        .eqv APOSTROPHE '\\''
        .eqv BACKSLASH '\\\\'
        .eqv WIDE '鯉'
        .eqv PAIR 99, '\\0'
        .data
        .word LETTER, NEWLINE, OCTAL, APOSTROPHE, BACKSLASH, WIDE, PAIR
        .text
        nop
        """;
    var snapshot = new AssemblySourceSnapshot(List.of(main), Map.of(main, source));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).isEmpty();
      assertThat(result.succeeded()).isTrue();
      int[] expected = {65, 10, 65, 39, 92, '鯉', 99, 0};
      for (int i = 0; i < expected.length; i++) {
        assertThat(session.memory.getWordNoNotify(session.memory.dataBaseAddress + i * 4))
            .isEqualTo(expected[i]);
      }
      assertThat(session.getProgram().getSourceLineList().get(9).getSource())
          .isEqualTo(".word 'A', '\\n', '\\101', '\\'', '\\\\', '鯉', 99, '\\0'");
    }
  }

  @Test
  void macroSubstitutionRewritesOnlyTheParsedTokenSpans() throws Exception {
    var main = directory.resolve("main.s");
    var source = """
        .eqv DATAARG %ab
        .macro words (%a, %ab)
          .word DATAARG, %a
          .word %ab, %a, %a
        .end_macro
        .macro emit (%text)
          .asciiz "%text", %text
        .end_macro
        .macro embed (%text, %address)
          .asciiz %text
          .word %address
        .end_macro
        .macro outer
          tag: .word 1
          embed("tag", tag)
        .end_macro
        .data
        words(7, 11)
        emit("A")
        outer()
        .text
        nop
        """;
    var snapshot = new AssemblySourceSnapshot(List.of(main), Map.of(main, source));
    try (var session = session("Default")) {
      var result = new SessionAssembler(session).assembleSnapshot(snapshot, null, true, false);
      assertThat(result.errors()).isEmpty();
      assertThat(result.succeeded()).isTrue();
      int base = session.memory.dataBaseAddress;
      int[] words = {11, 7, 11, 7, 7};
      for (int i = 0; i < words.length; i++) {
        assertThat(session.memory.getWordNoNotify(base + i * 4)).isEqualTo(words[i]);
      }
      String emitted = "%text\0A\0";
      for (int i = 0; i < emitted.length(); i++) {
        assertThat(session.memory.getByte(base + 20 + i)).isEqualTo(emitted.charAt(i));
      }
      assertThat(session.memory.getWordNoNotify(base + 28)).isEqualTo(1);
      assertThat(session.memory.getByte(base + 32)).isEqualTo('t');
      assertThat(session.memory.getByte(base + 33)).isEqualTo('a');
      assertThat(session.memory.getByte(base + 34)).isEqualTo('g');
      assertThat(session.memory.getByte(base + 35)).isZero();
      assertThat(session.memory.getWordNoNotify(base + 36)).isEqualTo(base + 28);
    }
  }

  @Test
  void cancellationAtAnySnapshotCheckpointPropagatesAndReleasesTheSession() throws Exception {
    var main = directory.resolve("main.s");
    var definitions = directory.resolve("defs.s");
    var snapshot = new AssemblySourceSnapshot(
        List.of(main),
        Map.of(
            definitions, """
                .eqv VALUE 7
                .macro emit (%register)
                  li %register, VALUE
                .end_macro
                """,
            main, """
                .include "defs.s"
                .data
                .word VALUE:3
                .word tail
                tail: .asciiz "%s"
                .text
                .globl main
                main:
                  emit ($a0)
                  li $t0, 0x12345678
                  nop
                """.formatted("a".repeat(600))));
    var checkpoints = new AtomicInteger();
    try (var baseline = session("Default")) {
      assertThat(new SessionAssembler(baseline)
          .assembleSnapshot(snapshot, null, true, false, checkpoints::incrementAndGet)
          .succeeded()).isTrue();
    }

    try (var session = session("Default");
        var executor = Executors.newSingleThreadExecutor()) {
      var assembler = new SessionAssembler(session);
      boolean cancelledDuringDataWrites = false;
      boolean cancelledDuringTextWrites = false;
      for (int checkpoint = 1; checkpoint <= checkpoints.get(); checkpoint++) {
        var remaining = new AtomicInteger(checkpoint);
        var cancellation = new CancellationException("stop assembly");
        assertThatThrownBy(() -> assembler.assembleSnapshot(snapshot, null, true, false, () -> {
          if (remaining.decrementAndGet() == 0) {
            throw cancellation;
          }
        })).isSameAs(cancellation);
        assertThat(session.getProgram()).isNull();
        int data = session.memory.dataBaseAddress;
        int text = session.memory.textBaseAddress;
        cancelledDuringDataWrites |=
            session.memory.getWordNoNotify(data) == 7 && session.memory.getWordNoNotify(data + 8) == 0;
        cancelledDuringTextWrites |=
            session.memory.getStatementNoNotify(text) != null
                && session.memory.getStatementNoNotify(text + 4) == null;
      }
      assertThat(cancelledDuringDataWrites).isTrue();
      assertThat(cancelledDuringTextWrites).isTrue();
      var rebuilt = executor.submit(() -> assembler.assembleSnapshot(snapshot, null, true, false))
          .get(10, TimeUnit.SECONDS);
      assertThat(rebuilt.succeeded()).isTrue();
      assertThat(rebuilt.program()).isSameAs(session.getProgram());
    }
  }

  @Test
  void pseudoExpansionUsesEachSessionsDelayedBranchSetting() throws Exception {
    var source =
        source(
            "pseudo.s",
            """
            .text
            .globl main
            main:
              li $t0, 2
              li $t1, 3
              mulo $a0, $t0, $t1
            done:
              li $v0, 1
              syscall
              li $v0, 10
              syscall
            """);
    var first = session("Default");
    var second = session("Default");
    second.settings.setDelayedBranchingEnabled(true);
    var plain = first.assemble(List.of(source));
    var delayed = second.assemble(List.of(source));
    assertThat(delayed.getMachineList()).hasSize(plain.getMachineList().size() + 1);
    assertThat(delayed.getLocalSymbolTable().getAddress("done"))
        .isEqualTo(plain.getLocalSymbolTable().getAddress("done") + 4);
    var plainIo = new RecordingEnvironment();
    var delayedIo = new RecordingEnvironment();
    plain.setSyscallEnvironment(plainIo);
    delayed.setSyscallEnvironment(delayedIo);
    assertThat(plain.simulate(100)).isTrue();
    assertThat(delayed.simulate(100)).isTrue();
    assertThat(plainIo.printed).containsExactly(6);
    assertThat(delayedIo.printed).containsExactly(6);
  }

  @Test
  void errorBudgetsAndFailedAssemblyDoNotChangeAnotherSession() throws Exception {
    var first = session("Default");
    var second = session("Default");
    var valid = first.assemble(List.of(source("valid.s", program(42, false))));
    int address = first.symbolTable.getAddress("result");
    var invalid = source("errors.s", ".text\nunknown_op\nunknown_op\nunknown_op\nunknown_op\n");
    second.settings.setAssemblerErrorLimit(1);
    assertThatThrownBy(() -> second.assemble(List.of(invalid)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error -> {
              assertThat(error.errors().getErrorLimit()).isEqualTo(1);
              assertThat(error.errors().getErrorMessages()).hasSize(2);
            });
    assertThat(second.getProgram()).isNull();
    assertThat(first.getProgram()).isSameAs(valid);
    assertThat(first.memory.getWord(address)).isEqualTo(42);
    assertThat(first.symbolTable.getAddress("result")).isEqualTo(address);
    second.settings.setAssemblerErrorLimit(20);
    assertThatThrownBy(() -> second.assemble(List.of(invalid)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error -> assertThat(error.errors().getErrorMessages()).hasSize(4));
    var duplicate = source("duplicate.s", ".data\nitem: .word 1\nitem: .word 2\n");
    assertThatThrownBy(() -> second.assemble(List.of(duplicate)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error -> {
              assertThat(error.errors().generateErrorReport())
                  .contains("already defined", "duplicate.s");
              assertThat(error.errors().getErrorMessages().getFirst().getLine()).isEqualTo(3);
            });
  }

  @Test
  void tokenizerErrorsUseTheSessionBudgetAndPreserveThePreviouslyAssembledProgram()
      throws Exception {
    var session = session("Default");
    var valid = session.assemble(List.of(source("valid-before-token-error.s", program(42, false))));
    session.settings.setAssemblerErrorLimit(2);
    var invalid = source("token-errors.s", ".data\n.byte 'ab'\n.byte 'cd'\n.byte 'ef'\n");
    assertThatThrownBy(() -> session.assemble(List.of(invalid)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error -> {
              assertThat(error.errors().getErrorLimit()).isEqualTo(2);
              assertThat(error.errors().getErrorMessages()).satisfiesExactly(
                  first -> assertThat(first.getMessage()).endsWith("Invalid language element: 'ab'"),
                  second -> assertThat(second.getMessage()).endsWith("Invalid language element: 'cd'"),
                  limit -> assertThat(limit.getMessage()).isEqualTo("Error Limit of 2 exceeded."));
            });
    assertThat(session.getProgram()).isSameAs(valid);
    assertThat(session.memory.getWord(session.symbolTable.getAddress("result"))).isEqualTo(42);
  }

  @Test
  void numericRegisterOnlyModeIsOwnedByTheAssemblySession() throws Exception {
    var regular = session("Default");
    var strict = session("Default");
    strict.settings.setBareMachineEnabled(true);
    var named = source("named-registers.s", ".text\nadd $t0, $t1, $t2\n");
    assertThat(regular.assemble(List.of(named)).getMachineList()).hasSize(1);
    assertThatThrownBy(() -> strict.assemble(List.of(named)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error -> assertThat(error.errors().generateErrorReport()).contains("register number"));
    var numbered = source("numbered-registers.s", ".text\nadd $8, $9, $10\n");
    assertThat(strict.assemble(List.of(numbered)).getMachineList()).hasSize(1);
  }

  @Test
  void staleAndMixedProgramsCannotReplaceOrRunAgainstTheCurrentSession() throws Exception {
    var session = session("Default");
    var old = session.assemble(List.of(source("old.s", program(1, false))));
    var current = session.assemble(List.of(source("new.s", program(2, false))));
    int address = session.symbolTable.getAddress("result");
    assertThatThrownBy(() -> old.simulate(100)).isInstanceOf(IllegalStateException.class);
    assertThat(old.getBackStepper()).isNull();
    assertThatThrownBy(
            () -> new Assembler(session).assemble(new MIPSprogram(TestSessions.create()), true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(session.getProgram()).isSameAs(current);
    assertThat(session.memory.getWord(address)).isEqualTo(2);
    assertThatThrownBy(() -> session.assemble(List.of(directory.resolve("missing.s"))))
        .isInstanceOf(ProcessingException.class);
    assertThat(session.getProgram()).isSameAs(current);
    var standaloneSource = new MIPSprogram(session);
    standaloneSource.readSource(source("direct-assembler.s", program(3, false)).toString());
    standaloneSource.tokenize();
    assertThat(new Assembler(session).assemble(standaloneSource, true)).isNotEmpty();
    assertThat(session.getProgram()).isNull();
    assertThatThrownBy(() -> current.simulate(100)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ownedExecutionSupportsStepLimitsBreakpointsAndUndo() throws Exception {
    var session = session("Default");
    var program =
        session.assemble(
            List.of(
                source(
                    "loop.s",
                    """
                    .text
                    .globl main
                    main:
                      addiu $t0, $t0, 1
                      b main
                      nop
                    """)));
    int base = session.memory.textBaseAddress;
    var completed = new ArrayList<Long>();
    assertThat(program.simulate(3, completed::add)).isFalse();
    assertThat(completed).containsExactly(1L, 2L, 3L);
    assertThat(session.registerFile.getValue(8)).isEqualTo(2);
    session.backStepper.backStep();
    assertThat(session.registerFile.getValue(8)).isEqualTo(1);
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    assertThat(program.simulateStepAtPC()).isFalse();
    assertThat(session.registerFile.getValue(8)).isEqualTo(2);
    assertThat(program.simulateFromPC(new int[] {base}, 100)).isFalse();
    assertThat(session.registerFile.getPC()).isEqualTo(base);
  }

  @Test
  void ownedExecutionDispatchesToItsOwnExceptionHandler() throws Exception {
    var session = session("Default");
    var program =
        session.assemble(
            List.of(
                source(
                    "handler.s",
                    """
                    .text
                    .globl main
                    main:
                      li $t0, 0x7fffffff
                      addi $t0, $t0, 1
                      li $v0, 10
                      syscall
                    .ktext 0x80000180
                      mfc0 $k0, $14
                      addiu $k0, $k0, 4
                      mtc0 $k0, $14
                      eret
                    """)));
    assertThat(program.simulate(100)).isTrue();
    assertThat(session.registerFile.getValue(8)).isEqualTo(Integer.MAX_VALUE);
    assertThat(session.coprocessor0.getValue(Coprocessor0State.STATUS) & 2).isZero();
    assertThat(session.coprocessor0.getValue(Coprocessor0State.CAUSE)).isEqualTo(12 << 2);
  }

  @Test
  void startAtMainAndEntryPointSettingsRemainSessionSpecific() throws Exception {
    var session = session("Default");
    session.settings.setStartAtMainEnabled(true);
    var program =
        session.assemble(
            List.of(
                source(
                    "entry.s",
                    """
                    .text
                      nop
                    .globl main
                    main:
                      li $v0, 17
                      li $a0, 7
                      syscall
                    """)));
    assertThat(session.registerFile.getPC()).isEqualTo(session.memory.textBaseAddress + 4);
    assertThat(program.simulate(100)).isTrue();
    assertThat(program.getExitCode()).isEqualTo(7);
  }

  private Path source(String filename, String text) throws Exception {
    var path = directory.resolve(filename);
    Files.writeString(path, text);
    return path;
  }

  private static List<Integer> assembleAndRun(MarsContext session, Path source) throws Exception {
    var program = session.assemble(List.of(source));
    var output = new RecordingEnvironment();
    program.setSyscallEnvironment(output);
    assertThat(program.simulate(200)).isTrue();
    return output.printed;
  }

  private static String program(int value, boolean padding) {
    return ".data\n"
        + (padding ? ".word 11\n" : "")
        + ".globl result\nresult: .word "
        + value
        + "\n"
        + """
        .text
        .globl main
        main:
          la $t1, result
          lw $t0, 0($t1)
          addiu $t0, $t0, 1
          sw $t0, 0($t1)
          move $a0, $t0
          li $v0, 1
          syscall
          li $v0, 10
          syscall
        """;
  }

  private static MarsContext session(String layout) {
    var settings = new Settings();
    settings
        .getSimulationSettings()
        .setMemoryConfiguration(layout)
        .setDelayedBranchingEnabled(false)
        .setStartAtMainEnabled(false)
        .setBareMachineEnabled(false)
        .setExtendedAssemblerEnabled(true)
        .setWarningsAreErrors(false);
    return DaggerMarsComponent.builder()
        .coreModule(new CoreModule(settings))
        .guiProxy(NO_GUI)
        .build()
        .context();
  }

  private static class RecordingEnvironment extends TestEnvironment {
    final List<Integer> printed = new ArrayList<>();

    @Override
    public void printInt(int value) {
      printed.add(value);
    }
  }

  private static final MarsGuiProxy NO_GUI =
      new MarsGuiProxy() {
        public boolean isGuiMode() {
          return false;
        }

        public void postMessage(String message) {}

        public void postRunMessage(String message) {}

        public String readInput(int maxLength) {
          return "";
        }

        public String readInputWithPrompt(String prompt) {
          return "";
        }

        public void clearRunConsole() {}
      };
}
