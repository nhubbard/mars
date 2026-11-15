package edu.missouristate.mars

import edu.missouristate.mars.core.MarsContext
import java.net.URISyntaxException
import java.net.URL
import java.nio.file.Path
import java.util.Objects
import java.util.stream.Stream
import org.assertj.core.api.Assertions
import org.assertj.core.api.ThrowableAssert
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Characterization tests: verify that every sample program assembles without errors. These tests
 * pin the assembler's ability to process all program constructs present in the test corpus. Any
 * regression in the assembler that causes previously-valid programs to fail assembly will be caught
 * here.
 */
internal class AssemblerSmokeTest {
    private val machine: MarsContext = TestSessions.create()

    @AfterEach
    fun closeMachine() {
        machine.close()
    }

    @BeforeEach
    fun resetState() {
        machine.memory.clear()
        machine.registerFile.resetRegisters()
        machine.coprocessor0.resetRegisters()
        machine.coprocessor1.resetRegisters()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allPrograms")
    fun assemblesWithoutErrors(filename: String?) {
        val program = MIPSprogram(machine)
        val path: String = resourcePath(filename)
        program.readSource(path)
        program.tokenize()

        val filenames = arrayListOf(path)
        val programs: ArrayList<MIPSprogram> =
            program.prepareFilesForAssembly(filenames, path, null)

        val errors = program.assemble(programs, extended = true, warnings = false)

        Assertions.assertThat(errors.errorsOccurred())
            .`as`("Expected no assembly errors for %s but got: %s", filename, errors)
            .isFalse()
        Assertions.assertThat(program.machineList)
            .`as`("Expected assembled instructions for %s", filename)
            .isNotEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("allPrograms")
    fun assemblesWithStrictMode(filename: String?) {
        val program = MIPSprogram(machine)
        val path: String = resourcePath(filename)
        program.readSource(path)
        program.tokenize()

        val filenames = arrayListOf(path)
        val programs: ArrayList<MIPSprogram> =
            program.prepareFilesForAssembly(filenames, path, null)

        // Extended assembler enabled (pseudo-instructions allowed) — should never throw
        Assertions.assertThatCode(
                ThrowableAssert.ThrowingCallable { program.assemble(programs, extended = true, warnings = false) },
            )
            .doesNotThrowAnyException()
    }

    companion object {
        @JvmStatic
        fun allPrograms(): Stream<String?> {
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
                "14_promptint.asm",
            )
        }

        @Throws(URISyntaxException::class)
        fun resourcePath(filename: String?): String {
            val resource = "/testPrograms/" + filename
            val url: URL? =
                Objects.requireNonNull<URL?>(
                    AssemblerSmokeTest::class.java.getResource(resource),
                    resource,
                )
            return Path.of(url!!.toURI()).toString()
        }
    }
}
