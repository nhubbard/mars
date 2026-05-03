package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Characterization tests: verify that every sample program assembles without errors. These tests
 * pin the assembler's ability to process all program constructs present in the test corpus. Any
 * regression in the assembler that causes previously-valid programs to fail assembly will be caught
 * here.
 */
class AssemblerSmokeTest {

  @BeforeAll
  static void initMars() {
    Globals.initialize();
  }

  @BeforeEach
  void resetState() {
    Globals.memory.clear();
    RegisterFile.resetRegisters();
    Coprocessor0.resetRegisters();
    Coprocessor1.resetRegisters();
    Globals.symbolTable = new SymbolTable("global");
    Globals.exitCode = 0;
  }

  static Stream<String> allPrograms() {
    return Stream.of(
        "01_helloworld.asm",
        "02_readint.asm",
        "03_readstring.asm",
        "04_addition.asm",
        "05_evenodd.asm",
        "06_quadratic.asm",
        "07_division.asm",
        "08_toupper.asm",
        "09_xor.asm",
        "10_shifts.asm",
        "11_exit.asm",
        "12_println.asm",
        "12_println2.asm",
        "13_printint.asm",
        "14_promptint.asm");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allPrograms")
  void assemblesWithoutErrors(String filename) throws Exception {
    MIPSprogram program = new MIPSprogram();
    String path = resourcePath(filename);
    program.readSource(path);
    program.tokenize();

    ArrayList<String> filenames = new ArrayList<>();
    filenames.add(path);
    ArrayList<MIPSprogram> programs = program.prepareFilesForAssembly(filenames, path, null);

    ErrorList errors = program.assemble(programs, /* extendedAssemblerEnabled= */ true, false);

    assertThat(errors.errorsOccurred())
        .as("Expected no assembly errors for %s but got: %s", filename, errors)
        .isFalse();
    assertThat(program.getMachineList())
        .as("Expected assembled instructions for %s", filename)
        .isNotEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("allPrograms")
  void assemblesWithStrictMode(String filename) throws Exception {
    MIPSprogram program = new MIPSprogram();
    String path = resourcePath(filename);
    program.readSource(path);
    program.tokenize();

    ArrayList<String> filenames = new ArrayList<>();
    filenames.add(path);
    ArrayList<MIPSprogram> programs = program.prepareFilesForAssembly(filenames, path, null);

    // Extended assembler enabled (pseudo-instructions allowed) — should never throw
    assertThatCode(() -> program.assemble(programs, true, false)).doesNotThrowAnyException();
  }

  static String resourcePath(String filename) throws URISyntaxException {
    return Path.of(
            AssemblerSmokeTest.class
                .getClassLoader()
                .getResource("testPrograms/" + filename)
                .toURI())
        .toString();
  }
}
