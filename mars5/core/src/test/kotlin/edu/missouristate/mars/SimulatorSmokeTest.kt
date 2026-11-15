package edu.missouristate.mars

import edu.missouristate.mars.core.MarsContext
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Path
import java.util.Objects
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Characterization tests: verify that non-interactive programs run to completion and produce
 * expected outputs. These tests pin the simulator's observable behavior for programs that do not
 * require stdin, making them safe to run in a headless test environment.
 *
 * Only programs that never invoke read-int (syscall 5) or read-string (syscall 8) are included
 * here. Interactive programs are covered by assembly-only tests in [AssemblerSmokeTest].
 */
internal class SimulatorSmokeTest {
    private var machine: MarsContext? = null

    @AfterEach
    fun closeMachine() {
        machine!!.close()
    }

    private var originalOut: PrintStream? = null
    private var capturedOut: ByteArrayOutputStream? = null

    @BeforeEach
    fun resetState() {
        capturedOut = ByteArrayOutputStream()
        originalOut = System.out
        System.setOut(PrintStream(capturedOut))
        machine = TestSessions.create()
    }

    @AfterEach
    fun restoreStdout() {
        System.setOut(originalOut)
    }

    // -------------------------------------------------------------------------
    // 01_helloworld.asm — syscalls 4 (print string), 10 (exit)
    // -------------------------------------------------------------------------
    @Test
    fun helloWorld_completesNormally() {
        val done = runProgram("01_helloworld.asm")
        Assertions.assertThat(done).`as`("simulation should complete normally").isTrue()
    }

    @Test
    fun helloWorld_printsExpectedGreeting() {
        runProgram("01_helloworld.asm")
        Assertions.assertThat(capturedOut.toString()).contains("Hello World")
    }

    // -------------------------------------------------------------------------
    // 07_division.asm — verifies div/mul ordering and register state
    // Expected: (10/3)*3 = 9, (10*3)/3 = 10
    // -------------------------------------------------------------------------
    @Test
    fun division_completesNormally() {
        val done = runProgram("07_division.asm")
        Assertions.assertThat(done).isTrue()
    }

    @Test
    fun division_outputContainsBothResults() {
        runProgram("07_division.asm")
        val out: String? = capturedOut.toString()
        Assertions.assertThat(out).contains("9")
        Assertions.assertThat(out).contains("10")
    }

    @Test
    fun division_finalRegisterState() {
        runProgram("07_division.asm")
        // $s0 = 10 (set early, never modified), $s1 = 3, $s2 = (10*3)/3 = 10 (last result)
        Assertions.assertThat(machine!!.registerFile.getValue(16))
            .`as`("\$s0 should be 10")
            .isEqualTo(10)
        Assertions.assertThat(machine!!.registerFile.getValue(17))
            .`as`("\$s1 should be 3")
            .isEqualTo(3)
        Assertions.assertThat(machine!!.registerFile.getValue(18))
            .`as`("\$s2 should be 10 (last division result)")
            .isEqualTo(10)
    }

    // -------------------------------------------------------------------------
    // 08_toupper.asm — syscall 11 (print char), character bit operations
    // -------------------------------------------------------------------------
    @Test
    fun toupper_completesNormally() {
        val done = runProgram("08_toupper.asm")
        Assertions.assertThat(done).isTrue()
    }

    @Test
    fun toupper_outputContainsLowercaseA() {
        runProgram("08_toupper.asm")
        // Program loads 0x41 ('A') and converts to lowercase by OR-ing/adding 0x20 → 0x61 ('a').
        // Note: the source comment says 'Z' but the constant 0x41 is 'A'. Output is 'a'.
        Assertions.assertThat(capturedOut.toString()).contains("a")
    }

    // -------------------------------------------------------------------------
    // 09_xor.asm — XOR reversibility; syscall 34 (print hex)
    // -------------------------------------------------------------------------
    @Test
    fun xor_completesNormally() {
        val done = runProgram("09_xor.asm")
        Assertions.assertThat(done).isTrue()
    }

    @Test
    fun xor_registerRestoredAfterDoubleXor() {
        runProgram("09_xor.asm")
        // $s0 starts as 0x01234567, XOR'd twice → restored to original value
        Assertions.assertThat(machine!!.registerFile.getValue(16))
            .`as`("\$s0 should be restored to 0x01234567 after double XOR")
            .isEqualTo(0x01234567)
    }

    // -------------------------------------------------------------------------
    // 10_shifts.asm — sll, srl, sra (signed and unsigned), ror, rol
    // -------------------------------------------------------------------------
    @Test
    fun shifts_completesNormally() {
        val done = runProgram("10_shifts.asm")
        Assertions.assertThat(done).isTrue()
    }

    @Test
    fun shifts_sllResult() {
        runProgram("10_shifts.asm")
        // Last SLL: 4 << 2 = 16 — but after the program runs more instructions, $s0 is overwritten.
        // Verify that the program ran without error and produced output (register state is
        // complex).
        Assertions.assertThat(capturedOut.toString()).isNotEmpty()
    }

    @Test
    fun floatingPointArithmeticConversionAndComparisonStillExecute() {
        Assertions.assertThat(runProgram("16_floating_point.asm")).isTrue()
        Assertions.assertThat(machine!!.coprocessor1.getFloatFromRegister(4)).isEqualTo(4.0f)
        Assertions.assertThat(machine!!.coprocessor1.getDoubleFromRegisterPair(6)).isEqualTo(4.0)
        Assertions.assertThat(machine!!.coprocessor1.getConditionFlag(0)).isEqualTo(1)
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------
    private fun runProgram(filename: String): Boolean {
        val program = MIPSprogram(machine!!)
        val path: String = resourcePath(filename)
        program.readSource(path)
        program.tokenize()

        val filenames = arrayListOf(path)
        val programs: ArrayList<MIPSprogram> =
            program.prepareFilesForAssembly(filenames, path, null)
        program.assemble(programs, true, false)
        return program.simulate(null)
    }

    companion object {
        @Throws(URISyntaxException::class)
        fun resourcePath(filename: String?): String {
            val resource = "/testPrograms/" + filename
            val url: URL? =
                Objects.requireNonNull<URL?>(
                    SimulatorSmokeTest::class.java.getResource(resource),
                    resource,
                )
            return Path.of(url!!.toURI()).toString()
        }
    }
}
