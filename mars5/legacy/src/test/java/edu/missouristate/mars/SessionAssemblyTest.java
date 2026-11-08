package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.missouristate.mars.api.HeadlessSyscallEnvironment;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.assembler.Assembler;
import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.core.MarsContext;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.mips.hardware.Coprocessor0State;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionAssemblyTest {
  @TempDir Path directory;

  @Test
  void twoProgramsAssembleAndRunWithoutGlobalSimulationObjects() throws Exception {
    var oldMemory = Globals.memory;
    var oldInstructions = Globals.instructionSet;
    var oldSymbols = Globals.symbolTable;
    var oldProgram = Globals.program;
    var oldSettings = Globals.settings;
    try {
      Globals.memory = null;
      Globals.instructionSet = null;
      Globals.symbolTable = null;
      Globals.program = null;
      Globals.settings = null;
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
      assertThat(Globals.memory).isNull();
      assertThat(Globals.instructionSet).isNull();
      assertThat(Globals.symbolTable).isNull();
      assertThat(Globals.program).isNull();
      assertThat(Globals.settings).isNull();
    } finally {
      Globals.memory = oldMemory;
      Globals.instructionSet = oldInstructions;
      Globals.symbolTable = oldSymbols;
      Globals.program = oldProgram;
      Globals.settings = oldSettings;
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
    session.settings.setAssemblerErrorLimit(1);
    var invalid = source("token-errors.s", ".data\n.byte 'ab'\n.byte 'cd'\n");
    assertThatThrownBy(() -> session.assemble(List.of(invalid)))
        .isInstanceOfSatisfying(
            ProcessingException.class,
            error -> {
              assertThat(error.errors().getErrorLimit()).isEqualTo(1);
              assertThat(error.errors().getErrorMessages()).hasSize(2);
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
            () -> new Assembler(session).assemble(new edu.missouristate.mars.LegacyProgram(), true))
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
    assertThat(program.simulate(3)).isFalse();
    assertThat(session.registerFile.getValue(8)).isEqualTo(2);
    session.backStepper.backStep();
    assertThat(session.registerFile.getValue(8)).isEqualTo(1);
    assertThat(session.registerFile.getPC()).isEqualTo(base);
    assertThat(program.simulateStepAtPC(null)).isFalse();
    assertThat(session.registerFile.getValue(8)).isEqualTo(2);
    assertThat(program.simulateFromPC(new int[] {base}, 100, null)).isFalse();
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

  private static class RecordingEnvironment extends HeadlessSyscallEnvironment {
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
