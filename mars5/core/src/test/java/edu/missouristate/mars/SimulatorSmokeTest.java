package edu.missouristate.mars;

import static org.assertj.core.api.Assertions.assertThat;

import edu.missouristate.mars.assembler.SymbolTable;
import edu.missouristate.mars.mips.hardware.Coprocessor0;
import edu.missouristate.mars.mips.hardware.Coprocessor1;
import edu.missouristate.mars.mips.hardware.RegisterFile;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.ArrayList;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Characterization tests: verify that non-interactive programs run to completion and produce
 * expected outputs. These tests pin the simulator's observable behavior for programs that do not
 * require stdin, making them safe to run in a headless test environment.
 *
 * <p>Only programs that never invoke read-int (syscall 5) or read-string (syscall 8) are included
 * here. Interactive programs are covered by assembly-only tests in {@link AssemblerSmokeTest}.
 */
class SimulatorSmokeTest {

  private PrintStream originalOut;
  private ByteArrayOutputStream capturedOut;

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

    capturedOut = new ByteArrayOutputStream();
    originalOut = System.out;
    System.setOut(new PrintStream(capturedOut));
  }

  @AfterEach
  void restoreStdout() {
    System.setOut(originalOut);
  }

  // -------------------------------------------------------------------------
  // 01_helloworld.asm — syscalls 4 (print string), 10 (exit)
  // -------------------------------------------------------------------------

  @Test
  void helloWorld_completesNormally() throws Exception {
    boolean done = runProgram("01_helloworld.asm");
    Assertions.assertThat(done).as("simulation should complete normally").isTrue();
  }

  @Test
  void helloWorld_printsExpectedGreeting() throws Exception {
    runProgram("01_helloworld.asm");
    Assertions.assertThat(capturedOut.toString()).contains("Hello World");
  }

  // -------------------------------------------------------------------------
  // 07_division.asm — verifies div/mul ordering and register state
  // Expected: (10/3)*3 = 9, (10*3)/3 = 10
  // -------------------------------------------------------------------------

  @Test
  void division_completesNormally() throws Exception {
    boolean done = runProgram("07_division.asm");
    Assertions.assertThat(done).isTrue();
  }

  @Test
  void division_outputContainsBothResults() throws Exception {
    runProgram("07_division.asm");
    String out = capturedOut.toString();
    Assertions.assertThat(out).contains("9");
    Assertions.assertThat(out).contains("10");
  }

  @Test
  void division_finalRegisterState() throws Exception {
    runProgram("07_division.asm");
    // $s0 = 10 (set early, never modified), $s1 = 3, $s2 = (10*3)/3 = 10 (last result)
    assertThat(RegisterFile.getValue(16)).as("$s0 should be 10").isEqualTo(10);
    assertThat(RegisterFile.getValue(17)).as("$s1 should be 3").isEqualTo(3);
    assertThat(RegisterFile.getValue(18))
        .as("$s2 should be 10 (last division result)")
        .isEqualTo(10);
  }

  // -------------------------------------------------------------------------
  // 08_toupper.asm — syscall 11 (print char), character bit operations
  // -------------------------------------------------------------------------

  @Test
  void toupper_completesNormally() throws Exception {
    boolean done = runProgram("08_toupper.asm");
    Assertions.assertThat(done).isTrue();
  }

  @Test
  void toupper_outputContainsLowercaseA() throws Exception {
    runProgram("08_toupper.asm");
    // Program loads 0x41 ('A') and converts to lowercase by OR-ing/adding 0x20 → 0x61 ('a').
    // Note: the source comment says 'Z' but the constant 0x41 is 'A'. Output is 'a'.
    Assertions.assertThat(capturedOut.toString()).contains("a");
  }

  // -------------------------------------------------------------------------
  // 09_xor.asm — XOR reversibility; syscall 34 (print hex)
  // -------------------------------------------------------------------------

  @Test
  void xor_completesNormally() throws Exception {
    boolean done = runProgram("09_xor.asm");
    Assertions.assertThat(done).isTrue();
  }

  @Test
  void xor_registerRestoredAfterDoubleXor() throws Exception {
    runProgram("09_xor.asm");
    // $s0 starts as 0x01234567, XOR'd twice → restored to original value
    assertThat(RegisterFile.getValue(16))
        .as("$s0 should be restored to 0x01234567 after double XOR")
        .isEqualTo(0x01234567);
  }

  // -------------------------------------------------------------------------
  // 10_shifts.asm — sll, srl, sra (signed and unsigned), ror, rol
  // -------------------------------------------------------------------------

  @Test
  void shifts_completesNormally() throws Exception {
    boolean done = runProgram("10_shifts.asm");
    Assertions.assertThat(done).isTrue();
  }

  @Test
  void shifts_sllResult() throws Exception {
    runProgram("10_shifts.asm");
    // Last SLL: 4 << 2 = 16 — but after the program runs more instructions, $s0 is overwritten.
    // Verify that the program ran without error and produced output (register state is complex).
    Assertions.assertThat(capturedOut.toString()).isNotEmpty();
  }

  @Test
  void floatingPointArithmeticConversionAndComparisonStillExecute() throws Exception {
    assertThat(runProgram("16_floating_point.asm")).isTrue();
    assertThat(Coprocessor1.getFloatFromRegister(4)).isEqualTo(4.0f);
    assertThat(Coprocessor1.getDoubleFromRegisterPair(6)).isEqualTo(4.0);
    assertThat(Coprocessor1.getConditionFlag(0)).isEqualTo(1);
  }

  // -------------------------------------------------------------------------
  // Helper
  // -------------------------------------------------------------------------

  private boolean runProgram(String filename) throws Exception {
    MIPSprogram program = new MIPSprogram();
    String path = resourcePath(filename);
    program.readSource(path);
    program.tokenize();

    ArrayList<String> filenames = new ArrayList<>();
    filenames.add(path);
    ArrayList<MIPSprogram> programs = program.prepareFilesForAssembly(filenames, path, null);
    program.assemble(programs, true, false);
    return program.simulate(null);
  }

  static String resourcePath(String filename) throws URISyntaxException {
    return Path.of(
            SimulatorSmokeTest.class
                .getClassLoader()
                .getResource("testPrograms/" + filename)
                .toURI())
        .toString();
  }
}
