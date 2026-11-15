package edu.missouristate.mars

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

internal class InstructionEncodingTest {
    @TempDir lateinit var directory: Path

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("vectors")
    fun basicInstructionEncodingsMatchIndependentFrozenVectors(
        type: String?,
        source: String?,
        expected: Int,
    ) {
        TestSessions.create().use { session ->
            val file = directory.resolve("instruction.s")
            Files.writeString(file, ".text\n$source\nlabel:\ntarget: nop\n")
            val program: MIPSprogram = session.assemble(listOf(file), null, extended = false, warnings = false)
            val statement =
                checkNotNull(program.machineList) { "Expected assembled instructions" }
                    .first()
            val instruction =
                checkNotNull(statement.instruction) { "Expected assembled instruction" }
            Assertions.assertThat(instruction.javaClass.simpleName).isEqualTo(type)
            Assertions.assertThat(statement.binaryStatement).isEqualTo(expected)
            val decoded = ProgramStatement(expected, statement.address, session.instructionSet)
            val decodedInstruction =
                checkNotNull(decoded.instruction) { "Expected decoded instruction" }
            Assertions.assertThat(decodedInstruction.name).isEqualTo(instruction.name)
        }
    }

    companion object {
        @JvmStatic
        fun vectors(): List<Arguments> {
            checkNotNull(InstructionEncodingTest::class
                        .java
                        .getResourceAsStream("/basic-encoding-vectors.tsv")) { "basic-encoding-vectors.tsv" }
                .use { input ->
                    return input
                        .bufferedReader(StandardCharsets.UTF_8)
                        .lineSequence()
                        .filter { line: String -> !line.startsWith("#") && line.isNotBlank() }
                        .map { line: String ->
                            val fields = line.split('\t')
                            Arguments.of(fields[0], fields[1], fields[2].toLong(16).toInt())
                        }
                        .toList()
                }
        }
    }
}
